package com.panofusion.app

import kotlin.math.*

/** A coarse ERP occupancy grid built from the real coverage mask; used only for capture recommendations. */
class CoverageGrid(private val covered: BooleanArray, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && covered.size == width * height) }
    fun yaw(x: Int) = (x + 0.5) * 360.0 / width - 180
    fun pitch(y: Int) = 90 - (y + 0.5) * 180.0 / height
    fun covered(yaw: Double, pitch: Double): Boolean {
        val x = floor((yaw + 180) / 360 * width).toInt().mod(width)
        val y = floor((90 - pitch) / 180 * height).toInt().coerceIn(0, height - 1)
        return covered[y * width + x]
    }
    /**
     * Nearest uncovered cell that touches covered area, so the next view still overlaps known features.
     * Returns null when nothing is covered (no visual anchor) or the target region is complete.
     */
    fun recommend(fromYaw: Double, fromPitch: Double, half: Boolean): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var bestDistance = Double.MAX_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (covered[y * width + x]) continue
            if (half && abs(yaw(x)) > 90) continue
            val frontier = (-1..1).any { dy -> (-1..1).any { dx ->
                val ny = y + dy
                ny in 0 until height && covered[ny * width + (x + dx).mod(width)]
            } }
            if (!frontier) continue
            val d = angularDistance(fromYaw, fromPitch, yaw(x), pitch(y))
            if (d < bestDistance) { bestDistance = d; best = yaw(x) to pitch(y) }
        }
        return best
    }
    companion object {
        fun angularDistance(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double): Double {
            val a = Math.toRadians(pitch1); val b = Math.toRadians(pitch2); val d = Math.toRadians(yaw2 - yaw1)
            return Math.toDegrees(acos((sin(a) * sin(b) + cos(a) * cos(b) * cos(d)).coerceIn(-1.0, 1.0)))
        }
        fun fromMask(file: java.io.File, sample: Int = 16): CoverageGrid? {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return CoverageGrid(BooleanArray(pixels.size) { android.graphics.Color.red(pixels[it]) > 0 }, bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        }
    }
}

object Guidance {
    /** Turn instructions from a visually localized pose; yaw grows to the right, pitch grows upward. */
    fun instruction(yaw: Double, pitch: Double, targetYaw: Double, targetPitch: Double, tolerance: Double = 12.0): String {
        val dYaw = (targetYaw - yaw + 540).mod(360.0) - 180
        val dPitch = targetPitch - pitch
        return when {
            abs(dYaw) > tolerance && abs(dYaw) >= abs(dPitch) -> if (dYaw > 0) "向右转 %.0f°".format(dYaw) else "向左转 %.0f°".format(-dYaw)
            abs(dPitch) > tolerance -> if (dPitch > 0) "抬高手机 %.0f°".format(dPitch) else "降低手机 %.0f°".format(-dPitch)
            else -> "保持当前方向"
        }
    }
}
