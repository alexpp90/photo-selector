package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity

/**
 * The kind of camera a photo was taken with (shoot debrief): a debrief groups a mixed folder by
 * it, never averages across it. The rules that decide a photo's class are SD-2b's (`CameraProfile`,
 * reviewed by the photo researcher before they are coded); until they land every photo is
 * [UNKNOWN] unless the user set a class for its camera, and an unknown camera gets image-based
 * counts only (C2: Learn never guesses a camera's controls).
 */
enum class CameraClass(val key: String) {
    PHONE("phone"),
    COMPACT("compact"),
    INTERCHANGEABLE("interchangeable"),
    DRONE("drone"),
    ACTION("action"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun ofKey(key: String?): CameraClass = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/** A camera as EXIF names it: the key of a user's class override (`camera_overrides`). */
data class CameraId(val make: String, val model: String) {
    /** "Canon EOS 100D", not "Canon Canon EOS 100D": many makers repeat the make in the model. */
    val displayName: String
        get() = if (make.isEmpty() || model.startsWith(make, ignoreCase = true)) model.ifEmpty { make } else "$make $model"

    companion object {
        /** The camera of a photo's stored facts, or null when it has neither make nor model. */
        fun of(facts: List<TechniqueFactEntity>?): CameraId? {
            val make = facts?.firstOrNull { it.kind == ScanFacts.CAMERA_MAKE }?.value?.trim().orEmpty()
            val model = facts?.firstOrNull { it.kind == ScanFacts.CAMERA_MODEL }?.value?.trim().orEmpty()
            return if (make.isEmpty() && model.isEmpty()) null else CameraId(make, model)
        }
    }
}

/** Which [CameraClass] a photo's stored facts belong to. */
fun interface CameraClassifier {
    fun classify(facts: List<TechniqueFactEntity>): CameraClass

    companion object {
        /**
         * The user's override for the photo's camera, else the class SD-2b's detector stores as
         * `camera.class`, else [CameraClass.UNKNOWN]: no rule here guesses one.
         */
        fun withOverrides(overrides: Map<CameraId, CameraClass>): CameraClassifier = CameraClassifier { facts ->
            CameraId.of(facts)?.let(overrides::get)
                ?: facts.firstOrNull { it.kind == "camera.class" }?.let { CameraClass.ofKey(it.value) }
                ?: CameraClass.UNKNOWN
        }
    }
}
