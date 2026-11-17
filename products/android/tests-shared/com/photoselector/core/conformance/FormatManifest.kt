package com.photoselector.core.conformance

import org.json.JSONObject
import org.junit.Assume
import java.io.File
import kotlin.math.abs

/**
 * testdata/formats/manifest.json — the cross-product format contract — and the
 * corpus it points at. See docs/shared/SUPPORTED_FORMATS.md.
 */
internal object FormatManifest {

    data class Format(val id: String, val tier: String, val kind: String, val extensions: List<String>)

    data class Expect(
        val longEdgePx: Int,
        val make: String,
        val model: String,
        val iso: Int,
        val exposureTimeS: Double,
        val fNumber: Double,
        val focalLengthMm: Double,
        val lens: String?,
    )

    data class Sample(val id: String, val format: String, val file: String, val source: String, val expect: Expect)

    val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "testdata/formats/manifest.json").isFile) dir = dir.parentFile
        requireNotNull(dir) { "testdata/formats/manifest.json not found above ${System.getProperty("user.dir")}" }
    }

    private val json: JSONObject by lazy {
        JSONObject(File(repoRoot, "testdata/formats/manifest.json").readText())
    }

    val minDisplayLongEdge: Int by lazy { json.getJSONObject("display").getInt("min_long_edge_px") }
    val relativeTolerance: Double by lazy { json.getJSONObject("exif_tolerance").getDouble("relative") }

    val formats: List<Format> by lazy {
        val arr = json.getJSONArray("formats")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ext = o.getJSONArray("extensions")
            Format(o.getString("id"), o.getString("tier"), o.getString("kind"),
                (0 until ext.length()).map { ext.getString(it) })
        }
    }

    val samples: List<Sample> by lazy {
        val arr = json.getJSONArray("samples")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val e = o.getJSONObject("expect")
            Sample(
                id = o.getString("id"),
                format = o.getString("format"),
                file = o.getString("file"),
                source = o.getString("source"),
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

    val repoSamples: List<Sample> get() = samples.filter { it.source == "repo" }
    val corpusSamples: List<Sample> get() = samples.filter { it.source == "corpus" }

    fun formatOf(sample: Sample): Format = formats.first { it.id == sample.format }

    val corpusDir: File by lazy {
        System.getenv("PST_FORMAT_CORPUS")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache/photo-selector-toolbox/format-corpus")
    }

    fun fileOf(sample: Sample): File =
        if (sample.source == "repo") File(repoRoot, "testdata/formats/${sample.file}")
        else File(corpusDir, sample.file)

    /**
     * Skip (or, with PST_REQUIRE_FORMAT_CORPUS=1, fail) when any corpus file is missing.
     */
    fun requireCorpus() {
        val missing = corpusSamples.filterNot { fileOf(it).isFile }.map { it.file }
        if (missing.isEmpty()) return
        val message = "Format corpus incomplete in $corpusDir (missing ${missing.size}: " +
            "${missing.take(3)}…). Fetch it with: python3 scripts/fetch_format_corpus.py"
        if (System.getenv("PST_REQUIRE_FORMAT_CORPUS") == "1") throw AssertionError(message)
        Assume.assumeTrue(message, false)
    }

    fun close(actual: Double?, expected: Double): Boolean =
        actual != null && abs(actual - expected) <= relativeTolerance * abs(expected)

    fun sameText(actual: String?, expected: String): Boolean =
        actual != null && actual.trim().equals(expected.trim(), ignoreCase = true)
}
