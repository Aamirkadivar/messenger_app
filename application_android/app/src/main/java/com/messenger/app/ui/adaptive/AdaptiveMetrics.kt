package com.messenger.app.ui.adaptive

import com.messenger.app.ui.viewmodel.ChatListItemUi

/**
 * Material 3 window-width breakpoints, as floats in dp so layout math can be
 * unit-tested on the JVM without spinning up Compose.
 *
 * Compact  < 600
 * Medium   600 until 840
 * Expanded >= 840
 *
 * Two-pane list+conversation is used for Medium and Expanded. Compact keeps
 * the existing single-pane navigation.
 */
enum class MessengerWidthClass {
    Compact,
    Medium,
    Expanded
}

object AdaptiveMetrics {
    const val CompactMaxDp = 600f
    const val MediumMaxDp = 840f

    /** Phone-sized bubble cap - the historical ChatScreen hard-code. */
    const val CompactBubbleMaxDp = 280f

    /** Wide-pane bubble cap: long enough to read, short enough not to sprawl. */
    const val ExpandedBubbleMaxDp = 420f

    /** Conversation pane must keep at least this much so bubbles stay usable. */
    const val MinConversationPaneDp = 280f

    fun widthClass(widthDp: Float): MessengerWidthClass = when {
        widthDp < CompactMaxDp -> MessengerWidthClass.Compact
        widthDp < MediumMaxDp -> MessengerWidthClass.Medium
        else -> MessengerWidthClass.Expanded
    }

    fun usesTwoPane(widthClass: MessengerWidthClass): Boolean =
        widthClass != MessengerWidthClass.Compact

    fun usesTwoPane(widthDp: Float): Boolean = usesTwoPane(widthClass(widthDp))

    /**
     * Sidebar width for a two-pane messenger. Fractions follow the Windows
     * client's ~340dp sidebar, scaled to the available window rather than
     * locked to one tablet resolution.
     */
    fun listPaneWidthDp(totalWidthDp: Float, widthClass: MessengerWidthClass): Float {
        if (widthClass == MessengerWidthClass.Compact) return totalWidthDp
        val fraction = when {
            totalWidthDp >= 1200f -> 0.28f
            widthClass == MessengerWidthClass.Expanded -> 0.32f
            else -> 0.38f
        }
        val min = when (widthClass) {
            MessengerWidthClass.Medium -> 280f
            MessengerWidthClass.Expanded -> 320f
            MessengerWidthClass.Compact -> totalWidthDp
        }
        val max = when {
            totalWidthDp >= 1200f -> 420f
            widthClass == MessengerWidthClass.Expanded -> 400f
            else -> 360f
        }
        val raw = (totalWidthDp * fraction).coerceIn(min, max)
        val maxList = (totalWidthDp - MinConversationPaneDp).coerceAtLeast(min)
        return raw.coerceAtMost(maxList)
    }

    fun messageBubbleMaxWidthDp(conversationPaneWidthDp: Float, compact: Boolean): Float {
        if (compact) return CompactBubbleMaxDp
        return (conversationPaneWidthDp * 0.65f).coerceIn(CompactBubbleMaxDp, ExpandedBubbleMaxDp)
    }

    /** Local filter of the already-loaded chat list. No network. */
    fun filterChatList(chats: List<ChatListItemUi>, query: String): List<ChatListItemUi> {
        val q = query.trim()
        if (q.isEmpty()) return chats
        return chats.filter { chat ->
            chat.name.contains(q, ignoreCase = true) ||
                chat.lastMessage.contains(q, ignoreCase = true)
        }
    }
}
