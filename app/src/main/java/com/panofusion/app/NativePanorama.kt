package com.panofusion.app

/** The JNI engine owns all OpenCV matrices, and polls cancellation between costly stages. */
object NativePanorama {
    init { System.loadLibrary("opencv_java4"); System.loadLibrary("panofusion") }
    external fun analyze(path: String, previousPath: String?): DoubleArray
    /** [groups] marks frames that share a lens (same source asset); they share one focal length. */
    external fun stitch(paths: Array<String>, groups: IntArray, outputDirectory: String, width: Int, mode: String, anchorIndex: Int, referenceYaw: Double, callback: EngineCallback): Double
    /** Diagnostics: per camera [initial focal, yaw, pitch, adjusted focal, yaw, pitch]; then matchMs, adjustMs, iterations, rmsBefore, rmsAfter. */
    external fun diagnoseCameras(paths: Array<String>, groups: IntArray): DoubleArray
    external fun openLocalizer(keyframes: Array<String>, revisionDirectory: String): Long
    /** [ok, yaw, pitch, roll, focal, inliers, keyframe, rotationResidual, overlayWritten] */
    external fun localize(handle: Long, query: String, overlay: String?, width: Int, height: Int): DoubleArray
    external fun closeLocalizer(handle: Long)
}
data class VisualPose(val yaw: Double, val pitch: Double, val roll: Double, val inliers: Int, val keyframe: Int, val residual: Double)
/** Matches live camera views against the keyframes of a committed revision. Not thread-safe; call off the main thread. */
class VisualLocalizer private constructor(private var handle: Long): AutoCloseable {
    fun localize(query: java.io.File, overlay: java.io.File?, width: Int, height: Int): Pair<VisualPose?, Boolean> {
        check(handle != 0L) { "定位器已关闭" }
        val v = NativePanorama.localize(handle, query.absolutePath, overlay?.absolutePath, width, height)
        return (if (v[0] > 0) VisualPose(v[1], v[2], v[3], v[5].toInt(), v[6].toInt(), v[7]) else null) to (v[8] > 0)
    }
    override fun close() { if (handle != 0L) NativePanorama.closeLocalizer(handle); handle = 0 }
    companion object {
        /** Keyframes are resolved through the revision's inputs.json, in the same order the cameras were saved. */
        fun keyframes(projectDirectory: java.io.File, revision: Int): List<java.io.File> {
            val inputs = java.io.File(projectDirectory, "history/revision-$revision/inputs.json")
            require(inputs.isFile) { "此版本没有关键帧记录" }
            val frames = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Frame.serializer()), inputs.readText())
            return frames.map { java.io.File(projectDirectory, "frames/" + frameKey(it.path)) }.onEach { require(it.isFile) { "关键帧已删除，无法视觉定位" } }
        }
        fun open(projectDirectory: java.io.File, revision: Int): VisualLocalizer {
            val directory = java.io.File(projectDirectory, "history/revision-$revision")
            require(java.io.File(directory, "cameras.yml").isFile) { "此结果不是相机拼接版本（例如 ERP 视频），暂不支持视觉定位" }
            return VisualLocalizer(NativePanorama.openLocalizer(keyframes(projectDirectory, revision).map { it.absolutePath }.toTypedArray(), directory.absolutePath))
        }
    }
}
interface EngineCallback {
    fun stage(name: String, completed: Int, total: Int)
    fun cancelled(): Boolean
}
object Coverage {
    fun percentage(mask: ByteArray, width: Int, height: Int, half: Boolean = false): Double {
        require(width > 0 && height > 0 && mask.size == width * height)
        var total = 0.0
        var covered = 0.0
        for (y in 0 until height) {
            // Integral of cos(latitude) across a row, rather than a pixel count.
            val weight = kotlin.math.sin(Math.PI / 2 - y * Math.PI / height) - kotlin.math.sin(Math.PI / 2 - (y + 1) * Math.PI / height)
            for (x in 0 until width) {
                if (half && (x < width / 4 || x >= width * 3 / 4)) continue
                total += weight
                if (mask[y * width + x].toInt() and 255 != 0) covered += weight
            }
        }
        return covered / total * 100
    }
}
