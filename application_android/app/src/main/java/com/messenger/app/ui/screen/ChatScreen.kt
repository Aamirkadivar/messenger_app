package com.messenger.app.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.messenger.app.data.repository.guessMimeType
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ChatBackground
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.ui.components.RecordingIndicator
import com.messenger.app.ui.components.VoiceBubbleContent
import com.messenger.app.ui.theme.ChatBubbleShapeReceived
import com.messenger.app.ui.theme.ChatBubbleShapeSent
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.viewmodel.ChatMessageUi
import com.messenger.app.ui.viewmodel.ChatViewModel
import com.messenger.app.ui.viewmodel.RecordingUiState
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    chatName: String,
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    /** From the nav route - known immediately, before any chat-list cache lookup can confirm it. */
    isGroup: Boolean = false,
    /** Non-null only for group chats; tapping the title opens group info. */
    onOpenGroupInfo: (() -> Unit)? = null,
    /** Non-null only for direct chats (see NavGraph) - group calling isn't supported yet. */
    onStartCall: ((calleeId: String, calleeName: String) -> Unit)? = null
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
    var pendingCall by remember { mutableStateOf(false) }
    val callMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && pendingCall) onStartCall?.invoke(state.otherUserId, chatName)
        pendingCall = false
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendAttachment(it) } }

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId, chatName, isGroup)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    val dark = MessengerExtendedColors.isDark

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, sheen = false) {
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
                actions = {
                    if (onStartCall != null) {
                        IconButton(onClick = {
                            if (hasMicPermission) {
                                onStartCall(state.otherUserId, chatName)
                            } else {
                                pendingCall = true
                                callMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call $chatName")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            }
        },
        containerColor = Color.Transparent,
        bottomBar = {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, sheen = false) {
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
                onCancelRecording = viewModel::cancelRecording,
                onPickAttachment = { attachmentPicker.launch("*/*") }
            )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AmbientGlow(
                modifier = Modifier.fillMaxSize(),
                baseColor = MaterialTheme.colorScheme.background,
                primaryGlow = MaterialTheme.colorScheme.primary,
                secondaryGlow = if (dark) Color(0xFF7A5C22) else Color(0xFF8A6A2E),
                intensity = if (dark) 0.7f else 0.4f
            )
            ChatBackground(modifier = Modifier.fillMaxSize(), baseOpacity = 0f)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    if (message.isSystem) {
                        SystemMessageRow(message.content)
                    } else {
                        MessageBubble(
                            message = message,
                            isPlaying = playback.messageId == message.id && playback.isPlaying,
                            positionMs = if (playback.messageId == message.id) playback.positionMs else 0,
                            onTogglePlay = { viewModel.toggleVoicePlayback(message) },
                            onFetchAttachment = { viewModel.fetchAttachmentFile(message) }
                        )
                    }
                }
            }
        }
    }
}

/** Centered small grey line for non-message events (e.g. a security-code-changed notice). */
@Composable
private fun SystemMessageRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(vertical = 4.dp, horizontal = 12.dp)
        )
    }
}

/** Opens [file] in an external app via a FileProvider content:// Uri. */
private fun openFileExternally(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, guessMimeType(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    isPlaying: Boolean = false,
    positionMs: Int = 0,
    onTogglePlay: () -> Unit = {},
    onFetchAttachment: suspend () -> File? = { null }
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
            } else if (message.isAttachment) {
                AttachmentContent(
                    message = message,
                    isMine = message.isMine,
                    onFetchAttachment = onFetchAttachment
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

/** Formats a byte count as e.g. "1.2 MB". */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun AttachmentContent(
    message: ChatMessageUi,
    isMine: Boolean,
    onFetchAttachment: suspend () -> File?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localFile by remember(message.id) { mutableStateOf<File?>(null) }
    var fetchFailed by remember(message.id) { mutableStateOf(false) }

    if (message.isImageAttachment) {
        LaunchedEffect(message.id) {
            localFile = onFetchAttachment()
            fetchFailed = localFile == null
        }
        val file = localFile
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(min = 120.dp, max = 240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background((if (isMine) Color.White else MaterialTheme.colorScheme.primary).copy(alpha = 0.08f))
                .then(
                    if (file != null) {
                        Modifier.clickable { openFileExternally(context, file) }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                file != null -> AsyncImage(
                    model = file,
                    contentDescription = message.attachmentName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                )
                fetchFailed -> Text(
                    "Couldn't load image",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    } else {
        val tint = if (isMine) Color.White else MaterialTheme.colorScheme.primary
        Row(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 240.dp)
                .clickable(role = Role.Button) {
                    scope.launch {
                        val file = localFile ?: onFetchAttachment().also { localFile = it }
                        if (file != null) openFileExternally(context, file) else fetchFailed = true
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    message.attachmentName.ifBlank { "Attachment" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMine) Color.White else MessengerExtendedColors.receivedBubbleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (fetchFailed) "Couldn't open file" else formatFileSize(message.attachmentSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMine) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    onCancelRecording: () -> Unit,
    onPickAttachment: () -> Unit
) {
    Surface(color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!recording.isRecording) {
                IconButton(onClick = onPickAttachment) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
