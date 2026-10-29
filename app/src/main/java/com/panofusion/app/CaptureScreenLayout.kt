package com.panofusion.app

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** Reserve the controls before measuring the native preview, including in a full-screen dialog. */
@Composable
internal fun CaptureScreenLayout(
    header: @Composable ColumnScope.() -> Unit,
    preview: @Composable BoxScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val systemPadding = captureWindowPadding()
    BoxWithConstraints(Modifier.fillMaxSize().padding(systemPadding)) {
        val headerLimit = maxHeight * 0.35f
        val controlsLimit = maxHeight * 0.55f
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = headerLimit)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = header,
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = controlsLimit)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = controls,
                    )
                }
            },
        ) { contentPadding ->
            Box(
                Modifier.fillMaxSize().padding(contentPadding).padding(vertical = 8.dp).clipToBounds(),
                content = preview,
            )
        }
    }
}

@Composable
private fun captureWindowPadding(): PaddingValues {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var rootBars by remember(view) { mutableStateOf(Insets.NONE) }
    DisposableEffect(view) {
        val update = Runnable {
            ViewCompat.getRootWindowInsets(view)?.let { insets ->
                rootBars = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update.run() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.post(update)
        onDispose {
            view.removeCallbacks(update)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    // Explicit padding uses this window's full insets even when the underlying project screen
    // already consumed Compose insets. Keep system bars reserved while transiently hidden.
    val safe = WindowInsets.safeDrawing
    return with(density) {
        PaddingValues.Absolute(
            left = max(rootBars.left, safe.getLeft(density, direction)).toDp(),
            top = max(rootBars.top, safe.getTop(density)).toDp(),
            right = max(rootBars.right, safe.getRight(density, direction)).toDp(),
            bottom = max(rootBars.bottom, safe.getBottom(density)).toDp(),
        )
    }
}
