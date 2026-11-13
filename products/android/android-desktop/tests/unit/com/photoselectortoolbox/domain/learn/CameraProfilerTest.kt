package com.photoselectortoolbox.domain.learn

import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The camera class rules of the photo researcher (`SD2B_RESEARCH.md` §1, shoot debrief SD-2b),
 * one row per class and per edge: values only, no photo. Before SD-2b a 1/2.3" compact with a
 * 4.5 mm lens and an RX100 were "phones" (any physical focal length under 10.5 mm with a 35 mm
 * equivalent of 18 mm or more, whatever the make).
 */
@Requirement("REQ-AND-COACH.01")
class CameraProfilerTest {

    private data class Row(
        val name: String,
        val exif: ExifData?,
        val expected: CameraClass,
        val width: Int? = null,
    )

    private fun exif(
        make: String?,
        model: String?,
        f: Double? = null,
        f35: Double? = null,
        shutter: Double? = 1.0 / 125,
        zoom: Double? = null,
        gimbal: Double? = null,
        fpx: Double? = null,
        fpUnit: Int? = null,
    ) = ExifData(
        make = make, model = model, focalLength = f, focalLength35mm = f35, shutterSpeed = shutter,
        digitalZoomRatio = zoom, gimbalPitchDegrees = gimbal, focalPlaneXResolution = fpx, focalPlaneResolutionUnit = fpUnit,
    )

    private val rows = listOf(
        Row("full frame", exif("SONY", "ILCE-7M4", 50.0, 50.0), CameraClass.INTERCHANGEABLE),
        Row("APS-C", exif("FUJIFILM", "X-T5", 23.0, 35.0), CameraClass.INTERCHANGEABLE),
        Row("Micro Four Thirds", exif("OM Digital Solutions", "OM-1", 25.0, 50.0), CameraClass.INTERCHANGEABLE),
        Row("large-sensor fixed lens", exif("FUJIFILM", "X100V", 23.0, 35.0), CameraClass.INTERCHANGEABLE),
        // Canon rarely writes a 35 mm equivalent: the focal-plane route, with the real pixel width.
        Row("Canon by focal-plane tags", exif("Canon", "Canon EOS 100D", 35.0, null, fpx = 5184.0 / 22.3, fpUnit = 4), CameraClass.INTERCHANGEABLE, width = 5184),
        Row("1-inch compact (RX100)", exif("SONY", "DSC-RX100M7", 9.0, 24.0), CameraClass.COMPACT),
        Row("1/2.3-inch compact at crop 5.6 with 4.5 mm", exif("Canon", "Canon PowerShot SX740 HS", 4.5, 25.0), CameraClass.COMPACT),
        Row("Pixel", exif("Google", "Pixel 8", 6.9, 25.0), CameraClass.PHONE),
        Row("iPhone", exif("Apple", "iPhone 15 Pro", 6.8, 24.0), CameraClass.PHONE),
        Row("Samsung phone", exif("samsung", "SM-S918B", 6.3, 23.0), CameraClass.PHONE),
        Row("Samsung NX camera", exif("SAMSUNG", "NX300", 30.0, 45.0), CameraClass.INTERCHANGEABLE),
        Row("phone make with a long physical focal length", exif("Google", "Lens test rig", 50.0, 50.0), CameraClass.INTERCHANGEABLE),
        Row("DJI drone", exif("DJI", "FC3582", 6.7, 24.0), CameraClass.DRONE),
        Row("DJI Mavic", exif("DJI", "Mavic 3", 12.3, 24.0), CameraClass.DRONE),
        Row("Hasselblad drone camera", exif("Hasselblad", "L2D-20c", 12.3, 24.0), CameraClass.DRONE),
        Row("gimbal tag only", exif("Unknown Aero", "X1", 4.5, 24.0, gimbal = -30.0), CameraClass.DRONE),
        Row("DJI Osmo Action", exif("DJI", "Osmo Action 4", 2.8, 13.0), CameraClass.ACTION),
        Row("GoPro", exif("GoPro", "HERO11 Black", 2.9, 15.0), CameraClass.ACTION),
        Row("Sony action cam", exif("SONY", "FDR-X3000", 2.8, 17.0), CameraClass.ACTION),
        Row("no EXIF", null, CameraClass.UNKNOWN),
        Row("no make or model", exif(null, null, 6.9, 25.0), CameraClass.UNKNOWN),
        Row("a make and nothing else (stripped export)", exif("Apple", "iPhone 15 Pro", shutter = null), CameraClass.UNKNOWN),
        // Digital zoom makes f35 / f wrong, and there are no focal-plane tags: no crop, no class.
        Row("digital zoom without focal-plane tags", exif("SONY", "DSC-HX99", 4.3, 48.0, zoom = 2.0), CameraClass.UNKNOWN),
        // The 35 mm equivalent alone never decides (phones write 24-27 mm).
        Row("35 mm equivalent only", exif("Acme", "Snap", null, 26.0), CameraClass.UNKNOWN),
    )

