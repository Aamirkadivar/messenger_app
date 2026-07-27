import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15

Rectangle {
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
    property string otherUserId: ""
    property bool isOnline: false
    property bool typingIndicator: false
    property string typingUser: ""

    // Signals
    signal sendMessage(string text)
    signal backClicked()
    signal openChatInfo(string chatId)
    signal createGroupClicked()

    property bool typingVisible: false
    property bool isLoadingMore: false

    color: bgColor

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
                chatViewRoot.addMessage(m.senderId, m.senderName, m.content, chatViewRoot.formatTime(m.createdAt), isMine, isRead)
                if (firstUnreadIndex === -1 && !isMine && (!m.readAt || m.readAt.length === 0)) {
                    firstUnreadIndex = i
                }
            }
            chatViewRoot.isLoadingMore = false
            chatViewRoot.scrollToUnreadOrEnd(firstUnreadIndex)
            chatService.markAsRead(chatId)
        }

        function onMessageError(error) {
            console.log("[ChatView] Error:", error)
            chatViewRoot.isLoadingMore = false
        }
    }

    Connections {
        target: websocketService

        function onMessageReceived(chatId, message) {
            if (chatId !== chatViewRoot.currentChatId) return
            // Our own sends are already shown optimistically when we hit send
            if (message.senderId === authService.currentUserId) return
            var text = chatService.decryptMessage(chatId, message.content, message.encrypted === true)
            chatViewRoot.addMessage(message.senderId, chatViewRoot.currentChatName, text,
                                     chatViewRoot.formatTime(message.createdAt), false, false)
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

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Chat header
        Rectangle {
            Layout.fillWidth: true
            height: 60
            color: chatViewRoot.surfaceColor

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 12

                // Back button
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    color: backMouse.containsPress ? Qt.rgba(108/255, 99/255, 255/255, 0.2) : (backMouse.containsMouse ? Qt.rgba(108/255, 99/255, 255/255, 0.1) : "transparent")
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

                // Avatar
                Item {
                    Layout.preferredWidth: 38
                    Layout.preferredHeight: 38

                    Rectangle {
                        anchors.fill: parent
                        radius: 19
                        color: chatViewRoot.accentColor

                        Text {
                            anchors.centerIn: parent
                            text: chatViewRoot.currentChatName ? chatViewRoot.currentChatName.substring(0, 1).toUpperCase() : "?"
                            font.pixelSize: 15
                            font.bold: true
                            color: "#FFFFFF"
                        }
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
                        visible: chatViewRoot.isOnline
                    }
                }

                // Name + status
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
                        text: chatViewRoot.typingIndicator ? chatViewRoot.typingUser + " is typing…" : (chatViewRoot.isOnline ? "Online" : "Offline")
                        font.pixelSize: 12
                        color: chatViewRoot.typingIndicator ? chatViewRoot.accentColor : (chatViewRoot.isOnline ? chatViewRoot.onlineColor : chatViewRoot.textSecondary)
                        elide: Text.ElideRight
                    }
                }

                // More options
                Rectangle {
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                    radius: 9
                    color: moreMouse.containsPress ? Qt.rgba(108/255, 99/255, 255/255, 0.2) : (moreMouse.containsMouse ? Qt.rgba(108/255, 99/255, 255/255, 0.1) : "transparent")
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

        Rectangle {
            Layout.fillWidth: true
            height: 1
            color: chatViewRoot.borderColor
        }

        // Messages area
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            ChatBackground {
                anchors.fill: parent
                baseColor: chatViewRoot.bgColor
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
                height: bubbleItem.height + (model.showSender ? 10 : 2)

                MessageBubble {
                    id: bubbleItem
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

        Rectangle {
            Layout.fillWidth: true
            height: 1
            color: chatViewRoot.borderColor
        }

        // Message input area
        Rectangle {
            Layout.fillWidth: true
            height: 68
            color: chatViewRoot.surfaceColor

            RowLayout {
                anchors.fill: parent
                anchors.margins: 12
                spacing: 10

                // Attachment button
                Rectangle {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40
                    radius: 10
                    color: attMouse.containsPress ? Qt.rgba(108/255, 99/255, 255/255, 0.2) : (attMouse.containsMouse ? Qt.rgba(108/255, 99/255, 255/255, 0.1) : "transparent")
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
                    }
                }

                // Message input
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 42
                    radius: 21
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

                // Send button
                Rectangle {
                    id: sendButton
                    Layout.preferredWidth: 42
                    Layout.preferredHeight: 42
                    radius: 21
                    property bool canSend: messageInput.text.trim().length > 0
                    color: !canSend ? (darkMode ? "#2A2A4A" : "#E0E0E5")
                           : sendMouse.pressed ? Qt.darker(chatViewRoot.myMessageBg, 1.15) : (sendMouse.containsMouse ? Qt.lighter(chatViewRoot.myMessageBg, 1.08) : chatViewRoot.myMessageBg)
                    Behavior on color {
                        enabled: !chatViewRoot.instantThemeActive
                        ColorAnimation { duration: 100 }
                    }

                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true, false)
                        chatService.sendMessage(chatViewRoot.currentChatId, text)
                        chatViewRoot.sendMessage(text)
                        messageInput.text = ""
                    }

                    Canvas {
                        anchors.centerIn: parent
                        width: 18
                        height: 18
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
                    MouseArea {
                        id: sendMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: sendButton.canSend ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: sendButton.trigger()
                    }
                }
            }
        }
    }

    function addMessage(senderId, senderName, text, time, isMine, isRead) {
        const prev = messagesModel.count > 0 ? messagesModel.get(messagesModel.count - 1) : null
        const showSender = !isMine && (!prev || prev.senderId !== senderId)
        messagesModel.append({
            senderId: senderId,
            senderName: senderName,
            messageText: text,
            messageTime: time,
            isMine: isMine,
            isRead: isRead === true,
            showSender: showSender
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
