package com.panofusion.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Long rotating sequences with a known pinhole camera: estimated focal and yaw must match ground truth. */
@RunWith(AndroidJUnit4::class)
class CameraEstimationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root: File
    @Before fun setup() { root = File(context.cacheDir, "estimate-${java.util.UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }

    private fun frames(w: Int, h: Int, focal: Double, step: Double, count: Int): List<File> {
        val pixel = TestMedia.perspective(w, h, focal, step)
        return (0 until count).map { k ->
            val pixels = IntArray(w * h) { pixel(k, it % w, it / w) }
            val file = File(root, "f$k.jpg")
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }; b.recycle() }
            file
        }
    }
    private fun check(label: String, w: Int, h: Int, focal: Double, step: Double, count: Int, sharedLens: Boolean = true) {
        val v = NativePanorama.diagnoseCameras(frames(w, h, focal, step, count).map { it.path }.toTypedArray(), IntArray(count) { if (sharedLens) 0 else it })
        val report = StringBuilder("CAMERA_DIAG $label truth f=$focal stepDeg=${Math.toDegrees(step)}\n")
        for (i in 0 until count) {
            val o = i * 6
            report.append("CAMERA_DIAG $label #$i init f=%.1f yaw=%.2f pitch=%.2f | ba f=%.1f yaw=%.2f pitch=%.2f\n".format(v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5]))
        }
        report.append("CAMERA_DIAG $label matchMs=%.0f adjustMs=%.0f iterations=%.0f rmsPx=%.3f->%.3f\n".format(v[count * 6], v[count * 6 + 1], v[count * 6 + 2], v[count * 6 + 3], v[count * 6 + 4]))
        println(report)
        val focals = (0 until count).map { v[it * 6 + 3] }
        val yawSpan = Math.abs(v[(count - 1) * 6 + 4] - v[4])
        assertEquals("$label focal", focal, focals.average(), focal * 0.05)
        assertEquals("$label yaw span", Math.toDegrees(step * (count - 1)), yawSpan, 2.0)
    }
    /** Real photographs (OpenCV testdata) with lens distortion must keep every camera. */
    @Test fun realPhotographsKeepAllCameras() {
        val inputs = listOf("boat1.jpg", "boat2.jpg", "boat3.jpg").map { name -> File(root, "w-$name").also { ImageInput.prepare(TestMedia.asset(name, root).path, it.path.let(::File)) } }
        val v = NativePanorama.diagnoseCameras(inputs.map { it.path }.toTypedArray(), intArrayOf(0, 1, 2))
        val n = inputs.size
        println("CAMERA_DIAG boat " + (0 until n).joinToString(" | ") { "#$it f=%.1f->%.1f yaw=%.2f->%.2f".format(v[it * 6], v[it * 6 + 3], v[it * 6 + 1], v[it * 6 + 4]) } +
            " iterations=%.0f rmsPx=%.3f->%.3f edges=%.0f".format(v[n * 6 + 2], v[n * 6 + 3], v[n * 6 + 4], v[n * 6 + 5]))
        // Refinement may move cameras slightly but not reorder or collapse them.
        for (i in 0 until n) { assertEquals(v[i * 6], v[i * 6 + 3], v[i * 6] * 0.1); assertEquals(v[i * 6 + 1], v[i * 6 + 4], 2.0) }
    }
    @Test fun longLowResolutionSequence() = check("320x240x24", 320, 240, 250.0, 0.1, 24)
    @Test fun shortSequence() = check("320x240x6", 320, 240, 250.0, 0.16, 6)
    /** Separate photos get independent focal lengths; the fixed reference rotation and constant residual scale must still hold them. */
    @Test fun longSequenceWithIndependentLenses() = check("320x240x24-independent", 320, 240, 250.0, 0.1, 24, sharedLens = false)
}
