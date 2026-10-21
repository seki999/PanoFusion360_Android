package com.panofusion.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectStorageIntegrationTest {
    @Test fun importCopiesOriginalAndDuplicateIsIndependent() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ProjectDatabase::class.java).build()
        val store=ProjectStore(context,db.dao())
        val source=File(context.cacheDir,"capture-storage-${System.nanoTime()}.jpg")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) };bitmap.recycle()
        val ids=mutableListOf<String>()
        try {
            val project=store.create("原始工程","180");ids+=project.id
            store.import(project.id,listOf(Uri.fromFile(source)))
            val asset=db.dao().assets(project.id).single()
            assertNotEquals(source.path,asset.path);assertArrayEquals(source.readBytes(),File(asset.path).readBytes())
            assertTrue(File(store.directory(project.id),"project.json").isFile)
            val copy=store.duplicate(project.id);ids+=copy
            assertNotEquals(asset.path,db.dao().assets(copy).single().path)
            store.delete(project.id);ids.remove(project.id)
            assertTrue(source.exists());assertTrue(File(db.dao().assets(copy).single().path).exists())
            store.rename(copy,"重命名")
            assertEquals("重命名",db.dao().project(copy)?.name)
        } finally { ids.forEach { store.delete(it) };source.delete();db.close() }
    }
}
