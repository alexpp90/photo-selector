// Generates landscape (and a few portrait) JPEG fixtures for the macOS UI smoke test.
//
// Usage: swift make_fixtures.swift <output-dir> <count>
// Writes <count> photos (90 % landscape 3:2, 10 % portrait 2:3) at camera-like resolution,
// spread over the root and one nested folder, plus decoy photos inside an excluded
// `Selection/` folder that the scanner must skip. Prints the expected photo count.
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let arguments = CommandLine.arguments
guard arguments.count >= 3, let count = Int(arguments[2]), count > 0 else {
    FileHandle.standardError.write(Data("usage: make_fixtures.swift <output-dir> <count>\n".utf8))
    exit(2)
}
let root = URL(fileURLWithPath: arguments[1], isDirectory: true)
let nested = root.appendingPathComponent("DCIM/100CANON", isDirectory: true)
let excluded = root.appendingPathComponent("Selection", isDirectory: true)
for dir in [root, nested, excluded] {
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
}

// Encoded files by (size, hue): a folder of thousands of photos reuses the few distinct images
// (17 hues x 2 shapes) instead of rendering and encoding each one, so a 10,000-photo fixture takes
// seconds, not minutes. Every file is still written separately (its own inode and modification date).
var encodedCache: [String: Data] = [:]

func writeJPEG(to url: URL, width: Int, height: Int, hue: CGFloat) {
    let cacheKey = "\(width)x\(height)-\(hue)"
    if let cached = encodedCache[cacheKey] {
        try? cached.write(to: url)
        return
    }
    let space = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(
        data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
        space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else { return }
    // Gradient background plus a checker pattern so the focus metric has edges to measure.
    let colors = [
        CGColor(red: hue, green: 0.3, blue: 1 - hue, alpha: 1),
        CGColor(red: 1 - hue, green: 0.8, blue: hue, alpha: 1),
    ] as CFArray
    if let gradient = CGGradient(colorsSpace: space, colors: colors, locations: [0, 1]) {
        ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: width, y: height), options: [])
    }
    ctx.setFillColor(CGColor(gray: 0, alpha: 0.35))
    let cell = max(16, width / 40)
    for y in stride(from: 0, to: height, by: cell * 2) {
        for x in stride(from: 0, to: width, by: cell * 2) {
            ctx.fill(CGRect(x: x, y: y, width: cell, height: cell))
        }
    }
    let output = NSMutableData()
    guard let image = ctx.makeImage(),
          let dest = CGImageDestinationCreateWithData(output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)
    else { return }
    // Camera-style EXIF so the metadata panel has something to show (and the smoke test can
    // assert that EXIF is visible together with the first photo).
    let properties: [CFString: Any] = [
        kCGImageDestinationLossyCompressionQuality: 0.85,
        kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Fixture", kCGImagePropertyTIFFModel: "Fixture X-1"],
        kCGImagePropertyExifDictionary: [
            kCGImagePropertyExifExposureTime: 0.004,
            kCGImagePropertyExifFNumber: 2.8,
            kCGImagePropertyExifISOSpeedRatings: [400],
            kCGImagePropertyExifFocalLength: 85.0,
            kCGImagePropertyExifLensModel: "Fixture 85mm f/1.8",
        ],
    ]
    CGImageDestinationAddImage(dest, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { return }
    let data = output as Data
    encodedCache[cacheKey] = data
    try? data.write(to: url)
}

for index in 0..<count {
    let portrait = index % 10 == 9
    let (w, h) = portrait ? (2000, 3000) : (3000, 2000)
    let folder = index < count / 2 ? root : nested
    let name = String(format: "IMG_%04d.JPG", index + 1)
    writeJPEG(to: folder.appendingPathComponent(name), width: w, height: h, hue: CGFloat(index % 17) / 16)
}
for index in 0..<3 {
    writeJPEG(to: excluded.appendingPathComponent(String(format: "SEL_%04d.JPG", index)), width: 600, height: 400, hue: 0.5)
}
print(count)
