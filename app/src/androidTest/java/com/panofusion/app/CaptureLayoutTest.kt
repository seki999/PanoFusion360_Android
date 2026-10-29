package com.panofusion.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var projectId: String? = null

    @After fun cleanup() { runBlocking {
        projectId?.let { (compose.activity.application as PanoApplication).store.delete(it) }
    } }

    @Test fun captureButtonsRemainAboveSystemNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant com.panofusion.app android.permission.CAMERA").close()
        val landscape = InstrumentationRegistry.getArguments().getString("captureLandscape") == "true"
        compose.activity.requestedOrientation = if (landscape)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(15_000) {
            compose.activity.resources.configuration.orientation == if (landscape)
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            else android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        val app = compose.activity.application as PanoApplication
        val project = runBlocking { app.store.create("布局验证-${System.nanoTime()}", "360") }
        projectId = project.id
        compose.waitUntil(15_000) { compose.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(project.name).performClick()
        compose.onNodeWithText("打开摄像头拍摄").performScrollTo().performClick()
        compose.onNodeWithText("拍摄 / 补拍").assertIsDisplayed()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("拍照") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val shot = instrumentation.uiAutomation.takeScreenshot()
        if (shot != null) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "capture-layout-debug.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/PanoFusionValidation")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            app.contentResolver.openOutputStream(uri).use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it!!) }
            shot.recycle()
        }
        for (label in listOf("拍照", "录像", "隐藏叠加")) {
            val button = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
            val coordinates = button.layoutInfo.coordinates
            val start = coordinates.localToWindow(Offset.Zero)
            val metrics = compose.activity.windowManager.currentWindowMetrics
            val bars = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
                .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            println("CAPTURE_BOUNDS $label start=$start size=${coordinates.size} clipped=${button.boundsInWindow} window=${metrics.bounds} bars=$bars")
            compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
            assertTrue("$label bottom=${start.y + coordinates.size.height}; safe bottom=${metrics.bounds.height() - bars.bottom}",
                start.y + coordinates.size.height <= metrics.bounds.height() - bars.bottom + 1)
            assertTrue("$label extends beyond the safe right edge", start.x + coordinates.size.width <= metrics.bounds.width() - bars.right + 1)
            assertTrue("$label extends into the left system bar", start.x >= bars.left - 1)
        }
        compose.onNodeWithText("返回工程").performClick()
        compose.onNodeWithText("拍摄 / 补拍").assertDoesNotExist()
        compose.onNodeWithText(project.name).assertIsDisplayed()
    }
}
