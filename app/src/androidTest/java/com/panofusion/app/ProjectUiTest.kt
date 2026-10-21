package com.panofusion.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createProjectThroughComposeUi() {
        val name="UI工程-${System.nanoTime()}"
        compose.onNodeWithText("新建全景").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText("创建",useUnmergedTree=true).performClick()
        compose.waitUntil(timeoutMillis=15000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText("导入视频 / 照片（新补拍批次）").assertExists()
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithText(name).assertExists()
    }
}
