package com.messenger.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Chat list screen showing all conversations
 */
@Composable
fun ChatListScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val uiState by viewModel.chatListState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadConversations()
    }

    ChatListContent(
        conversations = uiState.conversations,
        currentUser = uiState.currentUser,
        isLoading = uiState.isLoading,
        error = uiState.error,
        onlineUsers = uiState.onlineUsers,
        onConversationClick = { conversationId ->
            onNavigateToChat(conversationId)
        },
        onRefresh = { viewModel.loadConversations() },
        onProfileClick = onNavigateToProfile,
        modifier = modifier
    )
}

/**
 * Chat list UI content
 */
@Composable
private fun ChatListContent(
    conversations: List<com.messenger.app.data.model.Conversation>,
    currentUser: com.messenger.app.data.model.User?,
    isLoading: Boolean,
    error: String?,
    onlineUsers: Set<String>,
    onConversationClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            ChatListTopBar(
                showSearch = showSearch,
                searchQuery = searchQuery,
                onToggleSearch = { showSearch = !showSearch },
                onSearchQueryChange = { searchQuery = it },
                onProfileClick = onProfileClick,
                onRefresh = onRefresh
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MessengerThemeColors.ChatBackground)
        ) {
            when {
                isLoading && conversations.isEmpty() -> {
                    LoadingIndicator()
                }
                error != null -> {
                    ErrorScreen(
                        message = error,
                        onRetry = onRefresh
                    )
                }
                conversations.isEmpty() && searchQuery.isEmpty() -> {
                    EmptyState()
                }
                conversations.isEmpty() && searchQuery.isNotEmpty() -> {
                    NoSearchResults()
                }
                else -> {
                    val filteredConversations = if (searchQuery.isBlank()) {
                        conversations
                    } else {
                        conversations.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.lastMessage?.contains(searchQuery, ignoreCase = true) == true
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
                    ) {
                        items(filteredConversations, key = { it.id }) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                isOnline = onlineUsers.contains(conversation.otherUserId),
                                onClick = { onConversationClick(conversation.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    shrinkSpec = null,
                                    expandSpec = null
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top bar for chat list
 */
@Composable
private fun ChatListTopBar(
    showSearch: Boolean,
    searchQuery: String,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onProfileClick: () -> Unit,
    onRefresh: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = "Chats",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onProfileClick) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MessengerThemeColors.Primary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        actions = {
            if (showSearch) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close search",
                        tint = Color.White
                    )
                }
            } else {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White
                    )
                }
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MessengerThemeColors.Primary,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White
        ),
        windowInsets = WindowInsets(0)
    )

    // Search bar
    AnimatedVisibility(
        visible = showSearch,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search conversations...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    borderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent
                )
            )
        }
    }
}

/**
 * Individual conversation item
 */
@Composable
private fun ConversationItem(
    conversation: com.messenger.app.data.model.Conversation,
    isOnline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MessengerThemeColors.Primary,
                                Color(0xFF1E88C5)
                            )
                        )
                    )
            ) {
                // Online indicator
                if (isOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                }

                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = conversation.name.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Conversation info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = conversation.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A1A2E),
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = conversation.lastMessage ?: "No messages yet",
                    fontSize = 14.sp,
                    color = if (isOnline) Color(0xFF4CAF50) else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Metadata
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight()
            ) {
                Text(
                    text = conversation.lastMessageTime ?: "",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Unread count badge
                if (conversation.unreadCount > 0) {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = MessengerThemeColors.Primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loading indicator
 */
@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                color = MessengerThemeColors.Primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading conversations...",
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
    }
}

/**
 * Empty state when no conversations exist
 */
@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = RoundedCornerShape(50%),
                color = MessengerThemeColors.Primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = MessengerThemeColors.Primary,
                        modifier = Modifier.size(50.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No conversations yet",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A2E)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Start a new conversation to get started!",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Error display screen
 */
@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = Color(0xFFD32F2F),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = message,
                fontSize = 16.sp,
                color = Color(0xFFD32F2F),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

/**
 * No search results state
 */
@Composable
private fun NoSearchResults() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "No results found",
                fontSize = 16.sp,
                color = Color.Gray
            )
        }
    }
}