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
        fun text(id: Int) = compose.activity.getString(id)
        compose.onNodeWithText(text(R.string.new_panorama)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText(text(R.string.create),useUnmergedTree=true).performClick()
        compose.waitUntil(timeoutMillis=15000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertExists()
        compose.onNodeWithText(text(R.string.import_media)).assertExists()
        compose.onNodeWithText(text(R.string.back),substring=false).performClick()
        compose.onNodeWithText(name).assertExists()
    }
}
