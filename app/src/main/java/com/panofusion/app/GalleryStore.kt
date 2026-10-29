package com.panofusion.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** Publishes a separate original copy. A project deletion never deletes the gallery URI. */
object GalleryStore {
    @androidx.annotation.RequiresApi(29)
    fun save(context: Context, asset: Asset): Uri {
        check(Build.VERSION.SDK_INT >= 29) { "Android 9 及更早版本请使用“导出原件”选择保存位置" }
        val source = File(asset.path)
        require(source.isFile && source.length() > 0) { "原素材不存在或为空" }
        val video = asset.mime.startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(asset.mime)
            ?: if (video) "mp4" else "jpg"
        val name = asset.displayName.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "mkv", "webm", "m4v", "3gp", "jpg", "jpeg", "png", "webp", "heic", "heif") }
            ?: "PanoFusion-${UUID.randomUUID()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, asset.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/PanoFusion360" else "Pictures/PanoFusion360")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法在系统相册创建文件" }
        try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "相册文件不可写入" }
                source.inputStream().use { it.copyTo(output) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "无法完成相册保存"
            }
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
