package com.phototok

/**
 * An instrumented test that needs state only a prepared lab device has (a persisted SAF folder
 * grant, a fixture folder). Gradle's connected runs, CI included, leave it out
 * (`testInstrumentationRunnerArguments["notAnnotation"]` in `build.gradle.kts`); run it on a lab
 * device with `adb shell am instrument -w -e class <Test> com.phototok.test/androidx.test.runner.AndroidJUnitRunner`
 * (`playbook-run-android-device-matrix`, Traps).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class LabDeviceOnly
