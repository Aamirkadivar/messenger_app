import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15
import QtQuick.Dialogs
import Messenger 1.0

Item {
    id: chatViewRoot

    // The Window attached type only attaches to Item-derived elements, so it
    // can't be referenced directly from inside a Behavior - resolve it once
    // here instead and have the Behaviors below read this plain property.
    property bool instantThemeActive: Window.window ? Window.window.instantTheme : false

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#050403"
    property color surfaceColor: "#0C0A08"
    property color textColor: "#F0EAD6"
    property color textSecondary: "#A39A8A"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#C9A961"
    // Deep bronze like Android SentBubbleDark - brighter champagne is the
    // chrome accent, not the bubble fill (white text needs the darker gold).
    property color myMessageBg: "#6B511C"
    property color theirMessageBg: "#12100C"
    property color onlineColor: "#4CAF50"
    property string currentChatId: ""
    property string currentChatName: ""
    property string currentChatType: "direct"
    // Brief accent flash target after clicking a reply quote.
    property string highlightMessageId: ""
    property int highlightNonce: 0
    Timer {
        id: highlightClearTimer
        interval: 1400
        onTriggered: {
            chatViewRoot.highlightMessageId = ""
            chatViewRoot.highlightNonce = 0
        }
    }
    function flashMessage(messageId) {
        if (!messageId || messageId.length === 0) return
        chatViewRoot.highlightMessageId = messageId
        chatViewRoot.highlightNonce += 1
        highlightClearTimer.restart()
    }
    property string currentChatAvatarUrl: ""
    property string otherUserId: ""
    property bool isOnline: false
    readonly property bool isGroupChat: currentChatType === "group"
    property bool peerKeyPending: false
    property bool safetyVerified: false
    property bool typingIndicator: false
    property string typingUser: ""
    // Telegram-style bar above the first unread incoming message for this open.
    // Captured before markAsRead; cleared when switching chats.
    property string firstUnreadMessageId: ""
    property int openUnreadCount: 0

    // Signals
    signal sendMessage(string text)
    signal backClicked()
    signal openChatInfo(string chatId)
    signal createGroupClicked()

    property bool typingVisible: false
    property bool isLoadingMore: false
    // Set when the user taps audio/video-call on a group; cleared when group info
    // arrives and startGroupCall is invoked (or on mismatch / too many members).
    property string pendingGroupCallChatId: ""
    property string pendingGroupCallName: ""
    property bool pendingGroupCallVideo: false

    onCurrentChatIdChanged: {
        // Deliberately not leaving the previous chat's room here: ChatList.qml
        // joins every known chat's room up front precisely so notifications and
        // live badge/preview updates keep arriving for chats that aren't the
        // one currently open. Leaving on switch would undo that the moment you
        // navigate away from a chat.
        messagesModel.clear()
        firstUnreadMessageId = ""
        openUnreadCount = 0
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
            websocketService.joinChat(currentChatId)
            peerKeyPending = !isGroupChat && chatService.hasPendingPeerKeyChange(currentChatId)
            safetyVerified = !isGroupChat && chatService.hasVerifiedSafetyNumber(currentChatId)
        } else {
            peerKeyPending = false
            safetyVerified = false
        }
    }

    function openSafetyDialog() {
        safetyNumberText.text = chatService.safetyNumberForChat(chatViewRoot.currentChatId)
        safetyQrImage.source = ""
        safetyQrImage.source = chatService.safetyNumberQrUrl(chatViewRoot.currentChatId)
        chatViewRoot.safetyVerified = chatService.hasVerifiedSafetyNumber(chatViewRoot.currentChatId)
        safetyScanStatus.text = ""
        safetyDialog.open()
    }

    Connections {
        target: groupService
        function onGroupInfoFetched(group) {
            if (!chatViewRoot.pendingGroupCallChatId.length) return
            if (group.id !== chatViewRoot.pendingGroupCallChatId) return
            var chatId = chatViewRoot.pendingGroupCallChatId
            var groupName = group.name || chatViewRoot.pendingGroupCallName
            var video = chatViewRoot.pendingGroupCallVideo
            chatViewRoot.pendingGroupCallChatId = ""
            chatViewRoot.pendingGroupCallName = ""
            chatViewRoot.pendingGroupCallVideo = false

            var ids = []
            var names = []
            var members = group.members || []
            for (var i = 0; i < members.length; i++) {
                if (members[i].id === authService.currentUserId) continue
                ids.push(members[i].id)
                names.push(members[i].bestName || members[i].username || members[i].id)
            }
            // Full mesh caps at 4 including self.
            if (ids.length + 1 > 4) {
                console.warn("[ChatView] group call rejected: too many members (" + (ids.length + 1) + ")")
                return
            }
            if (ids.length < 1) {
                console.warn("[ChatView] group call rejected: no other members")
                return
            }
            callService.startGroupCall(chatId, groupName, ids, names, video)
        }
    }

    Connections {
        target: chatService

        function onMessagesFetched(chatId, messages) {
            if (chatId !== chatViewRoot.currentChatId) return
            messagesModel.clear()
            // Read this off the fetched data *before* markAsRead below flips it -
            // it's the last point where we can tell which messages were actually
            // still unread when the chat was opened.
            var firstUnreadIndex = -1
            var unreadCount = 0
            var firstUnreadId = ""
            for (var i = 0; i < messages.length; i++) {
                var m = messages[i]
                var isMine = m.senderId === authService.currentUserId
                var isRead = isMine && m.readAt && m.readAt.length > 0
                chatViewRoot.addMessage(m.senderId, m.senderName, m.content, chatViewRoot.formatTime(m.createdAt),
                                         isMine, isRead, m.fileUrl, m.durationMs, m.voiceEncrypted, m.id,
                                         m.fileType, m.fileName, m.fileSize, m.thumbnailUrl,
                                         m.rawContent, m.encrypted, m.keyVersion,
                                         m.encryptionVersion || 1,
                                         m.isForwarded === true, m.forwardedFromName || "", m.replyToId || "",
                                         m.senderDeviceId || "")
                if (!isMine && (!m.readAt || m.readAt.length === 0)) {
                    unreadCount++
                    if (firstUnreadIndex === -1) {
                        firstUnreadIndex = i
                        firstUnreadId = m.id || ""
                    }
                }
            }
            chatViewRoot.firstUnreadMessageId = firstUnreadId
            chatViewRoot.openUnreadCount = unreadCount
            // A security-code change detected while this chat wasn't open
            // still needs to surface - append it at the end of history now
            // that it's being opened.
            var pendingNotice = chatService.takePendingSecurityNotice(chatId)
            if (pendingNotice.length > 0) {
                chatViewRoot.addSystemMessage("🔒 Your security code with " + pendingNotice + " changed.")
            }
            chatViewRoot.isLoadingMore = false
            chatViewRoot.scrollToUnreadOrEnd(firstUnreadIndex)
            chatService.markAsRead(chatId)
            // Re-show any text still waiting to send (fetch clears the model).
            chatViewRoot.rehydratePendingOutgoing()
            chatViewRoot.flushOutgoingText()
        }

        function onMessageError(error) {
            console.log("[ChatView] Error:", error)
            chatViewRoot.isLoadingMore = false
            // Leave sendStatus as pending so reconnect / flushOutgoingText can retry.
            chatViewRoot.resetOutgoingInFlight()
        }

        function onMessageSent(chatId, message) {
            chatViewRoot.markOutgoingSent(chatId, message)
        }

        // Voice notes aren't shown optimistically like text - the bubble
        // needs the server-assigned message id to correlate with playback
        // prep (preparePlayableVoice / voiceReadyForPlayback), and the local
        // plaintext temp file is already gone by the time this fires (it's
        // deleted right after being read for upload).
        function onVoiceMessageSent(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.addMessage(authService.currentUserId, "Me", "", chatViewRoot.formatTime(message.createdAt),
                                     true, false, message.fileUrl, message.durationMs,
                                     message.encrypted === true, message.id, "audio", "", 0, "",
                                     "", message.encrypted === true, message.keyVersion || 0,
                                     message.encryptionVersion || 1,
                                     message.isForwarded === true, message.forwardedFromName || "", message.replyToId || "")
        }

        function onVoiceUploadError(error) {
            console.log("[ChatView] Voice upload error:", error)
        }

        // Shown once the server has assigned a real message id, which the
        // bubble needs to correlate its download/thumbnail callbacks.
        function onVideoNoteMessageSent(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.addMessage(authService.currentUserId, "Me", "", chatViewRoot.formatTime(message.createdAt),
                                     true, false, message.fileUrl, message.durationMs,
                                     message.encrypted === true, message.id, "video_note", "", 0,
                                     message.thumbnailUrl || "",
                                     "", message.encrypted === true, message.keyVersion || 0,
                                     message.encryptionVersion || 1,
                                     message.isForwarded === true, message.forwardedFromName || "", message.replyToId || "")
            scrollAfterSend.restart()
        }

        // Same reasoning as onVoiceMessageSent: shown once the server has
        // assigned a real message id, needed to correlate with
        // prepareAttachment/attachmentReady.
        function onAttachmentMessageSent(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.addMessage(authService.currentUserId, "Me", "", chatViewRoot.formatTime(message.createdAt),
                                     true, false, message.fileUrl, 0,
                                     message.encrypted === true, message.id,
                                     message.fileType, message.fileName, message.fileSize, "",
                                     "", message.encrypted === true, message.keyVersion || 0,
                                     message.encryptionVersion || 1,
                                     message.isForwarded === true, message.forwardedFromName || "", message.replyToId || "")
        }

        function onAttachmentUploadError(error) {
            console.log("[ChatView] Attachment upload error:", error)
        }

        function onForwardFinished(successCount, failCount) {
            console.log("[ChatView] Forward finished: ok=" + successCount + " fail=" + failCount)
        }

        function onMessageDeleted(chatId, messageId) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.removeMessageById(messageId)
        }

        function onMessageDeleteError(error) {
            console.log("[ChatView] Delete failed:", error)
        }

        function onSecurityCodeChanged(chatId, contactName) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatService.takePendingSecurityNotice(chatId)
            chatViewRoot.addSystemMessage("🔒 Your security code with " + contactName + " changed.")
            chatViewRoot.peerKeyPending = true
        }
        function onPeerKeyChangePendingChanged(chatId) {
            if (chatId === chatViewRoot.currentChatId)
                chatViewRoot.peerKeyPending = chatService.hasPendingPeerKeyChange(chatId)
        }
    }

    Connections {
        target: websocketService

        function onMessageDeletedRemotely(chatId, messageId) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.removeMessageById(messageId)
        }
    }

    // ---- Round video capture state ----
    // "voice" or "video". Tapping the composer button with nothing typed
    // flips between them, matching the Android client.
    property string captureMode: "voice"
    property bool captureActive: false
    property bool captureLocked: false
    property real captureDragX: 0
    property real captureDragY: 0
    property bool captureWillCancel: false
    property bool captureWillLock: false
    property string captureStatus: ""

    // Travel needed to arm each action. Larger than the Android thresholds
    // because a mouse drag has none of a thumb's slop to overcome.
    readonly property real cancelDistance: 150
    readonly property real lockDistance: 120

    // Smoothly brings a list row fully into view.
    //
    // positionViewAtIndex jumps instantly, so it is used only to *compute* the
    // minimal target: let the ListView work out where Contain would land, put
    // contentY straight back, then ease to it. That avoids reimplementing the
    // geometry (item offsets, header, spacing) and keeps the "scroll the least
    // amount necessary" behaviour.
    function smoothRevealIndex(idx) {
        if (idx < 0 || idx >= messagesModel.count) return
        if (messagesListView.dragging || messagesListView.flicking) return

        var from = messagesListView.contentY
        messagesListView.positionViewAtIndex(idx, ListView.Contain)
        var to = messagesListView.contentY
        if (Math.abs(to - from) < 1) return

        messagesListView.contentY = from
        revealScrollAnim.stop()
        revealScrollAnim.from = from
        revealScrollAnim.to = to
        revealScrollAnim.start()
    }

    // Same computed-target trick as smoothRevealIndex, but for the newest row.
    function smoothScrollToEnd() {
        if (messagesModel.count === 0) return
        if (messagesListView.dragging || messagesListView.flicking) return

        var from = messagesListView.contentY
        messagesListView.positionViewAtEnd()
        var to = messagesListView.contentY
        if (Math.abs(to - from) < 1) return

        messagesListView.contentY = from
        revealScrollAnim.stop()
        revealScrollAnim.from = from
        revealScrollAnim.to = to
        revealScrollAnim.start()
    }

    // ---- Middle-click autoscroll (Windows-style) ----
    // One middle click drops an anchor; after that the pointer's vertical
    // distance from it sets a continuous scroll speed, with no button held.
    // Any click, a wheel turn, or Escape cancels.
    property bool autoScrollActive: false
    property real autoScrollAnchorX: 0
    property real autoScrollAnchorY: 0
    property real autoScrollCursorY: 0

    /** Below this many pixels from the anchor, nothing moves - otherwise the
        view creeps whenever the hand is not perfectly still. */
    readonly property int autoScrollDeadZone: 14

    function startAutoScroll(x, y) {
        stopMessagesScrollAnim()
        autoScrollAnchorX = x
        autoScrollAnchorY = y
        autoScrollCursorY = y
        autoScrollActive = true
    }

    function stopAutoScroll() {
        autoScrollActive = false
    }

    Timer {
        id: autoScrollTicker
        running: chatViewRoot.autoScrollActive
        interval: 16          // ~60fps
        repeat: true
        onTriggered: {
            var dy = chatViewRoot.autoScrollCursorY - chatViewRoot.autoScrollAnchorY
            var dead = chatViewRoot.autoScrollDeadZone
            if (Math.abs(dy) <= dead)
                return
            // Speed ramps with distance past the dead zone, so a small nudge
            // creeps and a big one flies - the familiar browser feel.
            var travel = dy > 0 ? (dy - dead) : (dy + dead)
            var step = travel * 0.35
            // scrollMessagesBy treats a positive delta as "content moves down"
            // (wheel-up), so dragging below the anchor needs a negative delta.
            chatViewRoot.scrollMessagesBy(-step)
        }
    }

    // ---- Follow-the-conversation ----
    // Whether the view is parked at (or within a bubble's height of) the
    // newest message. Everything about auto-scrolling keys off this: a chat
    // that yanks you to the bottom while you are reading history is worse
    // than one that never scrolls at all.
    readonly property int followThreshold: 90
    readonly property bool messagesAtBottom: {
        if (messagesListView.count === 0)
            return true
        return messagesListView.contentY >= messagesScrollMaxY() - followThreshold
    }

    // Messages that arrived while scrolled up, shown on the jump button so
    // there is a reason to press it.
    property int missedMessageCount: 0

    onMessagesAtBottomChanged: if (messagesAtBottom) missedMessageCount = 0

    // ListView contentY is relative to originY (often non-zero with header/
    // footer). Clamping to [0, …] desyncs the scrollbar handle and makes
    // dragging it feel broken.
    function messagesScrollMinY() {
        return messagesListView.originY
    }
    function messagesScrollMaxY() {
        return messagesListView.originY
                + Math.max(0, messagesListView.contentHeight - messagesListView.height)
    }
    function clampMessagesContentY() {
        if (messagesListView.count <= 0)
            return
        var minY = messagesScrollMinY()
        var maxY = messagesScrollMaxY()
        if (messagesListView.contentY < minY)
            messagesListView.contentY = minY
        else if (messagesListView.contentY > maxY)
            messagesListView.contentY = maxY
    }
    function scrollMessagesBy(deltaY) {
        revealScrollAnim.stop()
        if (!messagesScrollBar.pressed)
            messagesListView.cancelFlick()
        var y = messagesListView.contentY - deltaY
        messagesListView.contentY = Math.max(messagesScrollMinY(),
                                            Math.min(messagesScrollMaxY(), y))
        clampMessagesContentY()
    }
    function stopMessagesScrollAnim() {
        revealScrollAnim.stop()
        // cancelFlick while the scrollbar is driving contentY fights the
        // handle and makes it flash/jump.
        if (!messagesScrollBar.pressed)
            messagesListView.cancelFlick()
    }

    // A round video bubble is created by a Loader and only reaches its real
    // height a frame or two after the row is appended, so scrolling in the
    // same tick lands short of the bottom.
    Timer {
        id: scrollAfterSend
        interval: 90
        onTriggered: chatViewRoot.smoothScrollToEnd()
    }

    NumberAnimation {
        id: revealScrollAnim
        target: messagesListView
        property: "contentY"
        duration: 320
        easing.type: Easing.OutCubic
    }

    function toggleCaptureMode() {
        captureMode = (captureMode === "voice") ? "video" : "voice"
    }

    function beginCapture() {
        captureDragX = 0
        captureDragY = 0
        captureWillCancel = false
        captureWillLock = false
        if (captureMode === "video") {
            if (!roundVideoService.startPreview()) return
            captureActive = true
            // The camera needs a moment before the encoder will take frames;
            // starting immediately yields a zero-length take.
            videoStartDelay.restart()
        } else {
            voiceService.startRecording()
        }
    }

    Timer {
        id: videoStartDelay
        interval: 220
        onTriggered: if (chatViewRoot.captureActive) roundVideoService.startRecording()
    }

    function updateCaptureDrag(dx, dy) {
        captureDragX = dx
        captureDragY = dy
        captureWillCancel = dx < -cancelDistance
        captureWillLock = dy < -lockDistance
    }

    // Lock beats cancel: dragging up is already a commitment to keep the take.
    function endCapture() {
        if (captureWillLock) {
            captureLocked = true
            captureDragX = 0
            captureDragY = 0
            captureWillLock = false
            captureWillCancel = false
            return
        }
        if (captureWillCancel) {
            cancelCapture()
            return
        }
        finishLockedCapture()
    }

    function finishLockedCapture() {
        if (captureMode === "video") {
            roundVideoService.stopRecording()   // overlay closes on recordingFinished
        } else {
            voiceService.stopRecording()
        }
        captureLocked = false
    }

    function cancelCapture() {
        if (captureMode === "video") {
            roundVideoService.cancelRecording()
            roundVideoService.stopPreview()
            captureActive = false
        } else {
            voiceService.cancelRecording()
        }
        captureLocked = false
        captureDragX = 0
        captureDragY = 0
        captureWillCancel = false
        captureWillLock = false
    }

    Connections {
        target: roundVideoService

        function onRecordingFinished(filePath, durationMs) {
            chatViewRoot.captureActive = false
            chatViewRoot.captureLocked = false
            roundVideoService.stopPreview()
            chatService.sendVideoNote(chatViewRoot.currentChatId, chatViewRoot.currentChatType,
                                       filePath, durationMs)
            chatViewRoot.dismissReplyBar()
        }

        function onRecordingTooShort() {
            chatViewRoot.captureActive = false
            chatViewRoot.captureLocked = false
            roundVideoService.stopPreview()
            chatViewRoot.captureStatus = "Hold to record - that was too short"
            captureStatusClear.restart()
        }

        function onRecordingFailed(error) {
            chatViewRoot.captureActive = false
            chatViewRoot.captureLocked = false
            roundVideoService.stopPreview()
            chatViewRoot.captureStatus = error
            captureStatusClear.restart()
        }
    }

    Connections {
        target: chatService

        function onVideoNoteUploadProgress(sent, total) {
            if (total > 0) {
                chatViewRoot.captureStatus = "Uploading " + Math.round(sent * 100 / total) + "%"
            }
        }

        function onVideoNoteMessageSent(chatId, message) {
            chatViewRoot.captureStatus = ""
        }

        function onVideoNoteUploadError(error) {
            chatViewRoot.captureStatus = error
            captureStatusClear.restart()
        }
    }

    Timer {
        id: captureStatusClear
        interval: 3000
        onTriggered: chatViewRoot.captureStatus = ""
    }

    Connections {
        target: voiceService

        function onRecordingFinished(filePath, durationMs) {
            chatService.sendVoiceNote(chatViewRoot.currentChatId, chatViewRoot.currentChatType, filePath, durationMs)
            chatViewRoot.dismissReplyBar()
        }

        function onRecordingFailed(error) {
            console.log("[ChatView] Recording failed:", error)
        }

        function onRecordingTooShort() {
            console.log("[ChatView] Recording too short, discarded")
        }

        function onPlaybackFailed(error) {
            console.log("[ChatView] Playback failed:", error)
        }
    }

    Connections {
        target: websocketService

        function onMessageReceived(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            // Skip only the echo of a send from THIS device - it is already on
            // screen optimistically. The same message arriving from another of
            // our devices has no local echo here, so dropping it left the open
            // chat stale until it was closed and reopened.
            if (message.senderId === authService.currentUserId) {
                var myDev = authService.getOrCreateDeviceId()
                var srcDev = message.senderDeviceId || ""
                if (srcDev === "" || srcDev === myDev) return
            }
            var hasFile = (message.fileType === "audio" || message.fileType === "image"
                           || message.fileType === "file" || message.fileType === "video_note")
            var text = hasFile ? "" : chatService.decryptMessage(chatId, message.content, message.encrypted === true,
                                                                  message.senderId, message.keyVersion || 0,
                                                                  message.encryptionVersion || 1, message.senderDeviceId || "")
            var env = (message.encrypted === true && message.content)
                      ? chatService.peekEnvelope(chatId, message.content, true,
                                                 message.senderId, message.keyVersion || 0,
                                                 message.encryptionVersion || 1, message.senderDeviceId || "")
                      : ({})
            // isMine: true when this is our own message relayed from another of
            // our devices, so it renders on the sender's side rather than as an
            // incoming bubble from ourselves.
            var fromMe = message.senderId === authService.currentUserId
            chatViewRoot.addMessage(message.senderId,
                                     fromMe ? "Me" : chatViewRoot.currentChatName, text,
                                     chatViewRoot.formatTime(message.createdAt), fromMe, false,
                                     hasFile ? (message.fileUrl || env.fileUrl || "") : "",
                                     (message.durationMs > 0 ? message.durationMs : (env.durationMs || 0)),
                                     hasFile && message.encrypted === true, message.id,
                                     message.fileType,
                                     message.fileName || env.fileName || "",
                                     message.fileSize > 0 ? message.fileSize : (env.fileSize || 0),
                                     message.thumbnailUrl || env.thumbnailUrl || "",
                                     hasFile ? "" : message.content,
                                     message.encrypted === true, message.keyVersion || 0,
                                     message.encryptionVersion || 1,
                                     message.isForwarded === true, message.forwardedFromName || "", message.replyToId || "",
                                     message.senderDeviceId || "")
            // This chat is already open and visible, so the message that just
            // arrived counts as read immediately - onCurrentChatIdChanged only
            // fires when switching chats, not for new messages in one already open.
            chatService.markAsRead(chatId)
        }

        function onMessageRead(chatId, readerId, readAt) {
            if (chatId !== chatViewRoot.currentChatId) return
            // The other participant just read our messages in this chat -
            // flip every one of our bubbles over to "seen".
            if (readerId === authService.currentUserId) return
            for (var i = 0; i < messagesModel.count; i++) {
                var row = messagesModel.get(i)
                // Only ACK'd messages can show double ticks.
                if (row.isMine && row.sendStatus !== "pending") {
                    messagesModel.setProperty(i, "isRead", true)
                }
            }
        }

        function onConnected() {
            if (chatViewRoot.currentChatId && chatViewRoot.currentChatId.length > 0) {
                websocketService.joinChat(chatViewRoot.currentChatId)
                // Catch up on anything sent while we were disconnected -
                // otherwise only new messages from this point on would show,
                // silently skipping whatever arrived during the outage.
                chatService.fetchMessages(chatViewRoot.currentChatId)
            }
            chatViewRoot.flushOutgoingText()
        }

        function onPresenceChanged(userId, online) {
            if (userId === chatViewRoot.otherUserId) {
                chatViewRoot.isOnline = online
            }
        }
    }

    ListModel {
        id: messagesModel
    }

    readonly property var imageExtensions: ["png", "jpg", "jpeg", "gif", "bmp", "webp"]
    function attachmentContentType(fileUrl) {
        var path = fileUrl.toString()
        var dot = path.lastIndexOf(".")
        if (dot === -1) return "file"
        var ext = path.substring(dot + 1).toLowerCase()
        return chatViewRoot.imageExtensions.indexOf(ext) !== -1 ? "image" : "file"
    }

    FileDialog {
        id: attachmentPicker
        title: "Send a file"
        onAccepted: {
            var contentType = chatViewRoot.attachmentContentType(selectedFile)
            chatService.sendAttachment(chatViewRoot.currentChatId, chatViewRoot.currentChatType,
                                        selectedFile.toString(), contentType)
            chatViewRoot.dismissReplyBar()
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        // Keep a growing composer from painting past the chat pane.
        clip: true

        // Chat header
        GlassPanel {
            Layout.fillWidth: true
            Layout.preferredHeight: 60
            Layout.maximumHeight: 60
            radius: 0
            sheen: false
            darkMode: chatViewRoot.darkMode

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 12

                // Back button - closes the open chat and returns focus to the
                // sidebar. Previously wired to a signal nothing ever
                // listened for (a dead click, found while auditing every
                // button's placement) - main.qml now clears chatViewLoader
                // on it.
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    color: backMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (backMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        anchors.centerIn: parent
                        width: 16
                        height: 16
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = chatViewRoot.textColor
                            ctx.lineWidth = 1.8
                            ctx.lineCap = "round"
                            ctx.lineJoin = "round"
                            ctx.beginPath()
                            ctx.moveTo(10, 2); ctx.lineTo(4, 8); ctx.lineTo(10, 14)
                            ctx.stroke()
                        }
                    }
                    MouseArea {
                        id: backMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: chatViewRoot.backClicked()
                    }
                }

                // Avatar + name/status - tapping either opens group info for a
                // group chat, same as the "..." button (and matching Android,
                // where tapping the header does the same thing). Wrapped in a
                // plain Item (rather than making the MouseArea itself a
                // RowLayout child) because anchors and Qt Quick Layouts don't
                // mix - a layout-managed child ignores anchors.fill entirely.
                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 38

                    RowLayout {
                        anchors.fill: parent
                        spacing: 12

                        Item {
                            Layout.preferredWidth: 38
                            Layout.preferredHeight: 38

                            Avatar {
                                anchors.fill: parent
                                name: chatViewRoot.currentChatName
                                avatarUrl: chatViewRoot.currentChatAvatarUrl
                                size: 38
                            }

                            Rectangle {
                                anchors.bottom: parent.bottom
                                anchors.right: parent.right
                                width: 11
                                height: 11
                                radius: 5.5
                                color: chatViewRoot.onlineColor
                                border.color: chatViewRoot.surfaceColor
                                border.width: 2
                                visible: chatViewRoot.isOnline && !chatViewRoot.isGroupChat
                            }
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1

                            Text {
                                Layout.fillWidth: true
                                text: chatViewRoot.currentChatName || "Select a chat"
                                font.pixelSize: 15
                                font.bold: true
                                color: chatViewRoot.textColor
                                elide: Text.ElideRight
                            }

                            Text {
                                Layout.fillWidth: true
                                text: chatViewRoot.typingIndicator ? chatViewRoot.typingUser + " is typing…"
                                      : chatViewRoot.isGroupChat ? "Tap for group info"
                                      : (chatViewRoot.isOnline ? "Online" : "Offline")
                                font.pixelSize: 12
                                color: chatViewRoot.typingIndicator ? chatViewRoot.accentColor
                                       : chatViewRoot.isGroupChat ? chatViewRoot.textSecondary
                                       : (chatViewRoot.isOnline ? chatViewRoot.onlineColor : chatViewRoot.textSecondary)
                                elide: Text.ElideRight
                            }
                        }
                    }

                    MouseArea {
                        anchors.fill: parent
                        enabled: chatViewRoot.isGroupChat
                        cursorShape: chatViewRoot.isGroupChat ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: chatViewRoot.openChatInfo(chatViewRoot.currentChatId)
                    }
                }

                // Audio call - direct (1:1) or group (full mesh, max 4).
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    visible: chatViewRoot.currentChatId.length > 0
                    color: callMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (callMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        id: voiceCallIcon
                        anchors.centerIn: parent
                        width: 16
                        height: 16
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { voiceCallIcon.requestPaint() }
                        }
                        Component.onCompleted: requestPaint()
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.6
                            ctx.lineCap = "round"
                            ctx.lineJoin = "round"
                            // Classic handset silhouette, matching the video
                            // camera glyph's textSecondary fill.
                            ctx.beginPath()
                            ctx.moveTo(3.2, 1.8)
                            ctx.quadraticCurveTo(1.4, 1.8, 1.4, 3.6)
                            ctx.quadraticCurveTo(1.4, 6.2, 3.5, 8.8)
                            ctx.quadraticCurveTo(5.8, 11.5, 8.8, 13.2)
                            ctx.quadraticCurveTo(11.2, 14.6, 13.2, 14.6)
                            ctx.quadraticCurveTo(15.0, 14.6, 15.0, 12.6)
                            ctx.quadraticCurveTo(15.0, 11.4, 13.6, 10.8)
                            ctx.lineTo(11.4, 10.0)
                            ctx.quadraticCurveTo(10.4, 9.6, 9.8, 10.4)
                            ctx.lineTo(8.6, 11.6)
                            ctx.quadraticCurveTo(6.2, 10.0, 4.6, 7.6)
                            ctx.lineTo(5.8, 6.2)
                            ctx.quadraticCurveTo(6.6, 5.4, 6.2, 4.4)
                            ctx.lineTo(5.2, 2.4)
                            ctx.quadraticCurveTo(4.6, 1.8, 3.2, 1.8)
                            ctx.closePath()
                            ctx.fill()
                        }
                    }
                    MouseArea {
                        id: callMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (chatViewRoot.isGroupChat) {
                                chatViewRoot.pendingGroupCallChatId = chatViewRoot.currentChatId
                                chatViewRoot.pendingGroupCallName = chatViewRoot.currentChatName
                                chatViewRoot.pendingGroupCallVideo = false
                                groupService.getGroupInfo(chatViewRoot.currentChatId)
                            } else {
                                callService.startOutgoingCall(chatViewRoot.currentChatId, chatViewRoot.otherUserId, chatViewRoot.currentChatName, false)
                            }
                        }
                    }
                }

                // Video call - direct and group (mesh, max 4).
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    visible: chatViewRoot.currentChatId.length > 0
                    color: videoCallMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (videoCallMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        id: videoCallIcon
                        anchors.centerIn: parent
                        width: 18
                        height: 14
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { videoCallIcon.requestPaint() }
                        }
                        Component.onCompleted: requestPaint()
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            // Camera body
                            ctx.beginPath()
                            ctx.roundedRect(0.5, 1.5, 11, 11, 2.2, 2.2)
                            ctx.fill()
                            // Lens barrel / viewfinder triangle on the right
                            ctx.beginPath()
                            ctx.moveTo(12.5, 5)
                            ctx.lineTo(17.5, 2)
                            ctx.lineTo(17.5, 12)
                            ctx.lineTo(12.5, 9)
                            ctx.closePath()
                            ctx.fill()
                        }
                    }
                    MouseArea {
                        id: videoCallMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (chatViewRoot.isGroupChat) {
                                chatViewRoot.pendingGroupCallChatId = chatViewRoot.currentChatId
                                chatViewRoot.pendingGroupCallName = chatViewRoot.currentChatName
                                chatViewRoot.pendingGroupCallVideo = true
                                groupService.getGroupInfo(chatViewRoot.currentChatId)
                            } else {
                                callService.startOutgoingCall(chatViewRoot.currentChatId, chatViewRoot.otherUserId, chatViewRoot.currentChatName, true)
                            }
                        }
                    }
                }

                // Encryption / security code (direct chats).
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    visible: !chatViewRoot.isGroupChat && chatViewRoot.currentChatId.length > 0
                    color: lockMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (lockMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        id: lockIcon
                        anchors.centerIn: parent
                        width: 14
                        height: 16
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { lockIcon.requestPaint() }
                        }
                        Component.onCompleted: requestPaint()
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.fillStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.6
                            ctx.beginPath()
                            ctx.arc(7, 6, 4, Math.PI, 0, false)
                            ctx.stroke()
                            ctx.beginPath()
                            ctx.roundedRect(2, 7, 10, 8, 2, 2)
                            ctx.fill()
                        }
                    }
                    MouseArea {
                        id: lockMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: chatViewRoot.openSafetyDialog()
                    }
                }

                // More options - group info panel. Direct chats have nothing
                // here yet (no contact-info screen has been built), so the
                // button is only shown when there's somewhere for it to go.
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    visible: chatViewRoot.isGroupChat
                    color: moreMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (moreMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        anchors.centerIn: parent
                        width: 16
                        height: 4
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            for (var i = 0; i < 3; i++) {
                                ctx.beginPath()
                                ctx.arc(2 + i * 6, 2, 1.8, 0, Math.PI * 2)
                                ctx.fill()
                            }
                        }
                    }
                    MouseArea {
                        id: moreMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: chatViewRoot.openChatInfo(chatViewRoot.currentChatId)
                    }
                }
            }
        }

        // Selection toolbar. Replaces the header while selecting, so the count
        // and the destructive actions sit where the chat title normally is
        // rather than floating over the conversation.
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 56
            visible: chatViewRoot.selectionMode
            color: Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                            chatViewRoot.accentColor.b, chatViewRoot.darkMode ? 0.18 : 0.12)

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 12

                ToolButton {
                    text: "✕"
                    onClicked: chatViewRoot.clearSelection()
                    ToolTip.visible: hovered
                    ToolTip.text: "Cancel selection"
                }

                Text {
                    text: chatViewRoot.selectedCount + (chatViewRoot.selectedCount === 1
                                                         ? " selected" : " selected")
                    color: chatViewRoot.textColor
                    font.pixelSize: 14
                    font.bold: true
                    Layout.fillWidth: true
                }

                ToolButton {
                    text: "Select all"
                    onClicked: chatViewRoot.selectAll()
                }

                ToolButton {
                    text: "Reply"
                    enabled: chatViewRoot.selectedCount === 1
                    onClicked: chatViewRoot.beginReply(chatViewRoot.selectedIds[0])
                }

                ToolButton {
                    text: "Forward"
                    enabled: chatViewRoot.selectedCount > 0
                    onClicked: chatViewRoot.openForwardPicker(chatViewRoot.selectedIds.slice())
                }

                ToolButton {
                    text: "Delete for me"
                    enabled: chatViewRoot.selectedCount > 0
                    onClicked: chatViewRoot.deleteSelected(false)
                }

                ToolButton {
                    // Only offered when every selected message is mine - the
                    // server refuses the rest anyway.
                    text: "Delete for everyone"
                    visible: chatViewRoot.allSelectedAreMine
                    enabled: chatViewRoot.selectedCount > 0
                    onClicked: chatViewRoot.deleteSelected(true)
                }
            }
        }

        // Messages area
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            AmbientGlow {
                anchors.fill: parent
                baseColor: chatViewRoot.bgColor
                primaryGlow: chatViewRoot.accentColor
                secondaryGlow: chatViewRoot.darkMode ? "#A6863F" : "#8A6A2E"
                intensity: chatViewRoot.darkMode ? 0.75 : 0.55
            }

            ChatBackground {
                anchors.fill: parent
                baseColor: chatViewRoot.bgColor
                baseOpacity: 0
                patternColor: chatViewRoot.accentColor
                patternOpacity: chatViewRoot.darkMode ? 0.05 : 0.09
            }

            Rectangle {
                id: keyChangeBanner
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                visible: chatViewRoot.peerKeyPending && !chatViewRoot.isGroupChat
                height: visible ? bannerCol.implicitHeight + 16 : 0
                color: Qt.rgba(0.75, 0.2, 0.15, chatViewRoot.darkMode ? 0.35 : 0.18)
                Column {
                    id: bannerCol
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 8
                    spacing: 6
                    Text {
                        width: parent.width
                        wrapMode: Text.WordWrap
                        color: chatViewRoot.textColor
                        font.pixelSize: 12
                        text: "Security code changed. Messages still encrypt to the previous key until you accept."
                    }
                    Row {
                        spacing: 12
                        Text {
                            text: "Verify"
                            color: chatViewRoot.accentColor
                            font.pixelSize: 13
                            MouseArea {
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                onClicked: chatViewRoot.openSafetyDialog()
                            }
                        }
                        Text {
                            text: "Accept new code"
                            color: chatViewRoot.accentColor
                            font.pixelSize: 13
                            MouseArea {
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    chatService.acceptPeerKeyChange(chatViewRoot.currentChatId)
                                    chatViewRoot.peerKeyPending = false
                                }
                            }
                        }
                    }
                }
            }

        ListView {
            id: messagesListView
            anchors.top: keyChangeBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.rightMargin: 14
            clip: true
            spacing: 2
            model: messagesModel
            // Zero so nested reply-quote / media taps are not eaten by the
            // Flickable's press-delay (a quick tap never reaches the child).
            // Drag-to-scroll still works via the drag threshold.
            pressDelay: 0
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: Flickable.VerticalFlick
            // More allocated delegates → less wild contentHeight estimates
            // while scrolling (variable-height chat bubbles).
            cacheBuffer: Math.max(800, Math.floor(height * 3))

            onMovementStarted: chatViewRoot.stopMessagesScrollAnim()
            onDraggingChanged: if (dragging) chatViewRoot.stopMessagesScrollAnim()
            onMovementEnded: chatViewRoot.clampMessagesContentY()
            onContentHeightChanged: {
                if (!messagesScrollBar.pressed && !moving && !flicking)
                    chatViewRoot.clampMessagesContentY()
            }

            WheelHandler {
                acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                enabled: !messagesScrollBar.pressed
                onWheel: function(event) {
                    var delta = event.pixelDelta.y !== 0
                                ? event.pixelDelta.y
                                : event.angleDelta.y / 2
                    chatViewRoot.scrollMessagesBy(delta)
                    event.accepted = true
                }
            }

            // Middle-click autoscroll is handled by the overlay below rather
            // than a DragHandler here. A DragHandler only pans while the wheel
            // is held down and dragged, which is not what middle-click
            // scrolling means on Windows: one click arms it, then the pointer's
            // distance from the anchor sets a continuous speed, hands-free.

            header: Item {
                width: messagesListView.width
                height: chatViewRoot.isLoadingMore ? 40 : 0
                visible: chatViewRoot.isLoadingMore

                BusyIndicator {
                    anchors.centerIn: parent
                    running: chatViewRoot.isLoadingMore
                    width: 24
                    height: 24
                }
            }

            delegate: Item {
                id: messageDelegate
                width: messagesListView.width
                readonly property bool showUnreadDivider:
                    model.messageKind !== "system"
                    && chatViewRoot.openUnreadCount > 0
                    && model.messageId.length > 0
                    && model.messageId === chatViewRoot.firstUnreadMessageId
                height: (showUnreadDivider ? unreadDivider.height : 0)
                        + (model.messageKind === "system"
                           ? systemNotice.implicitHeight + 16
                           : bubbleItem.height + (model.showSender ? 10 : 2))

                // Telegram-style accent bar marking where unread messages begin.
                Item {
                    id: unreadDivider
                    visible: messageDelegate.showUnreadDivider
                    width: parent.width
                    height: visible ? 36 : 0

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 10

                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 1
                            Layout.alignment: Qt.AlignVCenter
                            color: Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                           chatViewRoot.accentColor.b, 0.35)
                        }
                        Text {
                            text: chatViewRoot.openUnreadCount <= 1
                                  ? "Unread message"
                                  : (chatViewRoot.openUnreadCount + " unread messages")
                            font.pixelSize: 12
                            font.weight: Font.DemiBold
                            color: chatViewRoot.accentColor
                            Layout.alignment: Qt.AlignVCenter
                        }
                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 1
                            Layout.alignment: Qt.AlignVCenter
                            color: Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                           chatViewRoot.accentColor.b, 0.35)
                        }
                    }
                }

                // A tiny centered line, not a real bubble - matches how
                // WhatsApp shows "security code changed" and similar events
                // inline without making them look like something either
                // person actually sent.
                Text {
                    id: systemNotice
                    visible: model.messageKind === "system"
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: unreadDivider.bottom
                    anchors.topMargin: 8
                    width: parent.width - 64
                    text: model.messageText
                    font.pixelSize: 12
                    color: chatViewRoot.textSecondary
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                }

                MessageBubble {
                    id: bubbleItem
                    visible: model.messageKind !== "system"
                    // A playing round video is both taller and often the newest
                    // message, so it frequently sits half off-screen. Contain
                    // scrolls the minimum needed to show it whole, and the
                    // delayed second pass catches the grow animation - a single
                    // call would reveal the old, smaller bounds.
                    selectionMode: chatViewRoot.selectionMode
                    selected: chatViewRoot.isSelected(model.messageId)
                    flashHighlight: model.messageId.length > 0
                                    && model.messageId === chatViewRoot.highlightMessageId
                    flashNonce: (model.messageId.length > 0
                                 && model.messageId === chatViewRoot.highlightMessageId)
                                ? chatViewRoot.highlightNonce : 0
                    onToggleSelected: chatViewRoot.toggleSelection(model.messageId)
                    onRequestDelete: {
                        // In selection mode a right-click just adds to the
                        // selection; the toolbar owns the actions from there.
                        if (chatViewRoot.selectionMode) {
                            chatViewRoot.toggleSelection(model.messageId)
                            return
                        }
                        messageMenu.targetMessageId = model.messageId
                        messageMenu.targetIsMine = model.isMine
                        messageMenu.targetMessageText = bubbleItem.messageText || ""
                        messageMenu.popup()
                    }
                    onVideoPlaybackStarted: {
                        chatViewRoot.smoothRevealIndex(index)
                        revealAfterGrow.restart()
                    }
                    onTextExpandToggled: function(expanded) {
                        // Persist on the model row. Dragging the scrollbar
                        // calls positionViewAtIndex, which destroys and
                        // rebuilds delegates - state held only in the delegate
                        // went with them, so an expanded message silently
                        // collapsed mid-drag.
                        messagesModel.setProperty(index, "textExpanded", expanded)
                        if (expanded)
                            chatViewRoot.smoothRevealIndex(index)
                    }
                    Timer {
                        id: revealAfterGrow
                        // Fires once the grow animation has settled; the first
                        // reveal only knows the pre-growth size.
                        interval: 260
                        onTriggered: chatViewRoot.smoothRevealIndex(index)
                    }
                    x: 16
                    y: unreadDivider.height + (model.showSender ? 10 : 2)
                    width: parent.width - 32
                    darkMode: chatViewRoot.darkMode
                    // Reading cryptoRevision makes this binding depend on "a
                    // key was learned", so a row that first rendered as the
                    // placeholder re-decrypts itself the moment one arrives.
                    messageText: {
                        var revision = chatService.cryptoRevision
                        if (!model.rawContent || model.rawContent.length === 0) return model.messageText
                        return chatService.decryptMessage(chatViewRoot.currentChatId, model.rawContent,
                                                          model.rawEncrypted, model.senderId,
                                                          model.rawKeyVersion,
                                                          model.rawEncryptionVersion || 1,
                                                          model.rawSenderDeviceId || "")
                    }
                    messageTime: model.messageTime
                    isMine: model.isMine
                    isRead: model.isRead
                    sendStatus: model.sendStatus || "sent"
                    senderName: model.senderName
                    showSender: model.showSender
                    myMessageBg: chatViewRoot.myMessageBg
                    theirMessageBg: chatViewRoot.theirMessageBg
                    isEncrypted: true
                    accentColor: chatViewRoot.accentColor
                    messageId: model.messageId
                    senderId: model.senderId
                    keyVersion: model.rawKeyVersion || 0
                    encryptionVersion: model.rawEncryptionVersion || 1
                    voiceUrl: model.voiceUrl
                    voiceDurationMs: model.voiceDurationMs
                    voiceEncrypted: model.voiceEncrypted
                    contentType: model.contentType
                    thumbnailUrl: model.thumbnailUrl
                    fileName: model.fileName
                    fileSize: model.fileSize
                    chatId: chatViewRoot.currentChatId
                    isForwarded: model.isForwarded === true
                    forwardedFromName: model.forwardedFromName || ""
                    replyToId: model.replyToId || ""
                    replySenderName: {
                        var r = chatViewRoot.resolveReplyFields(model.replyToId || "")
                        return r.name
                    }
                    replyPreview: {
                        var r = chatViewRoot.resolveReplyFields(model.replyToId || "")
                        return r.preview
                    }
                    replyAvailable: {
                        var r = chatViewRoot.resolveReplyFields(model.replyToId || "")
                        return r.available
                    }
                    onReplyQuoteClicked: {
                        // Pure local scroll — never gated on websocket / replyAvailable.
                        chatViewRoot.jumpToMessage(model.replyToId || "")
                    }
                    // Restored from the model, so a recycled delegate comes
                    // back expanded - see onTextExpandToggled above.
                    textExpanded: model.textExpanded === true
                }
            }

            // Typing indicator
            footer: Item {
                width: messagesListView.width
                height: chatViewRoot.typingVisible ? 36 : 0
                visible: chatViewRoot.typingVisible

                RowLayout {
                    anchors.left: parent.left
                    anchors.leftMargin: 16
                    anchors.top: parent.top
                    anchors.topMargin: 4
                    spacing: 4

                    Repeater {
                        model: 3
                        Rectangle {
                            width: 7; height: 7; radius: 3.5
                            color: chatViewRoot.textSecondary
                            SequentialAnimation on opacity {
                                running: chatViewRoot.typingVisible
                                loops: Animation.Infinite
                                PauseAnimation { duration: index * 150 }
                                NumberAnimation { to: 1; duration: 350 }
                                NumberAnimation { to: 0.3; duration: 350 }
                            }
                        }
                    }
                }
            }
        }

            // Detached from ListView's ScrollBar.vertical on purpose: the
            // attached bar binds size/position to contentHeight, and chat
            // bubbles have variable heights so that estimate jumps every
            // frame while dragging → handle flashes. Drive by message index
            // instead so the handle never lands in estimated empty space.
            ScrollBar {
                id: messagesScrollBar
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                width: 14
                padding: 2
                z: 20
                policy: ScrollBar.AlwaysOn
                interactive: true
                minimumSize: 0.08

                property bool updatingFromList: false

                function visibleFraction() {
                    var c = Math.max(1, messagesListView.count)
                    var approxRow = 72
                    var visible = Math.max(1, messagesListView.height / approxRow)
                    return Math.min(1, Math.max(minimumSize, visible / c))
                }

                function firstVisibleIndex() {
                    var lv = messagesListView
                    if (lv.count <= 0)
                        return 0
                    var idx = lv.indexAt(Math.max(8, lv.width / 2), lv.contentY + 8)
                    if (idx >= 0)
                        return idx
                    idx = lv.indexAt(Math.max(8, lv.width / 2), lv.contentY + Math.min(80, lv.height / 3))
                    if (idx >= 0)
                        return idx
                    var maxY = Math.max(0, lv.contentHeight - lv.height)
                    if (maxY <= 0)
                        return 0
                    var t = (lv.contentY - lv.originY) / maxY
                    return Math.round(Math.min(1, Math.max(0, t)) * (lv.count - 1))
                }

                function syncFromList() {
                    if (pressed)
                        return
                    var c = messagesListView.count
                    updatingFromList = true
                    size = visibleFraction()
                    if (c <= 1) {
                        position = 0
                    } else {
                        var scrollable = Math.max(0.0001, 1 - size)
                        var atEnd = messagesListView.contentY
                                    >= messagesScrollMaxY() - 2
                        position = atEnd
                                   ? scrollable
                                   : (firstVisibleIndex() / (c - 1)) * scrollable
                    }
                    updatingFromList = false
                }

                contentItem: Rectangle {
                    implicitWidth: 8
                    radius: 4
                    color: messagesScrollBar.pressed
                           ? chatViewRoot.accentColor
                           : Qt.rgba(chatViewRoot.textSecondary.r,
                                     chatViewRoot.textSecondary.g,
                                     chatViewRoot.textSecondary.b, 0.55)
                }
                background: Rectangle {
                    implicitWidth: 14
                    color: Qt.rgba(1, 1, 1, chatViewRoot.darkMode ? 0.04 : 0.06)
                }

                onPressedChanged: {
                    if (pressed) {
                        updatingFromList = true
                        size = visibleFraction()
                        updatingFromList = false
                        chatViewRoot.stopMessagesScrollAnim()
                    } else {
                        chatViewRoot.clampMessagesContentY()
                        syncFromList()
                    }
                }

                onPositionChanged: {
                    if (updatingFromList || !pressed)
                        return
                    var c = messagesListView.count
                    if (c <= 0)
                        return
                    var scrollable = Math.max(0.0001, 1 - size)
                    var t = Math.min(1, Math.max(0, position / scrollable))
                    var idx = Math.round(t * (c - 1))
                    if (t >= 0.995)
                        messagesListView.positionViewAtEnd()
                    else if (t <= 0.005)
                        messagesListView.positionViewAtBeginning()
                    else
                        messagesListView.positionViewAtIndex(idx, ListView.Beginning)
                }

                Component.onCompleted: syncFromList()
            }

            Connections {
                target: messagesListView
                function onContentYChanged() { messagesScrollBar.syncFromList() }
                function onContentHeightChanged() { messagesScrollBar.syncFromList() }
                function onHeightChanged() { messagesScrollBar.syncFromList() }
                function onOriginYChanged() { messagesScrollBar.syncFromList() }
                function onCountChanged() { messagesScrollBar.syncFromList() }
            }

            // Arms autoscroll. MiddleButton only, so left clicks, selection and
            // every control inside the bubbles still receive their events
            // normally - this never sits in front of them.
            MouseArea {
                anchors.fill: parent
                acceptedButtons: Qt.MiddleButton
                onPressed: function(mouse) {
                    if (chatViewRoot.autoScrollActive)
                        chatViewRoot.stopAutoScroll()
                    else
                        chatViewRoot.startAutoScroll(mouse.x, mouse.y)
                }
            }

            // While armed this covers the list to track the pointer and to
            // swallow the click that cancels. It exists only in that state, so
            // it cannot interfere with anything the rest of the time.
            MouseArea {
                id: autoScrollCatcher
                anchors.fill: parent
                enabled: chatViewRoot.autoScrollActive
                visible: enabled
                hoverEnabled: true
                acceptedButtons: Qt.AllButtons
                cursorShape: Qt.SizeVerCursor
                z: 50
                onPositionChanged: function(mouse) {
                    chatViewRoot.autoScrollCursorY = mouse.y
                }
                onPressed: chatViewRoot.stopAutoScroll()
                onWheel: chatViewRoot.stopAutoScroll()
                Keys.onEscapePressed: chatViewRoot.stopAutoScroll()
                onEnabledChanged: if (enabled) forceActiveFocus()
            }

            // The anchor marker: origin of the gesture, and a reminder that a
            // mode is active.
            Rectangle {
                visible: chatViewRoot.autoScrollActive
                z: 51
                width: 28
                height: 28
                radius: 14
                x: chatViewRoot.autoScrollAnchorX - width / 2
                y: chatViewRoot.autoScrollAnchorY - height / 2
                color: chatViewRoot.darkMode ? Qt.rgba(0, 0, 0, 0.55)
                                             : Qt.rgba(1, 1, 1, 0.85)
                border.width: 1
                border.color: chatViewRoot.accentColor

                Canvas {
                    anchors.fill: parent
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = chatViewRoot.accentColor
                        ctx.lineWidth = 1.6
                        ctx.lineCap = "round"
                        ctx.lineJoin = "round"
                        var cx = width / 2
                        // Up and down chevrons around a centre dot.
                        ctx.beginPath()
                        ctx.moveTo(cx - 4, 11); ctx.lineTo(cx, 7); ctx.lineTo(cx + 4, 11)
                        ctx.moveTo(cx - 4, 17); ctx.lineTo(cx, 21); ctx.lineTo(cx + 4, 17)
                        ctx.stroke()
                        ctx.fillStyle = chatViewRoot.accentColor
                        ctx.beginPath()
                        ctx.arc(cx, 14, 1.4, 0, Math.PI * 2)
                        ctx.fill()
                    }
                }
            }

            // Jump to the newest message. Only shown while scrolled up, so it
            // never covers the conversation during normal reading - and it is
            // the counterpart to not auto-scrolling in that state: the view
            // stays put, and this says how much has been missed.
            Rectangle {
                id: jumpToBottom
                width: 40
                height: 40
                radius: 20
                anchors.right: parent.right
                anchors.rightMargin: 22
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 18
                color: jumpMouse.containsPress
                       ? Qt.darker(chatViewRoot.myMessageBg, 1.15)
                       : (jumpMouse.containsMouse
                          ? Qt.lighter(chatViewRoot.myMessageBg, 1.08)
                          : chatViewRoot.myMessageBg)

                visible: opacity > 0.01
                opacity: chatViewRoot.messagesAtBottom ? 0 : 1
                Behavior on opacity { NumberAnimation { duration: 140 } }
                // Slides up as it appears rather than popping in.
                transform: Translate {
                    y: chatViewRoot.messagesAtBottom ? 8 : 0
                    Behavior on y { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
                }

                Canvas {
                    anchors.centerIn: parent
                    width: 18
                    height: 18
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = "#FFFFFF"
                        ctx.lineWidth = 2
                        ctx.lineCap = "round"
                        ctx.lineJoin = "round"
                        ctx.beginPath()
                        ctx.moveTo(9, 3); ctx.lineTo(9, 14)
                        ctx.moveTo(4, 9.5); ctx.lineTo(9, 14.5); ctx.lineTo(14, 9.5)
                        ctx.stroke()
                    }
                }

                // Unread-since-scroll badge.
                Rectangle {
                    visible: chatViewRoot.missedMessageCount > 0
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.bottom: parent.top
                    anchors.bottomMargin: -6
                    width: Math.max(18, badgeText.implicitWidth + 10)
                    height: 18
                    radius: 9
                    color: "#E74C3C"
                    Text {
                        id: badgeText
                        anchors.centerIn: parent
                        text: chatViewRoot.missedMessageCount > 99
                              ? "99+" : chatViewRoot.missedMessageCount
                        color: "#FFFFFF"
                        font.pixelSize: 10
                        font.bold: true
                    }
                }

                MouseArea {
                    id: jumpMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        chatViewRoot.missedMessageCount = 0
                        chatViewRoot.smoothScrollToEnd()
                    }
                }
            }
        }

        // Message input area — grows upward into the message list (layout-
        // managed height), then scrolls inside once it hits ~40% of the chat.
        GlassPanel {
            id: composerBar
            Layout.fillWidth: true
            // Cap against the chat pane so a huge paste cannot push this bar
            // off the bottom of the window. The messages ListView (fillHeight)
            // shrinks to make room as we grow.
            readonly property int barMaxHeight: Math.max(68, Math.floor(chatViewRoot.height * 0.4))
            readonly property int inputMinHeight: 42
            readonly property int inputMaxHeight: Math.max(inputMinHeight, barMaxHeight - 24)
            readonly property int inputHeight: {
                if (voiceService.isRecording)
                    return inputMinHeight
                return Math.min(inputMaxHeight,
                                Math.max(inputMinHeight, messageInput.contentHeight + 22))
            }
            // Must be Layout.* — a bare `height:` grows the item outside its
            // layout cell and spills below the window.
            Layout.preferredHeight: Math.min(barMaxHeight, Math.max(68, inputHeight + 24)
                                             + (chatViewRoot.pendingReplyId.length > 0 ? 48 : 0))
            Layout.maximumHeight: barMaxHeight
            Layout.minimumHeight: 68
            radius: 0
            sheen: false
            darkMode: chatViewRoot.darkMode
            clip: true

            Rectangle {
                id: pendingReplyBar
                visible: chatViewRoot.pendingReplyId.length > 0
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                height: visible ? 48 : 0
                color: Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                chatViewRoot.accentColor.b, chatViewRoot.darkMode ? 0.18 : 0.12)
                z: 2
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 4
                    spacing: 8
                    Column {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            width: parent.width
                            text: chatViewRoot.pendingReplyName
                            color: chatViewRoot.accentColor
                            font.pixelSize: 12
                            font.bold: true
                            elide: Text.ElideRight
                        }
                        Text {
                            width: parent.width
                            text: chatViewRoot.pendingReplyPreview
                            color: chatViewRoot.textSecondary
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }
                    ToolButton {
                        text: "✕"
                        onClicked: chatViewRoot.clearReply()
                    }
                }
            }

            RowLayout {
                id: inputRow
                anchors.fill: parent
                anchors.margins: 12
                anchors.topMargin: pendingReplyBar.visible ? 52 : 12
                spacing: 10

                // Attachment button
                Rectangle {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40
                    Layout.alignment: Qt.AlignBottom
                    radius: 10
                    visible: !voiceService.isRecording
                    color: attMouse.containsPress ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.2) : (attMouse.containsMouse ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.1) : "transparent")
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    Canvas {
                        id: attachIcon
                        anchors.centerIn: parent
                        width: 20
                        height: 20
                        // Canvas does not repaint when a colour binding
                        // changes, so the icon would keep the old theme's
                        // colour after a light/dark switch.
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { attachIcon.requestPaint() }
                        }
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.6
                            ctx.lineCap = "round"
                            ctx.lineJoin = "round"

                            // Two nested capsules on a diagonal. The previous
                            // version chained arc()/arcTo() straight off
                            // lineTo(), which makes Canvas draw connecting
                            // lines into each arc's start point - the clip came
                            // out as a scribble. Rounded rectangles have no
                            // such ambiguity: the shape is fully determined by
                            // its bounds and radius.
                            ctx.translate(width / 2, height / 2)
                            ctx.rotate(-Math.PI / 4)

                            ctx.beginPath()
                            ctx.roundedRect(-4.6, -8, 9.2, 16, 4.6, 4.6)
                            ctx.stroke()

                            ctx.beginPath()
                            ctx.roundedRect(-1.9, -4.4, 3.8, 9.6, 1.9, 1.9)
                            ctx.stroke()
                        }
                    }
                    MouseArea {
                        id: attMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: attachmentPicker.open()
                    }
                }

                // Message input — Enter/Return (including keypad Enter) sends;
                // Shift+Enter inserts a newline. TextArea so multiline works.
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: composerBar.inputHeight
                    Layout.maximumHeight: composerBar.inputMaxHeight
                    Layout.alignment: Qt.AlignBottom
                    radius: 21
                    visible: !voiceService.isRecording
                    clip: true
                    // Was a "rgba(r,g,b,a)" string literal - that syntax silently
                    // drops the alpha channel in this Qt build (always resolves
                    // fully opaque), which is why this rendered solid black in
                    // light mode instead of a subtle tint. Qt.rgba() is reliable.
                    color: darkMode ? Qt.rgba(1, 1, 1, 0.05) : Qt.rgba(43/255, 36/255, 24/255, 0.06)
                    border.color: messageInput.activeFocus ? chatViewRoot.accentColor : "transparent"
                    border.width: 1.5
                    Behavior on border.color { ColorAnimation { duration: 100 } }

                    // A bare TextArea does not scroll its own overflow - past
                    // its bounds the text is simply clipped by the Rectangle
                    // above (clip: true), not shown via a scrollbar. That is
                    // what made a long paste "disappear": it was still there,
                    // just clipped off past inputMaxHeight. ScrollView gives it
                    // an actual scrolling viewport once content exceeds the cap.
                    ScrollView {
                        id: inputScroll
                        anchors.fill: parent
                        clip: true
                        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                        ScrollBar.vertical.policy: ScrollBar.AsNeeded

                        TextArea {
                            id: messageInput
                            // Required inside ScrollView so wrap uses the
                            // viewport width instead of growing forever sideways.
                            width: inputScroll.availableWidth
                            leftPadding: 16
                            rightPadding: 16
                            topPadding: 11
                            bottomPadding: 11
                            background: Item {}
                            placeholderText: "Type a message…"
                            placeholderTextColor: chatViewRoot.textSecondary
                            font.pixelSize: 14
                            color: chatViewRoot.textColor
                            selectByMouse: true
                            wrapMode: TextArea.Wrap
                            Keys.priority: Keys.BeforeItem
                            Keys.onPressed: function(event) {
                                if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                                    if (event.modifiers & Qt.ShiftModifier) {
                                        return
                                    }
                                    event.accepted = true
                                    sendButton.trigger()
                                }
                            }
                        }
                    }
                }

                // Recording indicator - replaces the text field while a voice
                // note is being recorded.
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 42
                    Layout.alignment: Qt.AlignBottom
                    radius: 21
                    visible: voiceService.isRecording
                    color: darkMode ? Qt.rgba(1, 1, 1, 0.05) : Qt.rgba(43/255, 36/255, 24/255, 0.06)

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 8

                        Rectangle {
                            Layout.preferredWidth: 8
                            Layout.preferredHeight: 8
                            radius: 4
                            color: "#E74C3C"
                            SequentialAnimation on opacity {
                                running: voiceService.isRecording
                                loops: Animation.Infinite
                                NumberAnimation { to: 0.25; duration: 500 }
                                NumberAnimation { to: 1.0; duration: 500 }
                            }
                        }

                        Text {
                            text: chatViewRoot.formatDuration(voiceService.recordingElapsedMs)
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                            color: chatViewRoot.textColor
                        }

                        Item { Layout.fillWidth: true }

                        Text {
                            text: "Recording voice message…"
                            font.pixelSize: 12
                            color: chatViewRoot.textSecondary
                        }
                    }
                }

                // Cancel recording
                Rectangle {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40
                    Layout.alignment: Qt.AlignBottom
                    radius: 10
                    visible: voiceService.isRecording
                    color: cancelRecMouse.containsMouse ? Qt.rgba(231/255, 76/255, 60/255, 0.15) : "transparent"

                    Canvas {
                        anchors.centerIn: parent
                        width: 16
                        height: 16
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = "#E74C3C"
                            ctx.lineWidth = 1.6
                            ctx.lineCap = "round"
                            ctx.beginPath(); ctx.moveTo(2, 4); ctx.lineTo(14, 4); ctx.stroke()
                            ctx.beginPath(); ctx.moveTo(6, 4); ctx.lineTo(6, 2); ctx.lineTo(10, 2); ctx.lineTo(10, 4); ctx.stroke()
                            ctx.beginPath()
                            ctx.moveTo(3.5, 4); ctx.lineTo(4.3, 14); ctx.lineTo(11.7, 14); ctx.lineTo(12.5, 4)
                            ctx.stroke()
                        }
                    }
                    ToolTip.visible: cancelRecMouse.containsMouse
                    ToolTip.text: "Cancel"
                    ToolTip.delay: 400
                    MouseArea {
                        id: cancelRecMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: voiceService.cancelRecording()
                    }
                }

                // Send / mic button - sends the typed text when there is any,
                // otherwise starts/stops recording a voice note.
                Rectangle {
                    id: sendButton
                    Layout.preferredWidth: 42
                    Layout.preferredHeight: 42
                    Layout.alignment: Qt.AlignBottom
                    radius: 21
                    property bool canSend: messageInput.text.trim().length > 0
                    color: voiceService.isRecording ? "#E74C3C"
                           : !canSend ? (sendMouse.containsMouse ? (darkMode ? "#3A3222" : "#D6D6DC") : (darkMode ? "#2A2418" : "#E0E0E5"))
                           : sendMouse.pressed ? Qt.darker(chatViewRoot.myMessageBg, 1.15) : (sendMouse.containsMouse ? Qt.lighter(chatViewRoot.myMessageBg, 1.08) : chatViewRoot.myMessageBg)
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        var replyId = chatViewRoot.pendingReplyId
                        var localId = "local-" + Date.now()
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true, false,
                                                 "", 0, false, localId, "", "", 0, "",
                                                 "", false, 0, 1, false, "", replyId)
                        chatViewRoot.queueOutgoingText(localId, chatViewRoot.currentChatId, text,
                                                       chatViewRoot.currentChatType, replyId)
                        chatViewRoot.clearReply()
                        messageInput.text = ""
                    }

                    // Send arrow
                    Canvas {
                        anchors.centerIn: parent
                        width: 18
                        height: 18
                        visible: !voiceService.isRecording && sendButton.canSend
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = "#FFFFFF"
                            ctx.beginPath()
                            ctx.moveTo(2, 9)
                            ctx.lineTo(16, 2)
                            ctx.lineTo(10, 16)
                            ctx.lineTo(8, 10)
                            ctx.lineTo(2, 9)
                            ctx.closePath()
                            ctx.fill()
                        }
                    }

                    // Camera icon - shown instead of the mic once the button
                    // has been tapped into video mode. Without this the two
                    // modes looked identical and the toggle appeared dead.
                    Canvas {
                        id: cameraIcon
                        anchors.centerIn: parent
                        width: 18
                        height: 18
                        visible: !voiceService.isRecording && !sendButton.canSend
                                 && chatViewRoot.captureMode === "video"
                        // Canvas does not repaint on a colour binding change.
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { cameraIcon.requestPaint() }
                        }
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            // Body
                            ctx.beginPath()
                            ctx.roundedRect(1, 4, 11, 10, 2.5, 2.5)
                            ctx.fill()
                            // Lens barrel
                            ctx.beginPath()
                            ctx.moveTo(13, 8)
                            ctx.lineTo(17, 5.5)
                            ctx.lineTo(17, 12.5)
                            ctx.lineTo(13, 10)
                            ctx.closePath()
                            ctx.fill()
                        }
                    }

                    // Mic icon (idle, no text typed, voice mode)
                    Canvas {
                        id: micIcon
                        anchors.centerIn: parent
                        width: 20
                        height: 20
                        visible: !voiceService.isRecording && !sendButton.canSend
                                 && chatViewRoot.captureMode === "voice"
                        Connections {
                            target: chatViewRoot
                            function onTextSecondaryChanged() { micIcon.requestPaint() }
                        }
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.5
                            ctx.lineCap = "round"

                            // Capsule from roundedRect rather than a chain of
                            // arcTo calls. arcTo takes tangent points, not the
                            // corner coordinates it looks like it takes, so the
                            // old capsule was drawing corners of the wrong
                            // radius in the wrong places.
                            ctx.beginPath()
                            ctx.roundedRect(7, 2, 6, 10, 3, 3)
                            ctx.fill()

                            // Cradle: the lower half of a circle around the
                            // capsule. 0 -> PI sweeps through +y, which is
                            // downward on screen.
                            ctx.beginPath()
                            ctx.arc(10, 9.5, 5, 0, Math.PI, false)
                            ctx.stroke()

                            // Stem and base
                            ctx.beginPath()
                            ctx.moveTo(10, 14.5); ctx.lineTo(10, 17.5)
                            ctx.stroke()
                            ctx.beginPath()
                            ctx.moveTo(6.5, 17.5); ctx.lineTo(13.5, 17.5)
                            ctx.stroke()
                        }
                    }

                    // Stop (recording -> tap to finish and send)
                    Rectangle {
                        anchors.centerIn: parent
                        width: 14
                        height: 14
                        radius: 3
                        color: "#FFFFFF"
                        visible: voiceService.isRecording
                    }

                    // Tap toggles voice/video when nothing is typed; press and
                    // hold records, and the drag while holding arms cancel
                    // (left) and lock (up). All three are phases of a single
                    // press, so they share one handler - splitting them is how
                    // a tap ends up both switching mode and starting a take.
                    MouseArea {
                        id: sendMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor

                        property real pressX: 0
                        property real pressY: 0
                        property bool holding: false

                        Timer {
                            id: holdTimer
                            interval: 320
                            onTriggered: {
                                sendMouse.holding = true
                                chatViewRoot.beginCapture()
                            }
                        }

                        onPressed: function(mouse) {
                            pressX = mouse.x
                            pressY = mouse.y
                            holding = false
                            if (!sendButton.canSend && !chatViewRoot.captureLocked) holdTimer.start()
                        }

                        onPositionChanged: function(mouse) {
                            if (!holding) return
                            chatViewRoot.updateCaptureDrag(mouse.x - pressX, mouse.y - pressY)
                        }

                        onReleased: {
                            holdTimer.stop()
                            if (holding) {
                                holding = false
                                chatViewRoot.endCapture()
                            }
                        }

                        onCanceled: {
                            holdTimer.stop()
                            if (holding) {
                                holding = false
                                chatViewRoot.endCapture()
                            }
                        }

                        onClicked: {
                            if (holding) return          // the hold already handled it
                            if (chatViewRoot.captureLocked) {
                                chatViewRoot.finishLockedCapture()
                            } else if (sendButton.canSend) {
                                sendButton.trigger()
                            } else {
                                chatViewRoot.toggleCaptureMode()
                            }
                        }
                    }
                }
            }
        }
    }


    function replyPreviewForMessage(m) {
        if (!m) return ""
        var text = (m.messageText || "").toString().replace(/\n/g, " ").trim()
        if (text.length > 0) return text.substring(0, 120)
        var ct = (m.contentType || "").toString()
        if (ct === "audio") return "Voice message"
        if (ct === "video_note") return "Video message"
        if (ct === "image") return "Photo"
        if (ct === "file") return (m.fileName || "File")
        return "Message"
    }

    function beginReply(messageId) {
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageId === messageId && row.messageKind !== "system") {
                pendingReplyId = messageId
                pendingReplyName = row.isMine ? "You" : (row.senderName || "Message")
                pendingReplyPreview = replyPreviewForMessage(row)
                chatService.pendingReplyToId = messageId
                clearSelection()
                return
            }
        }
    }

    // Hide the composer quote without clearing ChatService.pendingReplyToId —
    // media uploads take the id later when POST /messages is built.
    function dismissReplyBar() {
        pendingReplyId = ""
        pendingReplyName = ""
        pendingReplyPreview = ""
    }

    function clearReply() {
        dismissReplyBar()
        chatService.pendingReplyToId = ""
    }

    // Outgoing text waiting for a live connection / successful ACK.
    property var pendingOutgoing: []

    function queueOutgoingText(localId, chatId, text, chatType, replyToId) {
        var q = chatViewRoot.pendingOutgoing.slice()
        q.push({
            localId: localId,
            chatId: chatId,
            text: text,
            chatType: chatType || "direct",
            replyToId: replyToId || "",
            inFlight: false
        })
        chatViewRoot.pendingOutgoing = q
        chatViewRoot.flushOutgoingText()
    }

    function resetOutgoingInFlight() {
        var q = chatViewRoot.pendingOutgoing.slice()
        for (var i = 0; i < q.length; i++) q[i].inFlight = false
        chatViewRoot.pendingOutgoing = q
    }

    function flushOutgoingText() {
        if (typeof websocketService === "undefined" || !websocketService) return
        if (websocketService.connectionState !== "connected") return
        var q = chatViewRoot.pendingOutgoing
        if (!q || q.length === 0) return
        var item = null
        for (var i = 0; i < q.length; i++) {
            if (!q[i].inFlight) { item = q[i]; break }
        }
        if (!item) return
        item.inFlight = true
        chatViewRoot.pendingOutgoing = q.slice()
        if (item.replyToId && item.replyToId.length > 0)
            chatService.pendingReplyToId = item.replyToId
        else
            chatService.pendingReplyToId = ""
        chatService.sendMessage(item.chatId, item.text, item.chatType)
    }

    function markOutgoingSent(chatId, message) {
        var content = message.content || message.messageText || ""
        var serverId = message.id || ""
        // Prefer matching the head of the queue for this chat.
        var q = chatViewRoot.pendingOutgoing.slice()
        var matchedLocal = ""
        for (var i = 0; i < q.length; i++) {
            if (q[i].chatId === chatId && q[i].text === content) {
                matchedLocal = q[i].localId
                q.splice(i, 1)
                break
            }
        }
        if (matchedLocal.length === 0 && q.length > 0 && q[0].chatId === chatId && q[0].inFlight) {
            matchedLocal = q[0].localId
            q.splice(0, 1)
        }
        chatViewRoot.pendingOutgoing = q

        if (chatId === chatViewRoot.currentChatId) {
            for (var j = 0; j < messagesModel.count; j++) {
                var row = messagesModel.get(j)
                if (!row.isMine || row.sendStatus !== "pending") continue
                if ((matchedLocal.length > 0 && row.messageId === matchedLocal) ||
                    (matchedLocal.length === 0 && row.messageText === content)) {
                    if (serverId.length > 0)
                        messagesModel.setProperty(j, "messageId", serverId)
                    messagesModel.setProperty(j, "sendStatus", "sent")
                    messagesModel.setProperty(j, "isRead", false)
                    break
                }
            }
        }
        chatViewRoot.flushOutgoingText()
    }

    function rehydratePendingOutgoing() {
        if (!chatViewRoot.currentChatId) return
        var q = chatViewRoot.pendingOutgoing || []
        for (var i = 0; i < q.length; i++) {
            var item = q[i]
            if (item.chatId !== chatViewRoot.currentChatId) continue
            var exists = false
            for (var j = 0; j < messagesModel.count; j++) {
                if (messagesModel.get(j).messageId === item.localId) { exists = true; break }
            }
            if (exists) continue
            item.inFlight = false
            chatViewRoot.addMessage(authService.currentUserId, "Me", item.text,
                                    chatViewRoot.formatTime(new Date().toISOString()), true, false,
                                    "", 0, false, item.localId, "", "", 0, "",
                                    "", false, 0, 1, false, "", item.replyToId || "")
        }
        chatViewRoot.pendingOutgoing = q.slice()
    }

    function jumpToMessage(messageId) {
        if (!messageId || messageId.length === 0) return
        for (var i = 0; i < messagesModel.count; i++) {
            if (messagesModel.get(i).messageId === messageId) {
                // Always flash — including when the parent is already on screen
                // and no scroll is needed.
                chatViewRoot.flashMessage(messageId)
                // Prefer Beginning so the quoted message lands near the top of
                // the viewport rather than barely scraping into Contain.
                if (messagesListView.dragging || messagesListView.flicking) {
                    messagesListView.positionViewAtIndex(i, ListView.Beginning)
                    return
                }
                var from = messagesListView.contentY
                messagesListView.positionViewAtIndex(i, ListView.Beginning)
                var to = messagesListView.contentY
                if (Math.abs(to - from) < 1) {
                    // Already positioned - nudge with Contain as a fallback so
                    // a partially off-screen row still gets revealed.
                    messagesListView.positionViewAtIndex(i, ListView.Contain)
                    to = messagesListView.contentY
                }
                if (Math.abs(to - from) < 1) return
                messagesListView.contentY = from
                revealScrollAnim.stop()
                revealScrollAnim.from = from
                revealScrollAnim.to = to
                revealScrollAnim.start()
                return
            }
        }
    }

    function resolveReplyFields(replyToId) {
        var out = { available: false, name: "", preview: "" }
        if (!replyToId || replyToId.length === 0) return out
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageId === replyToId) {
                out.available = true
                out.name = row.isMine ? "You" : (row.senderName || "Message")
                out.preview = replyPreviewForMessage(row)
                return out
            }
        }
        return out
    }

    function addMessage(senderId, senderName, text, time, isMine, isRead, fileUrl, fileDurationMs, fileEncrypted,
                         messageId, contentType, fileName, fileSize, thumbnailUrl,
                         rawContent, rawEncrypted, rawKeyVersion, rawEncryptionVersion,
                         isForwarded, forwardedFromName, replyToId, senderDeviceId) {
        const prev = messagesModel.count > 0 ? messagesModel.get(messagesModel.count - 1) : null
        const showSender = !isMine && (!prev || prev.senderId !== senderId)
        // Sampled BEFORE the append, because appending changes contentHeight
        // and so changes the answer. Skipped entirely while a history fetch is
        // populating the list - that run ends in scrollToUnreadOrEnd, which
        // owns the final position.
        const wasAtBottom = !chatViewRoot.isLoadingMore && chatViewRoot.messagesAtBottom
        messagesModel.append({
            messageKind: "message",
            messageId: messageId || "",
            senderId: senderId,
            senderName: senderName,
            messageText: text,
            // Kept so the delegate can decrypt at display time. A row painted
            // from cache before the chat list was parsed has no key yet; with
            // only the decrypted string stored, it stayed stuck on the
            // placeholder for the rest of the session.
            rawContent: rawContent || "",
            rawEncrypted: rawEncrypted === true,
            rawKeyVersion: rawKeyVersion || 0,
            rawEncryptionVersion: rawEncryptionVersion || 1,
            rawSenderDeviceId: senderDeviceId || "",
            messageTime: time,
            isMine: isMine,
            isRead: isRead === true,
            // Clock until the server ACKs; history / media with real ids are sent.
            sendStatus: (isMine && (!messageId || ("" + messageId).indexOf("local-") === 0))
                        ? "pending" : "sent",
            showSender: showSender,
            voiceUrl: fileUrl || "",
            voiceDurationMs: fileDurationMs || 0,
            voiceEncrypted: fileEncrypted === true,
            contentType: contentType || "",
            thumbnailUrl: thumbnailUrl || "",
            fileName: fileName || "",
            fileSize: fileSize || 0,
            isForwarded: isForwarded === true,
            forwardedFromName: forwardedFromName || "",
            replyToId: replyToId || "",
            // Survives delegate recycling - see the delegate binding.
            textExpanded: false
        })

        // Follow the conversation only when already at the bottom. Previously
        // nothing scrolled at all on an incoming message, so a live reply
        // landed below the fold and simply went unseen.
        if (!chatViewRoot.isLoadingMore) {
            if (wasAtBottom) {
                // Deferred a frame: a bubble's real height (especially a round
                // video, built by a Loader) is not known in this tick, so
                // scrolling now would stop short of the true bottom.
                followNewMessage.restart()
            } else {
                chatViewRoot.missedMessageCount++
            }
        }

        // Keep the sidebar snippet current while this chat is open - own
        // sends never go through onMessageReceived (they're filtered as
        // echoes), and incoming ones used to be skipped for the active chat.
        pushChatListPreview()
    }

    Timer {
        id: followNewMessage
        interval: 60
        onTriggered: chatViewRoot.smoothScrollToEnd()
    }

    // A small non-bubble line inline in the thread (e.g. a security code
    // change notice) - never sent anywhere, never cached, purely local.
    // ---- Multi-select ----
    // selectedIds is a plain array of message ids. It is reassigned rather than
    // mutated in place, because QML only re-evaluates bindings on assignment -
    // push() alone would leave every "is this row selected" binding stale.
    property bool selectionMode: false
    property string pendingReplyId: ""
    property string pendingReplyName: ""
    property string pendingReplyPreview: ""
    property var selectedIds: []

    readonly property int selectedCount: selectedIds.length

    // "Delete for everyone" is the sender's privilege, so it is only offered
    // when every selected message is mine.
    readonly property bool allSelectedAreMine: {
        if (selectedIds.length === 0) return false
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (selectedIds.indexOf(row.messageId) !== -1 && !row.isMine) return false
        }
        return true
    }

    function isSelected(messageId) {
        return selectedIds.indexOf(messageId) !== -1
    }

    function toggleSelection(messageId) {
        if (!messageId || messageId.length === 0) return
        var next = selectedIds.slice()
        var at = next.indexOf(messageId)
        if (at === -1) next.push(messageId)
        else next.splice(at, 1)
        selectedIds = next
        // Leaving the last item deselected drops out of selection mode, so the
        // chat does not stay in a modal state with nothing selected.
        if (selectedIds.length === 0) selectionMode = false
    }

    function enterSelection(messageId) {
        selectionMode = true
        selectedIds = []
        toggleSelection(messageId)
    }

    function clearSelection() {
        selectedIds = []
        selectionMode = false
    }

    function selectAll() {
        var next = []
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageKind !== "system" && row.messageId && row.messageId.length > 0) {
                next.push(row.messageId)
            }
        }
        selectedIds = next
        selectionMode = next.length > 0
    }

    // Deletes every selected message. The rows disappear as each request comes
    // back (onMessageDeleted), not optimistically - a failed delete should not
    // leave the message missing from the list but present on the server.
    function deleteSelected(forEveryone) {
        var ids = selectedIds.slice()
        clearSelection()
        for (var i = 0; i < ids.length; i++) {
            chatService.deleteMessage(chatViewRoot.currentChatId, ids[i], forEveryone)
        }
    }

    // Drops a row by message id, wherever it currently sits.
    function removeMessageById(messageId) {
        if (!messageId || messageId.length === 0) return
        for (var i = 0; i < messagesModel.count; i++) {
            if (messagesModel.get(i).messageId === messageId) {
                messagesModel.remove(i)
                break
            }
        }
        // Drop it from the selection too, or the count keeps counting rows
        // that no longer exist.
        var at = selectedIds.indexOf(messageId)
        if (at !== -1) {
            var next = selectedIds.slice()
            next.splice(at, 1)
            selectedIds = next
            if (selectedIds.length === 0) selectionMode = false
        }
        // The open chat's messagesModel is authoritative while we're looking
        // at it - the SQLite cache can still hold a now-deleted "last"
        // message (or miss ones only ever shown optimistically), so push the
        // preview from here rather than waiting on ChatService's cache path.
        pushChatListPreview()
    }

    // Newest non-system bubble → chat-list preview string.
    function previewFromMessagesModel() {
        for (var i = messagesModel.count - 1; i >= 0; i--) {
            var m = messagesModel.get(i)
            if (m.messageKind === "system") continue
            var t = m.contentType || ""
            if (t === "audio") return "🎤 Voice message"
            if (t === "image") return "📷 Photo"
            if (t === "video_note") return "📹 Video message"
            if (t === "file") return "📎 " + (m.fileName && m.fileName.length > 0 ? m.fileName : "File")
            return m.messageText || ""
        }
        return ""
    }

    function pushChatListPreview() {
        if (!chatViewRoot.currentChatId || chatViewRoot.currentChatId.length === 0) return
        if (chatService !== undefined)
            chatService.notifyChatPreview(chatViewRoot.currentChatId, previewFromMessagesModel())
    }

    function addSystemMessage(text) {
        messagesModel.append({
            messageKind: "system",
            messageId: "",
            senderId: "",
            senderName: "",
            messageText: text,
            rawContent: "",
            rawEncrypted: false,
            rawKeyVersion: 0,
            rawEncryptionVersion: 1,
            messageTime: "",
            isMine: false,
            isRead: false,
            sendStatus: "sent",
            showSender: false,
            voiceUrl: "",
            voiceDurationMs: 0,
            voiceEncrypted: false,
            contentType: "",
            thumbnailUrl: "",
            fileName: "",
            fileSize: 0,
            isForwarded: false,
            replyToId: "",
            forwardedFromName: "",
            // Unused by a system notice, but ListModel roles are per-row:
            // omitting it here would make model.textExpanded undefined on
            // these rows and warn whenever the delegate reads it.
            textExpanded: false
        })
    }

    function scrollToUnreadOrEnd(unreadIndex) {
        // Deferred one tick so the ListView has laid out the freshly-populated
        // model before we ask it to position on an index.
        Qt.callLater(function() {
            if (unreadIndex >= 0) {
                messagesListView.positionViewAtIndex(unreadIndex, ListView.Beginning)
            } else if (messagesListView.count > 0) {
                messagesListView.positionViewAtEnd()
            }
        })
    }

    function formatDuration(ms) {
        var totalSec = Math.max(0, Math.round(ms / 1000))
        var m = Math.floor(totalSec / 60)
        var s = totalSec % 60
        return m + ":" + (s < 10 ? "0" : "") + s
    }

    function formatTime(isoString) {
        if (!isoString) return ""
        var d = new Date(isoString)
        if (isNaN(d.getTime())) return ""
        return Qt.formatTime(d, "h:mm AP")
    }

    // TextEdit.copy() is the QML-side clipboard API (no C++ helper needed).
    function copyTextToClipboard(text) {
        if (!text || text.length === 0) return
        clipboardHelper.text = text
        clipboardHelper.selectAll()
        clipboardHelper.copy()
        clipboardHelper.deselect()
    }

    TextEdit {
        id: clipboardHelper
        visible: false
        width: 1
        height: 1
    }

    // One menu shared by every row - instantiating a Menu per message would
    // build hundreds of them for a long chat.
    // Styled to match ChatList's context menu - a GlassPanel background and
    // hand-built MenuItem content. The stock QuickControls2 Menu paints an
    // opaque grey system popup that ignores the theme entirely, which looks
    // pasted on over the glass surfaces everything else uses.
    property var pendingForwardIds: []

    function messageRowById(messageId) {
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageId === messageId) return row
        }
        return null
    }

    function buildForwardItems(ids) {
        var items = []
        for (var i = 0; i < ids.length; i++) {
            var row = chatViewRoot.messageRowById(ids[i])
            if (!row || row.messageKind === "system") continue
            var fromName = row.forwardedFromName && row.forwardedFromName.length > 0
                           ? row.forwardedFromName
                           : (row.isMine ? "Me" : (row.senderName || ""))
            items.push({
                messageId: row.messageId,
                contentType: row.contentType || "",
                text: row.messageText || "",
                fileUrl: row.voiceUrl || "",
                encrypted: row.voiceEncrypted === true || row.rawEncrypted === true,
                senderId: row.senderId || "",
                keyVersion: row.rawKeyVersion || 0,
                encryptionVersion: row.rawEncryptionVersion || 1,
                fileName: row.fileName || "",
                fileSize: row.fileSize || 0,
                durationMs: row.voiceDurationMs || 0,
                thumbnailUrl: row.thumbnailUrl || "",
                forwardedFromName: fromName
            })
        }
        return items
    }

    function openForwardPicker(ids) {
        if (!ids || ids.length === 0) return
        pendingForwardIds = ids.slice()
        forwardChatModel.clear()
        // Open BEFORE fetching. fetchChats() emits the cached list
        // synchronously, and the handler below is gated on the dialog being
        // visible - fetching first meant that emission arrived while the
        // dialog was still hidden and was dropped, leaving an empty picker.
        // It also covers the case where a fetch is already in flight and
        // fetchChats() returns early without emitting at all: that request
        // will emit on completion, by which point this is open.
        forwardPicker.open()
        chatService.fetchChats()
    }

    function forwardToChat(targetChatId, targetChatType) {
        var items = chatViewRoot.buildForwardItems(pendingForwardIds)
        forwardPicker.close()
        chatViewRoot.clearSelection()
        pendingForwardIds = []
        if (items.length === 0) return
        chatService.forwardMessages(chatViewRoot.currentChatId, targetChatId,
                                     targetChatType || "direct", items)
    }

    ListModel {
        id: forwardChatModel
    }

    Connections {
        target: chatService
        enabled: forwardPicker.visible
        function onChatsFetched(chats) {
            forwardChatModel.clear()
            for (var i = 0; i < chats.length; i++) {
                var c = chats[i]
                if (!c.id || c.id === chatViewRoot.currentChatId) continue
                // For a direct chat always prefer the other person's name.
                // chat.name is the generic "Direct Chat" placeholder the
                // backend stamps in at creation (handlers/message.go), so it
                // is never empty and cannot be used as an "unset" signal -
                // testing it first is why every row read "Direct Chat".
                // ChatList.qml resolves it the same way.
                var type = c.type || "direct"
                var name = c.name || ""
                if (type === "direct" && c.other_user) {
                    name = c.other_user.display_name || c.other_user.username || name
                }
                if (!name || name.length === 0) name = (type === "group") ? "Group" : "Chat"
                forwardChatModel.append({
                    chatId: c.id,
                    chatName: name,
                    chatType: type,
                    // A group's picture is its own; a direct chat's is the
                    // other person's - same rule as the sidebar.
                    avatarUrl: (type === "group")
                               ? (c.avatar_url || "")
                               : (c.other_user ? (c.other_user.avatar_url || "") : "")
                })
            }
        }
    }

    Dialog {
        id: forwardPicker
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(360, chatViewRoot.width - 40)
        height: Math.min(420, chatViewRoot.height - 40)
        padding: 16
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        title: ""

        background: GlassPanel {
            darkMode: chatViewRoot.darkMode
            radius: 12
        }

        contentItem: ColumnLayout {
            spacing: 10

            Text {
                Layout.fillWidth: true
                text: "Forward to…"
                font.pixelSize: 16
                font.bold: true
                color: chatViewRoot.textColor
            }

            Text {
                Layout.fillWidth: true
                visible: forwardChatModel.count === 0
                text: "Loading chats…"
                font.pixelSize: 13
                color: chatViewRoot.textSecondary
            }

            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: forwardChatModel
                spacing: 2

                delegate: Rectangle {
                    width: ListView.view.width
                    height: 48
                    radius: 8
                    color: fwdMouse.containsMouse
                           ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                     chatViewRoot.accentColor.b, 0.12)
                           : "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 8
                        anchors.rightMargin: 8
                        spacing: 10

                        Avatar {
                            Layout.preferredWidth: 34
                            Layout.preferredHeight: 34
                            name: model.chatName
                            avatarUrl: model.avatarUrl
                            size: 34
                        }

                        Text {
                            Layout.fillWidth: true
                            text: model.chatName
                            font.pixelSize: 14
                            color: chatViewRoot.textColor
                            elide: Text.ElideRight
                        }
                    }

                    MouseArea {
                        id: fwdMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: chatViewRoot.forwardToChat(model.chatId, model.chatType)
                    }
                }
            }

            Text {
                Layout.alignment: Qt.AlignRight
                text: "Cancel"
                font.pixelSize: 13
                color: chatViewRoot.textSecondary
                MouseArea {
                    anchors.fill: parent
                    anchors.margins: -6
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        forwardPicker.close()
                        chatViewRoot.pendingForwardIds = []
                    }
                }
            }
        }
    }

    Menu {
        id: messageMenu
        property string targetMessageId: ""
        property bool targetIsMine: false
        property string targetMessageText: ""

        padding: 6

        background: GlassPanel {
            implicitWidth: 190
            darkMode: chatViewRoot.darkMode
            radius: 10
            sheen: false
        }

        MenuItem {
            text: "Copy"
            enabled: messageMenu.targetMessageText.length > 0
            onTriggered: chatViewRoot.copyTextToClipboard(messageMenu.targetMessageText)
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                color: parent.enabled ? chatViewRoot.textColor : chatViewRoot.textSecondary
                opacity: parent.enabled ? 1.0 : 0.5
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                radius: 6
                color: (parent.hovered && parent.enabled)
                       ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                 chatViewRoot.accentColor.b, 0.14)
                       : "transparent"
            }
        }

        
        MenuItem {
            text: "Reply"
            enabled: messageMenu.targetMessageId.length > 0
            onTriggered: chatViewRoot.beginReply(messageMenu.targetMessageId)
        }
