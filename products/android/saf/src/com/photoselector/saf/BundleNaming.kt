package com.photoselector.saf

/**
 * Collision-free names for a companion bundle (`docs/shared/FILE_OPERATIONS.md`): a photo and
 * everything that travels with it (RAW/JPEG twin, `.xmp` sidecars in both styles, Lightroom
 * `-Edit` / `_Edit` and `-Enhanced-NR` derivatives) are filed under one shared `-N` suffix,
 * so pairing survives a collision and nothing is ever overwritten.
 *
 * The derivative suffixes are the ones of the shared companion table
 * `testdata/formats/companions.json` (`SafCompanionTableTest` holds them to it).
 */
object BundleNaming {

    val DERIVATIVE_SUFFIXES: List<String> = listOf("-Edit", "_Edit", "-Enhanced-NR", "_Enhanced-NR")

    /**
     * Extract the shared base stem of a photo, sidecar or derivative so a
     * collision suffix (`-2`, `-3`, …) is inserted before `.ext`, `.RAW.xmp`
     * or `-Edit` / `_Edit` / `-Enhanced-NR`.
     */
    fun extractPrimaryStem(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        val withoutXmp = if (name.endsWith(".xmp", ignoreCase = true) && name.length > 4) {
            name.dropLast(4)
        } else {
            name
        }
        val withoutExt = if (withoutXmp.contains('.')) {
            withoutXmp.substringBeforeLast('.')
        } else {
            withoutXmp
        }
        for (suffix in DERIVATIVE_SUFFIXES) {
            if (withoutExt.endsWith(suffix, ignoreCase = true) && withoutExt.length > suffix.length) {
                return withoutExt.dropLast(suffix.length)
            }
        }
        return withoutExt
    }

    /**
     * Insert `-[suffixNumber]` into [fileName] after [primaryStem] so that:
     * - `IMG_0001.CR3` → `IMG_0001-2.CR3`
     * - `IMG_0001.JPG` → `IMG_0001-2.JPG`
     * - `IMG_0001.xmp` → `IMG_0001-2.xmp`
     * - `IMG_0001.CR3.xmp` → `IMG_0001-2.CR3.xmp`
     * - `IMG_0001-Edit.tif` → `IMG_0001-2-Edit.tif`
     * - `IMG_0001_Edit.tif` → `IMG_0001-2_Edit.tif`
     */
    fun suffixFileName(
        fileName: String,
        suffixNumber: Int,
        primaryStem: String = extractPrimaryStem(fileName),
    ): String {
        val name = fileName.substringAfterLast('/')
        val stemLen = if (
            primaryStem.isNotEmpty() &&
            name.startsWith(primaryStem, ignoreCase = true) &&
            (name.length == primaryStem.length || name[primaryStem.length] in charArrayOf('.', '-', '_'))
        ) {
            primaryStem.length
        } else {
            extractPrimaryStem(name).length
        }
        return name.substring(0, stemLen) + "-$suffixNumber" + name.substring(stemLen)
    }

    /**
     * Compute bundle-wide collision-free destination filenames (`-2`, `-3`, …).
     *
     * If any file in [fileNames] collides case-insensitively with an existing
     * name in its target folder ([existingNamesByFolder], keyed by [folderForFile]),
     * the entire bundle receives the lowest integer N ≥ 2 for which **every**
     * member is collision-free in its target folder.
     */
    fun resolveCollisionFreeBundleNames(
        fileNames: List<String>,
        primaryStem: String = fileNames.firstOrNull()?.let(::extractPrimaryStem).orEmpty(),
        existingNamesByFolder: Map<String, Set<String>>,
        folderForFile: (String) -> String,
    ): Map<String, String> {
        if (fileNames.isEmpty()) return emptyMap()

        val lowercaseByFolder: Map<String, Set<String>> = existingNamesByFolder.mapValues { (_, names) ->
            names.mapTo(HashSet(names.size)) { it.lowercase() }
        }

        val anyCollision = fileNames.any { name ->
            val folder = folderForFile(name)
            name.lowercase() in lowercaseByFolder[folder].orEmpty()
        }
        if (!anyCollision) {
            return fileNames.associateWith { it }
        }

        var n = 2
        while (true) {
            val candidateMap = LinkedHashMap<String, String>(fileNames.size)
            var allFree = true
            for (name in fileNames) {
                val candidate = suffixFileName(name, n, primaryStem)
                val folder = folderForFile(name)
                if (candidate.lowercase() in lowercaseByFolder[folder].orEmpty()) {
                    allFree = false
                    break
                }
                candidateMap[name] = candidate
            }
            if (allFree) {
                return candidateMap
            }
            n++
        }
    }
}
