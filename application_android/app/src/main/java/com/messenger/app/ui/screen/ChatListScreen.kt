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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.theme.AccentPurple
import com.messenger.app.ui.theme.OnlineColor
import com.messenger.app.ui.viewmodel.ChatListItemUi
import com.messenger.app.ui.viewmodel.ChatViewModel

private val avatarPalette = listOf(
    Color(0xFF6C63FF), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF00BCD4)
)

private fun avatarColorFor(name: String): Color {
    if (name.isEmpty()) return avatarPalette[0]
    return avatarPalette[(name.hashCode().and(Int.MAX_VALUE)) % avatarPalette.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chatViewModel: ChatViewModel,
    onChatClick: (chatId: String, chatName: String) -> Unit,
    onLogout: () -> Unit
) {
    var showNewChat by remember { mutableStateOf(false) }
    val listState by chatViewModel.chatListState.collectAsStateWithLifecycle()

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
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }, containerColor = AccentPurple) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = Color.White)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                            ChatRow(chat, onClick = { onChatClick(chat.id, chat.name) })
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
                onChatClick(chatId, name)
            }
        )
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(avatarColorFor(chat.name), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    chat.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
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
                color = if (chat.unreadCount > 0) AccentPurple else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chat.unreadCount > 0) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .background(AccentPurple, CircleShape)
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
                                modifier = Modifier.size(40.dp).background(AccentPurple, CircleShape),
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
