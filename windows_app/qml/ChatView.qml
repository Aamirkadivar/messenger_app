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
                                         m.fileType, m.fileName, m.fileSize)
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
                                     message.encrypted === true, message.id, "audio", "", 0)
        }

        function onVoiceUploadError(error) {
            console.log("[ChatView] Voice upload error:", error)
        }

        // Same reasoning as onVoiceMessageSent: shown once the server has
        // assigned a real message id, needed to correlate with
        // prepareAttachment/attachmentReady.
        function onAttachmentMessageSent(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            chatViewRoot.addMessage(authService.currentUserId, "Me", "", chatViewRoot.formatTime(message.createdAt),
                                     true, false, message.fileUrl, 0,
                                     message.encrypted === true, message.id,
                                     message.fileType, message.fileName, message.fileSize)
        }

        function onAttachmentUploadError(error) {
            console.log("[ChatView] Attachment upload error:", error)
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
            var hasFile = (message.fileType === "audio" || message.fileType === "image" || message.fileType === "file")
                          && message.fileUrl && message.fileUrl.length > 0
            var text = hasFile ? "" : chatService.decryptMessage(chatId, message.content, message.encrypted === true,
                                                                  message.senderId, message.keyVersion || 0)
            chatViewRoot.addMessage(message.senderId, chatViewRoot.currentChatName, text,
                                     chatViewRoot.formatTime(message.createdAt), false, false,
                                     hasFile ? message.fileUrl : "", message.durationMs,
                                     hasFile && message.encrypted === true, message.id,
                                     message.fileType, message.fileName, message.fileSize)
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

                // Call - direct chats only (group calling isn't supported yet).
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    visible: !chatViewRoot.isGroupChat && chatViewRoot.currentChatId.length > 0
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
                        onClicked: callService.startOutgoingCall(chatViewRoot.currentChatId, chatViewRoot.otherUserId, chatViewRoot.currentChatName)
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
                    x: 16
                    y: model.showSender ? 10 : 2
                    width: parent.width - 32
                    darkMode: chatViewRoot.darkMode
                    messageText: model.messageText
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
                    voiceUrl: model.voiceUrl
                    voiceDurationMs: model.voiceDurationMs
                    voiceEncrypted: model.voiceEncrypted
                    contentType: model.contentType
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
                        anchors.centerIn: parent
                        width: 17
                        height: 17
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.6
                            ctx.lineCap = "round"
                            ctx.beginPath()
                            ctx.moveTo(11, 3)
                            ctx.lineTo(4.5, 9.5)
                            ctx.arc(6, 11, 2.2, Math.PI * 1.1, Math.PI * 2.4, false)
                            ctx.lineTo(13, 6)
                            ctx.arcTo(15, 4, 13, 2, 2)
                            ctx.lineTo(6, 9)
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

                // Message input
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 42
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

                    TextField {
                        id: messageInput
                        anchors.fill: parent
                        leftPadding: 16
                        rightPadding: 16
                        verticalAlignment: TextInput.AlignVCenter
                        background: Item {}
                        placeholderText: "Type a message…"
                        placeholderTextColor: chatViewRoot.textSecondary
                        font.pixelSize: 14
                        color: chatViewRoot.textColor
                        selectByMouse: true
                        wrapMode: TextInput.Wrap
                        Keys.onReturnPressed: sendButton.trigger()
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

                    // Mic icon (idle, no text typed)
                    Canvas {
                        anchors.centerIn: parent
                        width: 16
                        height: 16
                        visible: !voiceService.isRecording && !sendButton.canSend
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = chatViewRoot.textSecondary
                            ctx.strokeStyle = chatViewRoot.textSecondary
                            ctx.lineWidth = 1.4
                            ctx.lineCap = "round"
                            // Capsule body
                            ctx.beginPath()
                            ctx.moveTo(8, 1)
                            ctx.arcTo(11, 1, 11, 4, 3)
                            ctx.lineTo(11, 8)
                            ctx.arcTo(11, 11, 8, 11, 3)
                            ctx.arcTo(5, 11, 5, 8, 3)
                            ctx.lineTo(5, 4)
                            ctx.arcTo(5, 1, 8, 1, 3)
                            ctx.closePath()
                            ctx.fill()
                            // Stand
                            ctx.beginPath(); ctx.moveTo(8, 11); ctx.lineTo(8, 15); ctx.stroke()
                            ctx.beginPath(); ctx.moveTo(4, 15); ctx.lineTo(12, 15); ctx.stroke()
                            ctx.beginPath()
                            ctx.arc(8, 8.5, 5.5, Math.PI * 0.15, Math.PI * 0.85, false)
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

                    MouseArea {
                        id: sendMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (voiceService.isRecording) {
                                voiceService.stopRecording()
                            } else if (sendButton.canSend) {
                                sendButton.trigger()
                            } else {
                                voiceService.startRecording()
                            }
                        }
                    }
                }
            }
        }
    }

    function addMessage(senderId, senderName, text, time, isMine, isRead, fileUrl, fileDurationMs, fileEncrypted,
                         messageId, contentType, fileName, fileSize) {
        const prev = messagesModel.count > 0 ? messagesModel.get(messagesModel.count - 1) : null
        const showSender = !isMine && (!prev || prev.senderId !== senderId)
        messagesModel.append({
            messageKind: "message",
            messageId: messageId || "",
            senderId: senderId,
            senderName: senderName,
            messageText: text,
            messageTime: time,
            isMine: isMine,
            isRead: isRead === true,
            showSender: showSender,
            voiceUrl: fileUrl || "",
            voiceDurationMs: fileDurationMs || 0,
            voiceEncrypted: fileEncrypted === true,
            contentType: contentType || "",
            fileName: fileName || "",
            fileSize: fileSize || 0
        })
    }

    // A small non-bubble line inline in the thread (e.g. a security code
    // change notice) - never sent anywhere, never cached, purely local.
    function addSystemMessage(text) {
        messagesModel.append({
            messageKind: "system",
            messageId: "",
            senderId: "",
            senderName: "",
            messageText: text,
            messageTime: "",
            isMine: false,
            isRead: false,
            showSender: false,
            voiceUrl: "",
            voiceDurationMs: 0,
            voiceEncrypted: false,
            contentType: "",
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

    Component.onCompleted: {
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
        }
    }
}
