package com.photoselectortoolbox.conformance

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.DocumentsContract
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.ImageRepositoryImpl
import com.photoselectortoolbox.data.source.LocalImageSourceImpl
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.data.source.PhotoImageLoader
import com.photoselectortoolbox.domain.format.ImageFormats
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Format conformance of Android Desktop against testdata/formats/manifest.json
 * (docs/shared/SUPPORTED_FORMATS.md), through the product's real paths:
 *
 * - **discover** — [LocalImageSourceImpl.discoverImages] walking a SAF-shaped
 *   provider over real files, including UPPER- and lower-case extensions;
 * - **pair** — Android Desktop shows a RAW and its JPEG as two frames (it has
 *   no companion pairing); the product rule checked is that both are
 *   discovered, the RAW is classified RAW, and filing sorts the RAW into
 *   `RAW/` and the JPEG into `JPEG/`;
 * - **display** — the app's Coil [PhotoImageLoader] with a 1024 px request,
 *   exactly what the frames issue;
 * - **exif** — [ImageRepositoryImpl.getExifData] on the content URI;
 * - **score** — [PhotoBitmapDecoder.decodeForAnalysis], the bitmap the OpenCV
 *   analysers receive (the OpenCV maths needs native libraries a JVM test
 *   cannot load; it is format-independent once the bitmap exists).
 *
 * The RAW corpus runs when downloaded (`python3 scripts/fetch_format_corpus.py`)
 * and is mandatory with PST_REQUIRE_FORMAT_CORPUS=1. [KNOWN_GAPS] is strict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormatConformanceTest {

    private enum class Cap { DISCOVER, PAIR, DISPLAY, EXIF, SCORE }

    private companion object {
        /** (sample, capability) → reason. Strict: an entry that starts passing fails the test. */
        val KNOWN_GAPS: Map<Pair<String, Cap>, String> = mapOf(
            ("heif-sample" to Cap.EXIF) to
                "ExifInterface reads HEIF through MediaMetadataRetriever, which Robolectric does not implement",
            ("heif-sample" to Cap.DISPLAY) to
                "Robolectric's native graphics has no HEIF codec (devices decode HEIF on API 28+)",
            ("heif-sample" to Cap.SCORE) to
                "Robolectric's native graphics has no HEIF codec (devices decode HEIF on API 28+)",
            ("dng-pixel4a" to Cap.DISPLAY) to
                "672 px embedded preview; the platform DNG decode it falls back to is absent under Robolectric",
            ("dng-pixel4a" to Cap.SCORE) to
                "672 px embedded preview; the platform DNG decode it falls back to is absent under Robolectric",
        )
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri(DirectoryDocumentsProvider.AUTHORITY, DirectoryDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Robolectric.buildContentProvider(DirectoryDocumentsProvider::class.java)
            .create(DirectoryDocumentsProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── a. Contract ───────────────────────────────────────────────────────

    @Test
    fun `every manifest extension is recognised in any case`() {
        val missing = FormatManifest.formats.flatMap { f ->
            f.extensions.flatMap { e ->
                listOf(e, e.uppercase(), e.replaceFirstChar { it.uppercase() })
                    .filterNot { ImageFormats.isSupported("DSC00001.$it") }
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `RAW formats are classified RAW and sorted into RAW, bitmaps are not`() {
        for (f in FormatManifest.formats) for (e in f.extensions) for (ext in listOf(e, e.uppercase())) {
            val name = "IMG_0001.$ext"
            assertEquals("$name isRaw", f.kind == "raw", ImageFormats.isRaw(name))
            if (f.kind == "raw") {
                assertEquals(name, ImageFormats.SelectionSubfolder.RAW, ImageFormats.selectionSubfolder(name))
            }
        }
        assertEquals(ImageFormats.SelectionSubfolder.JPEG, ImageFormats.selectionSubfolder("a.JPG"))
        assertEquals(ImageFormats.SelectionSubfolder.JPEG, ImageFormats.selectionSubfolder("a.jpeg"))
        assertEquals(ImageFormats.SelectionSubfolder.RAW, ImageFormats.selectionSubfolder("a-Edit.jpg"))
        assertEquals(ImageFormats.SelectionSubfolder.RAW, ImageFormats.selectionSubfolder("a.CR3.xmp"))
        assertEquals(ImageFormats.SelectionSubfolder.ROOT, ImageFormats.selectionSubfolder("a.xmp"))
        assertEquals(ImageFormats.SelectionSubfolder.ROOT, ImageFormats.selectionSubfolder("a.heic"))
        assertFalse(ImageFormats.isSupported("notes.txt"))
        assertFalse(ImageFormats.isSupported("README"))
    }

    // ── b./c. Samples ─────────────────────────────────────────────────────

    @Test
    fun `committed bitmaps - discover, display, EXIF, score`() {
        run(FormatManifest.repoSamples)
    }

    @Test
    fun `corpus RAW samples - discover, pair, display, EXIF, score`() {
        FormatManifest.requireCorpus()
        run(FormatManifest.corpusSamples)
    }

    private fun link(target: File, name: String, dir: File): File {
        val f = File(dir, name)
        Files.createSymbolicLink(f.toPath(), target.absoluteFile.toPath())
        return f
    }

    private fun run(samples: List<FormatManifest.Sample>) {
        val results = linkedMapOf<String, Map<Cap, List<String>>>()
        val loader = PhotoImageLoader.build(context)
        val decoder = PhotoBitmapDecoder(context)
        val repository = ImageRepositoryImpl(
            LocalImageSourceImpl(context, Dispatchers.IO), AndroidExifReader(), MediaStoreReader(), mockk(relaxed = true),
            Dispatchers.IO,
        )
        val sampleJpeg = File(FormatManifest.repoRoot, "testdata/formats/bitmaps/sample.jpg")

        for (s in samples) {
            val source = FormatManifest.fileOf(s)
            val isRaw = FormatManifest.formatOf(s).kind == "raw"
            val ext = source.extension
            val dir = tmp.newFolder(s.id)
            DirectoryDocumentsProvider.root = dir
            val upper = link(source, "UP_${s.id}.${ext.uppercase()}", dir)
            val lower = link(source, "lo_${s.id}.${ext.lowercase()}", dir)
            val companion = if (isRaw) link(sampleJpeg, "UP_${s.id}.JPG", dir) else null
            File(dir, "notes.txt").writeText("not a photo")

            val row = linkedMapOf<Cap, List<String>>()
            val discovered: List<ImageItem> = try {
                runBlocking { LocalImageSourceImpl(context, Dispatchers.IO).discoverImages(treeUri).last() }
            } catch (t: Throwable) {
                emptyList()
            }
            val names = discovered.flatMap { it.allFiles.map { f -> f.fileName } }.toSet()
            val expectedNames = listOfNotNull(upper.name, lower.name, companion?.name).toSet()
            row[Cap.DISCOVER] = if (names == expectedNames) emptyList() else listOf("discovered $names, expected $expectedNames")

            val targetFile = discovered.flatMap { it.allFiles }.firstOrNull { it.fileName == upper.name }
            row[Cap.PAIR] = if (!isRaw) emptyList() else buildList {
                val jpg = discovered.firstOrNull { it.fileName == companion?.name }
                val rawCompanion = jpg?.companions?.firstOrNull { it.fileName == upper.name }
                if (jpg == null || rawCompanion == null) add("RAW or JPEG companion not grouped under JPEG primary")
                if (discovered.any { it.fileName == upper.name }) add("Paired RAW emitted as duplicate primary ImageItem")
                if (rawCompanion != null && !ImageFormats.isRaw(rawCompanion.fileName)) add("RAW not classified RAW")
                if (rawCompanion != null && ImageFormats.selectionSubfolder(rawCompanion.fileName) != ImageFormats.SelectionSubfolder.RAW) add("RAW not sorted into RAW/")
                if (jpg != null && ImageFormats.selectionSubfolder(jpg.fileName) != ImageFormats.SelectionSubfolder.JPEG) add("JPEG not sorted into JPEG/")
            }

            val required = minOf(FormatManifest.minDisplayLongEdge, s.expect.longEdgePx)
            if (targetFile == null) {
                listOf(Cap.DISPLAY, Cap.EXIF, Cap.SCORE).forEach { row[it] = listOf("not discovered") }
            } else {
                val uri = Uri.parse(targetFile.uri)
                row[Cap.DISPLAY] = capture {
                    val result = runBlocking {
                        loader.execute(ImageRequest.Builder(context).data(targetFile.uri).size(1024).build())
                    }
                    val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    when {
                        bmp == null -> listOf("display failed: $result")
                        maxOf(bmp.width, bmp.height) < required -> listOf("display ${bmp.width}x${bmp.height} < $required")
                        else -> emptyList()
                    }
                }
                row[Cap.EXIF] = capture {
                    val exif = runBlocking { repository.getExifData(context, uri) }
                    exifProblems(exif, s.expect)
                }
                row[Cap.SCORE] = capture {
                    val bmp = decoder.decodeForAnalysis(uri, targetFile.fileName)
                    when {
                        bmp == null -> listOf("analysis decode failed")
                        maxOf(bmp.width, bmp.height) < required -> listOf("analysis ${bmp.width}x${bmp.height} < $required")
                        maxOf(bmp.width, bmp.height) > PhotoBitmapDecoder.ANALYSIS_MAX_LONG_EDGE * 2 ->
                            listOf("analysis ${bmp.width}x${bmp.height} exceeds the analysis cap")
                        else -> emptyList()
                    }
                }
            }
            results[s.id] = row
        }
        report(results)
    }

    private fun capture(block: () -> List<String>): List<String> = try {
        block()
    } catch (t: Throwable) {
        listOf("threw $t")
    }

    private fun exifProblems(exif: com.photoselector.core.model.ExifData?, e: FormatManifest.Expect): List<String> {
        if (exif == null) return listOf("no ExifData")
        return buildList {
            if (!FormatManifest.sameText(exif.make, e.make)) add("make '${exif.make}' != '${e.make}'")
            if (!FormatManifest.sameText(exif.model, e.model)) add("model '${exif.model}' != '${e.model}'")
            if (exif.iso != e.iso) add("iso ${exif.iso} != ${e.iso}")
            if (!FormatManifest.close(exif.shutterSpeed, e.exposureTimeS)) add("exposure ${exif.shutterSpeed} != ${e.exposureTimeS}")
            if (!FormatManifest.close(exif.aperture, e.fNumber)) add("f ${exif.aperture} != ${e.fNumber}")
            if (!FormatManifest.close(exif.focalLength, e.focalLengthMm)) add("focal ${exif.focalLength} != ${e.focalLengthMm}")
            if (e.lens != null && !FormatManifest.sameText(exif.lens, e.lens)) add("lens '${exif.lens}' != '${e.lens}'")
        }
    }

    private fun report(results: Map<String, Map<Cap, List<String>>>) {
        val caps = Cap.values().toList()
        val table = buildString {
            appendLine("sample".padEnd(24) + caps.joinToString("") { it.name.padEnd(10) })
            for ((id, row) in results) {
                append(id.padEnd(24))
                for (cap in caps) {
                    val p = row[cap].orEmpty()
                    val gap = (id to cap) in KNOWN_GAPS
                    append((if (p.isEmpty()) (if (gap) "FIXED" else "ok") else if (gap) "GAP" else "FAIL").padEnd(10))
                }
                appendLine()
            }
        }
        println(table)
        val failures = results.flatMap { (id, row) ->
            row.filter { (cap, p) -> p.isNotEmpty() && (id to cap) !in KNOWN_GAPS }.map { (cap, p) -> "$id/$cap: $p" }
        }
        val fixed = results.flatMap { (id, row) ->
            row.filter { (cap, p) -> p.isEmpty() && (id to cap) in KNOWN_GAPS }.map { (cap, _) -> "$id/$cap" }
        }
        assertTrue("conformance failures:\n${failures.joinToString("\n")}\n$table", failures.isEmpty())
        assertTrue("known gaps now pass — prune KNOWN_GAPS: $fixed\n$table", fixed.isEmpty())
    }
}
