package com.messenger.app.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import com.messenger.app.data.settings.PreviewPrivacy
import com.messenger.app.ui.theme.MessengerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Accessibility contract for the Settings primitives.
 *
 * These assertions run against Compose's *merged* semantics tree - the same one
 * TalkBack consumes. `uiautomator dump` exposes the unmerged tree instead, where
 * every Compose control looks unlabelled (stock Material3 components included),
 * so it cannot be used to judge this.
 */
class SettingsAccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun toggleRow_labelAndStateAreOnTheSameNode() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ToggleRow(
                    title = "Allow notifications",
                    subtitle = "Turn off to silence everything below",
                    checked = true,
                    onCheckedChange = {}
                )
            }
        }

        // Finding by label and then asserting toggle state only succeeds if the
        // text and the switch semantics belong to one merged node.
        rule.onNodeWithText("Allow notifications")
            .assertIsOn()
            .assertHasClickAction()
    }

    @Test
    fun toggleRow_subtitleIsPartOfTheSameControl() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ToggleRow(
                    title = "Vibrate",
                    subtitle = "Buzz on arrival",
                    checked = false,
                    onCheckedChange = {}
                )
            }
        }

        // The subtitle must resolve to the switch, not to a stray text node.
        rule.onNodeWithText("Buzz on arrival").assertIsOff().assertHasClickAction()
    }

    @Test
    fun toggleRow_reportsSpokenOnOffState() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ToggleRow(title = "Data saver", checked = true, onCheckedChange = {})
            }
        }

        rule.onNodeWithText("Data saver").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On")
        )
    }

    @Test
    fun toggleRow_wholeRowTogglesNotJustTheSwitch() {
        var checked = false
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ToggleRow(
                    title = "In-app sounds",
                    checked = checked,
                    onCheckedChange = { checked = it }
                )
            }
        }

        // Clicking the label region, far from the switch graphic, must still toggle.
        rule.onNodeWithText("In-app sounds").performClick()
        assertTrue("Tapping the row body should toggle the setting", checked)
    }

    @Test
    fun disabledToggleRow_isNotClickable() {
        var changed = false
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ToggleRow(
                    title = "Reactions",
                    checked = true,
                    enabled = false,
                    onCheckedChange = { changed = true }
                )
            }
        }

        rule.onNodeWithText("Reactions").performClick()
        assertEquals("A disabled row must not fire its callback", false, changed)
    }

    @Test
    fun valueRow_exposesItsValueAsSpokenState() {
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                ValueRow(
                    title = "Preview",
                    subtitle = "What appears on the lock screen",
                    value = "Name only",
                    onClick = {}
                )
            }
        }

        rule.onNodeWithText("Preview")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Name only"
                )
            )
    }

    @Test
    fun optionList_selectionIsExposedAndSelectable() {
        var selected = PreviewPrivacy.NAME_AND_MESSAGE
        rule.setContent {
            MessengerTheme(darkTheme = true) {
                OptionList(
                    options = PreviewPrivacy.entries,
                    selected = selected,
                    onSelect = { selected = it },
                    label = { it.label },
                    description = { it.description }
                )
            }
        }

        rule.onNodeWithText("Hidden").performClick()
        assertEquals(PreviewPrivacy.HIDDEN, selected)
    }
}
