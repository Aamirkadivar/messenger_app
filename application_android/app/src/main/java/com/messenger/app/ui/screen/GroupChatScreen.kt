package com.messenger.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch

/**
 * Group chat screen with member management
 */
@Composable
fun GroupChatScreen(
    groupId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val uiState by viewModel.groupChatState.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showMemberList by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        viewModel.loadGroupChat(groupId)
    }

    GroupChatContent(
        group = uiState.group,
        messages = uiState.messages,
        members = uiState.members,
        currentUser = uiState.currentUser,
        isTyping = uiState.isTyping,
        isLoading = uiState.isLoading,
        error = uiState.error,
        sendMessage = { text -> viewModel.sendGroupMessage(groupId, text) },
        loadMoreMessages = { viewModel.loadMoreMessages(groupId) },
        onToggleMemberList = { showMemberList = !showMemberList },
        onNavigateBack = onNavigateBack,
        lazyListState = lazyListState,
        modifier = modifier
    )
}

/**
 * Group chat content
 */
@Composable
private fun GroupChatContent(
    group: com.messenger.app.data.model.GroupChat?,
    messages: List<com.messenger.app.data.model.Message>,
    members: List<com.messenger.app.data.model.User>,
    currentUser: com.messenger.app.data.model.User?,
    isTyping: Boolean,
    isLoading: Boolean,
    error: String?,
    sendMessage: (String) -> Unit,
    loadMoreMessages: () -> Unit,
    onToggleMemberList: () -> Unit,
    onNavigateBack: () -> Unit,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MessengerThemeColors.ChatBackground)
    ) {
        // Background gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF0F2F5),
                            Color(0xFFE8E8E8)
                        )
                    )
                )
        )

        // Message list
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentPadding = PaddingValues(
                horizontal = 8.dp,
                vertical = 8.dp
            ),
            reverseLayout = false,
            verticalArrangement = Arrangement.Bottom
        ) {
            // Load more indicator
            if (messages.isNotEmpty()) {
                item {
                    LoadMoreIndicator(
                        onLoadMore = loadMoreMessages,
                        isLoading = isLoading
                    )
                }
            }

            // Messages
            items(messages, key = { it.id }) { message ->
                val isFromCurrentUser = message.senderId == currentUser?.id
                val isLastMessage = messages.lastOrNull()?.id == message.id

                GroupMessageBubble(
                    message = message,
                    isFromCurrentUser = isFromCurrentUser,
                    isLastMessage = isLastMessage,
                    onMessageClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )

                Spacer(modifier = Modifier.height(4.dp))
            }

            // Typing indicator
            item {
                AnimatedVisibility(
                    visible = isTyping,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TypingIndicator(modifier = Modifier.padding(8.dp))
                }
            }

            // Bottom spacer
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Error overlay
        if (error != null && messages.isEmpty()) {
            ErrorScreen(message = error, onRetry = loadMoreMessages)
        }

        // Input bar
        ChatInputBar(
            inputText = inputText,
            onInputChange = { inputText = it },
            onSendClick = {
                if (inputText.isNotBlank()) {
                    sendMessage(inputText)
                    inputText = ""
                    keyboardController?.hide()
                }
            },
            onKeyboardHide = { keyboardController?.hide() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Member list overlay
        if (showMemberList && group != null) {
            MemberListOverlay(
                group = group,
                members = members,
                onlineUsers = members.filter { it.isOnline }.toSet(),
                onDismiss = onToggleMemberList
            )
        }
    }
}

/**
 * Group message bubble with sender name
 */
@Composable
private fun GroupMessageBubble(
    message: com.messenger.app.data.model.Message,
    isFromCurrentUser: Boolean,
    isLastMessage: Boolean,
    onMessageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isFromCurrentUser) {
        MessengerThemeColors.SentBubble
    } else {
        MessengerThemeColors.ReceivedBubble
    }

    val textColor = if (isFromCurrentUser) {
        MessengerThemeColors.SentBubbleText
    } else {
        MessengerThemeColors.ReceivedBubbleText
    }

    Row(
        modifier = modifier
            .offset(x = if (isFromCurrentUser) 0.dp else (-16).dp),
        horizontalArrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable(onClick = onMessageClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
            shape = if (isFromCurrentUser) {
                if (isLastMessage) MessengerTheme.ChatBubbleShape else RoundedCornerShape(16.dp, 4.dp, 4.dp, 4.dp)
                else RoundedCornerShape(4.dp, 16.dp, 4.dp, 16.dp)
            } else {
                if (isLastMessage) MessengerTheme.ReceivedBubbleShape else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
            },
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                // Sender name (always shown in group chat)
                if (!isFromCurrentUser && message.senderName != null) {
                    Text(
                        text = message.senderName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isFromCurrentUser) textColor else MessengerThemeColors.Primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Message text
                Text(
                    text = message.content,
                    fontSize = 15.sp,
                    color = textColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Timestamp and status
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = formatMessageTime(message.timestamp),
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    if (isFromCurrentUser) {
                        Icon(
                            imageVector = getMessageStatusIcon(message.status),
                            contentDescription = message.status,
                            tint = if (message.status == "delivered" || message.status == "viewed") {
                                Color(0xFF53BDEB)
                            } else {
                                textColor.copy(alpha = 0.7f)
                            },
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Member list overlay
 */
@Composable
private fun MemberListOverlay(
    group: com.messenger.app.data.model.GroupChat,
    members: List<com.messenger.app.data.model.User>,
    onlineUsers: Set<String>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            // Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A2E)
                )

                Text(
                    text = "${members.size} members",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Members list
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(members, key = { it.id }) { member ->
                    MemberItem(
                        user = member,
                        isOnline = onlineUsers.contains(member.id),
                        isCreator = member.id == group.creatorId
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Individual member item
 */
@Composable
private fun MemberItem(
    user: com.messenger.app.data.model.User,
    isOnline: Boolean,
    isCreator: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(MessengerThemeColors.Primary, Color(0xFF1E88C5))
                    )
                )
        ) {
            if (isOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }

            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = user.name?.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // User info
        Column {
            Text(
                text = user.name ?: "Unknown",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1A1A2E)
            )
            Row {
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    fontSize = 12.sp,
                    color = if (isOnline) Color(0xFF4CAF50) else Color.Gray
                )
                if (isCreator) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "· Owner",
                        fontSize = 12.sp,
                        color = MessengerThemeColors.Primary
                    )
                }
            }
        }
    }
}