    @Test
    fun `every row gets its class`() {
        val wrong = rows.mapNotNull { row ->
            val got = CameraProfiler.profile(row.exif, row.width).cameraClass
            if (got == row.expected) null else "${row.name}: ${row.expected} expected, got $got"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the user's override wins`() {
        val pixel = exif("Google", "Pixel 8", 6.9, 25.0)
        val profile = CameraProfiler.profile(pixel, overrides = mapOf(CameraId("Google", "Pixel 8") to CameraClass.COMPACT))
        assertEquals(CameraClass.COMPACT, profile.cameraClass)
        assertEquals(1.0, profile.confidence, 0.0)
    }

    @Test
    fun `the controls follow the class`() {
        assertEquals(emptySet<Control>(), CameraProfiler.profile(null).controls)
        val phone = CameraProfiler.profile(exif("Google", "Pixel 8", 6.9, 25.0)).controls
        assertEquals(false, Control.APERTURE in phone)
        assertEquals(false, Control.SHUTTER in phone)
        val drone = CameraProfiler.profile(exif("DJI", "FC3582", 6.7, 24.0)).controls
        assertEquals(false, Control.APERTURE in drone)
        assertEquals(true, Control.GIMBAL_PITCH in drone)
        assertEquals(true, Control.LENS in CameraProfiler.profile(exif("SONY", "ILCE-7M4", 50.0, 50.0)).controls)
        assertEquals(false, Control.LENS in CameraProfiler.profile(exif("SONY", "DSC-RX100M7", 9.0, 24.0)).controls)
    }

    @Test
    fun `who chose the exposure`() {
        fun by(program: Int? = null, mode: Int? = null, ev: Double? = null) =
            CameraProfiler.profile(
                ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0, shutterSpeed = 1.0 / 30,
                    exposureProgram = program, exposureMode = mode, exposureCompensationEv = ev),
            ).decidedBy
        assertEquals(DecidedBy.PHOTOGRAPHER, by(program = 1))
        assertEquals(DecidedBy.PHOTOGRAPHER, by(mode = 1))
        assertEquals(DecidedBy.PHOTOGRAPHER, by(program = 4))
        assertEquals(DecidedBy.CAMERA, by(program = 3))
        assertEquals(DecidedBy.CAMERA, by(program = 2))
        assertEquals("an exposure bias with no program written means an automatic mode", DecidedBy.CAMERA, by(ev = -0.7))
        assertEquals(DecidedBy.UNKNOWN, by())
        val phone = ExifData(make = "Google", model = "Pixel 8", focalLength = 6.9, focalLength35mm = 25.0, exposureProgram = 1)
        assertEquals("never asked of a phone", DecidedBy.UNKNOWN, CameraProfiler.profile(phone).decidedBy)
    }
}
