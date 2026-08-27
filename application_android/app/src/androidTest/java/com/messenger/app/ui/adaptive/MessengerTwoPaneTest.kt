package com.messenger.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.MessengerTheme
import org.junit.Rule
import org.junit.Test

/**
 * Adaptive shell: two panes on expanded width, empty detail copy, selected row.
 */
class MessengerTwoPaneTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun expandedWidth_showsListAndEmptyConversation() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWidthClass provides MessengerWidthClass.Expanded) {
                    Box(Modifier.size(1000.dp, 800.dp)) {
                        MessengerTwoPane(
                            list = { Text("Chat list") },
                            detail = { ConversationEmptyPane() }
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("messenger_two_pane").assertIsDisplayed()
        rule.onNodeWithTag("chat_list_pane").assertIsDisplayed()
        rule.onNodeWithTag("conversation_pane").assertIsDisplayed()
        rule.onNodeWithTag("conversation_empty_pane").assertIsDisplayed()
        rule.onNodeWithText("Chat list").assertIsDisplayed()
        rule.onNodeWithText("Select a conversation").assertIsDisplayed()
        rule.onNodeWithText("Choose a chat from the list to start messaging").assertIsDisplayed()
    }

    @Test
    fun expandedWidth_showsConversationWhenSelected() {
        rule.setContent {
            MessengerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalWidthClass provides MessengerWidthClass.Expanded) {
                    Box(Modifier.size(1000.dp, 800.dp)) {
                        MessengerTwoPane(
                            list = { Text("Sidebar") },
                            detail = { Text("Active conversation") }
                        )
                    }
                }
            }
        }

        rule.onNodeWithText("Sidebar").assertIsDisplayed()
        rule.onNodeWithText("Active conversation").assertIsDisplayed()
        rule.onNodeWithTag("conversation_empty_pane").assertDoesNotExist()
    }

    @Test
    fun mediumWidth_stillUsesTwoPanes() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                CompositionLocalProvider(LocalWidthClass provides MessengerWidthClass.Medium) {
                    Box(Modifier.size(700.dp, 1000.dp)) {
                        MessengerTwoPane(
                            list = { Text("List pane") },
                            detail = { ConversationEmptyPane() }
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("messenger_two_pane").assertIsDisplayed()
        rule.onNodeWithText("List pane").assertIsDisplayed()
        rule.onNodeWithText("Select a conversation").assertIsDisplayed()
    }
}
