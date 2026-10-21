package com.panofusion.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
class ProjectionTest {
    private fun box(type: String, payload: ByteArray): ByteArray {
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use { it.writeInt(payload.size+8);it.writeBytes(type);it.write(payload) };return bytes.toByteArray()
    }
    private fun detect(bytes: ByteArray): VideoProjection.Type {
        val file=File.createTempFile("projection-test", ".mp4")
        try { file.writeBytes(bytes);return VideoProjection.detect(file) } finally { file.delete() }
    }
    @Test fun aspectRatioAloneNeverMeansErp() { assertEquals(VideoProjection.Type.UNKNOWN,detect(box("ftyp",ByteArray(16)))) }
    @Test fun explicitMonoErp() {
        val projection=box("sv3d",box("proj",box("equi",ByteArray(20))))
        val sample=box("avc1",ByteArray(78)+box("st3d",ByteArray(5))+projection)
        val stsd=box("stsd",ByteArray(8)+sample)
        assertEquals(VideoProjection.Type.MONO_ERP,detect(box("moov",box("trak",box("mdia",box("minf",box("stbl",stsd)))))))
    }
    @Test fun stereoIsRejectedAsMono() {
        assertEquals(VideoProjection.Type.STEREO,detect(box("moov",box("st3d",byteArrayOf(0,0,0,0,1)))))
    }
    @Test fun corruptBoxesDoNotCrash() { assertEquals(VideoProjection.Type.UNKNOWN,detect(byteArrayOf(0,0,0,2,109,111,111,118))) }
}
