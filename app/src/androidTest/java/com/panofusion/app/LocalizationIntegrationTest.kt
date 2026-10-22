package com.panofusion.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class LocalizationIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root: File
    private val quiet = object: EngineCallback { override fun stage(name: String, completed: Int, total: Int) {}; override fun cancelled() = false }
    @Before fun setup() { root = File(context.cacheDir, "localize-${java.util.UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }

    private fun prepared(name: String) = File(root, "work-$name").also { ImageInput.prepare(TestMedia.asset(name, root).path, it) }
    /** Optical-axis yaw/pitch of camera [index] as saved in cameras.yml. */
    private fun cameraAxis(directory: File, index: Int): Pair<Double, Double> {
        val text = File(directory, "cameras.yml").readText()
        val data = Regex("""R: !!opencv-matrix\s+rows: 3\s+cols: 3\s+dt: d\s+data: \[([^\]]+)\]""").findAll(text).elementAt(index).groupValues[1]
        val r = data.split(',').map { it.trim().toDouble() }
        return Math.toDegrees(atan2(r[2], r[8])) to Math.toDegrees(-asin(r[5]))
    }
    /** Mean absolute RGB difference over pixels where the rendered overlay has real coverage. */
    private fun overlayError(query: File, overlay: File): Pair<Double, Double> {
        val q = BitmapFactory.decodeFile(query.path); val o = BitmapFactory.decodeFile(overlay.path)
        try {
            assertEquals(q.width, o.width); assertEquals(q.height, o.height)
            var sum = 0.0; var count = 0
            for (y in 0 until q.height step 2) for (x in 0 until q.width step 2) {
                val c = o.getPixel(x, y); if (Color.alpha(c) < 255) continue
                val d = q.getPixel(x, y)
                sum += (abs(Color.red(c) - Color.red(d)) + abs(Color.green(c) - Color.green(d)) + abs(Color.blue(c) - Color.blue(d))) / 3.0; count++
            }
            return (if (count == 0) Double.MAX_VALUE else sum / count) to count.toDouble() / ((q.width / 2) * (q.height / 2))
        } finally { q.recycle(); o.recycle() }
    }
    private fun shrink(source: File, name: String, crop: Double = 1.0, scale: Double = 1.0): File {
        val bitmap = BitmapFactory.decodeFile(source.path)
        val cw = (bitmap.width * crop).toInt(); val ch = (bitmap.height * crop).toInt()
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - cw) / 2, (bitmap.height - ch) / 2, cw, ch)
        val s = minOf(scale, 800.0 / maxOf(cw, ch))
        val out = Bitmap.createScaledBitmap(cropped, (cw * s).toInt(), (ch * s).toInt(), true)
        val file = File(root, name); file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle(); if (cropped !== bitmap) cropped.recycle(); out.recycle()
        return file
    }

    @Test fun keyframesAndNewViewsLocalizeAgainstCommittedPanorama() {
        val boat1 = prepared("boat1.jpg"); val boat2 = prepared("boat2.jpg"); val boat3 = prepared("boat3.jpg")
        val revision = File(root, "revision").apply { mkdirs() }
        NativePanorama.stitch(arrayOf(boat1.path, boat2.path), intArrayOf(0, 1), revision.path, 2048, "360", 0, 0.0, quiet)
        val handle = NativePanorama.openLocalizer(arrayOf(boat1.path, boat2.path), revision.path)
        try {
            fun locate(query: File): DoubleArray {
                val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(query.path, it) }
                val started = android.os.SystemClock.elapsedRealtime()
                return NativePanorama.localize(handle, query.path, File(root, "overlay-${query.name}.png").path, size.outWidth, size.outHeight).also {
                    println("LOCALIZE ${query.name} ok=${it[0]} yaw=${it[1]} pitch=${it[2]} roll=${it[3]} inliers=${it[5]} key=${it[6]} residual=${it[7]} ms=${android.os.SystemClock.elapsedRealtime() - started}")
                }
            }
            // The anchor keyframe defines the reference: it must come back at the origin.
            val q1 = shrink(boat1, "q1.jpg"); val p1 = locate(q1)
            assertEquals(1.0, p1[0], 0.0); assertEquals(0.0, p1[1], 2.0); assertEquals(0.0, p1[2], 2.0)
            val (e1, c1) = overlayError(q1, File(root, "overlay-q1.jpg.png")); println("OVERLAY q1 error=$e1 coverage=$c1")
            assertTrue("rendered panorama must line up with the live view", e1 < 25 && c1 > 0.8)
            // A cropped, rescaled view of the second keyframe simulates a different camera FOV/resolution.
            val (yaw2, pitch2) = cameraAxis(revision, 1)
            val q2 = shrink(boat2, "q2.jpg", crop = 0.7, scale = 0.5); val p2 = locate(q2)
            assertEquals(1.0, p2[0], 0.0); assertEquals(yaw2, p2[1], 2.5); assertEquals(pitch2, p2[2], 2.5)
            // boat3 was never stitched: a genuinely new view that overlaps the existing panorama.
            val q3 = shrink(boat3, "q3.jpg"); val p3 = locate(q3)
            assertEquals(1.0, p3[0], 0.0)
            assertTrue("new view should lie beyond the second camera", abs(p3[1]) > abs(yaw2))
            val (e3, c3) = overlayError(q3, File(root, "overlay-q3.jpg.png")); println("OVERLAY q3 error=$e3 coverage=$c3")
            assertTrue(e3 < 30 && c3 > 0.05 && c3 < 0.95)
            File(root, "overlay-q3.jpg.png").copyTo(File(TestMedia.validationDirectory(), "localize-boat3-overlay.png"), overwrite = true)
            // Unrelated content must not be localized.
            val noise = File(root, "noise.jpg")
            val pixels = IntArray(640 * 480) { i -> var v = i * 1103515245 + 12345; v = v xor (v ushr 13); Color.rgb(v and 255, (v ushr 8) and 255, (v ushr 16) and 255) }
            Bitmap.createBitmap(pixels, 640, 480, Bitmap.Config.ARGB_8888).also { b -> noise.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }; b.recycle() }
            assertEquals(0.0, locate(noise)[0], 0.0)
        } finally { NativePanorama.closeLocalizer(handle) }
    }
    @Test fun referenceYawRotatesTheWholeSphere() {
        val boat1 = prepared("boat1.jpg"); val boat2 = prepared("boat2.jpg")
        val revision = File(root, "rotated").apply { mkdirs() }
        NativePanorama.stitch(arrayOf(boat1.path, boat2.path), intArrayOf(0, 1), revision.path, 2048, "360", 1, 30.0, quiet)
        val (yaw2, pitch2) = cameraAxis(revision, 1)
        assertEquals("anchor camera sits at -referenceYaw", -30.0, yaw2, 0.5); assertEquals(0.0, pitch2, 0.5)
        val (yaw1, _) = cameraAxis(revision, 0)
        assertTrue("the other camera keeps its real offset from the anchor", abs(yaw1 - yaw2) > 5)
    }
    @Test fun missingCameraParametersAreReported() {
        try { NativePanorama.openLocalizer(arrayOf(), root.path); fail("opened without cameras.yml") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("E_LOCALIZE")) }
    }
}
