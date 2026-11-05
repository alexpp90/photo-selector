package com.photoselectortoolbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The spelling of `cmd window fixed-to-user-rotation` per release, and the answers of a shell that
 * did not run it (a Pixel Tablet on API 37: `Unknown command: set-fix-to-user-rotation`, exit 255).
 */
class FixedRotationTest {

    @Test
    fun api30HasOnlyTheOldSpelling_api31AndLaterOnlyTheNewOne() {
        assertEquals("cmd window set-fix-to-user-rotation enabled", FixedRotation.setCommand(30, "enabled"))
        assertEquals("cmd window fixed-to-user-rotation enabled", FixedRotation.setCommand(31, "enabled"))
        assertEquals("cmd window fixed-to-user-rotation default", FixedRotation.setCommand(33, "default"))
        assertEquals("cmd window fixed-to-user-rotation disabled", FixedRotation.setCommand(37, "disabled"))
        assertEquals("cmd window fixed-to-user-rotation", FixedRotation.queryCommand(33))
    }

    @Test
    fun aModeTheCommandDoesNotKnowIsRefusedBeforeItIsSent() {
        assertThrows(IllegalArgumentException::class.java) { FixedRotation.setCommand(33, "true") }
    }

    @Test
    fun theQueryPrintsOneOfTheModes() {
        assertEquals("default", FixedRotation.parseQuery("default\n"))
        assertEquals("enabled", FixedRotation.parseQuery("enabled"))
        assertEquals("disabled", FixedRotation.parseQuery("  disabled \n"))
        assertEquals("enabled_if_no_auto_rotation", FixedRotation.parseQuery("enabled_if_no_auto_rotation\n"))
    }

    @Test
    fun anythingElseIsNoMode_andIsRestoredAsDefaultByTheCaller() {
        assertNull(FixedRotation.parseQuery(""))
        assertNull(FixedRotation.parseQuery("Unknown command: fixed-to-user-rotation"))
        assertNull(FixedRotation.parseQuery("Exception occurred while executing 'x':"))
    }

    @Test
    fun anUnknownCommandIsAFailureOfTheStep() {
        assertNotNull(ShellOutput.failure("Unknown command: set-fix-to-user-rotation\n"))
        assertNotNull(ShellOutput.failure("\n  Unknown command: foo"))
        assertNotNull(ShellOutput.failure("Error: Unknown command: foo"))
        assertNotNull(ShellOutput.failure("Exception occurred while executing 'x':\njava.lang.IllegalArgumentException"))
    }

    @Test
    fun theUsualAnswersAreNotFailures() {
        assertNull(ShellOutput.failure(""))
        assertNull(ShellOutput.failure("Physical size: 1600x2560\nOverride size: 2960x1848\n"))
        assertNull(ShellOutput.failure("default\n"))
        assertNull(ShellOutput.failure("Display: mDisplayId=0\n  init=... Unknown command is in the middle of a line"))
    }
}
