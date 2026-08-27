package com.messenger.app.ui.adaptive

import androidx.compose.runtime.saveable.listSaver

/**
 * The conversation currently shown in the tablet detail pane.
 *
 * Held above the NavHost destinations so rotation and process recreation can
 * restore it without depending on the compact [chat/{id}] route still being
 * on the back stack.
 */
data class SelectedConversation(
    val chatId: String,
    val chatName: String,
    val isGroup: Boolean
) {
    companion object {
        val Saver = listSaver<SelectedConversation?, Any>(
            save = { selected ->
                if (selected == null) emptyList()
                else listOf(selected.chatId, selected.chatName, selected.isGroup)
            },
            restore = { restored ->
                if (restored.isEmpty()) null
                else SelectedConversation(
                    chatId = restored[0] as String,
                    chatName = restored[1] as String,
                    isGroup = restored[2] as Boolean
                )
            }
        )
    }
}
