package com.jkdc.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jkdc.app.ui.AppTheme
import com.jkdc.app.ui.TypedReplySheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypedReplySheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun replyCanBeEnteredAndSentFromTheSheetWithoutLosingTheActionToTheKeyboard() {
        var sent: String? = null
        var dismissed = false
        compose.setContent { AppTheme { TypedReplySheet("Spanish", false, { sent = it }, { dismissed = true }) } }
        compose.onNodeWithTag("typed-reply-input").assertIsFocused().performTextInput("  Me gustaría un café.  ")
        compose.onNodeWithTag("typed-reply-send").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Me gustaría un café.", sent); assertTrue(dismissed) }
    }
}
