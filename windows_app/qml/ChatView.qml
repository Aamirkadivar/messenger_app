import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Rectangle {
    id: chatViewRoot
    radius: 0

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#1A1A2E"
    property color surfaceColor: "#16213E"
    property color textColor: "#E8E8E8"
    property color textSecondary: "#8B8B9E"
    property color borderColor: "#2A2A4A"
    property color accentColor: "#6C63FF"
    property color myMessageBg: "#6C63FF"
    property color theirMessageBg: "#2A2A4A"
    property color onlineColor: "#4CAF50"
    property string currentChatId: ""
    property string currentChatName: ""
    property bool typingIndicator: false
    property string typingUser: ""

    // Signals
    signal sendMessage(string text)
    signal backClicked()
    signal openChatInfo(string chatId)
    signal createGroupClicked()

    // Component properties
    property var messages: []
    property bool isSearching: false

    color: bgColor

    // Main column
    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Chat header
        Rectangle {
            Layout.fillWidth: true
            height: 64
            color: darkMode ? "rgba(22, 33, 62, 0.95)" : "rgba(255, 255, 255, 0.95)"
            border.color: borderColor
            border.width: 0

            RowLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 12

                // Back button
                Button {
                    width: 40
                    height: 40
                    background: Rectangle {
                        radius: 8
                        color: mouseArea.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.3)" : "rgba(108, 99, 255, 0.1)") : "transparent"
                        MouseArea { id: mouseArea; anchors.fill: parent; hoverEnabled: true; onClicked: chatViewRoot.backClicked() }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/back.svg"
                        width: 20
                        height: 20
                    }
                }

                // User avatar
                Rectangle {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40
                    radius: 20
                    color: chatViewRoot.accentColor

                    Text {
                        anchors.centerIn: parent
                        text: chatViewRoot.currentChatName ? chatViewRoot.currentChatName.substring(0, 1).toUpperCase() : "?"
                        font.pixelSize: 16
                        font.bold: true
                        color: "#FFFFFF"
                    }

                    // Online indicator
                    Rectangle {
                        anchors.bottom: parent.bottom
                        anchors.right: parent.right
                        anchors.margins: -2
                        width: 10
                        height: 10
                        radius: 5
                        color: chatViewRoot.onlineColor
                        border.color: darkMode ? "#16213E" : "#FFFFFF"
                        border.width: 2
                        visible: modelOnline
                    }
                }

                // User info
                ColumnLayout {
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2
                    Layout.fillWidth: true

                    Text {
                        text: chatViewRoot.currentChatName || "Unknown"
                        font.pixelSize: 16
                        font.bold: true
                        color: chatViewRoot.textColor
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }

                    Text {
                        id: statusText
                        text: chatViewRoot.typingIndicator ? chatViewRoot.typingUser + " is typing..." : (modelOnline ? "Online" : "Offline")
                        font.pixelSize: 12
                        color: chatViewRoot.typingIndicator ? chatViewRoot.onlineColor : (modelOnline ? chatViewRoot.onlineColor : chatViewRoot.textSecondary)
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }
                }

                // More options
                Button {
                    width: 40
                    height: 40
                    background: Rectangle {
                        radius: 8
                        color: mouseArea.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.3)" : "rgba(108, 99, 255, 0.1)") : "transparent"
                        MouseArea { id: mouseArea2; anchors.fill: parent; hoverEnabled: true }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/more.svg"
                        width: 20
                        height: 20
                    }
                }
            }
        }

        // Messages area
        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            anchors.margins: 0
            ScrollBar.policy: ScrollBar.AsNeeded

            Rectangle {
                id: messagesContainer
                width: ScrollView.contentWidth
                height: ScrollView.contentHeight
                color: "transparent"

                Column {
                    id: messagesColumn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    spacing: 4

                    // Reverse model to show oldest at top
                    Repeater {
                        id: messageRepeater
                        model: Qt.listModelToView(messagesViewRoot.reverseModel)

                        MessageBubble {
                            width: messagesContainer.width - 32
                            messageText: model.messageText
                            messageTime: model.messageTime
                            isMine: model.isMine
                            senderName: model.senderName
                            isEncrypted: true
                        }
                    }

                    // Typing indicator
                    Item {
                        width: messagesContainer.width
                        height: typingVisible ? 40 : 0
                        visible: typingVisible

                        Row {
                            anchors.centerIn: parent
                            spacing: 8
                            visible: typingVisible

                            Rectangle {
                                width: 8; height: 8; radius: 4
                                color: chatViewRoot.textSecondary
                                opacity: 0.6
                                NumberAnimation {
                                    target: parent
                                    property: "opacity"
                                    to: 1; duration: 600
                                    running: true; loops: Animation.Infinite
                                }
                            }
                            Rectangle {
                                width: 8; height: 8; radius: 4
                                color: chatViewRoot.textSecondary
                                opacity: 0.6
                                NumberAnimation {
                                    target: parent
                                    property: "opacity"
                                    to: 1; duration: 600; start: 200
                                    running: true; loops: Animation.Infinite
                                }
                            }
                            Rectangle {
                                width: 8; height: 8; radius: 4
                                color: chatViewRoot.textSecondary
                                opacity: 0.6
                                NumberAnimation {
                                    target: parent
                                    property: "opacity"
                                    to: 1; duration: 600; start: 400
                                    running: true; loops: Animation.Infinite
                                }
                            }
                        }
                    }

                    // Loading indicator for pagination
                    BusyIndicator {
                        anchors.centerIn: parent
                        running: isLoadingMore
                        visible: isLoadingMore
                        width: 24
                        height: 24
                    }
                }
            }
        }

        // Message input area
        Rectangle {
            Layout.fillWidth: true
            height: 60
            color: darkMode ? "rgba(22, 33, 62, 0.95)" : "rgba(255, 255, 255, 0.95)"
            border.top.color: borderColor
            border.top.width: 1

            RowLayout {
                anchors.fill: parent
                anchors.margins: 8
                spacing: 8

                // Attachment button
                Button {
                    width: 40
                    height: 40
                    background: Rectangle {
                        radius: 8
                        color: attMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.3)" : "rgba(108, 99, 255, 0.1)") : "transparent"
                        MouseArea { id: attMouse; anchors.fill: parent; hoverEnabled: true }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/attach.svg"
                        width: 20
                        height: 20
                    }
                }

                // Message input
                TextField {
                    id: messageInput
                    Layout.fillWidth: true
                    Layout.preferredHeight: 40
                    placeholderText: "Type a message..."
                    placeholderTextColor: textSecondary
                    font.pixelSize: 14
                    color: textColor
                    background: Rectangle {
                        radius: 20
                        color: darkMode ? "rgba(42, 42, 74, 0.5)" : "rgba(240, 240, 245, 1)"
                        border.color: messageInput.focus ? accentColor : "transparent"
                        border.width: 1
                    }
                    Keys.onReturnPressed: {
                        if (Keys.shiftModifier) {
                            messageInput.text += "\n"
                        } else {
                            sendButton.clicked()
                        }
                    }
                    onTextChanged: {
                        sendButton.enabled = text.trim().length > 0
                    }
                }

                // Send button
                Button {
                    id: sendButton
                    width: 44
                    height: 44
                    enabled: messageInput.text.trim().length > 0
                    background: Rectangle {
                        radius: 22
                        color: sendButton.enabled ? chatViewRoot.myMessageBg : (darkMode ? "#2A2A4A" : "#E0E0E5")
                        NumberAnimation {
                            target: sendButton
                            property: "opacity"
                            to: 0.8; duration: 100
                            running: sendButton.enabled
                        }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/send.svg"
                        width: 20
                        height: 20
                    }
                    onClicked: {
                        chatViewRoot.sendMessage(messageInput.text.trim())
                        messageInput.text = ""
                    }
                }
            }
        }
    }

    // State
    property bool typingVisible: false
    property bool isLoadingMore: false

    // Message model
    ListModel {
        id: messagesViewRoot
        property var reverseModel: []

        function addMessage(senderId, senderName, text, time, isMine) {
            append({
                senderId: senderId,
                senderName: senderName,
                messageText: text,
                messageTime: time,
                isMine: isMine,
                encrypted: true
            })
        }

        function loadSampleMessages() {
            clear()
            const samples = [
                { senderId: "1", senderName: "Alice", text: "Hey there!", time: "10:00 AM", isMine: false },
                { senderId: "me", senderName: "Me", text: "Hi Alice! How are you?", time: "10:02 AM", isMine: true },
                { senderId: "1", senderName: "Alice", text: "I'm good! Did you see the new feature?", time: "10:03 AM", isMine: false },
                { senderId: "me", senderName: "Me", text: "Yes, it looks amazing! 🎉", time: "10:05 AM", isMine: true },
                { senderId: "1", senderName: "Alice", text: "The encryption is top-notch", time: "10:06 AM", isMine: false },
                { senderId: "1", senderName: "Alice", text: "Finally a secure messaging app!", time: "10:06 AM", isMine: false },
            ]
            for (let i = 0; i < samples.length; i++) {
                append(samples[i])
            }
        }
    }

    // Load sample data
    Component.onCompleted: {
        messagesViewRoot.loadSampleMessages()
    }

    // Scroll to bottom when messages change
    function scrollToBottom() {
        // ScrollView doesn't have direct scroll-to-bottom in QtQuick
        // This would be implemented with a custom component
    }
}
