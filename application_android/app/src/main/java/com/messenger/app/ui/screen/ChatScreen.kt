package com.messenger.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ChatBackground
import com.messenger.app.ui.components.RecordingIndicator
import com.messenger.app.ui.components.VoiceBubbleContent
import com.messenger.app.ui.theme.ChatBubbleShapeReceived
import com.messenger.app.ui.theme.ChatBubbleShapeSent
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.viewmodel.ChatMessageUi
import com.messenger.app.ui.viewmodel.ChatViewModel
import com.messenger.app.ui.viewmodel.RecordingUiState
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    chatName: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    /** Non-null only for group chats; tapping the title opens group info. */
    onOpenGroupInfo: (() -> Unit)? = null
) {
    val state by viewModel.chatState.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        // Start straight away on grant, so the button needs only one press.
        if (granted) viewModel.startRecording()
    }

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId, chatName)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(
                                // Only groups have an info screen to open.
                                if (onOpenGroupInfo != null) {
                                    Modifier.clickable(
                                        role = Role.Button,
                                        onClick = onOpenGroupInfo
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .semantics(mergeDescendants = true) {
                                if (onOpenGroupInfo != null) {
                                    contentDescription = "$chatName, open group info"
                                }
                            }
                    ) {
                        Avatar(
                            name = chatName,
                            avatarUrl = state.chatAvatarUrl,
                            size = 36.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(chatName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            MessageInputBar(
                value = messageText,
                onValueChange = { messageText = it },
                onSend = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(messageText.trim())
                        messageText = ""
                    }
                },
                recording = recording,
                onStartRecording = {
                    // Ask the first time; afterwards go straight to recording.
                    if (hasMicPermission) {
                        viewModel.startRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecording = viewModel::stopRecordingAndSend,
                onCancelRecording = viewModel::cancelRecording
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatBackground(modifier = Modifier.fillMaxSize())
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isPlaying = playback.messageId == message.id && playback.isPlaying,
                        positionMs = if (playback.messageId == message.id) playback.positionMs else 0,
                        onTogglePlay = { viewModel.toggleVoicePlayback(message) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    isPlaying: Boolean = false,
    positionMs: Int = 0,
    onTogglePlay: () -> Unit = {}
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(if (message.isMine) ChatBubbleShapeSent else ChatBubbleShapeReceived)
                .background(if (message.isMine) MessengerExtendedColors.sentBubble else MessengerExtendedColors.receivedBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.isVoice) {
                val tint = if (message.isMine) Color.White else MaterialTheme.colorScheme.primary
                VoiceBubbleContent(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = message.voiceDurationMs,
                    tint = tint,
                    trackColor = tint.copy(alpha = 0.25f),
                    onTogglePlay = onTogglePlay
                )
            } else {
                Text(
                    message.content,
                    color = if (message.isMine) Color.White else MessengerExtendedColors.receivedBubbleText,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timeFormat.format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isMine) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isMine) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (message.isRead) Icons.Filled.DoneAll else Icons.Filled.Done,
                        contentDescription = if (message.isRead) "Seen" else "Sent",
                        tint = if (message.isRead) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    recording: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recording.isRecording) {
                RecordingIndicator(
                    elapsedMs = recording.elapsedMs,
                    amplitude = recording.amplitude,
                    onCancel = onCancelRecording,
                    modifier = Modifier.weight(1f)
                )
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Type a message…") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Mic while nothing is typed, send once there is text - the two
            // actions never compete for the same tap.
            val showMic = value.isBlank()
            FilledIconButton(
                onClick = {
                    when {
                        recording.isRecording -> onStopRecording()
                        showMic -> onStartRecording()
                        else -> onSend()
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recording.isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = when {
                        recording.isRecording -> Icons.Filled.Stop
                        showMic -> Icons.Filled.Mic
                        else -> Icons.AutoMirrored.Filled.Send
                    },
                    contentDescription = when {
                        recording.isRecording -> "Stop and send voice message"
                        showMic -> "Record voice message"
                        else -> "Send"
                    },
                    tint = Color.White
                )
            }
        }
    }
}
