package com.panofusion.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PerformanceIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<PanoApplication>()

    /** Runs the production worker and reports the metrics it writes; numbers depend on the device. */
    @Test fun recordRealPipelineMetrics() = runBlocking {
        val root = File(app.cacheDir, "perf-${System.nanoTime()}").apply { mkdirs() }
        val report = StringBuilder("device=${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.first()} sdk=${android.os.Build.VERSION.SDK_INT}\n")
        val ids = mutableListOf<String>()
        try {
            val small = File(root, "rotate-320x240.mp4")
            val encodeSmall = kotlin.system.measureTimeMillis { TestMedia.encode(small, 320, 240, 12, 4, TestMedia.perspective(320, 240, 250.0, 0.08)) }
            val large = File(root, "rotate-1280x720.mp4")
            val encodeLarge = kotlin.system.measureTimeMillis { TestMedia.encode(large, 1280, 720, 24, 4, TestMedia.perspective(1280, 720, 1000.0, 0.04)) }
            report.append("synthetic encode ms: 320x240=$encodeSmall 1280x720=$encodeLarge (test setup, not app time)\n")
            val scenarios = listOf(
                "photos boat1-3 (OpenCV testdata)" to listOf("boat1.jpg", "boat2.jpg", "boat3.jpg").map { TestMedia.asset(it, root) },
                "video 320x240 3s H.264" to listOf(small),
                "video 1280x720 6s H.264" to listOf(large))
            for ((name, inputs) in scenarios) {
                val project = app.store.create("性能 $name", "360"); ids += project.id
                app.store.import(project.id, inputs.map { Uri.fromFile(it) })
                val done = TestMedia.process(app, project.id, 900_000)
                assertEquals("$name: ${done.error}", "处理完成", done.status)
                val metrics = Json.parseToJsonElement(File(app.store.directory(project.id), "history/revision-${done.revision}/metrics.json").readText()).jsonObject
                val line = "$name | inputBytes=${inputs.sumOf { it.length() }} coverage=%.3f%% | %s".format(done.coverage, metrics)
                report.append(line).append('\n'); println("PERF $line")
                assertTrue(metrics.getValue("stitchMs").toString().toLong() > 0)
            }
        } finally { ids.forEach { app.store.delete(it) }; root.deleteRecursively() }
        File(TestMedia.validationDirectory(), "performance.txt").writeText(report.toString())
    }

    @Test fun cancellingTheWorkerKeepsPreviousStateAndIsReportedAsCancelled() = runBlocking {
        val root = File(app.cacheDir, "cancel-${System.nanoTime()}").apply { mkdirs() }
        val project = app.store.create("取消验证", "360")
        try {
            app.store.import(project.id, listOf("boat1.jpg", "boat2.jpg", "boat3.jpg").map { Uri.fromFile(TestMedia.asset(it, root)) })
            val manager = WorkManager.getInstance(app)
            PanoramaWorker.enqueue(app, project.id)
            withTimeout(60_000) { manager.getWorkInfosForUniqueWorkFlow("panorama-${project.id}").first { infos -> infos.any { it.state == WorkInfo.State.RUNNING } } }
            manager.cancelUniqueWork("panorama-${project.id}")
            val info = withTimeout(60_000) { manager.getWorkInfosForUniqueWorkFlow("panorama-${project.id}").first { infos -> infos.all { it.state.isFinished } } }.single()
            assertEquals(WorkInfo.State.CANCELLED, info.state)
            // The coroutine's cleanup may run just after WorkManager marks the job cancelled.
            val cancelled = withTimeout(30_000) { app.store.dao.observe().first { list -> list.first { it.id == project.id }.status in setOf("已取消", "处理失败", "处理完成") } }.first { it.id == project.id }
            assertEquals("已取消", cancelled.status); assertEquals(0, cancelled.revision); assertNull(cancelled.coverage)
            assertTrue(File(app.store.directory(project.id), "history").listFiles().isNullOrEmpty())
        } finally { app.store.delete(project.id); root.deleteRecursively() }
    }

    @Test fun interruptedProcessingIsRecoveredOnStart() = runBlocking {
        val project = app.store.create("中断恢复", "360")
        try {
            app.store.dao.save(project.copy(status = "处理中"))
            val stale = File(app.store.directory(project.id), "cache/job-stale").apply { mkdirs(); File(this, "partial.png").writeBytes(byteArrayOf(1)) }
            app.store.recoverInterrupted { false }
            val recovered = app.store.dao.project(project.id)!!
            assertEquals("已中断", recovered.status); assertFalse(stale.exists())
        } finally { app.store.delete(project.id) }
    }
}