MenuItem {
            text: "Select"
            onTriggered: chatViewRoot.enterSelection(messageMenu.targetMessageId)
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                color: chatViewRoot.textColor
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                radius: 6
                color: parent.hovered
                       ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                 chatViewRoot.accentColor.b, 0.14)
                       : "transparent"
            }
        }

        MenuItem {
            text: "Forward"
            enabled: messageMenu.targetMessageId.length > 0
            onTriggered: chatViewRoot.openForwardPicker([messageMenu.targetMessageId])
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                color: parent.enabled ? chatViewRoot.textColor : chatViewRoot.textSecondary
                opacity: parent.enabled ? 1.0 : 0.5
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                radius: 6
                color: (parent.hovered && parent.enabled)
                       ? Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g,
                                 chatViewRoot.accentColor.b, 0.14)
                       : "transparent"
            }
        }

        MenuSeparator {
            contentItem: Rectangle {
                implicitHeight: 1
                color: chatViewRoot.borderColor
            }
        }

        MenuItem {
            text: "Delete for me"
            onTriggered: chatService.deleteMessage(chatViewRoot.currentChatId,
                                                    messageMenu.targetMessageId, false)
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                color: "#FF6B6B"
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                radius: 6
                color: parent.hovered ? Qt.rgba(1, 0.42, 0.42, 0.12) : "transparent"
            }
        }

        MenuItem {
            // Retracting for both sides is the sender's privilege; the server
            // enforces it too, this just avoids offering what would be refused.
            text: "Delete for everyone"
            enabled: messageMenu.targetIsMine
            onTriggered: chatService.deleteMessage(chatViewRoot.currentChatId,
                                                    messageMenu.targetMessageId, true)
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                // Dimmed rather than hidden when it does not apply, so the
                // menu keeps a stable shape between your messages and theirs.
                color: parent.enabled ? "#FF6B6B" : chatViewRoot.textSecondary
                opacity: parent.enabled ? 1.0 : 0.5
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                radius: 6
                color: (parent.hovered && parent.enabled)
                       ? Qt.rgba(1, 0.42, 0.42, 0.12) : "transparent"
            }
        }
    }

    // Loaded on demand so the camera stack is only touched when a video
    // message is actually being recorded.
    Loader {
        id: roundVideoOverlayLoader
        anchors.fill: parent
        z: 100
        active: chatViewRoot.captureActive
        sourceComponent: RoundVideoOverlay {
            darkMode: chatViewRoot.darkMode
            accentColor: chatViewRoot.myMessageBg
            dragX: chatViewRoot.captureDragX
            dragY: chatViewRoot.captureDragY
            willCancel: chatViewRoot.captureWillCancel
            willLock: chatViewRoot.captureWillLock
            isLocked: chatViewRoot.captureLocked

            onStopRequested: chatViewRoot.finishLockedCapture()
            onCancelRequested: chatViewRoot.cancelCapture()
            onPauseRequested: roundVideoService.togglePause()
            onFlipRequested: roundVideoService.switchCamera()
        }
    }

    PairingQrScanner {
        id: safetyQrScanner
        onPreviewFrame: function(image) { safetyScanPreview.present(image) }
        onCodeFound: function(code) {
            var result = chatService.compareSafetyNumberScan(chatViewRoot.currentChatId, code)
            if (result === "match") {
                chatViewRoot.safetyVerified = true
                safetyScanStatus.text = "Security codes match."
                safetyQrScanner.stop()
                safetyScanPreview.clear()
            } else if (result === "mismatch") {
                safetyScanStatus.text = "Codes do not match."
            } else {
                safetyScanStatus.text = "Not a security-code QR."
            }
        }
    }

    Dialog {
        id: safetyDialog
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(360, chatViewRoot.width - 40)
        padding: 16
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        title: ""
        onClosed: {
            safetyQrScanner.stop()
            safetyScanPreview.clear()
        }

        background: GlassPanel {
            darkMode: chatViewRoot.darkMode
            radius: 12
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                Layout.fillWidth: true
                text: "Encryption"
                font.pixelSize: 16
                font.bold: true
                color: chatViewRoot.textColor
            }
            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                text: "Compare this code with " + chatViewRoot.currentChatName + " on their device, or scan their QR. If it matches, no one in the middle has swapped keys."
                font.pixelSize: 13
                color: chatViewRoot.textSecondary
            }
            Text {
                visible: chatViewRoot.safetyVerified
                Layout.fillWidth: true
                text: "Verified on this device."
                font.pixelSize: 13
                color: chatViewRoot.accentColor
            }
            Image {
                id: safetyQrImage
                Layout.alignment: Qt.AlignHCenter
                Layout.preferredWidth: 200
                Layout.preferredHeight: 200
                fillMode: Image.PreserveAspectFit
                cache: false
            }
            Text {
                id: safetyNumberText
                Layout.fillWidth: true
                font.family: "Consolas"
                font.pixelSize: 13
                font.bold: true
                color: chatViewRoot.textColor
                text: ""
            }
            Text {
                id: safetyScanStatus
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                font.pixelSize: 12
                color: chatViewRoot.accentColor
                text: ""
            }
            VideoFrame {
                id: safetyScanPreview
                Layout.fillWidth: true
                Layout.preferredHeight: safetyQrScanner.scanning ? 180 : 0
                visible: safetyQrScanner.scanning
                fillMode: VideoFrame.Cover
            }
            RowLayout {
                Layout.alignment: Qt.AlignRight
                spacing: 16
                Text {
                    text: safetyQrScanner.scanning ? "Stop scan" : "Scan"
                    font.pixelSize: 13
                    color: chatViewRoot.accentColor
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -6
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (safetyQrScanner.scanning) {
                                safetyQrScanner.stop()
                                safetyScanPreview.clear()
                            } else {
                                safetyScanStatus.text = ""
                                safetyQrScanner.start()
                            }
                        }
                    }
                }
                Text {
                    text: "Copy"
                    font.pixelSize: 13
                    color: chatViewRoot.accentColor
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -6
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            chatViewRoot.copyTextToClipboard(safetyNumberText.text)
                            safetyDialog.close()
                        }
                    }
                }
                Text {
                    text: "Close"
                    font.pixelSize: 13
                    color: chatViewRoot.textSecondary
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -6
                        cursorShape: Qt.PointingHandCursor
                        onClicked: safetyDialog.close()
                    }
                }
            }
        }
    }

    Component.onCompleted: {
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
        }
    }
}
