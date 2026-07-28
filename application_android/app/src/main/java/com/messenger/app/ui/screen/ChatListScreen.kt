package com.messenger.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.components.Avatar
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

    Scaffold(
        topBar = {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = Color.White)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
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
                            ChatRow(chat, onClick = { onChatClick(chat.id, chat.name, chat.isGroup) })
                        }
                    }
                }
            }
        }
        }
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

@Composable
private fun ChatRow(chat: ChatListItemUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
