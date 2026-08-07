package com.messenger.app.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.messenger.app.ui.components.RecordingLockHint
import com.messenger.app.ui.components.VoiceBubbleContent
import com.messenger.app.ui.components.CaptureButton
import com.messenger.app.ui.components.CaptureMode
import com.messenger.app.ui.components.RoundVideoBubble
import com.messenger.app.ui.components.RoundVideoRecorderOverlay
import com.messenger.app.ui.components.RoundVideoUiState
import com.messenger.app.ui.viewmodel.CaptureUiState
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
    /** Non-null for direct chats - places a 1:1 voice/video call. */
    onStartCall: ((calleeId: String, calleeName: String, video: Boolean) -> Unit)? = null,
    /** Non-null for group chats - places a mesh group voice/video call. */
    onStartGroupCall: ((video: Boolean) -> Unit)? = null
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
    // null = no call pending; false = voice call pending; true = video call
    // pending. Which permissions the launcher asked for depends on the kind.
    var pendingCall by remember { mutableStateOf<Boolean?>(null) }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasMicPermission = granted[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
        val wantsVideo = pendingCall
        // Mic is mandatory; a denied camera still allows a video call, which
        // then simply connects with our camera dark (same as toggling it off).
        if (hasMicPermission && wantsVideo != null) {
            if (onStartGroupCall != null) {
                onStartGroupCall.invoke(wantsVideo)
            } else {
                onStartCall?.invoke(state.otherUserId, chatName, wantsVideo)
            }
        }
        pendingCall = null
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.sendAttachment(it) } }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // A video message needs mic *and* camera, so both are requested together -
    // granting one and being asked for the other mid-gesture would abort the
    // press-and-hold the user is in the middle of.
    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasMicPermission = granted[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
        hasCameraPermission = granted[Manifest.permission.CAMERA] ?: hasCameraPermission
        // Deliberately not auto-starting here: the finger that began the
        // long-press is long gone by the time the dialog is dismissed.
    }

    val selectedIds by viewModel.selectedMessageIds.collectAsStateWithLifecycle()
    val inSelection by viewModel.inSelectionMode.collectAsStateWithLifecycle()
    val allSelectedMine by viewModel.allSelectedAreMine.collectAsStateWithLifecycle()

    // Back exits selection before it leaves the chat - the same precedence
    // every other app uses for a transient modal state.
    BackHandler(enabled = inSelection) { viewModel.clearSelection() }

    val capture by viewModel.capture.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val videoStates by viewModel.videoStates.collectAsStateWithLifecycle()
    val activePlayer by viewModel.activePlayer.collectAsStateWithLifecycle()
    var fullscreenVideo by remember { mutableStateOf<ChatMessageUi?>(null) }
    var pendingBulkDelete by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(capture.videoActive) {
        // A raised keyboard leaves almost no room for the preview and fights
        // the overlay for the bottom of the screen.
        if (capture.videoActive) keyboard?.hide()
    }

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId, chatName, isGroup)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    val dark = MessengerExtendedColors.isDark

    // The recorder is a full-screen layer, so it lives in a Box *around* the
    // Scaffold rather than inside its content slot. Inside, it was clipped to
    // the body area between the app bar and the composer - and with the
    // keyboard open that strip is only a couple of hundred pixels tall, which
    // put the preview circle behind the app bar and the action buttons inside
    // the message field.
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, sheen = false) {
            if (inSelection) {
                // Takes over the header while selecting, so the count and the
                // destructive action sit where the title normally is instead
                // of floating over the conversation.
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = {
                        Text("${selectedIds.size} selected", fontWeight = FontWeight.SemiBold)
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllMessages() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { pendingBulkDelete = true }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
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
                    if (onStartCall != null || onStartGroupCall != null) {
                        fun requestCall(video: Boolean) {
                            val needed = buildList {
                                if (!hasMicPermission) add(Manifest.permission.RECORD_AUDIO)
                                if (video && !hasCameraPermission) add(Manifest.permission.CAMERA)
                            }
                            if (needed.isEmpty()) {
                                if (onStartGroupCall != null) {
                                    onStartGroupCall.invoke(video)
                                } else {
                                    onStartCall?.invoke(state.otherUserId, chatName, video)
                                }
                            } else {
                                pendingCall = video
                                callPermissionLauncher.launch(needed.toTypedArray())
                            }
                        }
                        // Groups and directs both get audio + video buttons.
                        IconButton(onClick = { requestCall(true) }) {
                            Icon(Icons.Filled.Videocam, contentDescription = "Video call $chatName")
                        }
                        IconButton(onClick = { requestCall(false) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call $chatName")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            }
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
                onPickAttachment = { attachmentPicker.launch("*/*") },
                capture = capture,
                onToggleCaptureMode = viewModel::toggleCaptureMode,
                onStartCapture = {
                    // Video needs the camera as well as the mic, so it asks
                    // for both before the overlay can show anything.
                    if (capture.mode == CaptureMode.VIDEO) {
                        if (hasMicPermission && hasCameraPermission) {
                            viewModel.startVideoRecording()
                        } else {
                            capturePermissionLauncher.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            )
                        }
                    } else if (hasMicPermission) {
                        viewModel.startRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onCaptureDrag = viewModel::onCaptureDrag,
                onArmCancel = viewModel::onArmCancel,
                onArmLock = viewModel::onArmLock,
                onCaptureRelease = { cancelled, locked ->
                    if (capture.mode == CaptureMode.VIDEO) {
                        viewModel.onCaptureRelease(cancelled, locked)
                    } else {
                        // Same precedence as video: sliding up is already a
                        // commitment to keep the take, so lock beats cancel.
                        when {
                            locked -> viewModel.lockVoiceRecording()
                            cancelled -> viewModel.cancelRecording()
                            else -> viewModel.stopRecordingAndSend()
                        }
                    }
                }
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
                            onFetchAttachment = { viewModel.fetchAttachmentFile(message) },
                            isSelected = selectedIds.contains(message.id),
                            selectionMode = inSelection,
                            onToggleSelected = { viewModel.toggleMessageSelection(message.id) },
                            videoState = videoStates[message.id],
                            // Only the bubble that owns the borrowed player
                            // gets a TextureView; the rest show their poster.
                            videoPlayer = if (videoStates[message.id]?.isPlaying == true) activePlayer else null,
                            onToggleVideo = { viewModel.toggleVideoPlayback(message) },
                            onSeekVideo = { f -> viewModel.seekVideo(message.id, f) },
                            onExpandVideo = { fullscreenVideo = message },
                            onLongPress = { viewModel.toggleMessageSelection(message.id) }
                        )
                        if (message.isVideoNote) {
                            LaunchedEffect(message.id) { viewModel.ensureVideoThumbnail(message) }
                        }
                    }
                }
            }
        }
    }

    // Floats above the record button. It has to live out here rather than in
    // the composer Row: inside, it would be clipped to the bar's own height
    // and could only ever appear beside the button, not above it.
    if (recording.isRecording) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                // Clears the 64dp composer, then centres the 44dp capsule over
                // the 48dp button sitting 8dp in from the edge.
                .padding(end = 10.dp, bottom = 72.dp)
        ) {
            RecordingLockHint(
                dragY = capture.dragY,
                armed = capture.willLock,
                locked = capture.isLocked
            )
        }
    }

    // Drawn last, so it sits above the app bar, the message list and the
    // composer rather than behind them.
    if (pendingBulkDelete) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = false },
            title = { Text(if (count == 1) "Delete message?" else "Delete $count messages?") },
            text = {
                Text(
                    if (allSelectedMine) {
                        "Delete for everyone removes them from both sides. " +
                            "Delete for me leaves the other person's copy untouched."
                    } else {
                        "This removes them from your device only - the other " +
                            "person keeps their copy."
                    }
                )
            },
            confirmButton = {
                Row {
                    // Only offered when every selected message is ours; the
                    // server refuses the rest anyway.
                    if (allSelectedMine) {
                        TextButton(onClick = {
                            viewModel.deleteSelectedMessages(forEveryone = true)
                            pendingBulkDelete = false
                        }) {
                            Text("For everyone", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = {
                        viewModel.deleteSelectedMessages(forEveryone = false)
                        pendingBulkDelete = false
                    }) {
                        Text("For me", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (capture.videoActive) {
        RoundVideoRecorderOverlay(
            state = recorderState,
            dragX = capture.dragX,
            dragY = capture.dragY,
            willCancel = capture.willCancel,
            willLock = capture.willLock,
            isLocked = capture.isLocked,
            onBindPreview = { view -> viewModel.bindVideoPreview(lifecycleOwner, view.surfaceProvider) },
            onFlip = viewModel::flipCamera,
            onStop = viewModel::stopVideoRecordingAndSend,
            onCancel = viewModel::cancelVideoRecording,
            onTogglePause = viewModel::toggleVideoPause
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessageUi,
    isPlaying: Boolean = false,
    positionMs: Int = 0,
    onTogglePlay: () -> Unit = {},
    onFetchAttachment: suspend () -> File? = { null },
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onToggleSelected: () -> Unit = {},
    videoState: RoundVideoUiState? = null,
    videoPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    onToggleVideo: () -> Unit = {},
    onSeekVideo: (Float) -> Unit = {},
    onExpandVideo: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Long-press starts selecting; once selecting, a plain tap toggles. Keyed
    // on selectionMode so the tap handler is rebuilt when the mode flips.
    // Long-press starts selecting; once selecting, a plain tap toggles.
    //
    // combinedClickable, NOT pointerInput/detectTapGestures: the latter
    // consumes the pointer-down, so the enclosing LazyColumn never gets to
    // claim it for a drag and the message list stops scrolling wherever a
    // bubble sits under your finger - which is essentially everywhere.
    //
    // indication is null because a ripple spanning the whole row reads as a
    // mis-hit; the selection tint is the feedback that matters.
    val interactionSource = remember { MutableInteractionSource() }
    val longPressModifier = Modifier.combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = { if (selectionMode) onToggleSelected() },
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (selectionMode) onToggleSelected() else onLongPress()
        }
    )

    // A band behind the whole row. A bubble-only tint would not read on a
    // round video (a bare circle) or on a translucent received bubble.
    val selectionTint = if (isSelected) {
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    } else {
        Modifier
    }

    // A round video is a bare circle, not a chat bubble - giving it the usual
    // rounded-rectangle background would box the circle in a square.
    if (message.isVideoNote) {
        Row(
            modifier = Modifier.fillMaxWidth().then(selectionTint).then(longPressModifier),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
        ) {
            // The bubble owns its own tap handling, and a child is offered the
            // pointer before its parent - so while selecting, its play/pause
            // would swallow the tap and the circle could never be selected.
            // Routing its callbacks through selection here keeps one rule:
            // selecting beats playing.
            RoundVideoBubble(
                state = videoState ?: RoundVideoUiState(durationMs = message.videoDurationMs),
                player = videoPlayer,
                onTogglePlay = { if (selectionMode) onToggleSelected() else onToggleVideo() },
                onSeek = { fraction -> if (!selectionMode) onSeekVideo(fraction) },
                onExpand = { if (selectionMode) onToggleSelected() else onExpandVideo() }
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().then(selectionTint).then(longPressModifier),
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
    onPickAttachment: () -> Unit,
    capture: CaptureUiState,
    onToggleCaptureMode: () -> Unit,
    onStartCapture: () -> Unit,
    onCaptureDrag: (Float, Float) -> Unit,
    onArmCancel: (Boolean) -> Unit,
    onArmLock: (Boolean) -> Unit,
    onCaptureRelease: (cancelled: Boolean, locked: Boolean) -> Unit
) {
    Surface(color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Both slots below are ALWAYS composed, and only their contents
            // swap. Removing the attachment button while recording (which is
            // what this used to do) changed the Row's child list, so Compose
            // matched the remaining siblings to different slots and rebuilt
            // the CaptureButton - tearing down its pointerInput mid-gesture
            // and cancelling the press. That silently broke slide-to-lock for
            // voice notes, while video kept working because its recording
            // state never touched this branch.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (!recording.isRecording) {
                    IconButton(onClick = onPickAttachment) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "Attach file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (recording.isRecording) {
                    RecordingIndicator(
                        elapsedMs = recording.elapsedMs,
                        amplitude = recording.amplitude,
                        onCancel = onCancelRecording,
                        isLocked = capture.isLocked
                    )
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text("Type a message…") },
                        shape = RoundedCornerShape(24.dp),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // One control for send / voice / video: tap flips the mode when
            // there is nothing typed, hold records, and the drag while holding
            // arms cancel and lock.
            CaptureButton(
                mode = capture.mode,
                isRecording = recording.isRecording || capture.videoActive,
                hasText = value.isNotBlank(),
                // Only voice locks here - a locked video take is driven from
                // the full-screen overlay's own send button instead.
                isLocked = capture.isLocked && !capture.videoActive,
                onToggleMode = onToggleCaptureMode,
                onSend = {
                    if (capture.isLocked && recording.isRecording) onStopRecording() else onSend()
                },
                onStartRecording = onStartCapture,
                onDrag = onCaptureDrag,
                onArmCancel = onArmCancel,
                onArmLock = onArmLock,
                onRelease = onCaptureRelease
            )
        }
    }
}
