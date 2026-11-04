package com.photoselectortoolbox.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMP rating merge (FD-D-12, FD-D-13): only the XMP namespace's Rating, under its own prefix. */
class XmpSidecarWriterTest {

    private val ns = """xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:MicrosoftPhoto="http://ns.microsoft.com/photo/1.0/""""

    @Test
    fun `a Windows packet keeps its MicrosoftPhoto rating and gets one xmp Rating`() {
        val packet = """<rdf:Description rdf:about="" $ns><xmp:Rating>3</xmp:Rating><MicrosoftPhoto:Rating>50</MicrosoftPhoto:Rating></rdf:Description>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 5)!!

        assertEquals(
            packet.replace("<xmp:Rating>3</xmp:Rating>", "<xmp:Rating>5</xmp:Rating>"),
            updated,
        )
    }

    @Test
    fun `a Windows packet in attribute form changes only the xmp attribute`() {
        val packet = """<rdf:Description rdf:about="" $ns xmp:Rating="3" MicrosoftPhoto:Rating="50"/>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 5)!!

        assertEquals(packet.replace("xmp:Rating=\"3\"", "xmp:Rating=\"5\""), updated)
    }

    @Test
    fun `a packet that binds the xap prefix keeps it`() {
        val packet = """<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/"><xap:Rating>2</xap:Rating></rdf:Description>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 4)!!

        assertEquals(packet.replace(">2<", ">4<"), updated)
        assertFalse(updated.contains("xmp:"))
    }

    @Test
    fun `only a MicrosoftPhoto rating means the XMP rating is added beside it`() {
        val packet = """<rdf:Description rdf:about="" $ns><MicrosoftPhoto:Rating>75</MicrosoftPhoto:Rating></rdf:Description>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 4)!!

        assertTrue(updated.contains("<MicrosoftPhoto:Rating>75</MicrosoftPhoto:Rating>"))
        assertTrue(updated.contains("<xmp:Rating>4</xmp:Rating>"))
    }

    @Test
    fun `a prefix xmp bound to another namespace is not the XMP rating`() {
        val packet = """<rdf:Description rdf:about="" xmlns:xmp="urn:other"><xmp:Rating>2</xmp:Rating></rdf:Description>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 4)!!

        assertTrue(updated.contains("<xmp:Rating>2</xmp:Rating>"))
        assertTrue(updated.contains("<xmp:Rating xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">4</xmp:Rating>"))
    }

    @Test
    fun `content the rating cannot be merged into gives null, never a fresh packet`() {
        assertNull(XmpSidecarWriter.injectOrUpdateRating("<x:xmpmeta><other/></x:xmpmeta>", 3))
        // An open <rdf:Description with neither a closing tag nor "/>".
        assertNull(XmpSidecarWriter.injectOrUpdateRating("<x:xmpmeta><rdf:Description rdf:about=\"\"><crs:Look>keep</crs:Look></x:xmpmeta>", 3))
        assertFalse(XmpSidecarWriter.canUpdateInPlace("<x:xmpmeta><other/></x:xmpmeta>"))
        assertTrue(XmpSidecarWriter.canUpdateInPlace(null))
    }

    @Test
    fun `a self-closing description is opened around the rating and keeps its attributes`() {
        val packet = """<x:xmpmeta><rdf:Description rdf:about="" crs:Look="keep" /></x:xmpmeta>"""

        val updated = XmpSidecarWriter.injectOrUpdateRating(packet, 2)!!

        assertTrue(updated.startsWith("""<x:xmpmeta><rdf:Description rdf:about="" crs:Look="keep">"""))
        assertTrue(updated.contains("<xmp:Rating xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">2</xmp:Rating>"))
        assertTrue(updated.endsWith("</rdf:Description></x:xmpmeta>"))
    }
}
