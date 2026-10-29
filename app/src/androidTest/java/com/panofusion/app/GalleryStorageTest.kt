package com.panofusion.app

import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class GalleryStorageTest {
    @Test fun originalVideoIsPublishedWithoutTranscoding() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        val root = File(app.cacheDir, "gallery-video-${System.nanoTime()}").apply { mkdirs() }
        val project = app.store.create("录像相册保存", "360")
        var gallery: Uri? = null
        try {
            val original = File(root, "capture-${System.nanoTime()}.mp4")
            TestMedia.encode(original, 320, 240, 4, 4, TestMedia.perspective(320, 240, 250.0, 0.05))
            app.store.import(project.id, listOf(Uri.fromFile(original)))
            gallery = GalleryStore.save(app, app.store.dao.assets(project.id).single())
            app.contentResolver.openInputStream(gallery).use { assertArrayEquals(original.readBytes(), it!!.readBytes()) }
            app.contentResolver.query(gallery, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Movies/PanoFusion360/", it.getString(0)); assertEquals(0, it.getInt(1))
            }
        } finally {
            gallery?.let { app.contentResolver.delete(it, null, null) }
            app.store.delete(project.id); root.deleteRecursively()
        }
    }

    @Test fun originalCanBeSavedAndExportedBeforeAnyPanoramaExists() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        val root = File(app.cacheDir, "gallery-test-${System.nanoTime()}").apply { mkdirs() }
        val project = app.store.create("原素材救援", "360")
        var gallery: Uri? = null
        try {
            val original = TestMedia.asset("boat1.jpg", root)
            app.store.import(project.id, listOf(Uri.fromFile(original)))
            val asset = app.store.dao.assets(project.id).single()
            assertEquals(0, app.store.dao.project(project.id)!!.revision)
            gallery = GalleryStore.save(app, asset)
            app.contentResolver.openInputStream(gallery).use { assertArrayEquals(original.readBytes(), it!!.readBytes()) }
            app.contentResolver.query(gallery, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Pictures/PanoFusion360/", it.getString(0)); assertEquals(0, it.getInt(1))
            }
            val zip = File(root, "failed-project.zip")
            Exporter.archive(app.store, project.id, Uri.fromFile(zip))
            ZipFile(zip).use { archive ->
                assertNotNull(archive.getEntry("project.json"))
                val entry = archive.entries().asSequence().single { it.name.startsWith("original/") && !it.isDirectory }
                assertArrayEquals(original.readBytes(), archive.getInputStream(entry).readBytes())
            }
            app.store.delete(project.id)
            // A project operation must never delete the independent gallery copy.
            app.contentResolver.openInputStream(gallery).use { assertArrayEquals(original.readBytes(), it!!.readBytes()) }
        } finally {
            gallery?.let { app.contentResolver.delete(it, null, null) }
            if (app.store.dao.project(project.id) != null) app.store.delete(project.id)
            root.deleteRecursively()
        }
    }
}
