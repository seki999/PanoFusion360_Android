package com.panofusion.app

import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI

@RunWith(AndroidJUnit4::class)
class ErpExportIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<PanoApplication>()

    @Test fun markedMonoErpVideoBecomesStillPanoramaAndExportsAsSphere() = runBlocking {
        val root = File(app.cacheDir, "erp-${System.nanoTime()}").apply { mkdirs() }
        val project = app.store.create("ERP 视频验证", "360")
        try {
            val w = 1024; val h = 512
            val plain = File(root, "plain.mp4")
            TestMedia.encode(plain, w, h, frames = 8, fps = 4) { frame, x, y ->
                TestMedia.sphereRgb((x + 0.5) / w * 2 * PI - PI + frame * 0.01, (y + 0.5) / h * PI - PI / 2)
            }
            assertEquals("2:1 alone is not proof of a panorama", VideoProjection.Type.UNKNOWN, VideoProjection.detect(plain))
            val marked = File(root, "marked.mp4"); plain.copyTo(marked); TestMedia.injectMonoErp(marked)
            assertEquals(VideoProjection.Type.MONO_ERP, VideoProjection.detect(marked))
            app.store.import(project.id, listOf(Uri.fromFile(marked)))
            val done = TestMedia.process(app, project.id); assertEquals(done.error, "处理完成", done.status)
            assertEquals(100.0, done.coverage!!, 1e-9)
            val revision = File(app.store.directory(project.id), "history/revision-${done.revision}")
            val panorama = BitmapFactory.decodeFile(File(revision, "panorama.png").path)
            assertEquals(w, panorama.width); assertEquals(h, panorama.height)
            val frames = app.store.dao.frames(project.id)
            assertTrue("decoded several candidate frames", frames.size >= 4)
            assertNull("ERP has no stitched cameras", File(revision, "cameras.yml").takeIf { it.exists() })

            // Complete sphere → Photo Sphere XMP; requested size honoured; no upscaling.
            val jpeg = File(root, "sphere.jpg")
            Exporter.export(app, revision, Uri.fromFile(jpeg), Exporter.Options(jpeg = true, width = 512, quality = 90), done.mode, done.coverage)
            val bytes = jpeg.readBytes(); val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.contains("GPano:ProjectionType=\"equirectangular\"")); assertTrue(text.contains("pf:CompleteSphere=\"True\""))
            assertTrue(text.contains("GPano:FullPanoWidthPixels=\"512\""))
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { assertEquals(512, it.width); assertEquals(256, it.height); it.recycle() }
            try { Exporter.export(app, revision, Uri.fromFile(File(root, "up.jpg")), Exporter.Options(jpeg = true, width = 2048), done.mode, done.coverage); fail("upscaled export") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("渲染")) }
            println("ERP_RESULT frames=${frames.size} selected=${frames.count { it.selected }} jpegBytes=${bytes.size}")

            // Reference yaw: an exact column rotation of the same selected frame.
            app.store.setReferenceYaw(project.id, 90.0)
            val rotated = TestMedia.process(app, project.id); assertEquals(rotated.error, "处理完成", rotated.status)
            val turned = BitmapFactory.decodeFile(File(app.store.directory(project.id), "history/revision-${rotated.revision}/panorama.png").path)
            for (y in 0 until h step 37) for (x in 0 until w step 29) assertEquals(panorama.getPixel((x + w / 4) % w, y), turned.getPixel(x, y))
            panorama.recycle(); turned.recycle()
        } finally { app.store.delete(project.id); root.deleteRecursively() }
    }

    @Test fun partialPanoramaExportsKeepTruthfulMetadataAndMask() = runBlocking {
        val root = File(app.cacheDir, "export-${System.nanoTime()}").apply { mkdirs() }
        val project = app.store.create("部分导出", "360")
        try {
            app.store.import(project.id, listOf("boat1.jpg", "boat2.jpg").map { Uri.fromFile(TestMedia.asset(it, root)) })
            val done = TestMedia.process(app, project.id); assertEquals(done.error, "处理完成", done.status)
            val revision = File(app.store.directory(project.id), "history/revision-${done.revision}")
            val full = File(root, "full.jpg")
            Exporter.export(app, revision, Uri.fromFile(full), Exporter.Options(jpeg = true, width = 1024, background = Color.WHITE), done.mode, done.coverage)
            val fullText = String(full.readBytes(), Charsets.ISO_8859_1)
            assertFalse("incomplete canvas must not be tagged as a photo sphere", fullText.contains("GPano:"))
            assertTrue(fullText.contains("pf:CompleteSphere=\"False\"")); assertTrue(fullText.contains("pf:MissingAreaFill=\"#FFFFFF\""))
            BitmapFactory.decodeFile(full.path).also { assertEquals(1024, it.width); assertEquals(Color.WHITE, it.getPixel(0, 0)); it.recycle() }

            val bounds = requireNotNull(Exporter.validBounds(File(revision, "coverage.png")))
            val cropped = File(root, "cropped.png")
            Exporter.export(app, revision, Uri.fromFile(cropped), Exporter.Options(jpeg = false, width = 2048, cropToCoverage = true), done.mode, done.coverage)
            val croppedText = String(cropped.readBytes(), Charsets.ISO_8859_1)
            assertTrue(croppedText.contains("GPano:CroppedAreaLeftPixels=\"${bounds.left}\""))
            assertTrue(croppedText.contains("GPano:FullPanoWidthPixels=\"2048\""))
            BitmapFactory.decodeFile(cropped.path).also { assertEquals(bounds.width(), it.width); assertEquals(bounds.height(), it.height); it.recycle() }

            val mask = File(root, "mask.png")
            Exporter.export(app, revision, Uri.fromFile(mask), Exporter.Options(jpeg = false, width = 1024, content = Exporter.Content.COVERAGE_MASK), done.mode, done.coverage)
            val m = BitmapFactory.decodeFile(mask.path)
            assertEquals(1024, m.width); assertEquals(512, m.height)
            val values = mutableSetOf<Int>(); for (y in 0 until m.height step 3) for (x in 0 until m.width step 3) values += Color.red(m.getPixel(x, y))
            assertEquals("nearest-neighbour mask stays binary", setOf(0, 255), values); m.recycle()
        } finally { app.store.delete(project.id); root.deleteRecursively() }
    }
}
