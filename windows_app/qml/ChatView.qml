import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Rectangle {
    id: chatViewRoot

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
    property bool isOnline: true
    property bool typingIndicator: false
    property string typingUser: ""

    // Signals
    signal sendMessage(string text)
    signal backClicked()
    signal openChatInfo(string chatId)
    signal createGroupClicked()

    property bool typingVisible: false
    property bool isLoadingMore: false
    property string joinedChatId: ""

    color: bgColor

    onCurrentChatIdChanged: {
        if (joinedChatId && joinedChatId.length > 0 && joinedChatId !== currentChatId) {
            websocketService.leaveChat(joinedChatId)
        }
        messagesModel.clear()
        if (currentChatId && currentChatId.length > 0) {
            isLoadingMore = true
            chatService.fetchMessages(currentChatId)
            chatService.markAsRead(currentChatId)
            websocketService.joinChat(currentChatId)
            joinedChatId = currentChatId
        } else {
            joinedChatId = ""
        }
    }

    Connections {
        target: chatService

        function onMessagesFetched(chatId, messages) {
            if (chatId !== chatViewRoot.currentChatId) return
            messagesModel.clear()
            for (var i = 0; i < messages.length; i++) {
                var m = messages[i]
                var isMine = m.senderId === authService.currentUserId
                chatViewRoot.addMessage(m.senderId, m.senderName, m.content, chatViewRoot.formatTime(m.createdAt), isMine)
            }
            chatViewRoot.isLoadingMore = false
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
                                     chatViewRoot.formatTime(message.createdAt), false)
        }

        function onConnected() {
            if (chatViewRoot.currentChatId && chatViewRoot.currentChatId.length > 0) {
                websocketService.joinChat(chatViewRoot.currentChatId)
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
                    Behavior on color { ColorAnimation { duration: 100 } }

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
                    Behavior on color { ColorAnimation { duration: 100 } }

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
        ScrollView {
            id: messagesScroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            ColumnLayout {
                width: messagesScroll.width
                spacing: 2

                Item { Layout.fillHeight: true }

                BusyIndicator {
                    Layout.alignment: Qt.AlignHCenter
                    running: chatViewRoot.isLoadingMore
                    visible: chatViewRoot.isLoadingMore
                    width: 24
                    height: 24
                }

                Repeater {
                    id: messageRepeater
                    model: messagesModel

                    MessageBubble {
                        Layout.fillWidth: true
                        Layout.leftMargin: 16
                        Layout.rightMargin: 16
                        Layout.topMargin: model.showSender ? 10 : 2
                        darkMode: chatViewRoot.darkMode
                        messageText: model.messageText
                        messageTime: model.messageTime
                        isMine: model.isMine
                        senderName: model.senderName
                        showSender: model.showSender
                        myMessageBg: chatViewRoot.myMessageBg
                        theirMessageBg: chatViewRoot.theirMessageBg
                        isEncrypted: true
                    }
                }

                // Typing indicator
                RowLayout {
                    Layout.leftMargin: 16
                    Layout.topMargin: 4
                    Layout.bottomMargin: 8
                    visible: chatViewRoot.typingVisible
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
                    Behavior on color { ColorAnimation { duration: 100 } }

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
                    color: darkMode ? "rgba(255,255,255,0.05)" : "rgba(0,0,0,0.03)"
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
                    Behavior on color { ColorAnimation { duration: 100 } }

                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true)
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

    function addMessage(senderId, senderName, text, time, isMine) {
        const prev = messagesModel.count > 0 ? messagesModel.get(messagesModel.count - 1) : null
        const showSender = !isMine && (!prev || prev.senderId !== senderId)
        messagesModel.append({
            senderId: senderId,
            senderName: senderName,
            messageText: text,
            messageTime: time,
            isMine: isMine,
            showSender: showSender
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
            chatService.markAsRead(currentChatId)
        }
    }
}
