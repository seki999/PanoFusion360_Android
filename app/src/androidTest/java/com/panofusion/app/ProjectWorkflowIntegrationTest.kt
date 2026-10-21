package com.panofusion.app

import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ProjectWorkflowIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realPhotoProjectRebuildFailureUndoAndArchiveRoundTrip() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        val store=app.store
        val root=File(app.cacheDir,"workflow-${System.nanoTime()}").apply{mkdirs()}
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val ids=mutableListOf<String>()
        try {
            val project=store.create("真实照片工作流","360");ids+=project.id
            val testContext=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
            val photos=listOf("boat1.jpg","boat2.jpg").map { name ->
                File(root,name).also { file -> testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } } }
            }
            store.import(project.id,photos.map { Uri.fromFile(it) })
            suspend fun process(): Project {
                val manager=WorkManager.getInstance(app)
                val previousIds=manager.getWorkInfosForUniqueWork("panorama-${project.id}").get().map { it.id }.toSet()
                PanoramaWorker.enqueue(app,project.id)
                withTimeout(180_000) { manager.getWorkInfosForUniqueWorkFlow("panorama-${project.id}").first { infos -> infos.any { it.id !in previousIds && it.state.isFinished } } }
                return requireNotNull(store.dao.project(project.id))
            }
            val first=process();assertEquals(first.error,"处理完成",first.status);assertTrue(first.revision>0)
            compose.onNodeWithText(project.name).performClick()
            compose.onNodeWithText("展开图").performScrollTo().performClick()
            compose.onNodeWithContentDescription("展开图：透明区域尚未拍摄").performTouchInput { click(center) }
            compose.onNodeWithText("球面区域").assertExists()
            compose.onNodeWithText("确定").performClick()
            compose.onNodeWithText("覆盖 Mask").performScrollTo().performClick()
            compose.onNodeWithText("JPEG",substring=false).performScrollTo().assertIsDisplayed()
            fun result(p: Project) = File(store.directory(project.id),"history/revision-${p.revision}/panorama.png")
            fun digest(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
            val hash=digest(result(first))
            val jpeg=File(root,"export.jpg");Exporter.image(app,result(first),Uri.fromFile(jpeg),true)
            val exported=BitmapFactory.decodeFile(jpeg.path)
            assertNotNull(exported);assertEquals(2048,exported.width);assertEquals(1024,exported.height)
            assertEquals(255,Color.alpha(exported.getPixel(0,0)));exported.recycle()
            val png=File(root,"export.png");Exporter.image(app,result(first),Uri.fromFile(png),false)
            val transparent=BitmapFactory.decodeFile(png.path)
            assertEquals(0,Color.alpha(transparent.getPixel(0,0)));transparent.recycle()
            val supplemental=File(root,"boat3.jpg")
            testContext.assets.open("boat3.jpg").use { input -> supplemental.outputStream().use { input.copyTo(it) } }
            store.import(project.id,listOf(Uri.fromFile(supplemental)))
            val addedSession=store.dao.allSessions(project.id).last()
            val expanded=process();assertEquals(expanded.error,"处理完成",expanded.status)
            assertTrue("A new matching session must increase real coverage",expanded.coverage!!>first.coverage!!)
            println("SESSION_PIPELINE_RESULT initial=${first.coverage} supplemented=${expanded.coverage}")
            if(android.os.Build.VERSION.SDK_INT>=29) {
                val values=android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"positive-session-panorama.png");put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/PanoFusionValidation") }
                val uri=app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
                app.contentResolver.openOutputStream(uri).use { out -> result(expanded).inputStream().use { it.copyTo(requireNotNull(out)) } }
            }
            store.dao.enableSession(addedSession.id,false)
            val reverted=process();assertEquals(reverted.error,"处理完成",reverted.status);assertEquals(first.coverage!!,reverted.coverage!!,0.3)
            assertEquals(hash,digest(result(reverted)))
            val bad=File(root,"unrelated.jpg")
            val pixels=IntArray(640*480) { i -> var v=i*1103515245+12345;v=v xor (v ushr 13); Color.rgb(v and 255,(v ushr 8) and 255,(v ushr 16) and 255) }
            val bitmap=Bitmap.createBitmap(pixels,640,480,Bitmap.Config.ARGB_8888)
            bad.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,98,it) };bitmap.recycle()
            store.import(project.id,listOf(Uri.fromFile(bad)))
            val failed=process();assertEquals("处理失败",failed.status);assertEquals(reverted.revision,failed.revision);assertEquals(first.coverage,failed.coverage);assertEquals(hash,digest(result(failed)))
            val lastSession=store.dao.allSessions(project.id).last();store.dao.enableSession(lastSession.id,false)
            val undone=process();assertEquals(undone.error,"处理完成",undone.status);assertEquals(first.coverage!!,undone.coverage!!,0.3)
            store.restoreRevision(project.id,first.revision)
            assertEquals(first.revision,store.dao.project(project.id)?.revision)
            val zip=File(root,"project.zip");Exporter.archive(store,project.id,Uri.fromFile(zip))
            val restored=Exporter.restore(store,Uri.fromFile(zip));ids+=restored
            assertEquals(first.coverage!!,store.dao.project(restored)?.coverage!!,1e-9)
            assertTrue(store.dao.assets(restored).all { File(it.path).exists() })
            assertEquals(store.dao.frames(project.id).size,store.dao.frames(restored).size)
            assertEquals(hash,digest(File(store.directory(restored),"history/revision-${first.revision}/panorama.png")))
        } finally { ids.forEach { WorkManager.getInstance(app).cancelUniqueWork("panorama-$it");store.delete(it) };scenario.close();root.deleteRecursively() }
    }
}
