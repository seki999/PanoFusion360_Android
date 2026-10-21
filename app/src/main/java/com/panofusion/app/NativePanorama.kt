package com.panofusion.app

/** The JNI engine owns all OpenCV matrices, and polls cancellation between costly stages. */
object NativePanorama {
    init { System.loadLibrary("opencv_java4"); System.loadLibrary("panofusion") }
    external fun analyze(path: String, previousPath: String?): DoubleArray
    external fun stitch(paths: Array<String>, outputDirectory: String, width: Int, mode: String, callback: EngineCallback): Double
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
