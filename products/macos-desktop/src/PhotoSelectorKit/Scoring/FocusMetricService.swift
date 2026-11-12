import Foundation
import Accelerate
import CoreGraphics

/// Result of focus and sharpness analysis on an image.
public struct FocusMetricResult: Sendable, Equatable {
    /// Normalized photographic sharpness score in range 0.0 ... 100.0.
    public let score: Double

    /// Raw Laplacian variance prior to noise-floor subtraction and compressive mapping.
    public let rawVariance: Double

    /// Estimated sensor noise standard deviation (sigma).
    public let estimatedNoiseSigma: Double

    /// Sharpness classification category.
    public let category: SharpnessCategory

    public init(
        score: Double,
        rawVariance: Double,
        estimatedNoiseSigma: Double,
        category: SharpnessCategory
    ) {
        self.score = score
        self.rawVariance = rawVariance
        self.estimatedNoiseSigma = estimatedNoiseSigma
        self.category = category
    }
}

/// High-performance focus and sharpness evaluation service using Apple Accelerate (vImage and vDSP).
public final class FocusMetricService: Sendable {

    // 3x3 Gaussian pre-filter kernel (sum = 1.0)
    private static let gaussianKernel: [Float] = [
        1.0 / 16.0, 2.0 / 16.0, 1.0 / 16.0,
        2.0 / 16.0, 4.0 / 16.0, 2.0 / 16.0,
        1.0 / 16.0, 2.0 / 16.0, 1.0 / 16.0
    ]

    // 3x3 Discrete 4-connected Laplacian kernel
    private static let laplacianKernel: [Float] = [
         0.0,  1.0,  0.0,
         1.0, -4.0,  1.0,
         0.0,  1.0,  0.0
    ]

    // Effective kernel sum of squares: sum( (G * L)^2 ) = 0.40625
    private static let compoundNoiseVarianceFactor = 0.40625

    // MARK: - Scoring size

    /// The long edge, in pixels, the score is computed at: the 2,048 px preview tier the viewport
    /// and the library pass decode. A larger image is scaled down to it first, so the same photo
    /// does not land on two sides of the Blurry / Sharp bands because it was decoded at two sizes.
    /// Smaller images (an embedded RAW preview) are scored as they are, not enlarged.
    public static let scoringLongEdge = 2048

    /// Pixel size an image of `width` x `height` is scored at (never enlarged).
    public static func scoringSize(width: Int, height: Int) -> (width: Int, height: Int) {
        let longEdge = max(width, height)
        guard longEdge > scoringLongEdge, width > 0, height > 0 else { return (width, height) }
        let scale = Double(scoringLongEdge) / Double(longEdge)
        return (max(3, Int((Double(width) * scale).rounded())), max(3, Int((Double(height) * scale).rounded())))
    }

