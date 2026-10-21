package com.panofusion.app

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class SessionLifecycleIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<PanoApplication>()

    @Test fun version1DatabaseMigratesWithoutLosingProjects() = runBlocking {
        val name = "migration-${System.nanoTime()}.db"
        // Exact version-1 schema (before referenceYaw/anchorFrame) as Room created it.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `coverage` REAL, `status` TEXT NOT NULL, `error` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `sessions` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_sessions_projectId` ON `sessions` (`projectId`)")
            db.execSQL("CREATE TABLE `assets` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sourceUri` TEXT NOT NULL, `path` TEXT NOT NULL, `mime` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `analyzed` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `durationMs` INTEGER, `rotation` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_assets_sessionId` ON `assets` (`sessionId`)")
            db.execSQL("CREATE INDEX `index_assets_projectId` ON `assets` (`projectId`)")
            db.execSQL("CREATE TABLE `frames` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `path` TEXT NOT NULL, `timeUs` INTEGER NOT NULL, `sharpness` REAL NOT NULL, `features` INTEGER NOT NULL, `selected` INTEGER NOT NULL, `reason` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX `index_frames_projectId` ON `frames` (`projectId`)")
            db.execSQL("CREATE INDEX `index_frames_assetId` ON `frames` (`assetId`)")
            db.execSQL("INSERT INTO projects VALUES ('11111111-1111-1111-1111-111111111111','旧版工程','360',1,2,3,12.5,'处理完成',NULL)")
            db.version = 1
        }
        val db = Room.databaseBuilder(app, ProjectDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            val project = requireNotNull(db.dao().project("11111111-1111-1111-1111-111111111111"))
            assertEquals("旧版工程", project.name); assertEquals(3, project.revision); assertEquals(12.5, project.coverage!!, 0.0)
            assertEquals(0.0, project.referenceYaw, 0.0); assertNull(project.anchorFrame)
        } finally { db.close(); app.deleteDatabase(name) }
    }

    @Test fun anchorSurvivesFrameReorderingAndDeletedBatchIsRemoved() = runBlocking {
        val store = app.store
        val root = File(app.cacheDir, "lifecycle-${System.nanoTime()}").apply { mkdirs() }
        val project = store.create("批次生命周期", "360")
        try {
            store.import(project.id, listOf("boat1.jpg", "boat2.jpg").map { Uri.fromFile(TestMedia.asset(it, root)) })
            val first = TestMedia.process(app, project.id); assertEquals(first.error, "处理完成", first.status)
            val anchor = requireNotNull(first.anchorFrame)
            val boat1Frame = store.dao.frames(project.id).first { frameKey(it.path) == anchor }
            // Re-inserting the anchor frame moves it behind boat2 in processing order (as a re-analysis would).
            store.dao.clearAssetFrames(boat1Frame.assetId); store.dao.addFrame(boat1Frame.copy(id = java.util.UUID.randomUUID().toString()))
            assertNotEquals(anchor, frameKey(store.dao.frames(project.id).first().path))
            val second = TestMedia.process(app, project.id); assertEquals(second.error, "处理完成", second.status)
            assertEquals(anchor, second.anchorFrame); assertNull(second.error)
            val cameras = File(store.directory(project.id), "history/revision-${second.revision}/cameras.yml").readText()
            assertTrue(cameras.contains("anchor: 1"))
            assertEquals(first.coverage!!, second.coverage!!, 0.3)

            val sessionId = store.import(project.id, listOf(Uri.fromFile(TestMedia.asset("boat3.jpg", root))))
            val expanded = TestMedia.process(app, project.id); assertTrue(expanded.coverage!! > second.coverage!!)
            val added = store.dao.assets(project.id).filter { it.sessionId == sessionId }
            assertEquals(1, added.size)
            assertTrue(File(store.directory(project.id), "original/$sessionId").isDirectory)
            store.deleteSession(project.id, sessionId)
            assertFalse(File(store.directory(project.id), "original/$sessionId").exists())
            assertFalse(File(store.directory(project.id), "frames/${added.single().id}").exists())
            assertTrue(store.dao.frames(project.id).none { it.assetId == added.single().id })
            assertTrue(store.dao.allSessions(project.id).none { it.id == sessionId })
            assertTrue("external source must survive", File(root, "boat3.jpg").isFile)
            val manifest = File(store.directory(project.id), "project.json").readText()
            assertFalse(manifest.contains(sessionId))
            val rebuilt = TestMedia.process(app, project.id); assertEquals(rebuilt.error, "处理完成", rebuilt.status)
            assertEquals(second.coverage!!, rebuilt.coverage!!, 0.3)
            // The revision that used the deleted batch can no longer be restored, and says so.
            try { store.restoreRevision(project.id, expanded.revision); fail("restored a revision whose batch was deleted") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("删除")) }
            assertEquals(rebuilt.revision, store.dao.project(project.id)!!.revision)
        } finally { store.delete(project.id); root.deleteRecursively() }
    }

    @Test fun referenceYawRotatesErpOutputExactly() {
        val w = 64; val h = 32
        val pixels = IntArray(w * h) { android.graphics.Color.rgb(it % w * 4, it / w * 8, 0) }
        val source = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val rotated = PanoramaWorker.rotateErpYaw(source, 90.0)
        // Content that was at yaw +90° (x = 3W/4) is now at the centre column (x = W/2).
        for (y in 0 until h) for (x in 0 until w) assertEquals(source.getPixel((x + w / 4) % w, y), rotated.getPixel(x, y))
        assertSame(source, PanoramaWorker.rotateErpYaw(source, 0.0))
        rotated.recycle(); source.recycle()
    }
}
