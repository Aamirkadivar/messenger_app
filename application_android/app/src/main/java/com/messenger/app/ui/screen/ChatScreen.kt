package com.messenger.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main chat screen for one-to-one and group conversations
 */
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val uiState by viewModel.chatState.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversationId) {
        viewModel.loadMessages(conversationId)
        viewModel.loadTypingStatus(conversationId)
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            lazyListState.animateScrollToItem(uiState.messages.size)
        }
    }

    // Load typing status
    LaunchedEffect(conversationId) {
        viewModel.typingStatusFlow[conversationId]?.collect { isTyping ->
            // Update typing indicator state
        }
    }

    ChatContent(
        conversation = uiState.conversation,
        messages = uiState.messages,
        isTyping = uiState.isTyping,
        currentUser = uiState.currentUser,
        isLoading = uiState.isLoading,
        error = uiState.error,
        sendMessage = { text -> viewModel.sendMessage(conversationId, text) },
        loadMoreMessages = { viewModel.loadMoreMessages(conversationId) },
        onMessageStatusUpdate = { messageId, status ->
            viewModel.updateMessageStatus(conversationId, messageId, status)
        },
        lazyListState = lazyListState,
        onProfileClick = { /* Navigate to profile */ },
        onNavigateBack = onNavigateBack,
        modifier = modifier
    )
}

/**
 * Chat content with message list and input area
 */
@Composable
private fun ChatContent(
    conversation: com.messenger.app.data.model.Conversation?,
    messages: List<com.messenger.app.data.model.Message>,
    isTyping: Boolean,
    currentUser: com.messenger.app.data.model.User?,
    isLoading: Boolean,
    error: String?,
    sendMessage: (String) -> Unit,
    loadMoreMessages: () -> Unit,
    onMessageStatusUpdate: (String, String) -> Unit,
    lazyListState: LazyListState,
    onProfileClick: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MessengerThemeColors.ChatBackground)
    ) {
        // Chat background pattern
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
            // Load more indicator at start
            if (messages.isNotEmpty()) {
                item {
                    LoadMoreIndicator(
                        onLoadMore = loadMoreMessages,
                        isLoading = isLoading
                    )
                }
            }

            // Messages
            itemsIndexed(messages, key = { index, msg -> msg.id }) { index, message ->
                val isLastMessage = index == messages.size - 1
                val isSameSenderAsNext = index < messages.size - 1 &&
                    messages[index + 1].senderId == message.senderId

                MessageBubble(
                    message = message,
                    isFromCurrentUser = message.senderId == currentUser?.id,
                    isLastMessage = isLastMessage,
                    isSameSenderAsNext = isSameSenderAsNext,
                    onMessageClick = { onMessageStatusUpdate(message.id, "viewed") },
                    onLongClick = { /* Show context menu */ },
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

            // Bottom spacer for input bar
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
    }
}

/**
 * Message bubble with Telegram-style design
 */
@Composable
private fun MessageBubble(
    message: com.messenger.app.data.model.Message,
    isFromCurrentUser: Boolean,
    isLastMessage: Boolean,
    isSameSenderAsNext: Boolean,
    onMessageClick: () -> Unit,
    onLongClick: () -> Unit,
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

    val horizontalOffset = if (isFromCurrentUser) 24.dp else 56.dp

    Row(
        modifier = modifier
            .offset(x = if (isFromCurrentUser) 0.dp else (-16).dp),
        horizontalArrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isFromCurrentUser && !isSameSenderAsNext) {
            // Avatar for received messages
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(MessengerThemeColors.Primary, Color(0xFF1E88C5))
                        )
                    ),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = message.senderName?.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Message bubble
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clickable(onClick = onMessageClick, indication = null, interactionSource = remember { MutableInteractionSource() })
                .onLongClick(onLongClick),
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
                // Sender name for group chats
                if (!isFromCurrentUser && message.senderName != null && isSameSenderAsNext) {
                    Text(
                        text = message.senderName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
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

                    // Message status icon
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
 * Chat input bar with send button
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onKeyboardHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = Color.White.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment button
            IconButton(
                onClick = { /* Open attachment picker */ },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Text input
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp),
                placeholder = { Text("Message", color = Color.Gray) },
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onKeyboardHide() }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    borderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color(0xFFF0F2F5),
                    containerColor = Color(0xFFF0F2F5)
                ),
                maxLines = 8
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Send button
            IconButton(
                onClick = onSendClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (inputText.isNotBlank()) {
                            Brush.linearGradient(
                                colors = listOf(MessengerThemeColors.Primary, Color(0xFF1E88C5))
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(Color.Gray.copy(alpha = 0.5f), Color.Gray.copy(alpha = 0.5f))
                            )
                        },
                        CircleShape
                    ),
                enabled = inputText.isNotBlank()
            ) {
                Icon(
                    imageVector = if (inputText.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                    contentDescription = if (inputText.isNotBlank()) "Send" else "Voice message",
                    tint = if (inputText.isNotBlank()) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Typing indicator animation
 */
@Composable
private fun TypingIndicator() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        modifier = Modifier.padding(horizontal = 16.dp).wrapContentWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Typing dots with animation
            for (i in 0 until 3) {
                TypingDot(index = i)
            }
        }
    }
}

@Composable
private fun TypingDot(index: Int) {
    val transition = updateTransition(targetState = index, label = "typing_dot")
    val scale by transition.animateFloat(
        label = "scale"
    ) { state ->
        animateFloat(
            animationSpec = androidx.compose.animation.core.RepeatableSpec(
                iterations = 3,
                durationMillis = 600,
                delayMillis = 200 * state
            )
        ) { i ->
            if (i < 0.5f) 0.6f + i else 1.4f - i
        }
    }

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                Color.Gray.copy(alpha = 0.6f),
                CircleShape
            )
            .scale(scale)
    )
}

/**
 * Load more indicator at top of message list
 */
@Composable
private fun LoadMoreIndicator(
    onLoadMore: () -> Unit,
    isLoading: Boolean
) {
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MessengerThemeColors.Primary,
                trackColor = MessengerThemeColors.Primary.copy(alpha = 0.1f)
            )
        }
    }
}

// ==================== Helper Functions ====================

private fun formatMessageTime(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    // In production, use proper date formatting
    // For now, return as-is (ISO 8601 format from backend)
    return try {
        val parts = timestamp.split("T")
        if (parts.size >= 2) {
            val timeParts = parts[1].split(":")
            if (timeParts.size >= 2) {
                "$${timeParts[0]}:${timeParts[1]}"
            } else {
                timestamp
            }
        } else {
            timestamp
        }
    } catch (e: Exception) {
        timestamp
    }
}

private fun getMessageStatusIcon(status: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (status) {
        "sent" -> Icons.Default.Send
        "delivered" -> Icons.Default.Done
        "viewed" -> Icons.Default.DoneAll
        "failed" -> Icons.Default.Error
        else -> Icons.Default.Send
    }
}