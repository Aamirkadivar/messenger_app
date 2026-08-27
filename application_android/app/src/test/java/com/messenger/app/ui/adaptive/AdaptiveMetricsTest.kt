package com.messenger.app.ui.adaptive

import com.messenger.app.ui.viewmodel.ChatListItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Window-width breakpoints and pane math. These must stay aligned with
 * Material 3 compact / medium / expanded so a resize, not a device type,
 * is what switches the messenger between stacked and two-pane.
 */
class AdaptiveMetricsTest {

    @Test
    fun widthClass_matchesMaterial3Breakpoints() {
        assertEquals(MessengerWidthClass.Compact, AdaptiveMetrics.widthClass(359f))
        assertEquals(MessengerWidthClass.Compact, AdaptiveMetrics.widthClass(599.9f))
        assertEquals(MessengerWidthClass.Medium, AdaptiveMetrics.widthClass(600f))
        assertEquals(MessengerWidthClass.Medium, AdaptiveMetrics.widthClass(839.9f))
        assertEquals(MessengerWidthClass.Expanded, AdaptiveMetrics.widthClass(840f))
        assertEquals(MessengerWidthClass.Expanded, AdaptiveMetrics.widthClass(1280f))
    }

    @Test
    fun twoPane_onlyAboveCompact() {
        assertFalse(AdaptiveMetrics.usesTwoPane(400f))
        assertFalse(AdaptiveMetrics.usesTwoPane(MessengerWidthClass.Compact))
        assertTrue(AdaptiveMetrics.usesTwoPane(600f))
        assertTrue(AdaptiveMetrics.usesTwoPane(840f))
        assertTrue(AdaptiveMetrics.usesTwoPane(MessengerWidthClass.Medium))
        assertTrue(AdaptiveMetrics.usesTwoPane(MessengerWidthClass.Expanded))
    }

    @Test
    fun listPane_leavesRoomForConversation() {
        val medium = AdaptiveMetrics.listPaneWidthDp(700f, MessengerWidthClass.Medium)
        assertTrue("medium list $medium", medium in 280f..360f)
        assertTrue(700f - medium >= AdaptiveMetrics.MinConversationPaneDp)

        val expanded = AdaptiveMetrics.listPaneWidthDp(1000f, MessengerWidthClass.Expanded)
        assertTrue("expanded list $expanded", expanded in 320f..400f)
        assertTrue(1000f - expanded >= AdaptiveMetrics.MinConversationPaneDp)

        val wide = AdaptiveMetrics.listPaneWidthDp(1400f, MessengerWidthClass.Expanded)
        assertTrue("wide list $wide", wide in 340f..420f)
        assertTrue(1400f - wide >= AdaptiveMetrics.MinConversationPaneDp)
    }

    @Test
    fun listPane_onTightMediumStillFitsBothPanes() {
        val list = AdaptiveMetrics.listPaneWidthDp(600f, MessengerWidthClass.Medium)
        assertEquals(600f, list + (600f - list), 0.01f)
        assertTrue(list >= 280f)
        assertTrue(600f - list >= AdaptiveMetrics.MinConversationPaneDp)
    }

    @Test
    fun compactListPane_usesFullWidth() {
        assertEquals(400f, AdaptiveMetrics.listPaneWidthDp(400f, MessengerWidthClass.Compact), 0.01f)
    }

    @Test
    fun messageBubbles_stayReadableOnWidePanes() {
        assertEquals(
            AdaptiveMetrics.CompactBubbleMaxDp,
            AdaptiveMetrics.messageBubbleMaxWidthDp(360f, compact = true),
            0.01f
        )
        val tablet = AdaptiveMetrics.messageBubbleMaxWidthDp(700f, compact = false)
        assertTrue("tablet bubble $tablet", tablet in 280f..420f)
        assertEquals(
            AdaptiveMetrics.ExpandedBubbleMaxDp,
            AdaptiveMetrics.messageBubbleMaxWidthDp(2000f, compact = false),
            0.01f
        )
    }

    @Test
    fun filterChatList_matchesNameOrPreview() {
        val chats = listOf(
            chat("1", "Alice", "Hello there"),
            chat("2", "Bob", "See you"),
            chat("3", "Project team", "Alice: shipped it")
        )
        assertEquals(chats, AdaptiveMetrics.filterChatList(chats, "  "))
        assertEquals(listOf(chats[0]), AdaptiveMetrics.filterChatList(chats, "hello"))
        assertEquals(listOf(chats[1]), AdaptiveMetrics.filterChatList(chats, "see"))
        assertEquals(2, AdaptiveMetrics.filterChatList(chats, "Alice").size)
        assertTrue(AdaptiveMetrics.filterChatList(chats, "zzz").isEmpty())
    }

    @Test
    fun isChatRoute_doesNotMatchChatList() {
        assertFalse(isChatRoute("chat_list"))
        assertFalse(isChatRoute("settings"))
        assertTrue(isChatRoute("chat/{chatId}/{chatName}?isGroup={isGroup}"))
    }

    private fun chat(id: String, name: String, last: String) = ChatListItemUi(
        id = id,
        name = name,
        otherUserId = "",
        lastMessage = last,
        timestamp = "",
        unreadCount = 0,
        isOnline = false
    )
}
