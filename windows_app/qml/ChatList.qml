import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Rectangle {
    id: chatListRoot
    radius: 0

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#1A1A2E"
    property color surfaceColor: "#16213E"
    property color textColor: "#E8E8E8"
    property color textSecondary: "#8B8B9E"
    property color borderColor: "#2A2A4A"
    property color accentColor: "#6C63FF"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"

    color: bgColor

    // Signals
    signal chatSelected(string chatId, string chatName)
    signal newChatClicked()

    // Column
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 0
        spacing: 0

        // Header
        Item {
            Layout.fillWidth: true
            height: 64

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 20
                spacing: 0

                Text {
                    Layout.fillWidth: true
                    text: "Chats"
                    font.pixelSize: 24
                    font.bold: true
                    color: chatListRoot.textColor
                }
            }

            // Search button
            Button {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                width: 40
                height: 40
                background: Rectangle {
                    radius: 8
                    color: mouseArea.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.3)" : "rgba(108, 99, 255, 0.1)") : "transparent"
                }
                MouseArea {
                    id: mouseArea
                    anchors.fill: parent
                    hoverEnabled: true
                }
                Image {
                    anchors.centerIn: parent
                    source: "qrc:/icons/search.svg"
                    width: 20
                    height: 20
                }
            }
        }

        // Search bar (expanded)
        Rectangle {
            id: searchBar
            Layout.fillWidth: true
            height: searchVisible ? 48 : 0
            color: darkMode ? "rgba(22, 33, 62, 0.95)" : "rgba(255, 255, 255, 0.95)"
            opacity: searchVisible ? 1 : 0
            visible: searchVisible

            TextField {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.margins: 12
                placeholderText: "Search chats..."
                placeholderTextColor: textSecondary
                font.pixelSize: 14
                color: textColor
                background: Rectangle {
                    radius: 8
                    color: "transparent"
                }
                onTextChanged: filterChats(text)
            }
        }

        // New chat button
        Button {
            Layout.fillWidth: true
            height: 56
            contentItem: RowLayout {
                spacing: 12
                Image {
                    source: "qrc:/icons/plus.svg"
                    width: 20
                    height: 20
                }
                Text {
                    text: "New Chat"
                    font.pixelSize: 15
                    font.bold: true
                    color: "#FFFFFF"
                }
            }
            background: Rectangle {
                radius: 0
                color: mouseArea.containsPress ? Qt.darker(chatListRoot.accentColor, 1.1) : chatListRoot.accentColor
                MouseArea {
                    id: mouseArea2
                    anchors.fill: parent
                    hoverEnabled: true
                    onClicked: chatListRoot.newChatClicked()
                }
            }
        }

        // Divider
        Rectangle {
            Layout.fillWidth: true
            height: 1
            color: borderColor
        }

        // Online users section
        Rectangle {
            Layout.fillWidth: true
            height: 60
            color: darkMode ? "rgba(76, 175, 80, 0.05)" : "rgba(76, 175, 80, 0.03)"
            visible: onlineUsers.length > 0

            RowLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 12

                Text {
                    text: "Online"
                    font.pixelSize: 12
                    font.bold: true
                    color: onlineColor
                    Layout.preferredWidth: 80
                    elide: Text.ElideRight
                }

                Repeater {
                    model: onlineUsers.slice(0, 5)
                    Rectangle {
                        width: 28
                        height: 28
                        radius: 14
                        color: "#4CAF50"
                        border.color: darkMode ? bgColor : "#FFFFFF"
                        border.width: 2

                        Text {
                            anchors.centerIn: parent
                            text: modelData ? modelData.substring(0, 1).toUpperCase() : "?"
                            font.pixelSize: 11
                            font.bold: true
                            color: "#FFFFFF"
                        }

                        ToolTip {
                            text: modelData || "Online"
                            timeout: 2000
                        }
                    }
                }

                Item { Layout.fillWidth: true }

                Text {
                    text: "+" + (onlineUsers.length - 5) + " more"
                    font.pixelSize: 11
                    color: onlineColor
                    visible: onlineUsers.length > 5
                }
            }
        }

        // Chat list
        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.margins: 0
            clip: true

            Rectangle {
                width: ScrollView.contentWidth
                height: ScrollView.contentHeight
                color: "transparent"

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 0

                    Repeater {
                        id: chatRepeater
                        model: chatModel

                        Rectangle {
                            Layout.fillWidth: true
                            height: 76
                            color: mouseArea.containsMouse ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                            MouseArea {
                                id: mouseArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: chatListRoot.chatSelected(model.chatId, model.chatName)
                            }

                            RowLayout {
                                anchors.fill: parent
                                anchors.margins: 16
                                spacing: 14

                                // Avatar
                                Rectangle {
                                    id: avatarRect
                                    Layout.preferredWidth: 44
                                    Layout.preferredHeight: 44
                                    radius: 22
                                    color: model.avatarColor !== "" ? model.avatarColor : chatListRoot.accentColor

                                    // Online indicator
                                    Rectangle {
                                        anchors.bottom: parent.bottom
                                        anchors.right: parent.right
                                        anchors.margins: -2
                                        width: 12
                                        height: 12
                                        radius: 6
                                        color: model.online ? chatListRoot.onlineColor : chatListRoot.bgColor
                                        border.color: chatListRoot.onlineColor
                                        border.width: 2
                                        visible: model.online
                                    }

                                    Text {
                                        anchors.centerIn: parent
                                        text: (model.chatName && model.chatName.length > 0) ? model.chatName.substring(0, 1).toUpperCase() : "?"
                                        font.pixelSize: 18
                                        font.bold: true
                                        color: "#FFFFFF"
                                    }
                                }

                                // Chat info
                                Item {
                                    Layout.fillWidth: true
                                    height: 44

                                    Column {
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        anchors.top: parent.top
                                        spacing: 4

                                        Row {
                                            anchors.left: parent.left
                                            anchors.right: lastMsgTime.right
                                            spacing: 8
                                            Layout.fillWidth: true

                                            Text {
                                                text: model.chatName
                                                font.pixelSize: 15
                                                font.bold: true
                                                color: chatListRoot.textColor
                                                elide: Text.ElideRight
                                            }
                                        }

                                        Text {
                                            id: lastMsgTime
                                            anchors.right: parent.right
                                            anchors.rightMargin: 0
                                            text: model.lastMessage
                                            font.pixelSize: 13
                                            color: chatListRoot.textSecondary
                                            elide: Text.ElideRight
                                            width: parent.width - 20
                                        }
                                    }
                                }

                                // Unread badge & time
                                Column {
                                    anchors.right: parent.right
                                    spacing: 8

                                    Rectangle {
                                        width: 24
                                        height: 24
                                        radius: 12
                                        color: chatListRoot.accentColor
                                        visible: model.unreadCount > 0

                                        Text {
                                            anchors.centerIn: parent
                                            text: model.unreadCount > 0 ? model.unreadCount : ""
                                            font.pixelSize: 12
                                            font.bold: true
                                            color: "#FFFFFF"
                                            horizontalAlignment: Text.AlignHCenter
                                        }
                                    }

                                    Text {
                                        text: model.timestamp
                                        font.pixelSize: 11
                                        color: chatListRoot.textSecondary
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // State
    property bool searchVisible: false

    // Chat model
    ListModel {
        id: chatModel
    }

    // Online users
    property var onlineUsers: []

    // Filter function
    function filterChats(query) {
        chatModel.clear()
        const filtered = originalChats.filter(c =>
            c.chatName.toLowerCase().includes(query.toLowerCase())
        )
        for (let i = 0; i < filtered.length; i++) {
            chatModel.append(filtered[i])
        }
    }

    // Load sample data
    function loadSampleChats() {
        originalChats = [
            { chatId: "1", chatName: "Alice Johnson", lastMessage: "Hey! How are you?", timestamp: "12:30", unreadCount: 3, online: true, avatarColor: "#6C63FF" },
            { chatId: "2", chatName: "Bob Smith", lastMessage: "See you tomorrow", timestamp: "11:45", unreadCount: 0, online: true, avatarColor: "#4CAF50" },
            { chatId: "3", chatName: "Team Channel", lastMessage: "Meeting at 3pm", timestamp: "Yesterday", unreadCount: 12, online: false, avatarColor: "#FF9800" },
            { chatId: "4", chatName: "Carol Williams", lastMessage: "Thanks!", timestamp: "Monday", unreadCount: 0, online: false, avatarColor: "#E91E63" },
            { chatId: "5", chatName: "Dev Group", lastMessage: "New PR merged", timestamp: "Sunday", unreadCount: 5, online: false, avatarColor: "#9C27B0" },
        ]
        chatModel.clear()
        for (let i = 0; i < originalChats.length; i++) {
            chatModel.append(originalChats[i])
        }
    }

    property var originalChats: []

    Component.onCompleted: {
        loadSampleChats()
    }
}