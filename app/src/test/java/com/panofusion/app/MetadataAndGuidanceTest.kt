package com.panofusion.app

import org.junit.Assert.*
import org.junit.Test

class MetadataAndGuidanceTest {
    private val full = PanoMetadata.Layout(2048, 1024, 0, 0, 2048, 1024)

    @Test fun partialFullCanvasDoesNotClaimSphere() {
        val xmp = PanoMetadata.xmp(full, "360", 42.0, "transparent")
        assertFalse(xmp.contains("GPano:"))
        assertTrue(xmp.contains("pf:CompleteSphere=\"False\""))
        assertTrue(xmp.contains("pf:CoveragePercent=\"42.0000\""))
    }
    @Test fun completeSphereWritesPhotoSphere() {
        val xmp = PanoMetadata.xmp(full, "360", 100.0, "#171A1F")
        assertTrue(xmp.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(xmp.contains("GPano:CroppedAreaImageWidthPixels=\"2048\""))
        assertTrue(xmp.contains("pf:CompleteSphere=\"True\""))
        // A complete 180° project is still half a sphere on a 360° canvas.
        assertFalse(PanoMetadata.xmp(full, "180", 100.0, "transparent").contains("GPano:"))
    }
    @Test fun croppedExportRecordsValidAreaInsideFullCanvas() {
        val xmp = PanoMetadata.xmp(PanoMetadata.Layout(4096, 2048, 1000, 600, 900, 500), "360", 5.0, "transparent")
        assertTrue(xmp.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertTrue(xmp.contains("GPano:CroppedAreaLeftPixels=\"1000\""))
        assertTrue(xmp.contains("GPano:CroppedAreaTopPixels=\"600\""))
        assertTrue(xmp.contains("pf:CompleteSphere=\"False\""))
    }
    @Test fun jpegSegmentFollowsJfif() {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val out = PanoMetadata.insertJpegXmp(jpeg, "<x/>")
        assertArrayEquals(app0, out.copyOfRange(2, 8))
        assertEquals(0xE1, out[9].toInt() and 255)
        val length = ((out[10].toInt() and 255) shl 8) or (out[11].toInt() and 255)
        assertEquals(2 + 29 + 4, length)
        assertTrue(String(out, 12, 28, Charsets.ISO_8859_1) == "http://ns.adobe.com/xap/1.0/")
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), out.copyOfRange(out.size - 2, out.size))
    }
    @Test fun pngChunkHasValidCrc() {
        val ihdr = ByteArray(13)
        val crc = java.util.zip.CRC32().apply { update("IHDR".toByteArray()); update(ihdr) }.value
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) + "IHDR".toByteArray() + ihdr +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()) + byteArrayOf(0, 0, 0, 0) + "IEND".toByteArray() + byteArrayOf(0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
        val out = PanoMetadata.insertPngXmp(png, "<x/>")
        assertEquals("iTXt", String(out, 37, 4, Charsets.US_ASCII))
        val size = java.nio.ByteBuffer.wrap(out, 33, 4).int
        val chunkCrc = java.nio.ByteBuffer.wrap(out, 41 + size, 4).int.toLong() and 0xffffffffL
        assertEquals(java.util.zip.CRC32().apply { update(out, 37, 4 + size) }.value, chunkCrc)
        assertTrue(String(out, 41, size, Charsets.UTF_8).startsWith("XML:com.adobe.xmp"))
    }
    @Test fun recommendationStaysAdjacentToCoverage() {
        // 36×18 grid (10° cells); coverage is a block around yaw 0, pitch 0.
        val w = 36; val h = 18
        val mask = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 16..19 && y in 7..10 }
        val grid = CoverageGrid(mask, w, h)
        val (yaw, pitch) = grid.recommend(0.0, 0.0, half = false)!!
        assertFalse(grid.covered(yaw, pitch))
        assertTrue(CoverageGrid.angularDistance(0.0, 0.0, yaw, pitch) < 35)
        assertNull(CoverageGrid(BooleanArray(w * h), w, h).recommend(0.0, 0.0, false))
        assertNull(CoverageGrid(BooleanArray(w * h) { true }, w, h).recommend(0.0, 0.0, false))
    }
    @Test fun recommendationHonoursHalfSphere() {
        val w = 36; val h = 18
        val mask = BooleanArray(w * h) { i -> val x = i % w; x !in 30..35 && x !in 0..1 }
        assertNull("only |yaw|>90 is missing, outside a 180° target", CoverageGrid(mask, w, h).recommend(0.0, 0.0, half = true))
    }
    @Test fun guidanceWrapsAcrossSeam() {
        assertEquals("向右转 20°", Guidance.instruction(170.0, 0.0, -170.0, 0.0))
        assertEquals("向左转 30°", Guidance.instruction(10.0, 0.0, -20.0, 0.0))
        assertEquals("抬高手机 40°", Guidance.instruction(0.0, 0.0, 5.0, 40.0))
        assertEquals("降低手机 30°", Guidance.instruction(0.0, 10.0, 0.0, -20.0))
        assertEquals("保持当前方向", Guidance.instruction(0.0, 0.0, 5.0, -5.0))
    }
}

class KeyframeBudgetTest {
    @Test fun longVideoIsThinnedEvenlyAndEveryAssetKeepsAFrame() {
        val frames = (0 until 95).map { "video" to it } + listOf("photo" to 0)
        val kept = KeyframeBudget.thin(frames, 24) { it.first }
        assertEquals(24, kept.size)
        assertTrue(kept.contains("photo" to 0))
        val video = kept.filter { it.first == "video" }.map { it.second }
        assertEquals(0, video.first()); assertEquals(94, video.last())
        assertEquals(video.sorted(), video)
        val gaps = video.zipWithNext { a, b -> b - a }
        assertTrue("even spacing", gaps.max() - gaps.min() <= 1)
    }
    @Test fun withinBudgetIsUnchanged() {
        val frames = (0 until 10).map { "a" to it }
        assertEquals(frames, KeyframeBudget.thin(frames, 24) { it.first })
    }
}
