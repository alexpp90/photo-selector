package com.photoselector.saf

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bundle naming against the shared companion table `testdata/formats/companions.json` (B-6):
 * every bundle the table groups shares one primary stem, so a collision gives every member the
 * same `-N` and pairing survives the transfer. Grouping itself is each product's (their
 * `CompanionGroupingTest`); this holds the naming to the same table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCompanionTableTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "testdata/formats/companions.json").isFile) return dir
            dir = dir.parentFile
        }
        error("testdata/formats/companions.json not found above ${File("").absolutePath}")
    }

    private fun bundles(): List<Pair<String, List<String>>> {
        val cases = JSONObject(File(repoRoot(), "testdata/formats/companions.json").readText()).getJSONArray("cases")
        return (0 until cases.length()).flatMap { i ->
            val case = cases.getJSONObject(i)
            val bundles = case.getJSONArray("bundles")
            (0 until bundles.length()).map { b ->
                val names = bundles.getJSONArray(b)
                case.getString("name") to (0 until names.length()).map(names::getString)
            }
        }
    }

    @Test
    fun `the table is read`() {
        assertTrue(bundles().size >= 5)
    }

    @Test
    fun `every member of a table bundle has its shot's primary stem`() {
        for ((case, bundle) in bundles()) {
            val stem = BundleNaming.extractPrimaryStem(bundle.first()).lowercase()
            for (member in bundle) {
                assertEquals("$case: $member", stem, BundleNaming.extractPrimaryStem(member).lowercase())
            }
        }
    }

    @Test
    fun `a collision on the shot gives every member of a table bundle the same -2`() {
        for ((case, bundle) in bundles()) {
            val resolved = BundleNaming.resolveCollisionFreeBundleNames(
                fileNames = bundle,
                existingNamesByFolder = mapOf("ONE" to setOf(bundle.first())),
                folderForFile = { "ONE" },
            )
            val stem = BundleNaming.extractPrimaryStem(bundle.first())
            for (member in bundle) {
                val renamed = resolved.getValue(member)
                assertTrue("$case: $member -> $renamed", renamed.startsWith("$stem-2", ignoreCase = true))
                assertEquals("$case: $renamed keeps the bundle stem", "$stem-2".lowercase(), BundleNaming.extractPrimaryStem(renamed).lowercase())
            }
        }
    }

    @Test
    fun `the table's derivative suffixes are the ones the naming knows`() {
        val tableText = File(repoRoot(), "testdata/formats/companions.json").readText()
        for (suffix in BundleNaming.DERIVATIVE_SUFFIXES) {
            assertTrue("$suffix appears in the table", tableText.contains(suffix, ignoreCase = true))
        }
    }
}