    private static func scaledForScoring(_ image: CGImage) -> CGImage? {
        let size = scoringSize(width: image.width, height: image.height)
        guard size.width != image.width || size.height != image.height else { return image }
        guard let context = CGContext(
            data: nil, width: size.width, height: size.height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
        return context.makeImage()
    }

    // MARK: - Asynchronous Public API

    /// Evaluates focus and sharpness asynchronously on a cooperative background thread.
    /// - Parameters:
    ///   - cgImage: The image to analyze.
    ///   - cropToCenter: If true, analyzes only the central 50% ROI of the frame.
    /// - Returns: A `FocusMetricResult` or `nil` if the image buffer is invalid or dimensions < 3.
    public static func evaluate(
        cgImage: CGImage,
        cropToCenter: Bool = false
    ) async -> FocusMetricResult? {
        await Task.detached(priority: .userInitiated) {
            evaluateSync(cgImage: cgImage, cropToCenter: cropToCenter)
        }.value
    }

    // MARK: - Synchronous Core API

    /// Evaluates focus and sharpness synchronously on the current thread.
    /// - Parameters:
    ///   - cgImage: The image to analyze.
    ///   - cropToCenter: If true, crops to the center 50% region before analysis.
    /// - Returns: A `FocusMetricResult` or `nil` if the image buffer is invalid or dimensions < 3.
    public static func evaluateSync(
        cgImage: CGImage,
        cropToCenter: Bool = false
    ) -> FocusMetricResult? {
        let targetImage: CGImage
        if cropToCenter {
            let w = CGFloat(cgImage.width)
            let h = CGFloat(cgImage.height)
            let cropRect = CGRect(x: w * 0.25, y: h * 0.25, width: w * 0.5, height: h * 0.5)
            guard let cropped = cgImage.cropping(to: cropRect) else { return nil }
            targetImage = cropped
        } else {
            guard let scaled = scaledForScoring(cgImage) else { return nil }
            targetImage = scaled
        }

        let width = targetImage.width
        let height = targetImage.height
        // Safe check for tiny or invalid buffers (3x3 convolution requires at least 3x3 pixels)
        guard width >= 3, height >= 3 else { return nil }

        // 1. Grayscale Planar8 conversion via vImage
        let colorSpace = CGColorSpaceCreateDeviceGray()
        var format = vImage_CGImageFormat(
            bitsPerComponent: 8,
            bitsPerPixel: 8,
            colorSpace: Unmanaged.passUnretained(colorSpace),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            version: 0,
            decode: nil,
            renderingIntent: .defaultIntent
        )

        var src8 = vImage_Buffer()
        guard vImageBuffer_InitWithCGImage(&src8, &format, nil, targetImage, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            return nil
        }
        defer { free(src8.data) }

        // 2. Sensor noise estimation via fast pairwise MAD on Planar8
        let noiseSigma = estimateNoiseSigma(from: &src8, width: width, height: height)

        // 3. Convert Planar8 to PlanarF (scale: 0.0 ... 255.0 to maintain consistent variance calibration)
        var srcF = vImage_Buffer()
        guard vImageBuffer_Init(&srcF, vImagePixelCount(height), vImagePixelCount(width), 32, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            return nil
        }
        defer { free(srcF.data) }
        vImageConvert_Planar8toPlanarF(&src8, &srcF, 255.0, 0.0, vImage_Flags(kvImageNoFlags))

        // 4. Gaussian smoothing pre-filter to suppress single-pixel sensor noise
        var smoothedF = vImage_Buffer()
        guard vImageBuffer_Init(&smoothedF, vImagePixelCount(height), vImagePixelCount(width), 32, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            return nil
        }
        defer { free(smoothedF.data) }

        let errSmooth = vImageConvolve_PlanarF(
            &srcF,
            &smoothedF,
            nil,
            0, 0,
            gaussianKernel,
            3, 3,
            0.0,
            vImage_Flags(kvImageEdgeExtend)
        )
        guard errSmooth == kvImageNoError else { return nil }

        // 5. 3x3 Laplacian convolution
        var laplacianF = vImage_Buffer()
        guard vImageBuffer_Init(&laplacianF, vImagePixelCount(height), vImagePixelCount(width), 32, vImage_Flags(kvImageNoFlags)) == kvImageNoError else {
            return nil
        }
        defer { free(laplacianF.data) }

        let errLapl = vImageConvolve_PlanarF(
            &smoothedF,
            &laplacianF,
            nil,
            0, 0,
            laplacianKernel,
            3, 3,
            0.0,
            vImage_Flags(kvImageEdgeExtend)
        )
        guard errLapl == kvImageNoError else { return nil }

        // 6. Vectorized variance calculation respecting vImage_Buffer.rowBytes
        var sumMean: Double = 0.0
        var sumMeanSquare: Double = 0.0
        for row in 0..<height {
            let rowPtr = laplacianF.data.advanced(by: row * laplacianF.rowBytes).assumingMemoryBound(to: Float.self)
            let rowBuffer = UnsafeBufferPointer(start: rowPtr, count: width)
            sumMean += Double(vDSP.mean(rowBuffer))
            sumMeanSquare += Double(vDSP.meanSquare(rowBuffer))
        }
        let mean = sumMean / Double(height)
        let meanSquare = sumMeanSquare / Double(height)
        let rawVariance = Double(max(0.0, meanSquare - (mean * mean)))

        // 7. Noise floor subtraction
        let noiseFloor = compoundNoiseVarianceFactor * (noiseSigma * noiseSigma)
        let correctedVariance = max(0.0, rawVariance - noiseFloor)

        // 8. Compressive non-linear score mapping to 0.0 ... 100.0
        let score: Double
        if correctedVariance <= 0.0 {
            score = 0.0
        } else {
            let mapped = 100.0 * (1.0 - exp(-sqrt(correctedVariance) / 12.0))
            score = min(100.0, max(0.0, (mapped * 10.0).rounded() / 10.0))
        }

        let category = SharpnessCategory.from(score: score)

        return FocusMetricResult(
            score: score,
            rawVariance: (rawVariance * 10.0).rounded() / 10.0,
            estimatedNoiseSigma: (noiseSigma * 10.0).rounded() / 10.0,
            category: category
        )
    }

    /// PROJECT.md Contract Compatibility: Returns raw Laplacian variance.
    public static func computeLaplacianVariance(cgImage: CGImage) -> Double? {
        evaluateSync(cgImage: cgImage, cropToCenter: false)?.rawVariance
    }

    // MARK: - Private Helpers

    /// Fast, non-allocating pairwise Median Absolute Deviation noise estimation respecting rowBytes and valid width.
    private static func estimateNoiseSigma(
        from buffer: inout vImage_Buffer,
        width: Int,
        height: Int
    ) -> Double {
        guard width >= 2, height >= 1 else { return 0.0 }
        let totalPairs = (width - 1) * height
        guard totalPairs > 0 else { return 0.0 }
        let sampleCount = min(2048, totalPairs)
        guard sampleCount > 0 else { return 0.0 }

        var diffs = [Float]()
        diffs.reserveCapacity(sampleCount)

        let ptr = buffer.data.assumingMemoryBound(to: UInt8.self)
        let rowBytes = buffer.rowBytes

        // Low-discrepancy sampling using golden ratio to avoid periodic harmonic resonance on grid/checkerboard patterns
        let phiFraction = 0.6180339887498949
        for i in 0..<sampleCount {
            let u = (Double(i) * phiFraction).truncatingRemainder(dividingBy: 1.0)
            let pairIndex = Int(u * Double(totalPairs))
            let r = pairIndex / (width - 1)
            let c = pairIndex % (width - 1)
            let offset = r * rowBytes + c
            let diff = Float(ptr[offset]) - Float(ptr[offset + 1])
            diffs.append(diff)
        }

        diffs.sort()
        let median = diffs[sampleCount / 2]

        var absDiffs = [Float]()
        absDiffs.reserveCapacity(sampleCount)
        for d in diffs {
            absDiffs.append(abs(d - median))
        }
        absDiffs.sort()
        let mad = absDiffs[sampleCount / 2]

        // Scale by 0.6745 * sqrt(2) for difference of two independent Gaussian noise variables
        let sigma = Double(mad) / (0.6745 * sqrt(2.0))
        return sigma
    }
}
