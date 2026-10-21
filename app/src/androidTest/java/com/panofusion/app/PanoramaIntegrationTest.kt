package com.panofusion.app

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class PanoramaIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root: File
    private val callback = object: EngineCallback { override fun stage(name: String, completed: Int, total: Int) {} ;override fun cancelled() = false }
    @Before fun setup() { root = File(context.cacheDir,"integration-${java.util.UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }
    /** A deterministic, textured sphere projected through known rotating pinhole cameras. */
    private fun frame(yaw: Double, seed: Int = 17): File {
        val w = 640; val h = 480; val f = 500.0
        val pixels = IntArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            val rx = (x-w/2.0)/f; val ry = (y-h/2.0)/f
            val wx = cos(yaw)*rx+sin(yaw); val wz = -sin(yaw)*rx+cos(yaw)
            val u = floor((atan2(wx,wz)+PI)*1600/PI).toInt()
            val v = floor((atan2(ry,sqrt(wx*wx+wz*wz))+PI/2)*1600/PI).toInt()
            val cellX = u/10; val cellY = v/10
            var hash = (cellX*73856093) xor (cellY*19349663) xor (seed*83492791)
            hash = hash xor (hash ushr 13)
            val r = 30 + (hash and 191); val g = 30 + ((hash ushr 8) and 191); val b = 30 + ((hash ushr 16) and 191)
            pixels[y*w+x] = Color.rgb(r,g,b)
        }
        val bitmap = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        val file = File(root,"view-$yaw-$seed.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,98,it) };bitmap.recycle()
        return file
    }
    private fun stitch(inputs: List<File>, name: String): Pair<Double,File> {
        val output = File(root,name).apply { mkdirs() }
        val coverage = NativePanorama.stitch(inputs.map { it.path }.toTypedArray(),output.path,2048,"360",callback)
        return coverage to output
    }
    @Test fun partialPanoramaPreservesMissingRegionsAndIncrementalCoverage() {
        val images = listOf(frame(0.0),frame(0.3),frame(0.6),frame(0.9))
        val analysis = NativePanorama.analyze(images[0].path,null)
        assertTrue(analysis[0]>35);assertTrue(analysis[1]>60)
        val first = stitch(images.take(3),"first")
        assertTrue(first.first>0 && first.first<50)
        val bitmap = BitmapFactory.decodeFile(File(first.second,"panorama.png").path)
        assertEquals(2048,bitmap.width);assertEquals(1024,bitmap.height)
        var missing = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) if (Color.alpha(bitmap.getPixel(x,y))==0) missing++
        assertTrue(missing>0);bitmap.recycle()
        val second = stitch(images,"second")
        assertTrue("new views must increase measured area",second.first > first.first)
        val undo = stitch(images.take(3),"undo")
        assertEquals(first.first,undo.first,0.3)
        assertTrue(File(first.second,"panorama.png").isFile)
    }
    @Test fun unrelatedSceneRejected() {
        try { stitch(listOf(frame(0.0),frame(0.3),frame(0.6,997)),"mismatch"); fail("Unrelated scene was fused") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("E_DISCONNECTED")) }
    }
    @Test fun cancellationDoesNotPublish() {
        val out = File(root,"cancel").apply { mkdirs() }
        try {
            NativePanorama.stitch(arrayOf(frame(0.0).path,frame(0.3).path),out.path,2048,"360",object: EngineCallback {
                override fun stage(name: String, completed: Int, total: Int) {}
                override fun cancelled() = true
            });fail("Cancellation ignored")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("E_CANCELLED")) }
        assertFalse(File(out,"panorama.png").exists())
    }
    @Test fun databaseSurvivesCloseAndReopen() = runBlocking {
        val databaseName = "persistence-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context,ProjectDatabase::class.java,databaseName).build()
        try {
            val project = Project(name="保存恢复测试",mode="180")
            val session = Session(projectId=project.id)
            val source = frame(0.0)
            db.dao().restore(project,listOf(session),listOf(Asset(projectId=project.id,sessionId=session.id,sourceUri="test",path=source.path,mime="image/jpeg")))
            db.close();db=Room.databaseBuilder(context,ProjectDatabase::class.java,databaseName).build()
            assertEquals("180",db.dao().project(project.id)?.mode)
            assertTrue(File(db.dao().assets(project.id).single().path).exists())
            assertTrue(db.dao().allSessions(project.id).single().enabled)
        } finally { db.close();context.deleteDatabase(databaseName) }
    }
}
