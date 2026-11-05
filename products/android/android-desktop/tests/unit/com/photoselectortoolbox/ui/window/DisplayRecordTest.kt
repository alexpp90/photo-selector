package com.photoselectortoolbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record `WindowGeometryRule` leaves on the device while it has the display forced, so that a
 * run killed in the middle (Moto G77, 2026-11-05) does not make the next run adopt the leftover
 * tablet geometry as the phone's own.
 */
class DisplayRecordTest {

    private val untouched = DisplayRecord(
        sizeOverride = null,
        densityOverride = null,
        accelerometerRotation = "0",
        userRotation = "0",
        fixedToUserRotation = "default",
        widthDpBefore = 392,
    )

    @Test
    fun aRecordSurvivesTheDevice() {
        val forced = untouched.copy(sizeOverride = "1080x2352", densityOverride = "420", fixedToUserRotation = "enabled")
        assertEquals(untouched, DisplayRecord.decode(untouched.encode()))
        assertEquals(forced, DisplayRecord.decode(forced.encode()))
    }

    @Test
    fun everyModeTheCommandPrintsIsKeptExactly() {
        listOf("default", "enabled", "disabled", "enabled_if_no_auto_rotation").forEach { mode ->
            val record = untouched.copy(fixedToUserRotation = mode)
            assertEquals(mode, DisplayRecord.decode(record.encode())?.fixedToUserRotation)
        }
    }

    @Test
    fun anUnknownRotationMode_isNotTrusted() {
        assertNull(DisplayRecord.decode("size=-,density=-,accel=0,rot=0,fixed=true,width=392"))
    }

    @Test
    fun valuesTheBuildDoesNotGive_comeBackAsNone() {
        val unknown = untouched.copy(fixedToUserRotation = null, widthDpBefore = null)
        assertEquals(unknown, DisplayRecord.decode(unknown.encode()))
    }

    @Test
    fun anUnsetSetting_isKeptAsNullAndComesBackAsNull() {
        // `settings get` prints the word null for a key that has no value.
        val unset = untouched.copy(userRotation = "null", accelerometerRotation = "null")
        assertEquals(unset, DisplayRecord.decode(unset.encode()))
    }

    @Test
    fun noRecord_isNull() {
        // `settings get global key` on a key that is not there.
        assertNull(DisplayRecord.decode("null"))
        assertNull(DisplayRecord.decode("null\n"))
        assertNull(DisplayRecord.decode(""))
        assertNull(DisplayRecord.decode(null))
    }

    @Test
    fun aDamagedRecord_isNotTrusted() {
        assertNull(DisplayRecord.decode("size=banana,density=320,accel=0,rot=0,fixed=false,width=392"))
        assertNull(DisplayRecord.decode("size=-,density=wide,accel=0,rot=0,fixed=false,width=392"))
        assertNull(DisplayRecord.decode("size=-,density=-,fixed=false,width=392"))
        assertNull(DisplayRecord.decode("some other text"))
    }

    @Test
    fun theEncodingIsSafeInsideASingleQuotedShellArgument() {
        val text = untouched.copy(sizeOverride = "1848x2960", densityOverride = "320", fixedToUserRotation = "enabled").encode()
        assertTrue(text, text.none { it == '\'' || it == ' ' || it == ';' || it == '$' || it == '"' })
    }

    @Test
    fun aValueThatCouldBreakTheEncodingIsNeverWritten() {
        val hostile = untouched.copy(userRotation = "1,size=1x1; reboot")
        assertEquals("-", DisplayRecord.decode(hostile.encode())?.userRotation)
    }
}
