package com.messenger.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import com.messenger.app.ui.navigation.Routes
import java.net.URLDecoder

/**
 * Keeps compact (stacked) and two-pane (list+detail) navigation in sync across
 * window-size changes. Selected conversation is the tablet source of truth;
 * the `chat/{id}` route is the phone source of truth.
 *
 * Expanding while on a chat route: copy args into [selected] and pop to the
 * list so both panes show. Collapsing while a conversation is selected and
 * the list is showing: push the chat route so the conversation stays open.
 */
@Composable
fun SyncListDetailNavigation(
    twoPane: Boolean,
    selected: SelectedConversation?,
    currentRoute: String?,
    onAbsorbChatRoute: (SelectedConversation) -> Unit,
    navController: NavHostController
) {
    LaunchedEffect(twoPane, selected?.chatId, currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        if (twoPane && isChatRoute(route)) {
            val entry = navController.currentBackStackEntry ?: return@LaunchedEffect
            val chatId = entry.arguments?.getString("chatId") ?: return@LaunchedEffect
            val encoded = entry.arguments?.getString("chatName").orEmpty()
            val chatName = URLDecoder.decode(encoded, "UTF-8")
            val isGroup = entry.arguments?.getBoolean("isGroup") ?: false
            onAbsorbChatRoute(SelectedConversation(chatId, chatName, isGroup))
            navController.popBackStack(Routes.CHAT_LIST, inclusive = false)
        } else if (!twoPane && selected != null && route == Routes.CHAT_LIST) {
            navController.navigate(Routes.chat(selected.chatId, selected.chatName, selected.isGroup))
        }
    }
}

internal fun isChatRoute(route: String): Boolean = route.startsWith("chat/")
