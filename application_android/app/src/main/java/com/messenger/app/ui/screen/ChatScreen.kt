package com.messenger.app.ui.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.pairing.PairingQr
import com.messenger.app.ui.pairing.PairingQrScanDialog
import com.messenger.app.data.encryption.E2ECrypto
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.messenger.app.data.repository.guessMimeType
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ChatBackground
import com.messenger.app.ui.components.ConnectionStatusBanner
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.ui.components.MessageAction
import com.messenger.app.ui.components.MessageActionBar
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
import com.messenger.app.ui.viewmodel.ChatListItemUi
import com.messenger.app.ui.viewmodel.ChatMessageUi
import com.messenger.app.ui.viewmodel.ChatViewModel
import com.messenger.app.ui.viewmodel.DeliveryStatus
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.ui.viewmodel.RecordingUiState
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Hard ceiling on how far a text bubble may grow.
 *
 * Past roughly this height a bubble stops taking touch input over its lower
 * part - the collapse link, long-press selection and list scrolling all stop
 * working there - so an unbounded message has to be read full-screen instead.
 */
private const val EXPANDED_MESSAGE_MAX_LINES = 40

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
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val safetyNumber by viewModel.safetyNumber.collectAsStateWithLifecycle()
    val pendingPeerKeyChange by viewModel.pendingPeerKeyChange.collectAsStateWithLifecycle()
    val safetyVerified by viewModel.safetyVerified.collectAsStateWithLifecycle()
    var showSafetyNumber by remember { mutableStateOf(false) }
    var showSafetyScan by remember { mutableStateOf(false) }
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
    val pendingReply by viewModel.pendingReply.collectAsStateWithLifecycle()
    var scrollToMessageId by remember { mutableStateOf<String?>(null) }
    val inSelection by viewModel.inSelectionMode.collectAsStateWithLifecycle()
    val allSelectedMine by viewModel.allSelectedAreMine.collectAsStateWithLifecycle()
    val chatList by viewModel.chatListState.collectAsStateWithLifecycle()

    // Back exits selection before it leaves the chat - the same precedence
    // every other app uses for a transient modal state.
    BackHandler(enabled = inSelection) { viewModel.clearSelection() }

    // Height the floating action bar occupies over the list while selecting.
    val actionBarInset by animateDpAsState(
        targetValue = if (inSelection) 88.dp else 0.dp,
        label = "actionBarInset"
    )

    val capture by viewModel.capture.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val videoStates by viewModel.videoStates.collectAsStateWithLifecycle()
    val activePlayer by viewModel.activePlayer.collectAsStateWithLifecycle()
    var fullscreenVideo by remember { mutableStateOf<ChatMessageUi?>(null) }
    var pendingBulkDelete by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(capture.videoActive) {
        // A raised keyboard leaves almost no room for the preview and fights
        // the overlay for the bottom of the screen.
        if (capture.videoActive) keyboard?.hide()
    }

    LaunchedEffect(showForwardPicker) {
        if (showForwardPicker && chatList.chats.isEmpty()) {
            viewModel.loadChats()
        }
    }

    LaunchedEffect(chatId) {
        viewModel.openChat(chatId, chatName, isGroup)
    }

    // Snap once history is ready: first unread if any, otherwise the latest
    // message. Instant positionView - animating through a long history feels
    // like a harsh scroll.
    var didInitialSnap by remember(chatId) { mutableStateOf(false) }
    var trackedLastMessageId by remember(chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId, state.historySettled, state.messages.size, state.firstUnreadMessageId) {
        if (didInitialSnap || !state.historySettled || state.messages.isEmpty()) return@LaunchedEffect
        val unreadIdx = state.firstUnreadMessageId
            ?.let { id -> state.messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        val target = unreadIdx ?: state.messages.lastIndex
        // Yield a frame so LazyColumn has measured items before we jump.
        kotlinx.coroutines.yield()
        listState.scrollToItem(target)
        trackedLastMessageId = state.messages.lastOrNull()?.id
        didInitialSnap = true
    }

    // Live messages while already open: only soft-scroll if the user is near
    // the bottom, otherwise leave them reading history in peace.
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val lastId = state.messages.lastOrNull()?.id ?: return@LaunchedEffect
        if (!didInitialSnap) return@LaunchedEffect
        if (lastId == trackedLastMessageId) return@LaunchedEffect
        trackedLastMessageId = lastId
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= state.messages.size - 3) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(scrollToMessageId) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val idx = state.messages.indexOfFirst { it.id == target }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
            scrollToMessageId = null
        }
    }

    val dark = MessengerExtendedColors.isDark
    val hazeState = remember { HazeState() }

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
                // Telegram-style: header only shows count + exit / select-all.
                // Destructive and message actions live in the floating bottom bar.
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = {
                        Text(
                            if (selectedIds.size == 1) "1 selected"
                            else "${selectedIds.size} selected",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllMessages() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
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
                    if (!isGroup) {
                        IconButton(onClick = {
                            viewModel.reloadSafetyNumber()
                            showSafetyNumber = true
                        }) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = if (safetyVerified) "Encryption verified" else "Encryption",
                                tint = if (safetyVerified) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current
                            )
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
            // Slides down out of view when selection starts, and back up when
            // it ends, instead of vanishing between frames.
            //
            // Nothing is left behind while hidden: the Scaffold reserves height
            // for whatever is in this slot, and the selection action bar is
            // drawn as an overlay on the message list precisely so messages can
            // pass behind it - leaving a placeholder here would push the list
            // back up and give the glass nothing to blur again.
            // expand/shrinkVertically is what stops the action bar jumping.
            // With only a slide, the Scaffold keeps reserving the composer's
            // full height for the whole animation and then drops it to zero in
            // one frame - the overlaid action bar is anchored to the content's
            // bottom, so it lurched down at the end. Animating the height means
            // the content grows at the same rate the composer leaves, and the
            // bar glides with it.
            AnimatedVisibility(
                visible = !inSelection,
                enter = slideInVertically(
                    animationSpec = tween(Tokens.Motion.SLOW_MS, easing = Tokens.Motion.easeOut)
                ) { it } + expandVertically(
                    animationSpec = tween(Tokens.Motion.SLOW_MS, easing = Tokens.Motion.easeOut)
                ) + fadeIn(),
                exit = slideOutVertically(
                    animationSpec = tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)
                ) { it } + shrinkVertically(
                    animationSpec = tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)
                ) + fadeOut()
            ) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.ui.graphics.RectangleShape, sheen = false) {
            Column(modifier = Modifier.fillMaxWidth()) {
                pendingReply?.let { reply ->
                    ReplyComposerBar(
                        message = reply,
                        onDismiss = { viewModel.clearReply() }
                    )
                }
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
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AmbientGlow(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0f),
                baseColor = MaterialTheme.colorScheme.background,
                primaryGlow = MaterialTheme.colorScheme.primary,
                secondaryGlow = if (dark) Color(0xFF7A5C22) else Color(0xFF8A6A2E),
                intensity = if (dark) 0.7f else 0.4f
            )
            ChatBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0.5f),
                baseOpacity = 0f
            )
            Column(modifier = Modifier.fillMaxSize()) {
                if (pendingPeerKeyChange && !isGroup) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Security code changed. Messages still encrypt to the previous key until you accept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showSafetyNumber = true }) {
                                    Text("Verify")
                                }
                                TextButton(onClick = { viewModel.acceptPeerKeyChange() }) {
                                    Text("Accept new code")
                                }
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .hazeSource(state = hazeState, zIndex = 1f),
                    // Extra room at the bottom while the floating action bar
                    // is up, so the last messages can still be scrolled clear
                    // of it. Animated, so the list eases rather than jumping
                    // as selection starts and ends.
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 8.dp + actionBarInset
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                items(state.messages, key = { it.id }) { message ->
                    if (message.id == state.firstUnreadMessageId && state.openUnreadCount > 0) {
                        UnreadMessagesDivider(count = state.openUnreadCount)
                    }
                    if (message.isSystem) {
                        SystemMessageRow(message.content)
                    } else {
                        MessageBubble(
                            message = message,
                            replyParent = state.messages.firstOrNull { it.id == message.replyToId },
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
                            onLongPress = { viewModel.toggleMessageSelection(message.id) },
                            onReplyQuoteClick = { id -> scrollToMessageId = id }
                        )
                        if (message.isVideoNote) {
                            LaunchedEffect(message.id) { viewModel.ensureVideoThumbnail(message) }
                        }
                    }
                }
                }
            }

            // Overlaid rather than placed in the Column above: in the flow it
            // took a full-width row, pushing the whole conversation down each
            // time it appeared and leaving a band around the pill. Floating it
            // means everything around the pill is the chat itself.
            ConnectionStatusBanner(
                state = connectionState,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

        }
    }

    // A permanent backdrop for the system navigation bar strip.
    //
    // That strip used to be coloured by the composer, which extends behind it.
    // Sliding the composer away therefore left it uncovered for the length of
    // the animation, so the phone's button bar flashed transparent and then
    // filled back in. Painting it here - always, never animated - stops the
    // strip's colour depending on whatever is sliding above it.
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(MaterialTheme.colorScheme.background)
    )

    // Keep composed while exiting so the 100→0 fade / blur collapse can play.
    // Anchored to the SCREEN, not Scaffold content — otherwise the bar slides
    // with the composer as selection starts and ends.
    run {
        val selectedText = state.messages
            .filter { it.id in selectedIds && it.content.isNotBlank() && !it.isSystem }
            .joinToString("\n\n") { it.content }
        val singleSelected = selectedIds.size == 1
        MessageActionBar(
            visible = inSelection,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
            actions = listOf(
                MessageAction(
                    id = "reply",
                    label = "Reply",
                    icon = Icons.AutoMirrored.Filled.Reply,
                    enabled = singleSelected,
                    onClick = {
                        selectedIds.firstOrNull()?.let { viewModel.beginReply(it) }
                    }
                ),
                MessageAction(
                    id = "copy",
                    label = "Copy",
                    icon = Icons.Outlined.ContentCopy,
                    enabled = selectedText.isNotBlank(),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", selectedText))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        viewModel.clearSelection()
                    }
                ),
                MessageAction(
                    id = "forward",
                    label = "Forward",
                    icon = Icons.AutoMirrored.Filled.Forward,
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { showForwardPicker = true }
                ),
                MessageAction(
                    id = "delete",
                    label = "Delete",
                    icon = Icons.Outlined.Delete,
                    enabled = selectedIds.isNotEmpty(),
                    destructive = true,
                    onClick = { pendingBulkDelete = true }
                )
            )
        )
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

    if (showSafetyNumber && !isGroup) {
        AlertDialog(
            onDismissRequest = { showSafetyNumber = false },
            title = { Text("Encryption") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Compare this code with $chatName on their device, or scan their QR. If it matches, no one in the middle has swapped keys."
                    )
                    if (safetyVerified) {
                        Text("Verified on this device.", fontWeight = FontWeight.Medium)
                    }
                    val qr = remember(safetyNumber) {
                        safetyNumber?.let { E2ECrypto.safetyNumberQrPayload(it) }?.let { PairingQr.toImageBitmap(it, 320) }
                    }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Security code QR",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    }
                    Text(
                        text = safetyNumber ?: "Keys aren’t ready yet. Open this chat once while online.",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = safetyNumber
                    if (!code.isNullOrBlank()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("security code", code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    showSafetyNumber = false
                }) { Text("Copy") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showSafetyScan = true }) { Text("Scan") }
                    TextButton(onClick = { showSafetyNumber = false }) { Text("Close") }
                }
            }
        )
    }

    if (showSafetyScan) {
        PairingQrScanDialog(
            title = "Scan security code",
            onDismiss = { showSafetyScan = false },
            onCode = { raw ->
                viewModel.onSafetyNumberScanned(raw) { ok ->
                    showSafetyScan = false
                    Toast.makeText(
                        context,
                        if (ok) "Security codes match" else "Codes do not match",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
    if (showForwardPicker) {
        ForwardChatPickerSheet(
            chats = chatList.chats.filter { it.id != chatId },
            isLoading = chatList.isLoading,
            onDismiss = { showForwardPicker = false },
            onPick = { target ->
                showForwardPicker = false
                viewModel.forwardSelectedTo(
                    target.id,
                    if (target.isGroup) "group" else "direct"
                )
            }
        )
    }

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

/** Thin accent divider marking where unread messages begin for this open. */
@Composable
private fun UnreadMessagesDivider(count: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(accent.copy(alpha = 0.35f))
        )
        Text(
            text = if (count <= 1) "Unread message" else "$count unread messages",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(accent.copy(alpha = 0.35f))
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
    replyParent: ChatMessageUi? = null,
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
    onLongPress: () -> Unit = {},
    onReplyQuoteClick: (String) -> Unit = {}
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
            Column(
                horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
            ) {
                if (message.replyToId.isNotBlank()) {
                    ReplyQuote(
                        parent = replyParent,
                        isMine = message.isMine,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .then(longPressModifier)
                            .clickable(enabled = replyParent != null) {
                                replyParent?.let { onReplyQuoteClick(it.id) }
                            }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (message.isForwarded) {
                    ForwardedLabel(
                        name = message.forwardedFromName,
                        isMine = message.isMine,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
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
        }
        return
    }

    // Long-press is applied to body pieces, not the whole row — otherwise it
    // sits above Show more/less and eats the collapse tap.
    Row(
        modifier = Modifier.fillMaxWidth().then(selectionTint),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(if (message.isMine) ChatBubbleShapeSent else ChatBubbleShapeReceived)
                .background(if (message.isMine) MessengerExtendedColors.sentBubble else MessengerExtendedColors.receivedBubble)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.isForwarded) {
                ForwardedLabel(
                    name = message.forwardedFromName,
                    isMine = message.isMine,
                    modifier = longPressModifier
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (message.replyToId.isNotBlank()) {
                ReplyQuote(
                    parent = replyParent,
                    isMine = message.isMine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressModifier)
                        .clickable(enabled = replyParent != null) {
                            replyParent?.let { onReplyQuoteClick(it.id) }
                        }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (message.isVoice) {
                val tint = if (message.isMine) Color.White else MaterialTheme.colorScheme.primary
                Column(modifier = longPressModifier) {
                    VoiceBubbleContent(
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = message.voiceDurationMs,
                        tint = tint,
                        trackColor = tint.copy(alpha = 0.25f),
                        onTogglePlay = onTogglePlay
                    )
                }
            } else if (message.isAttachment) {
                Column(modifier = longPressModifier) {
                    AttachmentContent(
                        message = message,
                        isMine = message.isMine,
                        onFetchAttachment = onFetchAttachment
                    )
                }
            } else {
                CollapsibleMessageText(
                    text = message.content,
                    messageId = message.id,
                    color = if (message.isMine) Color.White else MessengerExtendedColors.receivedBubbleText,
                    linkColor = if (message.isMine) {
                        Color.White.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    bodyModifier = longPressModifier
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = longPressModifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    timeFormat.format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isMine) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isMine) {
                    Spacer(modifier = Modifier.width(4.dp))
                    when {
                        message.deliveryStatus == DeliveryStatus.PENDING -> Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = "Sending",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                        message.isRead -> Icon(
                            imageVector = Icons.Filled.DoneAll,
                            contentDescription = "Seen",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = "Sent",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsibleMessageText(
    text: String,
    messageId: String,
    color: Color,
    linkColor: Color,
    bodyModifier: Modifier = Modifier,
    collapsedMaxLines: Int = 8
) {
    // Saveable, so scrolling the bubble out of the LazyColumn (or a config
    // change) does not silently drop the message back to collapsed.
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var showFullMessage by rememberSaveable(messageId) { mutableStateOf(false) }
    var overflowsCollapsed by remember(messageId, text) { mutableStateOf(false) }
    var overflowsExpanded by remember(messageId, text) { mutableStateOf(false) }

    // Restored expanded state implies the message was long - the link is the
    // only way to reach it.
    val showToggle = overflowsCollapsed || expanded

    val bringIntoView = remember { BringIntoViewRequester() }
    var collapseRequests by remember(messageId) { mutableIntStateOf(0) }
    LaunchedEffect(collapseRequests) {
        if (collapseRequests > 0) bringIntoView.bringIntoView()
    }

    Column(modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            // Never Int.MAX_VALUE. A bubble grown to the full height of a
            // multi-thousand-line message stops responding to touch past the
            // first stretch of it: long-press, the toggle and even list
            // scrolling die on the dead part. Capping expansion keeps the
            // bubble a size the UI can handle; the rest is read full-screen.
            maxLines = if (expanded) EXPANDED_MESSAGE_MAX_LINES else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = bodyModifier.fillMaxWidth(),
            onTextLayout = { result ->
                if (expanded) {
                    overflowsExpanded = result.hasVisualOverflow
                } else {
                    overflowsCollapsed =
                        result.hasVisualOverflow || result.lineCount >= collapsedMaxLines
                }
            }
        )
        if (showToggle) {
            // Extra bottom room: the timestamp row below carries its own click
            // handler and touch targets are padded out to 48dp, so a tight gap
            // lets the timestamp sit over the link and swallow the tap.
            Row(
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MessageTextLink(
                    text = if (expanded) "Show less" else "Show more",
                    color = linkColor
                ) {
                    val collapsing = expanded
                    expanded = !expanded
                    // A tall bubble leaves the list scrolled far past itself;
                    // collapsing then lands the viewport in whatever follows
                    // and reads as "nothing happened".
                    if (collapsing) collapseRequests++
                }
                // Only when even the capped expansion cannot show all of it.
                if (expanded && overflowsExpanded) {
                    MessageTextLink(text = "Read all", color = linkColor) {
                        showFullMessage = true
                    }
                }
            }
        }
    }

    if (showFullMessage) {
        FullMessageDialog(text = text, onDismiss = { showFullMessage = false })
    }
}

@Composable
private fun MessageTextLink(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        // clickable before padding, so the padding widens the hit area instead
        // of sitting outside it - one small line of text is easy to miss.
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 2.dp, horizontal = 4.dp)
    )
}

/**
 * Full-screen reader for messages too long to sit in a bubble.
 *
 * The body is split into blocks fed through a LazyColumn rather than one
 * enormous Text, for the same reason the bubble is capped: a single text node
 * that tall stops behaving.
 */
@Composable
private fun FullMessageDialog(text: String, onDismiss: () -> Unit) {
    val blocks = remember(text) {
        text.split("\n").chunked(20).map { it.joinToString("\n") }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(blocks.size) { index ->
                        Text(
                            text = blocks[index],
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}


private fun replyPreviewText(message: ChatMessageUi): String = when {
    message.content.isNotBlank() -> message.content.replace("\n", " ").take(120)
    message.isVoice -> "Voice message"
    message.isVideoNote -> "Video message"
    message.isImageAttachment -> "Photo"
    message.isAttachment -> message.attachmentName.ifBlank { "File" }
    else -> "Message"
}

@Composable
private fun ReplyComposerBar(
    message: ChatMessageUi,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.senderName.ifBlank { if (message.isMine) "You" else "Message" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = replyPreviewText(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Cancel reply")
        }
    }
}

@Composable
private fun ReplyQuote(
    parent: ChatMessageUi?,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val bar = if (isMine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary
    val title = if (parent == null) {
        "Original message unavailable"
    } else {
        parent.senderName.ifBlank { if (parent.isMine) "You" else "Message" }
    }
    val body = if (parent == null) "" else replyPreviewText(parent)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isMine) Color.White.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(bar)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isMine) Color.White else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMine) Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ForwardedLabel(
    name: String,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val label = if (name.isBlank()) "Forwarded" else "Forwarded from $name"
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontStyle = FontStyle.Italic,
        color = if (isMine) {
            Color.White.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardChatPickerSheet(
    chats: List<ChatListItemUi>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPick: (ChatListItemUi) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                "Forward to…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            when {
                chats.isEmpty() && isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                chats.isEmpty() -> {
                    Text(
                        "No other chats yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
                else -> {
                    chats.forEach { chat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(chat) }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = chat.name, avatarUrl = chat.avatarUrl, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    chat.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
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
                    }
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
