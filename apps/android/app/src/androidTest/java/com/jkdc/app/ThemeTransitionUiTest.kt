package com.jkdc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jkdc.app.ui.AppTheme
import com.jkdc.app.ui.TopicTransitionDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThemeTransitionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissingTopicSwitchKeepsActiveConversationContext() {
        val gate = TopicTransitionGate<String>()
        val token = gate.request("new topic")
        var appliedSelections = 0
        var starts = 0
        var dialogVisible by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                if (dialogVisible && gate.pending != null) TopicTransitionDialog(
                    title = gate.pending!!.value,
                    onConfirm = { appliedSelections++; starts++ },
                    onDismiss = { gate.cancel(token); dialogVisible = false },
                )
            }
        }

        compose.onNodeWithTag("topic-switch-cancel").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(0, appliedSelections)
            assertEquals(0, starts)
            assertEquals(null, gate.pending)
        }
    }
}
