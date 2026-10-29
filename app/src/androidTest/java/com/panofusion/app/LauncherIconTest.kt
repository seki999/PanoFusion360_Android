package com.panofusion.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun launcherUsesCustomAdaptiveIcon() {
        val app = ApplicationProvider.getApplicationContext<PanoApplication>()
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        val icon = app.packageManager.getApplicationIcon(app.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, 512, 512)
            icon.draw(Canvas(bitmap))
            assertNotEquals(bitmap.getPixel(256, 256), bitmap.getPixel(256, 80))
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "PanoFusion360-icon-preview.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PanoFusionValidation")
                }
                val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                app.contentResolver.openOutputStream(uri).use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it)))
                }
            }
        } finally { bitmap.recycle() }
    }
}
