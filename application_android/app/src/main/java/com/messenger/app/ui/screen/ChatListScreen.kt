package com.messenger.app.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Block
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.R
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ChatPeekDialog
import com.messenger.app.ui.components.ConnectionStatusBanner
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.OnlineColor
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.luxuryTween
import com.messenger.app.ui.viewmodel.ChatListItemUi
import com.messenger.app.ui.viewmodel.ChatViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

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
    var pendingBlock by remember { mutableStateOf<ChatListItemUi?>(null) }
    // Non-null while the avatar peek is open for that chat.
    var peekChat by remember { mutableStateOf<ChatListItemUi?>(null) }
    val listState by chatViewModel.chatListState.collectAsStateWithLifecycle()
    val sessionExpired by chatViewModel.sessionExpired.collectAsStateWithLifecycle()
    val keyTakeover by chatViewModel.keyTakeover.collectAsStateWithLifecycle()
    val connectionState by chatViewModel.connectionState.collectAsStateWithLifecycle()

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
    val hazeState = remember { HazeState() }
    Box(modifier = Modifier.fillMaxSize()) {
    AmbientGlow(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(state = hazeState, zIndex = 0f),
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
        // Box so the connecting pill can float over the list instead of
        // taking a row in the Column - in the flow it pushed every chat down
        // whenever the socket reconnected.
        // Extra room above the first chat while the connecting pill is up, so
        // the floating pill never sits on top of a row. animateDpAsState so it
        // eases in and out rather than snapping on every reconnect.
        val connecting = connectionState == WebSocketManager.ConnectionState.CONNECTING ||
            connectionState == WebSocketManager.ConnectionState.RECONNECTING
        val connectingInset by animateDpAsState(
            targetValue = if (connecting) 40.dp else 0.dp,
            label = "connectingInset"
        )

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (keyTakeover) {
                KeyTakeoverBanner(onDismiss = chatViewModel::dismissKeyTakeover)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .hazeSource(state = hazeState, zIndex = 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (dark) 0.06f else 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                )
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // The connecting pill floats over this list, so the
                            // first row needs to get out from under it while it
                            // is showing. Animated, so rows ease down and back
                            // instead of jumping when the socket reconnects.
                            contentPadding = PaddingValues(
                                top = 6.dp + connectingInset,
                                bottom = 88.dp
                            )
                        ) {
                            items(listState.chats, key = { it.id }) { chat ->
                                ChatRow(
                                    chat,
                                    onClick = { onChatClick(chat.id, chat.name, chat.isGroup) },
                                    onLongClick = { menuChat = chat },
                                    onAvatarLongPress = {
                                        peekChat = chat
                                        chatViewModel.loadPreview(chat.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

            ConnectionStatusBanner(
                state = connectionState,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }
    }
    }

    peekChat?.let { chat ->
        val previewMessages by chatViewModel.previewMessages.collectAsStateWithLifecycle()
        val previewLoading by chatViewModel.previewLoading.collectAsStateWithLifecycle()
        ChatPeekDialog(
            chatName = chat.name,
            avatarUrl = chat.avatarUrl,
            messages = previewMessages,
            loading = previewLoading,
            onDismiss = {
                peekChat = null
                chatViewModel.clearPreview()
            },
            onOpenChat = {
                peekChat = null
                chatViewModel.clearPreview()
                onChatClick(chat.id, chat.name, chat.isGroup)
            }
        )
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
            onBlock = {
                menuChat = null
                pendingBlock = chat
            },
            onDelete = {
                menuChat = null
                pendingDelete = chat
            }
        )
    }

    pendingBlock?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingBlock = null },
            title = { Text(stringResource(R.string.block_user)) },
            text = {
                Text(
                    "Block ${chat.name}? ${stringResource(R.string.confirm_block_user)} " +
                        "The chat will also be removed from your list."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.blockUser(chat.id, chat.otherUserId)
                    pendingBlock = null
                }) {
                    Text(stringResource(R.string.block_user), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlock = null }) {
                    Text(stringResource(R.string.cancel))
                }
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
private fun ChatRow(
    chat: ChatListItemUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarLongPress: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val dark = MessengerExtendedColors.isDark
    val accent = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scope = rememberCoroutineScope()

    // Baseline glow for unread rows; press / click bloom on top of that.
    val baseline = if (chat.unreadCount > 0) 0.42f else 0f
    val pressAnim = remember { Animatable(baseline) }
    var opening by remember { mutableStateOf(false) }
    val pressInSpec = luxuryTween<Float>(durationMs = 280, easing = Tokens.Motion.easeOut)
    val releaseSpec = luxuryTween<Float>(durationMs = 460, easing = Tokens.Motion.easeOut)
    val openSpec = luxuryTween<Float>(durationMs = 340, easing = Tokens.Motion.easeOut)

    LaunchedEffect(pressed, opening, baseline) {
        if (opening) return@LaunchedEffect
        val target = if (pressed) 1f else baseline
        // Press-in is confident; release settles longer so the light eases out.
        pressAnim.animateTo(target, if (pressed) pressInSpec else releaseSpec)
    }

    val amount = pressAnim.value
    val cardShape = RoundedCornerShape(16.dp)
    val cardColor = accent.copy(
        alpha = lerp(
            if (chat.unreadCount > 0) (if (dark) 0.10f else 0.08f) else 0f,
            0.16f,
            amount
        )
    )
    val borderAlpha = lerp(
        if (chat.unreadCount > 0) 0.22f else 0f,
        0.40f,
        amount
    )
    val scale = lerp(1f, 0.972f, amount)
    val elevation = lerp(0f, 12f, amount).dp
    val shadowAlpha = lerp(0f, if (dark) 0.50f else 0.16f, amount)
    val catchLight = lerp(0f, 0.24f, amount)
    val barAlpha = lerp(if (chat.unreadCount > 0) 0.55f else 0f, 1f, amount)

    fun openChat() {
        if (opening) return
        opening = true
        scope.launch {
            // Bloom the card, then navigate so the press reads before the screen changes.
            pressAnim.animateTo(1f, openSpec)
            onClick()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Slight lift toward the finger so the press feels dimensional.
                translationY = lerp(0f, -1.5f, amount)
            }
    ) {
        // Soft drop shadow under elevated rows - opacity tracks the press anim.
        if (amount > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 5.dp)
                    .padding(horizontal = 3.dp)
                    .graphicsLayer { alpha = amount }
                    .background(
                        Color.Black.copy(alpha = if (dark) 0.48f else 0.14f),
                        cardShape
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 2.dp)
                    .graphicsLayer { alpha = amount * 0.85f }
                    .background(
                        Color.Black.copy(alpha = if (dark) 0.24f else 0.07f),
                        cardShape
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = amount * 0.9f }
                    .background(
                        accent.copy(alpha = if (dark) 0.14f else 0.09f),
                        RoundedCornerShape(18.dp)
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = elevation,
                    shape = cardShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha * 1.15f)
                )
                .clip(cardShape)
                .background(cardColor, cardShape)
                .then(
                    if (borderAlpha > 0.01f) Modifier.border(1.dp, accent.copy(alpha = borderAlpha), cardShape)
                    else Modifier
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { openChat() },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
        ) {
            // Top catch-light blooms with the press.
            if (catchLight > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .graphicsLayer { alpha = catchLight / 0.24f }
                        .background(Color.White.copy(alpha = catchLight), RoundedCornerShape(50))
                )
            }

            // Accent indicator bar eases in with the press amount.
            if (barAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 1.dp)
                        .width(10.dp)
                        .height(52.dp)
                        .graphicsLayer { alpha = barAlpha * 0.7f }
                        .background(accent.copy(alpha = 0.32f), RoundedCornerShape(5.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 5.dp)
                        .width(3.dp)
                        .height(44.dp)
                        .graphicsLayer {
                            alpha = barAlpha
                            scaleY = lerp(0.55f, 1f, amount)
                        }
                        .background(accent, RoundedCornerShape(1.5.dp))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .offset(y = 2.dp)
                            .background(
                                Color.Black.copy(alpha = if (dark) 0.40f else 0.12f),
                                CircleShape
                            )
                    )
                    // Holding the picture peeks at the conversation; holding
                    // anywhere else on the row still opens the action sheet.
                    Avatar(
                        name = chat.name,
                        avatarUrl = chat.avatarUrl,
                        size = 48.dp,
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAvatarLongPress()
                            }
                        )
                    )
                    if (chat.isOnline) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .background(OnlineColor.copy(alpha = 0.35f), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.background, CircleShape)
                                .padding(2.dp)
                                .background(OnlineColor, CircleShape)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        chat.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        chat.lastMessage.replace('\n', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (chat.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        color = if (chat.unreadCount > 0) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        chat.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (chat.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chat.unreadCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (chat.unreadCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .graphicsLayer { alpha = lerp(0.7f, 1f, amount) }
                                    .background(accent.copy(alpha = 0.32f), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .background(accent, CircleShape)
                                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
    onBlock: () -> Unit,
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
            if (!chat.isGroup && chat.otherUserId.isNotBlank()) {
                ChatActionItem(
                    icon = Icons.Outlined.Block,
                    label = stringResource(R.string.block_user),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onBlock
                )
            }
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
