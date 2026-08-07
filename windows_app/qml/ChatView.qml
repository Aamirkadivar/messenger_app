import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15
import QtQuick.Dialogs

Item {
    id: chatViewRoot

    // The Window attached type only attaches to Item-derived elements, so it
    // can't be referenced directly from inside a Behavior - resolve it once
    // here instead and have the Behaviors below read this plain property.
    property bool instantThemeActive: Window.window ? Window.window.instantTheme : false

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#15152B"
    property color surfaceColor: "#1B1B36"
    property color textColor: "#EDEDF2"
    property color textSecondary: "#9494AC"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#6C63FF"
    property color myMessageBg: "#6C63FF"
    property color theirMessageBg: "#26264A"
    property color onlineColor: "#4CAF50"
    property string currentChatId: ""
    property string currentChatName: ""
    property string currentChatType: "direct"
    property string currentChatAvatarUrl: ""
    property string otherUserId: ""
    property bool isOnline: false
    readonly property bool isGroupChat: currentChatType === "group"
    property bool typingIndicator: false
    property string typingUser: ""

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
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
            websocketService.joinChat(currentChatId)
        }
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
            for (var i = 0; i < messages.length; i++) {
                var m = messages[i]
                var isMine = m.senderId === authService.currentUserId
                var isRead = isMine && m.readAt && m.readAt.length > 0
                chatViewRoot.addMessage(m.senderId, m.senderName, m.content, chatViewRoot.formatTime(m.createdAt),
                                         isMine, isRead, m.fileUrl, m.durationMs, m.voiceEncrypted, m.id,
                                         m.fileType, m.fileName, m.fileSize, m.thumbnailUrl,
                                         m.rawContent, m.encrypted, m.keyVersion)
                if (firstUnreadIndex === -1 && !isMine && (!m.readAt || m.readAt.length === 0)) {
                    firstUnreadIndex = i
                }
            }
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
        }

        function onMessageError(error) {
            console.log("[ChatView] Error:", error)
            chatViewRoot.isLoadingMore = false
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
                                     "", message.encrypted === true, message.keyVersion || 0)
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
                                     "", message.encrypted === true, message.keyVersion || 0)
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
                                     "", message.encrypted === true, message.keyVersion || 0)
        }

        function onAttachmentUploadError(error) {
            console.log("[ChatView] Attachment upload error:", error)
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
            // Consume the same pending entry onMessagesFetched would
            // otherwise replay later (e.g. navigating away and back) -
            // it's being shown right now instead.
            chatService.takePendingSecurityNotice(chatId)
            chatViewRoot.addSystemMessage("🔒 Your security code with " + contactName + " changed.")
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
            // Our own sends are already shown optimistically when we hit send
            if (message.senderId === authService.currentUserId) return
            var hasFile = (message.fileType === "audio" || message.fileType === "image"
                           || message.fileType === "file" || message.fileType === "video_note")
                          && message.fileUrl && message.fileUrl.length > 0
            var text = hasFile ? "" : chatService.decryptMessage(chatId, message.content, message.encrypted === true,
                                                                  message.senderId, message.keyVersion || 0)
            chatViewRoot.addMessage(message.senderId, chatViewRoot.currentChatName, text,
                                     chatViewRoot.formatTime(message.createdAt), false, false,
                                     hasFile ? message.fileUrl : "", message.durationMs,
                                     hasFile && message.encrypted === true, message.id,
                                     message.fileType, message.fileName, message.fileSize,
                                     message.thumbnailUrl || "",
                                     hasFile ? "" : message.content,
                                     message.encrypted === true, message.keyVersion || 0)
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
                if (messagesModel.get(i).isMine) {
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
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Chat header
        GlassPanel {
            Layout.fillWidth: true
            height: 60
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

                    Text {
                        anchors.centerIn: parent
                        text: "📞"
                        font.pixelSize: 16
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
                secondaryGlow: Qt.darker(chatViewRoot.accentColor, 1.6)
                intensity: chatViewRoot.darkMode ? 0.7 : 0.4
            }

            ChatBackground {
                anchors.fill: parent
                baseColor: chatViewRoot.bgColor
                baseOpacity: 0
                patternColor: chatViewRoot.accentColor
                // The same alpha reads much fainter against a pale background
                // than a near-black one, so light mode needs a bit more to
                // land at the same visual weight (same lesson as the hover
                // highlights and input pills earlier).
                patternOpacity: chatViewRoot.darkMode ? 0.05 : 0.09
            }

        ListView {
            id: messagesListView
            anchors.fill: parent
            clip: true
            spacing: 2
            model: messagesModel
            ScrollBar.vertical: ScrollBar {}

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
                width: messagesListView.width
                height: model.messageKind === "system"
                        ? systemNotice.implicitHeight + 16
                        : bubbleItem.height + (model.showSender ? 10 : 2)

                // A tiny centered line, not a real bubble - matches how
                // WhatsApp shows "security code changed" and similar events
                // inline without making them look like something either
                // person actually sent.
                Text {
                    id: systemNotice
                    visible: model.messageKind === "system"
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.top: parent.top
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
                        messageMenu.popup()
                    }
                    onVideoPlaybackStarted: {
                        chatViewRoot.smoothRevealIndex(index)
                        revealAfterGrow.restart()
                    }
                    Timer {
                        id: revealAfterGrow
                        // Fires once the grow animation has settled; the first
                        // reveal only knows the pre-growth size.
                        interval: 260
                        onTriggered: chatViewRoot.smoothRevealIndex(index)
                    }
                    x: 16
                    y: model.showSender ? 10 : 2
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
                                                          model.rawKeyVersion)
                    }
                    messageTime: model.messageTime
                    isMine: model.isMine
                    isRead: model.isRead
                    senderName: model.senderName
                    showSender: model.showSender
                    myMessageBg: chatViewRoot.myMessageBg
                    theirMessageBg: chatViewRoot.theirMessageBg
                    isEncrypted: true
                    accentColor: chatViewRoot.accentColor
                    messageId: model.messageId
                    senderId: model.senderId
                    keyVersion: model.rawKeyVersion || 0
                    voiceUrl: model.voiceUrl
                    voiceDurationMs: model.voiceDurationMs
                    voiceEncrypted: model.voiceEncrypted
                    contentType: model.contentType
                    thumbnailUrl: model.thumbnailUrl
                    fileName: model.fileName
                    fileSize: model.fileSize
                    chatId: chatViewRoot.currentChatId
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
        }

        // Message input area
        GlassPanel {
            Layout.fillWidth: true
            height: 68
            radius: 0
            sheen: false
            darkMode: chatViewRoot.darkMode

            RowLayout {
                anchors.fill: parent
                anchors.margins: 12
                spacing: 10

                // Attachment button
                Rectangle {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40
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
                    Layout.preferredHeight: Math.min(120, Math.max(42, messageInput.contentHeight + 22))
                    radius: 21
                    visible: !voiceService.isRecording
                    // Was a "rgba(r,g,b,a)" string literal - that syntax silently
                    // drops the alpha channel in this Qt build (always resolves
                    // fully opaque), which is why this rendered solid black in
                    // light mode instead of a subtle tint. Qt.rgba() is reliable.
                    color: darkMode ? Qt.rgba(1, 1, 1, 0.05) : Qt.rgba(43/255, 36/255, 24/255, 0.06)
                    border.color: messageInput.activeFocus ? chatViewRoot.accentColor : "transparent"
                    border.width: 1.5
                    Behavior on border.color { ColorAnimation { duration: 100 } }

                    TextArea {
                        id: messageInput
                        anchors.fill: parent
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
                                    // Let TextArea insert the newline.
                                    return
                                }
                                event.accepted = true
                                sendButton.trigger()
                            }
                        }
                    }
                }

                // Recording indicator - replaces the text field while a voice
                // note is being recorded.
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 42
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
                    radius: 21
                    property bool canSend: messageInput.text.trim().length > 0
                    color: voiceService.isRecording ? "#E74C3C"
                           : !canSend ? (sendMouse.containsMouse ? (darkMode ? "#33335A" : "#D6D6DC") : (darkMode ? "#2A2A4A" : "#E0E0E5"))
                           : sendMouse.pressed ? Qt.darker(chatViewRoot.myMessageBg, 1.15) : (sendMouse.containsMouse ? Qt.lighter(chatViewRoot.myMessageBg, 1.08) : chatViewRoot.myMessageBg)
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true, false)
                        chatService.sendMessage(chatViewRoot.currentChatId, text, chatViewRoot.currentChatType)
                        chatViewRoot.sendMessage(text)
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

    function addMessage(senderId, senderName, text, time, isMine, isRead, fileUrl, fileDurationMs, fileEncrypted,
                         messageId, contentType, fileName, fileSize, thumbnailUrl,
                         rawContent, rawEncrypted, rawKeyVersion) {
        const prev = messagesModel.count > 0 ? messagesModel.get(messagesModel.count - 1) : null
        const showSender = !isMine && (!prev || prev.senderId !== senderId)
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
            messageTime: time,
            isMine: isMine,
            isRead: isRead === true,
            showSender: showSender,
            voiceUrl: fileUrl || "",
            voiceDurationMs: fileDurationMs || 0,
            voiceEncrypted: fileEncrypted === true,
            contentType: contentType || "",
            thumbnailUrl: thumbnailUrl || "",
            fileName: fileName || "",
            fileSize: fileSize || 0
        })
        // Keep the sidebar snippet current while this chat is open - own
        // sends never go through onMessageReceived (they're filtered as
        // echoes), and incoming ones used to be skipped for the active chat.
        pushChatListPreview()
    }

    // A small non-bubble line inline in the thread (e.g. a security code
    // change notice) - never sent anywhere, never cached, purely local.
    // ---- Multi-select ----
    // selectedIds is a plain array of message ids. It is reassigned rather than
    // mutated in place, because QML only re-evaluates bindings on assignment -
    // push() alone would leave every "is this row selected" binding stale.
    property bool selectionMode: false
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
            messageTime: "",
            isMine: false,
            isRead: false,
            showSender: false,
            voiceUrl: "",
            voiceDurationMs: 0,
            voiceEncrypted: false,
            contentType: "",
            thumbnailUrl: "",
            fileName: "",
            fileSize: 0
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

    // One menu shared by every row - instantiating a Menu per message would
    // build hundreds of them for a long chat.
    // Styled to match ChatList's context menu - a GlassPanel background and
    // hand-built MenuItem content. The stock QuickControls2 Menu paints an
    // opaque grey system popup that ignores the theme entirely, which looks
    // pasted on over the glass surfaces everything else uses.
    Menu {
        id: messageMenu
        property string targetMessageId: ""
        property bool targetIsMine: false

        padding: 6

        background: GlassPanel {
            implicitWidth: 190
            darkMode: chatViewRoot.darkMode
            radius: 10
            sheen: false
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

    Component.onCompleted: {
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
        }
    }
}
