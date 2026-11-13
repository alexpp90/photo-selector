package com.phototok.conformance

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.request.CachePolicy
import com.photoselector.core.model.ExifData
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.phototok.data.model.ImageItem
import com.phototok.data.source.LocalImageSourceImpl
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.domain.PhotoExtensions
import com.phototok.domain.RelatedFiles
import com.phototok.image.PhotoTokImageLoader
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Format conformance against the shared contract `testdata/formats/manifest.json`.
 *
 * - **Contract** (always): every extension of every format is discovered,
 *   case-insensitively; every `kind: raw` format is RAW for companion pairing,
 *   bitmaps are not.
 * - **Committed bitmaps** (always) and **RAW corpus** (when downloaded with
 *   `scripts/fetch_format_corpus.py`): discovery through the real SAF walk in
 *   [LocalImageSourceImpl], RAW+JPEG pairing through [RelatedFiles] and the
 *   file-type filter, display decode through the app's real Coil loader
 *   ([PhotoTokImageLoader]) with the same model the feed uses (the document
 *   `Uri`), and EXIF through [LocalImageSourceImpl.getExifData].
 *
 * Gaps that genuinely cannot pass are listed in [KNOWN_GAPS]; the list is
 * enforced strictly, so a gap that starts passing fails the test until it is
 * pruned.
 *
 * Robolectric's native graphics mode runs the real Skia decoders, so a decode
 * that succeeds here decodes real pixels rather than a shadow placeholder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormatConformanceTest {

    private enum class Capability { DISCOVER, PAIR, DISPLAY, EXIF }

    private data class Format(val id: String, val tier: String, val kind: String, val extensions: List<String>)

    private data class Expect(
        val longEdgePx: Int,
        val make: String,
        val model: String,
        val iso: Int,
        val exposureTimeS: Double,
        val fNumber: Double,
        val focalLengthMm: Double,
        val lens: String?,
    )

    private data class Sample(val id: String, val format: String, val file: String, val source: String, val expect: Expect)

    private class Manifest(
        val repoRoot: File,
        val formats: List<Format>,
        val samples: List<Sample>,
        val minLongEdgePx: Int,
        val relativeTolerance: Double,
        val cacheEnv: String,
        val requireEnv: String,
        val defaultCacheDir: String,
        val fetchScript: String,
    )

    companion object {
        /**
         * (sample id, capability) → why it cannot pass in PhotoTok today.
         * Enforced strictly in both directions.
         */
        private val KNOWN_GAPS: Map<Pair<String, Capability>, String> = mapOf(
            ("heif-sample" to Capability.DISPLAY) to
                "Robolectric's native graphics has no HEIF codec (devices decode HEIF on API 28+)",
            ("heif-sample" to Capability.EXIF) to
                "ExifInterface reads HEIF through MediaMetadataRetriever, which Robolectric does not implement",
            ("dng-pixel4a" to Capability.DISPLAY) to
                "672 px embedded preview; the platform DNG decode it falls back to is absent under Robolectric",
        )

        /** A high-density phone screen's long edge — what the pager requests. */
        private const val DISPLAY_REQUEST_PX = 2048

        /**
         * Longest a sample's decode may take before it counts as hung. A decode takes well under
         * a second here; 2 of 4 full unit runs on the T14 (2026-11-05) instead sat for > 10 min in
         * `loader.execute` with every Coil worker idle (backlog 2026-11-05).
         */
        internal const val DISPLAY_TIMEOUT_MS = 60_000L

        /**
         * Runs [block] off the test thread and waits at most [timeoutMs] for it. A hung request
         * becomes an [AssertionError] naming [what], instead of blocking the Gradle run: the wait
         * is on `await`, which is cancellable even when the request itself is not.
         */
        internal fun <T> awaitBounded(what: String, timeoutMs: Long, block: suspend () -> T): T {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val work = scope.async { block() }
            try {
                return runBlocking { withTimeoutOrNull(timeoutMs) { work.await() } }
                    ?: throw AssertionError("$what did not finish within ${timeoutMs / 1000.0} s (hung)\n${hangReport()}")
            } finally {
                scope.cancel()
            }
        }

        /**
         * What a recurrence needs to name its cause (the hang never reproduced on demand): the
         * dispatcher `Dispatchers.Main` resolves to — Coil starts every request on
         * `Dispatchers.Main.immediate`, and on Robolectric's own main looper that request waits for
         * the test thread, which is blocked here — and the stacks of the threads that run Coil or
         * coroutine code, plus the waiting thread's.
         */
        internal fun hangReport(): String = buildString {
            append("Dispatchers.Main = ").append(Dispatchers.Main).append('\n')
            val waiting = Thread.currentThread()
            for ((thread, stack) in Thread.getAllStackTraces()) {
                val relevant = thread == waiting ||
                    stack.any { it.className.startsWith("coil.") || it.className.startsWith("kotlinx.coroutines.") }
                if (!relevant) continue
                append("thread \"").append(thread.name).append("\" ").append(thread.state).append('\n')
                stack.take(12).forEach { append("    at ").append(it).append('\n') }
            }
        }

        private val manifest: Manifest by lazy { loadManifest() }

        private val tempDirs = mutableListOf<File>()

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            tempDirs.forEach { it.deleteRecursively() }
        }

        private fun findRepoRoot(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "testdata/formats/manifest.json").isFile) return dir
                dir = dir.parentFile
            }
            error("testdata/formats/manifest.json not found above ${File("").absolutePath}")
        }

        private fun loadManifest(): Manifest {
            val root = findRepoRoot()
            val json = JSONObject(File(root, "testdata/formats/manifest.json").readText())
            val formats = json.getJSONArray("formats").let { arr ->
                (0 until arr.length()).map { i ->
                    val f = arr.getJSONObject(i)
                    val ext = f.getJSONArray("extensions")
                    Format(
                        id = f.getString("id"),
                        tier = f.getString("tier"),
                        kind = f.getString("kind"),
                        extensions = (0 until ext.length()).map { ext.getString(it) },
                    )
                }
            }
            val samples = json.getJSONArray("samples").let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val e = s.getJSONObject("expect")
                    Sample(
                        id = s.getString("id"),
                        format = s.getString("format"),
                        file = s.getString("file"),
                        source = s.getString("source"),
                        expect = Expect(
                            longEdgePx = e.getInt("long_edge_px"),
                            make = e.getString("make"),
                            model = e.getString("model"),
                            iso = e.getInt("iso"),
                            exposureTimeS = e.getDouble("exposure_time_s"),
                            fNumber = e.getDouble("f_number"),
                            focalLengthMm = e.getDouble("focal_length_mm"),
                            lens = if (e.has("lens")) e.getString("lens") else null,
                        ),
                    )
                }
            }
            val corpus = json.getJSONObject("corpus")
            return Manifest(
                repoRoot = root,
                formats = formats,
                samples = samples,
                minLongEdgePx = json.getJSONObject("display").getInt("min_long_edge_px"),
                relativeTolerance = json.getJSONObject("exif_tolerance").getDouble("relative"),
                cacheEnv = corpus.getString("cache_env"),
                requireEnv = corpus.getString("require_env"),
                defaultCacheDir = corpus.getString("default_cache_dir"),
                fetchScript = corpus.getString("fetch_script"),
            )
        }
    }

    private val context: Context = RuntimeEnvironment.getApplication()

    private val loader by lazy { PhotoTokImageLoader.create(context) }

    @Before
    fun setUp() {
        // Coil hops to Main for request lifecycle; the test thread *is* Main, so
        // runBlocking would deadlock on Robolectric's paused looper.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Robolectric.buildContentProvider(FolderDocumentsProvider::class.java)
            .create(FolderDocumentsProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Hang guard ────────────────────────────────────────────────────────

    /** A decode that never finishes fails fast and names the sample, instead of hanging the run. */
    @Test
    fun `a hung decode fails with the sample's name instead of hanging`() {
        val started = System.nanoTime()
        val error = try {
            awaitBounded("decode of heif-sample", timeoutMs = 300) {
                // Not cancellable: the guard must not depend on the request honouring cancellation.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(5_000)
                }
            }
            null
        } catch (e: AssertionError) {
            e
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the hang was not reported", error != null)
        assertTrue("the report names the sample: ${error!!.message}", error.message!!.contains("heif-sample"))
        assertTrue("the report names the Main dispatcher: ${error.message}", error.message!!.contains("Dispatchers.Main = "))
        assertTrue("the report has the waiting thread's stack", error.message!!.contains("thread \"${Thread.currentThread().name}\""))
        assertTrue("the guard waited $elapsedMs ms", elapsedMs < 3_000)
        assertEquals("a finished decode passes its result through", 42, awaitBounded("x", 1_000) { 42 })
    }

    /**
     * The cause of the hang, reproduced on purpose: Coil's interceptor on the paused main looper
     * (what a frozen `DEFAULT_REQUEST_OPTIONS` hands every request) never finishes a decode while
     * the test thread waits; the bounded wait reports it. The display check's own request does
     * not depend on that default.
     */
    @Test
    fun `a decode resumed on the paused main looper hangs, and the display request never uses it`() {
        val dir = newTempDir("main-looper")
        link(File(manifest.repoRoot, "testdata/formats/bitmaps/sample.jpg"), "IMG_1.jpg", dir)
        val uri = Uri.parse(discover(dir).single().uri)
        val frozen = android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher().immediate
        val onMainLooper = displayRequest(uri).newBuilder().interceptorDispatcher(frozen).build()
        val error = try {
            awaitBounded("decode of jpeg-sample", timeoutMs = 2_000) { loader.execute(onMainLooper) }
            null
        } catch (e: AssertionError) {
            e
        }
        assertTrue("the main-looper decode was expected to hang", error?.message?.contains("hung") == true)

        assertEquals(Dispatchers.IO, displayRequest(uri).interceptorDispatcher)
        val result = awaitBounded("decode of jpeg-sample", DISPLAY_TIMEOUT_MS) { loader.execute(displayRequest(uri)) }
        assertTrue("decoded: $result", result is SuccessResult)
    }

    // ── Contract ──────────────────────────────────────────────────────────

    @Test
    fun `every contract extension is discovered case-insensitively`() {
        val missing = manifest.formats.flatMap { format ->
            format.extensions.flatMap { ext -> variantsOf("photo.$ext") }
                .filterNot { PhotoExtensions.isSupported(it) }
        }
        assertTrue("extensions not discovered: $missing", missing.isEmpty())

        // And through the real walk, not just the predicate.
        val names = manifest.formats.flatMap { it.extensions }.flatMap { ext ->
            listOf("a_$ext.$ext", "b_$ext.${ext.uppercase()}", "c_$ext.${mixedCase(ext)}")
        }
        val dir = newTempDir("contract")
        names.forEach { File(dir, it).writeBytes(ByteArray(1)) }
        val discovered = discover(dir).map { it.fileName }.toSet()
        assertEquals(names.toSet(), discovered)
    }

    @Test
    fun `raw formats pair as RAW and bitmaps do not`() {
        manifest.formats.forEach { format ->
            format.extensions.flatMap { variantsOf("shot.$it") }.forEach { name ->
                when (format.kind) {
                    "raw" -> assertTrue("$name must be RAW", PhotoExtensions.isRaw(name))
                    "bitmap" -> assertFalse("$name must not be RAW", PhotoExtensions.isRaw(name))
                    else -> error("unknown kind ${format.kind} for ${format.id}")
                }
            }
        }
        val jpeg = manifest.formats.single { it.id == "jpeg" }
        jpeg.extensions.flatMap { variantsOf("shot.$it") }.forEach {
            assertTrue("$it must be the JPEG companion", PhotoExtensions.isJpeg(it))
        }
    }

    // ── Samples ───────────────────────────────────────────────────────────

    @Test
    fun `committed bitmaps are discovered, displayed and read`() {
        val samples = manifest.samples.filter { it.source == "repo" }
        assertTrue("manifest lists no committed bitmaps", samples.isNotEmpty())
        val results = samples.associate { sample ->
            val file = File(manifest.repoRoot, "testdata/formats/${sample.file}")
            assertTrue("missing committed sample $file", file.isFile)
            sample.id to evaluate(sample, file, pair = false)
        }
        assertMatchesKnownGaps(results)
    }

    @Test
    fun `corpus RAW samples are discovered, paired, displayed and read`() {
        val samples = manifest.samples.filter { it.source == "corpus" }
        val corpusDir = File(
            System.getenv(manifest.cacheEnv)
                ?: manifest.defaultCacheDir.replaceFirst("~", System.getProperty("user.home")),
        )
        val missing = samples.filterNot { File(corpusDir, it.file).isFile }.map { it.file }
        if (missing.isNotEmpty()) {
            val message = "RAW format corpus incomplete in $corpusDir (missing $missing); " +
                "run python3 ${manifest.fetchScript}"
            if (System.getenv(manifest.requireEnv) == "1") throw AssertionError(message)
            assumeTrue(message, false)
        }
        val results = samples.associate { sample ->
            sample.id to evaluate(sample, File(corpusDir, sample.file), pair = true)
        }
        assertMatchesKnownGaps(results)
    }

    // ── Evaluation ────────────────────────────────────────────────────────

    /**
     * Capability → null on pass, failure reason otherwise. The sample is linked
     * into a fresh folder twice — upper- and lower-case extension — and, for
     * RAWs, next to a JPEG of the same stem, then discovered through the real
     * SAF walk; display and EXIF use the discovered document URI.
     */
    private fun evaluate(sample: Sample, file: File, pair: Boolean): Map<Capability, String?> {
        val dir = newTempDir(sample.id)
        val upper = "UP_${sample.id}.${file.extension.uppercase()}"
        val lower = "lo_${sample.id}.${file.extension.lowercase()}"
        val companion = if (pair) "UP_${sample.id}.JPG" else null
        link(file, upper, dir)
        link(file, lower, dir)
        companion?.let { link(File(manifest.repoRoot, "testdata/formats/bitmaps/sample.jpg"), it, dir) }
        File(dir, "notes.txt").writeText("not a photo")

        val all = try { discover(dir) } catch (e: Throwable) { emptyList() }
        val item = all.firstOrNull { it.fileName == upper }
        val results = linkedMapOf<Capability, String?>()
        val expectedNames = listOfNotNull(upper, lower, companion).toSet()
        val names = all.map { it.fileName }.toSet()
        results[Capability.DISCOVER] = if (names == expectedNames) null else "discovered $names, expected $expectedNames"
        if (pair) results[Capability.PAIR] = check { checkPairing(all, upper, companion!!) }
        results[Capability.DISPLAY] = if (item == null) "not discovered" else check { checkDisplay(sample, Uri.parse(item.uri)) }
        results[Capability.EXIF] = if (item == null) "not discovered" else check { checkExif(sample, Uri.parse(item.uri)) }
        return results
    }

    private fun check(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: Throwable) {
        e.message ?: e.toString()
    }

    /** PhotoTok keeps both files in the feed; pairing links them for actions and filters. */
    private fun checkPairing(all: List<ImageItem>, rawName: String, jpegName: String) {
        val raw = all.singleOrNull { it.fileName == rawName } ?: throw AssertionError("RAW not discovered")
        val others = all.filter { it.fileName != rawName && it.fileName != jpegName }
        val jpg = all.singleOrNull { it.fileName == jpegName } ?: throw AssertionError("JPEG not discovered")
        if (!PhotoExtensions.isRaw(raw.fileName)) throw AssertionError("RAW not classified RAW")
        if (RelatedFiles.siblings(all, raw) - others.toSet() != listOf(jpg)) throw AssertionError("RAW has no JPEG companion")
        if (raw !in RelatedFiles.siblings(all, jpg)) throw AssertionError("JPEG has no RAW companion")
        if (!RelatedFiles.hasRawJpegPairs(all)) throw AssertionError("RAW+JPEG pair not detected")
        if (raw !in PhoneFeedOrdering.filterByType(all, FileTypeFilter.RAW)) throw AssertionError("RAW filter drops the RAW")
        if (PhoneFeedOrdering.filterByType(all, FileTypeFilter.JPG) != listOf(jpg)) {
            throw AssertionError("JPG filter does not select exactly the JPEG")
        }
    }

    /**
     * Decodes the way the feed does: a Coil request whose model is the photo's
     * SAF document `Uri`, executed by the app's loader at a phone-screen size.
     */
    private fun checkDisplay(sample: Sample, uri: Uri) {
        val request = displayRequest(uri)
        val result = awaitBounded("decode of ${sample.id}", DISPLAY_TIMEOUT_MS) { loader.execute(request) }
        val drawable = when (result) {
            is SuccessResult -> result.drawable
            is ErrorResult -> throw AssertionError("decode failed: ${result.throwable}")
        }
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: throw AssertionError("not a bitmap: $drawable")
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val required = minOf(manifest.minLongEdgePx, sample.expect.longEdgePx)
        if (longEdge < required) throw AssertionError("decoded ${bitmap.width}x${bitmap.height} < $required")

        // The feed's orientation sort reads header dimensions; RAWs the platform
        // cannot size (CR3) fall back to the embedded preview's.
        if (PhotoExtensions.isRaw(uri.lastPathSegment.orEmpty())) {
            val source = LocalImageSourceImpl(context, mockk(relaxed = true), mockk(relaxed = true))
            val (w, h) = runBlocking { source.getImageDimensions(uri) }
            if (w <= 0 || h <= 0) throw AssertionError("no header dimensions ($w x $h)")
        }
    }

    /**
     * The feed's request for [uri]. Its interceptor dispatcher is pinned: Coil's default is
     * `Dispatchers.Main.immediate` as it was when `coil.util.-Requests.DEFAULT_REQUEST_OPTIONS`
     * was initialised, once per Robolectric sandbox. When an earlier test class of the same
     * sandbox (any screen test that shows a photo) touched Coil while `Dispatchers.Main` was the
     * real main looper, the default stays that looper's dispatcher, the decode resumes there, and
     * the looper is paused while this thread waits: the run hung in 2 of 4 full runs, depending on
     * which classes ran first in the JVM (backlog 2026-11-05).
     */
    private fun displayRequest(uri: Uri): ImageRequest = ImageRequest.Builder(context)
        .data(uri)
        .size(DISPLAY_REQUEST_PX)
        // Never let Coil upscale a small decode to the requested size: the
        // check is about the pixels the source really yields.
        .precision(Precision.INEXACT)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .interceptorDispatcher(Dispatchers.IO)
        .build()

    private fun checkExif(sample: Sample, uri: Uri) {
        val source = LocalImageSourceImpl(context, AndroidExifReader(), MediaStoreReader())
        val exif: ExifData = runBlocking { source.getExifData(uri) }
            ?: throw AssertionError("no EXIF")
        val e = sample.expect
        val problems = buildList {
            if (!exif.make.orEmpty().trim().equals(e.make.trim(), ignoreCase = true)) add("make '${exif.make}' != '${e.make}'")
            if (!exif.model.orEmpty().trim().equals(e.model.trim(), ignoreCase = true)) add("model '${exif.model}' != '${e.model}'")
            if (exif.iso != e.iso) add("iso ${exif.iso} != ${e.iso}")
            if (!close(exif.shutterSpeed, e.exposureTimeS)) add("exposure ${exif.shutterSpeed} != ${e.exposureTimeS}")
            if (!close(exif.aperture, e.fNumber)) add("f-number ${exif.aperture} != ${e.fNumber}")
            if (!close(exif.focalLength, e.focalLengthMm)) add("focal ${exif.focalLength} != ${e.focalLengthMm}")
            if (e.lens != null && !exif.lens.trim().equals(e.lens.trim(), ignoreCase = true)) {
                add("lens '${exif.lens}' != '${e.lens}'")
            }
        }
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("; "))
    }

    private fun close(actual: Double?, expected: Double): Boolean =
        actual != null && abs(actual - expected) <= expected * manifest.relativeTolerance

    // ── Gap bookkeeping ───────────────────────────────────────────────────

    private fun assertMatchesKnownGaps(results: Map<String, Map<Capability, String?>>) {
        val table = buildString {
            appendLine("sample".padEnd(24) + Capability.entries.joinToString("") { it.name.padEnd(10) })
            results.forEach { (id, caps) ->
                append(id.padEnd(24))
                Capability.entries.forEach { cap ->
                    val cell = when {
                        cap !in caps -> "-"
                        caps[cap] == null -> "ok"
                        (id to cap) in KNOWN_GAPS -> "GAP"
                        else -> "FAIL"
                    }
                    append(cell.padEnd(10))
                }
                appendLine()
            }
            results.forEach { (id, caps) ->
                caps.forEach { (cap, reason) -> if (reason != null) appendLine("  $id/$cap: $reason") }
            }
        }
        println(table)
        val failing = results.flatMap { (id, caps) -> caps.filterValues { it != null }.keys.map { id to it } }.toSet()
        val expectedGaps = KNOWN_GAPS.keys.filter { it.first in results.keys }.toSet()
        val unexpected = failing - expectedGaps
        val fixed = expectedGaps - failing
        assertTrue("unexpected failures $unexpected\n$table", unexpected.isEmpty())
        assertTrue("known gaps now pass — prune KNOWN_GAPS: $fixed\n$table", fixed.isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun newTempDir(prefix: String): File =
        Files.createTempDirectory("phototok-$prefix").toFile().also { tempDirs += it }

    private fun variantsOf(name: String): List<String> {
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.')
        return listOf(ext.lowercase(), ext.uppercase(), mixedCase(ext)).map { "$stem.$it" }
    }

    private fun mixedCase(s: String) =
        s.mapIndexed { i, c -> if (i == 0) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")

    private fun link(target: File, name: String, dir: File) {
        Files.createSymbolicLink(File(dir, name).toPath(), target.absoluteFile.toPath())
    }

    /** The real cursor-based SAF walk of [LocalImageSourceImpl] over [dir]. */
    private fun discover(dir: File): List<ImageItem> {
        FolderDocumentsProvider.folder = dir
        val source = LocalImageSourceImpl(context, mockk(relaxed = true), mockk(relaxed = true))
        return runBlocking { source.discoverImages(FolderDocumentsProvider.treeUri).last() }
    }
}
