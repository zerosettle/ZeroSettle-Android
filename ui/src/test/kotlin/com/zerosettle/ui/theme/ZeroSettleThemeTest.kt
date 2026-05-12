package com.zerosettle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleThemeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun theme_rendersChildren() {
        composeRule.setContent {
            MaterialTheme { ZeroSettleTheme { Text("hello") } }
        }
        composeRule.onNodeWithText("hello").assertIsDisplayed()
    }

    @Test fun defaults_deriveAccentFromBrandGreen() {
        assertEquals(Color(0xFF6CA358), ZeroSettleDefaults.offerAccentColor())
    }
}
