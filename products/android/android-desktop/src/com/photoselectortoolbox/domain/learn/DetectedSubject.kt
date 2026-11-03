package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity

/**
 * The subject the scan actually detected in a photo, for the crop suggestion and the composition
 * coaching built on it (finding FD-B-1).
 *
 * Learn used to feed [CropSuggester] a constant centre box ("confidence 0.70") for every photo, so
 * every photo got the same advice from a subject nobody had found. The only subject box this
 * product detects is [DefaultSubjectDetector]'s, stored by the scan as the
 * `composition.subject_placement` fact with a `subject_box` geometry. No such fact (not scanned,
 * or nothing stood out) means no subject: callers show no crop suggestion.
 */
object DetectedSubject {

    const val PLACEMENT_KIND = "composition.subject_placement"

    /** The detected subject among [facts], or null when none was detected. */
    fun fromFacts(facts: List<TechniqueFactEntity>): SubjectBoundingBox? {
        val fact = facts.firstOrNull { it.kind == PLACEMENT_KIND } ?: return null
        val json = fact.geometryJson ?: return null
        if (!json.contains("\"type\":\"subject_box\"")) return null
        fun number(name: String): Double? =
            Regex("\"$name\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        val xMin = number("xMin") ?: return null
        val yMin = number("yMin") ?: return null
        val xMax = number("xMax") ?: return null
        val yMax = number("yMax") ?: return null
        if (xMax <= xMin || yMax <= yMin) return null
        return SubjectBoundingBox(
            xMin = xMin,
            yMin = yMin,
            xMax = xMax,
            yMax = yMax,
            centroidX = number("centroidX") ?: ((xMin + xMax) / 2),
            centroidY = number("centroidY") ?: ((yMin + yMax) / 2),
            confidence = fact.confidence,
        )
    }
}
