package com.panofusion.app

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CapturePersistenceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun completingPhotoCaptureKeepsOriginalAndGalleryCopyWhenStitchingFails() {
        val app = compose.activity.application as PanoApplication
        val settings = runBlocking { SettingsStore.flow(app).first() }
        val project = runBlocking { app.store.create("拍摄保存验证-${System.nanoTime()}", "360") }
        val root = File(app.cacheDir, "capture-persistence-${System.nanoTime()}").apply { mkdirs() }
        var gallery: android.net.Uri? = null
        try {
            runBlocking { SettingsStore.save(app, settings.copy(saveCapturedToGallery = true)) }
            // A previous failed worker must not be mistaken for the new capture's result.
            runBlocking { app.store.import(project.id, listOf(android.net.Uri.fromFile(TestMedia.asset("boat1.jpg", root)))) }
            assertEquals("处理失败", runBlocking { TestMedia.process(app, project.id) }.status)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.panofusion.app android.permission.CAMERA").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(project.name).performClick()
            compose.onNodeWithText("打开摄像头拍摄").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("拍照") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("拍照").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("完成拍摄（1 项作为新批次）").fetchSemanticsNodes().isNotEmpty() }
            // Returning with uncommitted footage asks to preserve it instead of silently losing it.
            compose.onNodeWithText("返回工程").performClick()
            compose.onNodeWithText("保存本次拍摄？").assertIsDisplayed()
            compose.onNodeWithText("保存并返回").performClick()
            compose.waitUntil(60_000) { runBlocking { app.store.dao.project(project.id)?.status == "处理失败" } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("尚未生成全景").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保留素材并关闭").performClick()
            val asset = runBlocking { app.store.dao.assets(project.id).single { it.displayName.startsWith("capture-") } }
            val original = File(asset.path)
            assertTrue(original.isFile && original.length() > 0)
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            app.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(asset.displayName, "Pictures/PanoFusion360/"), null)!!.use { cursor ->
                assertTrue("Camera original should be visible in the phone gallery", cursor.moveToFirst())
                gallery = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
            app.contentResolver.openInputStream(gallery!!).use { assertArrayEquals(original.readBytes(), it!!.readBytes()) }
            compose.onNodeWithText("导出工程 / 诊断包（含原素材）").performScrollTo().assertIsDisplayed()
        } finally {
            gallery?.let { app.contentResolver.delete(it, null, null) }
            runBlocking { app.store.delete(project.id); SettingsStore.save(app, settings) }
            root.deleteRecursively()
        }
    }
}
