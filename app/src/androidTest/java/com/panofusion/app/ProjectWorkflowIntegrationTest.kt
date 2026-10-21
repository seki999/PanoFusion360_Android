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
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ProjectWorkflowIntegrationTest {
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
                PanoramaWorker.enqueue(app,project.id)
                withTimeout(180_000) { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("panorama-${project.id}").first { infos -> infos.isNotEmpty() && infos.first().state.isFinished } }
                return requireNotNull(store.dao.project(project.id))
            }
            val first=process();assertEquals(first.error,"处理完成",first.status);assertTrue(first.revision>0)
            fun result(p: Project) = File(store.directory(project.id),"history/revision-${p.revision}/panorama.png")
            fun digest(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
            val hash=digest(result(first))
            val bad=File(root,"unrelated.jpg")
            val pixels=IntArray(640*480) { i -> var v=i*1103515245+12345;v=v xor (v ushr 13); Color.rgb(v and 255,(v ushr 8) and 255,(v ushr 16) and 255) }
            val bitmap=Bitmap.createBitmap(pixels,640,480,Bitmap.Config.ARGB_8888)
            bad.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,98,it) };bitmap.recycle()
            store.import(project.id,listOf(Uri.fromFile(bad)))
            val failed=process();assertEquals("处理失败",failed.status);assertEquals(first.revision,failed.revision);assertEquals(first.coverage,failed.coverage);assertEquals(hash,digest(result(failed)))
            val lastSession=store.dao.allSessions(project.id).last();store.dao.enableSession(lastSession.id,false)
            val undone=process();assertEquals(undone.error,"处理完成",undone.status);assertEquals(first.coverage!!,undone.coverage!!,0.3)
            store.restoreRevision(project.id,first.revision)
            assertEquals(first.revision,store.dao.project(project.id)?.revision)
            val zip=File(root,"project.zip");Exporter.archive(store,project.id,Uri.fromFile(zip))
            val restored=Exporter.restore(store,Uri.fromFile(zip));ids+=restored
            assertEquals(first.coverage,store.dao.project(restored)?.coverage)
            assertTrue(store.dao.assets(restored).all { File(it.path).exists() })
            assertEquals(store.dao.frames(project.id).size,store.dao.frames(restored).size)
            assertEquals(hash,digest(File(store.directory(restored),"history/revision-${first.revision}/panorama.png")))
        } finally { ids.forEach { WorkManager.getInstance(app).cancelUniqueWork("panorama-$it");store.delete(it) };scenario.close();root.deleteRecursively() }
    }
}
