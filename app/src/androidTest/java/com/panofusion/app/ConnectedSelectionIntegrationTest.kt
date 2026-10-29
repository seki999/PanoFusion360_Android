package com.panofusion.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI

@RunWith(AndroidJUnit4::class)
class ConnectedSelectionIntegrationTest {
    @Test fun repeatedRevolutionsUseVerifiedBridgesAndKeepEveryAsset() {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        val root = File(app.cacheDir, "three-rounds-${System.nanoTime()}").apply { mkdirs() }
        val callback = object: EngineCallback {
            override fun stage(name: String, completed: Int, total: Int) {
                if (completed == 0 || completed == total) println("THREE_ROUNDS $name $completed/$total")
            }
            override fun cancelled() = false
        }
        try {
            val w = 320; val h = 240; val count = 18
            val pixel = TestMedia.perspective(w, h, 250.0, 2 * PI / count)
            val oneRound = (0 until count).map { frame ->
                val bitmap = Bitmap.createBitmap(IntArray(w * h) { pixel(frame, it % w, it / w) }, w, h, Bitmap.Config.ARGB_8888)
                File(root, "view-$frame.jpg").also { file ->
                    try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) } }
                    finally { bitmap.recycle() }
                }
            }
            val paths = List(3) { oneRound }.flatten()
            val groups = List(paths.size) { "asset-${it / count}" }
            val graph = NativePanorama.frameGraph(paths.map { it.path }.toTypedArray(), callback)
            val selected = ConnectedFrameSelector.select(groups, graph, KeyframeBudget.LIMIT)
            assertTrue(selected.disconnected.isEmpty())
            assertTrue(selected.indices.size in 3..KeyframeBudget.LIMIT)
            assertEquals(groups.toSet(), selected.indices.map { groups[it] }.toSet())
            assertTrue("Keep viewpoints around the sphere, not repeated copies", selected.indices.map { it % count }.toSet().size >= 12)
            val output = File(root, "result").apply { mkdirs() }
            val coverage = NativePanorama.stitch(selected.indices.map { paths[it].path }.toTypedArray(),
                selected.indices.map { it / count }.toIntArray(), output.path, 2048, "360", 0, 0.0, callback)
            assertTrue(coverage in 20.0..65.0)
            assertTrue(File(output, "coverage.png").isFile)
            println("THREE_ROUNDS_RESULT candidates=${paths.size} kept=${selected.indices.size} uniqueViews=${selected.indices.map { it % count }.toSet().size} coverage=$coverage")
        } finally { root.deleteRecursively() }
    }

    @Test fun retryCanRecoverPreviouslyThinnedFramesWithoutReimporting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        val project = app.store.create("精简帧恢复", "360")
        val root = File(app.cacheDir, "recover-thinned-${System.nanoTime()}").apply { mkdirs() }
        try {
            app.store.import(project.id, listOf("boat1.jpg", "boat2.jpg").map { android.net.Uri.fromFile(TestMedia.asset(it, root)) })
            val first = TestMedia.process(app, project.id)
            assertEquals(first.error, "处理完成", first.status)
            val frame = app.store.dao.frames(project.id).last()
            app.store.dao.addFrame(frame.copy(selected = false, reason = "自动精简（超过 24 张上限）"))
            val retry = TestMedia.process(app, project.id)
            assertEquals(retry.error, "处理完成", retry.status)
            assertEquals(first.coverage!!, retry.coverage!!, 0.3)
        } finally { app.store.delete(project.id); root.deleteRecursively() }
    }
}
