package com.photoselectortoolbox

/**
 * Compose test tags become accessibility resource ids (`testTagsAsResourceId`) only in builds the
 * device lab and instrumented tests drive. The shipped release build does not publish its
 * internal widget names to every accessibility service on the device.
 */
object TestTagExposure {
    private val driven = setOf("debug", "lab")

    fun enabledFor(buildType: String): Boolean = buildType in driven
}
