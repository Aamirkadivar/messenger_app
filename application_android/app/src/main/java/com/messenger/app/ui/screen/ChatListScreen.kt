package com.messenger.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.OnlineColor
import com.messenger.app.ui.theme.ThemeState
import com.messenger.app.ui.viewmodel.ChatListItemUi
import com.messenger.app.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chatViewModel: ChatViewModel,
    onChatClick: (chatId: String, chatName: String, isGroup: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateGroup: () -> Unit,
    onSessionExpired: () -> Unit,
    onLogout: () -> Unit
) {
    var showNewChat by remember { mutableStateOf(false) }
    // Non-null while the delete confirmation for that chat is showing.
    // Long-press opens an action sheet; Delete from there raises the confirm dialog.
    var menuChat by remember { mutableStateOf<ChatListItemUi?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatListItemUi?>(null) }
    val listState by chatViewModel.chatListState.collectAsStateWithLifecycle()
    val sessionExpired by chatViewModel.sessionExpired.collectAsStateWithLifecycle()
    val keyTakeover by chatViewModel.keyTakeover.collectAsStateWithLifecycle()

    // The server no longer accepts our token - hand off to login instead of
    // leaving the user on a list that can never refresh.
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) onSessionExpired()
    }

    LaunchedEffect(Unit) {
        chatViewModel.loadChats()
    }

    // Refresh unread badges whenever this screen comes back into view
    // (e.g. returning from a chat that just got marked read).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        chatViewModel.loadChats()
    }

    val dark = MessengerExtendedColors.isDark
    Box(modifier = Modifier.fillMaxSize()) {
    AmbientGlow(
        modifier = Modifier.fillMaxSize(),
        baseColor = MaterialTheme.colorScheme.background,
        primaryGlow = MaterialTheme.colorScheme.primary,
        secondaryGlow = if (dark) Color(0xFFA6863F) else Color(0xFF8A6A2E),
        intensity = if (dark) 1f else 0.6f
    )
    Scaffold(
        topBar = {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, sheen = false) {
                TopAppBar(
                    title = { Text("Chats", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { ThemeState.isDarkMode = !ThemeState.isDarkMode }) {
                            Icon(
                                if (ThemeState.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (ThemeState.isDarkMode) "Switch to light mode" else "Switch to dark mode"
                            )
                        }
                        IconButton(onClick = onCreateGroup) {
                            Icon(Icons.Outlined.GroupAdd, contentDescription = "New group")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = Color.White)
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (keyTakeover) {
            KeyTakeoverBanner(onDismiss = chatViewModel::dismissKeyTakeover)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                listState.isLoading && listState.chats.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                listState.chats.isEmpty() -> {
                    Text(
                        listState.error ?: "No conversations yet",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(listState.chats, key = { it.id }) { chat ->
                            ChatRow(
                                chat,
                                onClick = { onChatClick(chat.id, chat.name, chat.isGroup) },
                                onLongClick = { menuChat = chat }
                            )
                        }
                    }
                }
            }
        }
        }
    }
    }

    menuChat?.let { chat ->
        ChatActionsSheet(
            chat = chat,
            onDismiss = { menuChat = null },
            onMarkRead = {
                chatViewModel.markChatRead(chat.id)
                menuChat = null
            },
            onToggleMute = {
                chatViewModel.toggleMute(chat.id)
                menuChat = null
            },
            onDelete = {
                menuChat = null
                pendingDelete = chat
            }
        )
    }

    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = {
                // States plainly what this does and does not do - the server
                // only marks this user as having left the chat.
                Text(
                    "\"${chat.name}\" will be removed from your chat list. " +
                        if (chat.isGroup) {
                            "Other members keep the group and its messages."
                        } else {
                            "The other person keeps their copy, and a new message will bring the chat back."
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteChat(chat.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showNewChat) {
        NewChatSheet(
            chatViewModel = chatViewModel,
            onDismiss = { showNewChat = false },
            onChatStarted = { chatId, name ->
                showNewChat = false
                onChatClick(chatId, name, false)
            }
        )
    }
}

/**
 * Explains why older messages read "Encrypted message" on this device.
 *
 * Shown once, when signing in here replaced the account's E2EE identity key
 * that another device had registered. Without it the user just sees every
 * conversation turn into placeholders with no reason given.
 */
@Composable
private fun KeyTakeoverBanner(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Encryption keys were reset on this device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "This account's keys were previously set up on another device, so " +
                    "messages sent before you signed in here can't be read. New " +
                    "messages work normally.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatListItemUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // A long-press has no visual affordance, so confirm it
                    // registered before the dialog appears.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.BottomEnd) {
            Avatar(name = chat.name, avatarUrl = chat.avatarUrl, size = 48.dp)
            if (chat.isOnline) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                        .padding(2.dp)
                        .background(OnlineColor, CircleShape)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(chat.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                chat.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                chat.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chat.unreadCount > 0) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Long-press action menu for a chat row - the mobile equivalent of the Windows
 * client's right-click context menu. Delete is listed last and coloured as the
 * destructive action so it isn't hit by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatActionsSheet(
    chat: ChatListItemUi,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleMute: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name = chat.name, avatarUrl = chat.avatarUrl, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        chat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (chat.isGroup) "Group" else "Direct chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Only offered when there is actually something unread to clear.
            if (chat.unreadCount > 0) {
                ChatActionItem(
                    icon = Icons.Outlined.MarkChatRead,
                    label = "Mark as read",
                    onClick = onMarkRead
                )
            }
            ChatActionItem(
                icon = if (chat.isMuted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                label = if (chat.isMuted) "Unmute notifications" else "Mute notifications",
                onClick = onToggleMute
            )
            ChatActionItem(
                icon = Icons.Outlined.Delete,
                label = "Delete chat",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun ChatActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 56dp keeps every row well past the 48dp touch-target minimum.
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onChatStarted: (chatId: String, name: String) -> Unit
) {
    val searchState by chatViewModel.userSearchState.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp)) {
            Text("New Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchState.query,
                onValueChange = { chatViewModel.searchUsers(it) },
                label = { Text("Search by username or email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (searchState.isSearching) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(searchState.results) { user ->
                        val displayName = user.displayName ?: user.username
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chatViewModel.startDirectChat(user.id, displayName) { chatId ->
                                        onChatStarted(chatId, displayName)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(displayName, fontWeight = FontWeight.Bold)
                                Text(
                                    user.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
