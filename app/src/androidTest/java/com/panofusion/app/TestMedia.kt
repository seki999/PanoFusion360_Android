package com.panofusion.app

import android.media.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

/** Reproducible inputs for instrumentation tests. Nothing here is used by the app at runtime. */
object TestMedia {
    fun asset(name: String, directory: File): File {
        val file = File(directory, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    /** Textured sphere colour for a world direction; cells are 1/1600 π wide, as in the native tests. */
    fun sphereRgb(yaw: Double, pitch: Double): Int {
        val u = floor((yaw + PI) * 1600 / PI).toInt() / 10
        val v = floor((pitch + PI / 2) * 1600 / PI).toInt() / 10
        var hash = (u * 73856093) xor (v * 19349663) xor (17 * 83492791); hash = hash xor (hash ushr 13)
        return android.graphics.Color.rgb(30 + (hash and 191), 30 + ((hash ushr 8) and 191), 30 + ((hash ushr 16) and 191))
    }
    /** A pinhole camera at the sphere centre, rotating by [yawStep] radians per frame. */
    fun perspective(w: Int, h: Int, focal: Double, yawStep: Double): (Int, Int, Int) -> Int = { frame, x, y ->
        val yaw = frame * yawStep
        val rx = (x - w / 2.0) / focal; val ry = (y - h / 2.0) / focal
        val wx = cos(yaw) * rx + sin(yaw); val wz = -sin(yaw) * rx + cos(yaw)
        sphereRgb(atan2(wx, wz), atan2(ry, sqrt(wx * wx + wz * wz)))
    }
    /** H.264 via the device encoder and MediaMuxer: a real, decodable MP4. */
    fun encode(output: File, w: Int, h: Int, frames: Int, fps: Int, pixel: (Int, Int, Int) -> Int) {
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, w * h * 8); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
            val info = MediaCodec.BufferInfo()
            fun drain(eos: Boolean) {
                var waits = 0
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    else if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) { check(started); buffer.position(info.offset); buffer.limit(info.offset + info.size); muxer.writeSampleData(track, buffer, info) }
                        val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false); if (ended) return
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; check(++waits < 1000) { "Encoder EOS timeout" } }
                }
            }
            val rgb = IntArray(w * h)
            for (frame in 0 until frames) {
                for (y in 0 until h) for (x in 0 until w) rgb[y * w + x] = pixel(frame, x, y)
                val index = encoder.dequeueInputBuffer(2_000_000); check(index >= 0)
                val image = encoder.getInputImage(index) ?: error("Encoder does not expose flexible YUV input")
                for (planeId in 0..2) {
                    val plane = image.planes[planeId]; val shift = if (planeId == 0) 0 else 1
                    for (y in 0 until (h shr shift)) for (x in 0 until (w shr shift)) {
                        val c = rgb[(y shl shift) * w + (x shl shift)]
                        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                        val value = when (planeId) { 0 -> ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16; 1 -> ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128; else -> ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128 }.coerceIn(0, 255)
                        plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                    }
                }
                image.close(); encoder.queueInputBuffer(index, 0, w * h * 3 / 2, frame * 1_000_000L / fps, 0); drain(false)
            }
            val end = encoder.dequeueInputBuffer(2_000_000); check(end >= 0)
            encoder.queueInputBuffer(end, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(true)
            encoder.stop(); muxer.stop(); started = false
        } finally { encoder.release(); if (started) runCatching { muxer.stop() }; muxer.release() }
    }
    /**
     * Adds Spherical Video V2 `st3d` (mono) and `sv3d/proj/equi` boxes to the visual sample entry,
     * the same structure 360° cameras write. Parent sizes and chunk offsets are patched.
     */
    fun injectMonoErp(file: File) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        fun size(at: Int) = buffer.getInt(at)
        fun type(at: Int) = String(bytes, at + 4, 4, Charsets.US_ASCII)
        fun child(start: Int, end: Int, name: String): Int {
            var at = start
            while (at + 8 <= end) { if (type(at) == name) return at; at += size(at).also { check(it >= 8) } }
            error("box $name not found")
        }
        val moov = child(0, bytes.size, "moov"); val mdat = child(0, bytes.size, "mdat")
        val path = mutableListOf(moov)
        for (name in listOf("trak", "mdia", "minf", "stbl")) path += child(path.last() + 8, path.last() + size(path.last()), name)
        val stsd = child(path.last() + 8, path.last() + size(path.last()), "stsd"); path += stsd
        val entry = stsd + 16; check(type(entry) == "avc1"); path += entry
        fun box(name: String, payload: ByteArray) = ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(name.toByteArray()).put(payload).array()
        val st3d = box("st3d", byteArrayOf(0, 0, 0, 0, 0))
        val svhd = box("svhd", byteArrayOf(0, 0, 0, 0, 0))
        val prhd = box("prhd", ByteArray(4 + 12))
        val equi = box("equi", ByteArray(4 + 16))
        val sv3d = box("sv3d", svhd + box("proj", prhd + equi))
        val insert = st3d + sv3d
        val at = entry + size(entry)
        val out = bytes.copyOfRange(0, at) + insert + bytes.copyOfRange(at, bytes.size)
        val patched = ByteBuffer.wrap(out)
        for (box in path) patched.putInt(box, size(box) + insert.size)
        if (moov < mdat) {
            // moov precedes the media data, so every absolute chunk offset moves by the inserted length.
            val stbl = path[4]; val stblEnd = stbl + size(stbl) + insert.size
            var p = stbl + 8
            while (p + 8 <= stblEnd) {
                val s = patched.getInt(p); val t = String(out, p + 4, 4, Charsets.US_ASCII)
                if (t == "stco") { val n = patched.getInt(p + 12); for (i in 0 until n) patched.putInt(p + 16 + 4 * i, patched.getInt(p + 16 + 4 * i) + insert.size) }
                if (t == "co64") { val n = patched.getInt(p + 12); for (i in 0 until n) patched.putLong(p + 16 + 8 * i, patched.getLong(p + 16 + 8 * i) + insert.size) }
                p += s
            }
        }
        file.writeBytes(out)
    }
    suspend fun process(app: PanoApplication, projectId: String, timeoutMs: Long = 300_000): Project {
        val manager = WorkManager.getInstance(app)
        val previous = manager.getWorkInfosForUniqueWork("panorama-$projectId").get().map { it.id }.toSet()
        PanoramaWorker.enqueue(app, projectId)
        withTimeout(timeoutMs) { manager.getWorkInfosForUniqueWorkFlow("panorama-$projectId").first { infos -> infos.any { it.id !in previous && it.state.isFinished } } }
        return requireNotNull(app.store.dao.project(projectId))
    }
    fun validationDirectory(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "validation").apply { mkdirs() }
}
