package com.photoselectortoolbox.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ComponentRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.FitWindow
import com.photoselectortoolbox.ui.fit.fitExif
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.selector.SelectorScreen
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The README screenshot of Frameduel, rendered from the shipped [SelectorScreen] at the
 * reference geometry (1480 x 924 dp, 2.0x: the Galaxy Tab S11 Ultra's 2960 x 1848 px) with the
 * real ViewModel over mocked storage. The photos are procedural landscapes drawn here, so the
 * image holds no person and no third-party picture.
 *
 * It writes `build/readme-screenshot/android_desktop_screenshot.png`; `assets/` takes a copy of
 * it by hand (the capture is a release asset, not a golden: nothing compares pixels). The test
 * itself asserts what the README caption claims the image shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1480dp-h924dp-land-xhdpi")
class ReadmeScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)

    private val scenes = mapOf(
        "DSC04211.JPG" to Scene(seed = 11, sunX = 0.72f, warm = true),
        "DSC04210.JPG" to Scene(seed = 10, sunX = 0.66f, warm = true),
        "DSC04212.JPG" to Scene(seed = 12, sunX = 0.78f, warm = false),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Coil.setImageLoader(SceneImageLoader(scenes.mapValues { (_, scene) -> scene.draw(1500, 1000) }))
    }

    @After
    fun tearDown() {
        Coil.reset()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun photo(name: String, modified: Long, sharpness: Double, noise: Double): ImageItem =
        fitPhoto(name, modified).copy(
            exifData = fitExif.copy(lens = "FE 35mm F1.8"),
            scanResult = ScanResult(
                filePath = name, sharpnessScore = sharpness, noiseLevel = noise,
                highlightClipping = 0.4, shadowClipping = 1.2,
            ),
        )

    @Test
    fun `the README screenshot shows the cull view as it ships`() {
        val photos = listOf(
            photo("DSC04212.JPG", 3_000, sharpness = 61.8, noise = 1.4),
            photo("DSC04211.JPG", 2_000, sharpness = 74.2, noise = 1.1),
            photo("DSC04210.JPG", 1_000, sharpness = 38.5, noise = 1.3),
        )
        val vm = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(details = true, filmstrip = false), photos,
        )
        vm.navigateToImage(1)
        val cell = FitCell(1480, 924, 1f)
        rule.setContent {
            FitWindow(cell) {
                PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("DSC04211.JPG")).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        // What the caption says is on it: the labelled sidebar with Learn, the named metric rows.
        assertTrue(rule.onAllNodes(hasText("Learn")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("Sharpness")).fetchSemanticsNodes().isNotEmpty())
        // And what it must not show: no aesthetic score row (REQ-AND-FEATURE.02), no snackbar.
        assertTrue(rule.onAllNodes(hasText("Aesthetic", substring = true)).fetchSemanticsNodes().isEmpty())

        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/readme-screenshot").apply { mkdirs() }.resolve("android_desktop_screenshot.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
        vm.viewModelScope.cancel()
    }

    /**
     * Usability panel 2026-10-24: the card's values had no names and the personas took them for
     * another frame's. The readout names every metric and is drawn against the current frame:
     * left of it, in its row, clear of both neighbours.
     */
    @Test
    fun `the values beside the current frame are named and sit against that frame`() {
        val photos = listOf(
            photo("DSC04212.JPG", 3_000, sharpness = 61.8, noise = 1.4),
            photo("DSC04211.JPG", 2_000, sharpness = 74.2, noise = 1.1),
            photo("DSC04210.JPG", 1_000, sharpness = 38.5, noise = 1.3),
        )
        val vm = fitSelectorViewModel(ApplicationProvider.getApplicationContext(), appScope, FitSelectorSettings(), photos)
        vm.navigateToImage(1)
        val cell = FitCell(1480, 924, 1f)
        rule.setContent {
            FitWindow(cell) {
                PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("current_readout")).fetchSemanticsNodes().isNotEmpty() }
        val inReadout = hasAnyAncestor(hasTestTag("current_readout"))
        for (name in listOf("Sharpness", "Noise", "Highlight clipping", "Shadow clipping", "DSC04211.JPG")) {
            assertTrue("$name in the readout", rule.onAllNodes(hasText(name) and inReadout, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        val readout = rule.onNodeWithTag("current_readout", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val current = rule.onNodeWithTag("column_current", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("readout $readout ends left of the current frame $current", readout.right <= current.left + 1f)
        assertTrue("readout $readout is in the current frame's row $current", readout.top < current.bottom && readout.bottom > current.top)
        for (tag in listOf("column_previous", "column_next")) {
            val frame = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val o = readout.intersect(frame)
            assertTrue("readout overlaps $tag", o.width <= 1f || o.height <= 1f)
        }
        vm.viewModelScope.cancel()
    }

    /** A landscape at dusk: sky, sun, two mountain ranges, a lake and its reflection. No figures. */
    private data class Scene(val seed: Int, val sunX: Float, val warm: Boolean) {
        fun draw(width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val random = Random(seed)
            val horizon = height * 0.62f
            val skyTop = if (warm) 0xFF1E2A4A.toInt() else 0xFF14243A.toInt()
            val skyLow = if (warm) 0xFFF2A25C.toInt() else 0xFFB9C7D8.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), horizon, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, horizon, skyTop, skyLow, Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(width * sunX, horizon * 0.78f, height * 0.06f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (warm) 0xFFFFE2A8.toInt() else 0xFFF4F7FA.toInt()
            })
            fun ridge(base: Float, amplitude: Float, color: Int) {
                val path = Path().apply {
                    moveTo(0f, horizon)
                    var x = 0f
                    while (x <= width) {
                        lineTo(x, base - amplitude * (0.4f + random.nextFloat() * 0.6f))
                        x += width / 14f
                    }
                    lineTo(width.toFloat(), horizon)
                    close()
                }
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            }
            ridge(horizon * 0.92f, height * 0.22f, 0xFF3B4466.toInt())
            ridge(horizon, height * 0.14f, 0xFF232A40.toInt())
            canvas.drawRect(0f, horizon, width.toFloat(), height.toFloat(), Paint().apply {
                shader = LinearGradient(0f, horizon, 0f, height.toFloat(), skyLow, skyTop, Shader.TileMode.CLAMP)
                alpha = 220
            })
            val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = 3f }
            repeat(26) {
                val y = horizon + 12f + random.nextFloat() * (height - horizon - 24f)
                val x = width * sunX + (random.nextFloat() - 0.5f) * width * 0.12f
                canvas.drawLine(x - 30f, y, x + 30f, y, glint)
            }
            return bitmap
        }
    }

    /** Answers each request with its scene's bitmap at once, on the calling thread. */
    private class SceneImageLoader(private val bitmaps: Map<String, Bitmap>) : ImageLoader {
        override val defaults = DefaultRequestOptions()
        override val components = ComponentRegistry()
        override val memoryCache: MemoryCache? = null
        override val diskCache: DiskCache? = null

        private fun answer(request: ImageRequest): ImageResult {
            val name = request.data.toString().substringAfterLast('/')
            val bitmap = bitmaps[name]
                ?: return ErrorResult(null, request, IllegalStateException("no scene for $name"))
            return SuccessResult(BitmapDrawable(request.context.resources, bitmap), request, DataSource.MEMORY)
        }

        override fun enqueue(request: ImageRequest): Disposable {
            val result = answer(request)
            return object : Disposable {
                override val job: Deferred<ImageResult> = CompletableDeferred(result)
                override val isDisposed: Boolean = true
                override fun dispose() = Unit
            }
        }

        override suspend fun execute(request: ImageRequest): ImageResult = answer(request)
        override fun shutdown() = Unit
        override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException("test loader")
    }
}
