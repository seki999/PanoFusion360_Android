package com.panofusion.app

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * XMP for exported panoramas. Google Photo Sphere (GPano) tags are written only when they are true:
 * a complete 360° sphere, or an explicit cropped area inside the full canvas. A full-size canvas
 * with missing regions carries only PanoFusion tags, so viewers do not treat filler as image data.
 */
object PanoMetadata {
    data class Layout(val fullWidth: Int, val fullHeight: Int, val left: Int, val top: Int, val width: Int, val height: Int) {
        val cropped get() = width != fullWidth || height != fullHeight
    }
    fun completeSphere(mode: String, coverage: Double?) = mode == "360" && coverage != null && coverage >= 99.95
    fun xmp(layout: Layout, mode: String, coverage: Double?, missingFill: String): String {
        val complete = completeSphere(mode, coverage)
        val gpano = layout.cropped || complete
        val attributes = buildString {
            if (gpano) {
                append(" GPano:ProjectionType=\"equirectangular\" GPano:UsePanoramaViewer=\"True\"")
                append(" GPano:FullPanoWidthPixels=\"${layout.fullWidth}\" GPano:FullPanoHeightPixels=\"${layout.fullHeight}\"")
                append(" GPano:CroppedAreaImageWidthPixels=\"${layout.width}\" GPano:CroppedAreaImageHeightPixels=\"${layout.height}\"")
                append(" GPano:CroppedAreaLeftPixels=\"${layout.left}\" GPano:CroppedAreaTopPixels=\"${layout.top}\"")
            }
            append(" pf:ProjectMode=\"${if (mode == "自定义") "custom" else mode}\"")
            append(" pf:CompleteSphere=\"${if (complete) "True" else "False"}\"")
            append(" pf:CanvasWidthPixels=\"${layout.fullWidth}\" pf:CanvasHeightPixels=\"${layout.fullHeight}\"")
            coverage?.let { append(" pf:CoveragePercent=\"%.4f\"".format(java.util.Locale.ROOT, it)) }
            append(" pf:MissingAreaFill=\"$missingFill\"")
        }
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" xmlns:pf=\"https://panofusion.app/ns/1.0/\"$attributes/>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
    }
    /** Inserts an XMP APP1 segment after SOI and any leading APP0 (JFIF must stay first). */
    fun insertJpegXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "不是 JPEG 数据" }
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val body = xmp.toByteArray(Charsets.UTF_8)
        val length = 2 + header.size + body.size
        require(length <= 65535) { "XMP 过长" }
        var at = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) at = 4 + (((jpeg[4].toInt() and 255) shl 8) or (jpeg[5].toInt() and 255))
        return ByteArrayOutputStream(jpeg.size + length + 2).apply {
            write(jpeg, 0, at); write(0xFF); write(0xE1); write(length ushr 8); write(length and 255); write(header); write(body)
            write(jpeg, at, jpeg.size - at)
        }.toByteArray()
    }
    /** Inserts an uncompressed iTXt "XML:com.adobe.xmp" chunk directly after IHDR. */
    fun insertPngXmp(png: ByteArray, xmp: String): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        require(png.size > 33 && png.copyOfRange(0, 8).contentEquals(signature) && String(png, 12, 4, Charsets.US_ASCII) == "IHDR") { "不是 PNG 数据" }
        val at = 8 + 12 + 13
        val data = ByteArrayOutputStream().apply {
            write("XML:com.adobe.xmp".toByteArray(Charsets.ISO_8859_1)); write(0); write(0); write(0); write(0); write(0)
            write(xmp.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val type = "iTXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value
        fun ByteArrayOutputStream.int(v: Long) { write((v ushr 24).toInt() and 255); write((v ushr 16).toInt() and 255); write((v ushr 8).toInt() and 255); write(v.toInt() and 255) }
        return ByteArrayOutputStream(png.size + data.size + 12).apply {
            write(png, 0, at); int(data.size.toLong()); write(type); write(data); int(crc); write(png, at, png.size - at)
        }.toByteArray()
    }
}
