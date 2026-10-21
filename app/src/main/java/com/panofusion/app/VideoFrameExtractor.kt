package com.panofusion.app

import android.graphics.*
import android.media.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File

/** Bounded streaming decoder. Android YUV planes can have independent row/pixel strides. */
class VideoFrameExtractor {
    data class Metadata(val width: Int, val height: Int, val durationUs: Long, val rotation: Int, val mime: String)
    suspend fun extract(path: String, output: File, samplingUs: Long = 500_000, onFrame: suspend (File, Long, Double) -> Unit): Metadata {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("E_NO_VIDEO：没有可解码的视频轨道")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            require(duration > 0) { "E_DURATION：无法读取视频时长" }
            require(duration <= 180_000_000L) { "首版每段视频限 3 分钟，请先裁剪" }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val meta = Metadata(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), duration, rotation, format.getString(MediaFormat.KEY_MIME)!!)
            require(meta.width.toLong() * meta.height <= 3840L * 2160) { "首版解码上限为 4K，请先降低分辨率" }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(meta.mime)
            codec.configure(format, null, null, 0)
            codec.start()
            output.mkdirs()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var nextUs = 0L
            var idle = 0
            var count = 0
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    idle = 0
                    try {
                        if (info.size > 0 && info.presentationTimeUs >= nextUs) {
                            require(count < 360) { "候选帧超过上限，请缩短视频" }
                            val image = codec.getOutputImage(index) ?: error("E_COLOR：设备解码器不提供 YUV 图像")
                            val bitmap = image.use { yuvBitmap(it) }
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = if (rotation != 0) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
                            val scale = minOf(1.0, 1280.0 / maxOf(rotated.width, rotated.height))
                            val scaled = if (scale < 1) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
                            val file = File(output, "${info.presentationTimeUs}.jpg")
                            try { file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 94, it)) } }
                            finally { if (scaled !== rotated) scaled.recycle(); if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
                            onFrame(file, info.presentationTimeUs, info.presentationTimeUs.toDouble() / duration)
                            nextUs = info.presentationTimeUs + samplingUs
                            count++
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                } else { idle++; require(idle < 3000) { "E_DECODE_TIMEOUT：解码器长时间没有输出" } }
            }
            require(count > 0) { "E_DECODE_EMPTY：视频未产生有效画面" }
            return meta
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException("E_DECODE：无法处理此视频。${e.message ?: e.javaClass.simpleName}。请确认文件完整且设备支持其编码。",e) }
        finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
    private fun yuvBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val w = crop.width() and -2
        val h = crop.height() and -2
        val data = ByteArray(w * h * 3 / 2)
        for (planeIndex in 0..2) {
            val plane = image.planes[planeIndex]
            val shift = if (planeIndex == 0) 0 else 1
            val pw = w shr shift
            val ph = h shr shift
            val left = crop.left shr shift
            val top = crop.top shr shift
            val buffer = plane.buffer
            for (row in 0 until ph) for (col in 0 until pw) {
                val source = (row + top) * plane.rowStride + (col + left) * plane.pixelStride
                val dest = if (planeIndex == 0) row * w + col else w * h + row * w + col * 2 + if (planeIndex == 1) 1 else 0
                data[dest] = buffer.get(source)
            }
        }
        val stream = ByteArrayOutputStream()
        check(YuvImage(data, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, stream))
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("YUV 转换失败")
    }
}
object ImageInput {
    fun prepare(path: String, output: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "E_IMAGE：图片损坏或编码不支持" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败")
        val exif = androidx.exifinterface.media.ExifInterface(path)
        val matrix = Matrix().apply { postRotate(exif.rotationDegrees.toFloat()); if (exif.isFlipped) postScale(-1f, 1f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        try { output.parentFile?.mkdirs(); output.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG, 94, it)) } }
        finally { if (rotated !== source) rotated.recycle(); source.recycle() }
    }
}
