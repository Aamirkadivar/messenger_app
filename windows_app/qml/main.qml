import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15

ApplicationWindow {
    id: appRoot
    width: 1200
    height: 800
    minimumWidth: 900
    minimumHeight: 600
    visible: true
    title: "Messenger"
    visibility: "Maximized"

    property bool darkMode: true
    property string accentColor: "#6C63FF"
    property bool isLoggedIn: authService.isLoggedIn

    // Colors
    property color bgColor: darkMode ? "#15152B" : "#F5F5F7"
    property color surfaceColor: darkMode ? "#1B1B36" : "#FFFFFF"
    property color surfaceColorHover: darkMode ? "#22224A" : "#F0F0F2"
    property color primaryColor: "#6C63FF"
    property color primaryColorDark: "#5A52D5"
    property color textColor: darkMode ? "#EDEDF2" : "#1A1A2E"
    property color textSecondary: darkMode ? "#9494AC" : "#6B6B7B"
    property color borderColor: darkMode ? Qt.rgba(1, 1, 1, 0.08) : "#E0E0E5"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"

    flags: Qt.FramelessWindowHint | Qt.Window

    Rectangle {
        anchors.fill: parent
        color: appRoot.bgColor
    }

    Item {
        anchors.fill: parent

        // Login/Register screens
        Loader {
            id: authLoader
            anchors.fill: parent
            source: isLoggedIn ? "" : "qrc:/qml/Login.qml"
        }

        // Main app (when logged in)
        ColumnLayout {
            anchors.fill: parent
            spacing: 0
            visible: isLoggedIn

            // Custom title bar
            Rectangle {
                Layout.fillWidth: true
                height: 44
                color: appRoot.surfaceColor
                z: 10

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    spacing: 8

                    Rectangle {
                        Layout.preferredWidth: 22
                        Layout.preferredHeight: 22
                        radius: 6
                        color: appRoot.primaryColor

                        Canvas {
                            anchors.centerIn: parent
                            width: 13
                            height: 13
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                var w = width, h = height * 0.72, r = 3
                                ctx.fillStyle = "#FFFFFF"
                                ctx.beginPath()
                                ctx.moveTo(r, 0); ctx.lineTo(w - r, 0); ctx.arcTo(w, 0, w, r, r)
                                ctx.lineTo(w, h - r); ctx.arcTo(w, h, w - r, h, r)
                                ctx.lineTo(w * 0.32, h); ctx.lineTo(w * 0.18, h + height * 0.2); ctx.lineTo(w * 0.22, h)
                                ctx.lineTo(r, h); ctx.arcTo(0, h, 0, h - r, r)
                                ctx.lineTo(0, r); ctx.arcTo(0, 0, r, 0, r)
                                ctx.closePath(); ctx.fill()
                            }
                        }
                    }

                    Text {
                        text: "Messenger"
                        font.pixelSize: 13
                        font.bold: true
                        color: appRoot.textColor
                    }

                    Item { Layout.fillWidth: true }

                    // Logout
                    Rectangle {
                        Layout.preferredWidth: 36
                        Layout.preferredHeight: 36
                        radius: 8
                        visible: appRoot.isLoggedIn
                        color: logoutMouse.containsPress ? appRoot.borderColor : (logoutMouse.containsMouse ? appRoot.surfaceColorHover : "transparent")
                        Behavior on color { ColorAnimation { duration: 100 } }

                        Canvas {
                            anchors.centerIn: parent
                            width: 16
                            height: 16
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = logoutMouse.containsMouse ? "#E74C3C" : appRoot.textSecondary
                                ctx.lineWidth = 1.5
                                ctx.lineCap = "round"
                                ctx.lineJoin = "round"
                                // door / frame
                                ctx.beginPath()
                                ctx.moveTo(8.5, 2); ctx.lineTo(3, 2); ctx.lineTo(3, 14); ctx.lineTo(8.5, 14)
                                ctx.stroke()
                                // arrow out
                                ctx.beginPath(); ctx.moveTo(7, 8); ctx.lineTo(15, 8); ctx.stroke()
                                ctx.beginPath()
                                ctx.moveTo(12, 5); ctx.lineTo(15, 8); ctx.lineTo(12, 11)
                                ctx.stroke()
                            }
                        }

                        ToolTip.visible: logoutMouse.containsMouse
                        ToolTip.text: "Log out"
                        ToolTip.delay: 400

                        MouseArea {
                            id: logoutMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: logoutDialog.open()
                        }
                    }

                    // Theme toggle
                    Rectangle {
                        Layout.preferredWidth: 36
                        Layout.preferredHeight: 36
                        radius: 8
                        color: themeMouse.containsPress ? appRoot.borderColor : (themeMouse.containsMouse ? appRoot.surfaceColorHover : "transparent")
                        Behavior on color { ColorAnimation { duration: 100 } }

                        Canvas {
                            anchors.centerIn: parent
                            width: 16
                            height: 16
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = appRoot.textSecondary
                                ctx.fillStyle = appRoot.textSecondary
                                ctx.lineWidth = 1.4
                                if (appRoot.darkMode) {
                                    // sun
                                    ctx.beginPath(); ctx.arc(8, 8, 3.2, 0, Math.PI * 2); ctx.fill()
                                    for (var i = 0; i < 8; i++) {
                                        var a = i * Math.PI / 4
                                        var x1 = 8 + Math.cos(a) * 5.5, y1 = 8 + Math.sin(a) * 5.5
                                        var x2 = 8 + Math.cos(a) * 7.2, y2 = 8 + Math.sin(a) * 7.2
                                        ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke()
                                    }
                                } else {
                                    // moon
                                    ctx.beginPath()
                                    ctx.arc(8, 8, 6, 0, Math.PI * 2)
                                    ctx.fill()
                                    ctx.globalCompositeOperation = "destination-out"
                                    ctx.beginPath()
                                    ctx.arc(11, 5.5, 5.2, 0, Math.PI * 2)
                                    ctx.fill()
                                }
                            }
                        }
                        MouseArea {
                            id: themeMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: appRoot.darkMode = !appRoot.darkMode
                        }
                    }

                    // Minimize
                    Rectangle {
                        Layout.preferredWidth: 36
                        Layout.preferredHeight: 36
                        radius: 8
                        color: minMouse.containsPress ? appRoot.borderColor : (minMouse.containsMouse ? appRoot.surfaceColorHover : "transparent")
                        Behavior on color { ColorAnimation { duration: 100 } }

                        Rectangle {
                            anchors.centerIn: parent
                            width: 10
                            height: 1.4
                            color: appRoot.textSecondary
                        }
                        MouseArea {
                            id: minMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: appRoot.visibility = Window.Minimized
                        }
                    }

                    // Close
                    Rectangle {
                        Layout.preferredWidth: 36
                        Layout.preferredHeight: 36
                        radius: 8
                        color: closeMouse.containsMouse ? "#E74C3C" : "transparent"
                        Behavior on color { ColorAnimation { duration: 100 } }

                        Canvas {
                            anchors.centerIn: parent
                            width: 12
                            height: 12
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = closeMouse.containsMouse ? "#FFFFFF" : appRoot.textSecondary
                                ctx.lineWidth = 1.4
                                ctx.lineCap = "round"
                                ctx.beginPath(); ctx.moveTo(1, 1); ctx.lineTo(11, 11); ctx.stroke()
                                ctx.beginPath(); ctx.moveTo(11, 1); ctx.lineTo(1, 11); ctx.stroke()
                            }
                        }
                        MouseArea {
                            id: closeMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: Qt.quit()
                        }
                    }
                }

                // Drag region: the title bar minus the button cluster on the right
                MouseArea {
                    anchors.left: parent.left
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    width: parent.width - 150
                    onPressed: appRoot.startNativeDrag()
                }
            }

            Rectangle {
                Layout.fillWidth: true
                height: 1
                color: appRoot.borderColor
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                // Sidebar
                ChatList {
                    id: chatList
                    Layout.preferredWidth: 340
                    Layout.fillHeight: true
                    darkMode: appRoot.darkMode
                    bgColor: appRoot.bgColor
                    surfaceColor: appRoot.surfaceColor
                    surfaceColorHover: appRoot.surfaceColorHover
                    textColor: appRoot.textColor
                    textSecondary: appRoot.textSecondary
                    borderColor: appRoot.borderColor
                    accentColor: appRoot.accentColor
                    onlineColor: appRoot.onlineColor
                    offlineColor: appRoot.offlineColor
                    activeChatId: chatViewLoader.item ? chatViewLoader.item.currentChatId : ""

                    onChatSelected: {
                        chatViewLoader.source = "qrc:/qml/ChatView.qml"
                        chatViewLoader.item.darkMode = appRoot.darkMode
                        chatViewLoader.item.bgColor = appRoot.bgColor
                        chatViewLoader.item.surfaceColor = appRoot.surfaceColor
                        chatViewLoader.item.textColor = appRoot.textColor
                        chatViewLoader.item.textSecondary = appRoot.textSecondary
                        chatViewLoader.item.borderColor = appRoot.borderColor
                        chatViewLoader.item.accentColor = appRoot.accentColor
                        chatViewLoader.item.onlineColor = appRoot.onlineColor
                        chatViewLoader.item.currentChatId = chatId
                        chatViewLoader.item.currentChatName = chatName
                    }

                    onNewChatClicked: {
                        newChatDialog.open()
                    }
                }

                // Divider
                Rectangle {
                    Layout.preferredWidth: 1
                    Layout.fillHeight: true
                    color: appRoot.borderColor
                }

                // Chat area
                Loader {
                    id: chatViewLoader
                    Layout.fillWidth: true
                    Layout.fillHeight: true

                    Rectangle {
                        anchors.fill: parent
                        visible: chatViewLoader.status !== Loader.Ready
                        color: "transparent"

                        Column {
                            anchors.centerIn: parent
                            spacing: 10

                            Rectangle {
                                anchors.horizontalCenter: parent.horizontalCenter
                                width: 64
                                height: 64
                                radius: 32
                                color: appRoot.surfaceColor

                                Canvas {
                                    anchors.centerIn: parent
                                    width: 28
                                    height: 28
                                    onPaint: {
                                        var ctx = getContext("2d")
                                        ctx.reset()
                                        var w = width, h = height * 0.72, r = 6
                                        ctx.fillStyle = appRoot.textSecondary
                                        ctx.beginPath()
                                        ctx.moveTo(r, 0); ctx.lineTo(w - r, 0); ctx.arcTo(w, 0, w, r, r)
                                        ctx.lineTo(w, h - r); ctx.arcTo(w, h, w - r, h, r)
                                        ctx.lineTo(w * 0.32, h); ctx.lineTo(w * 0.18, h + height * 0.2); ctx.lineTo(w * 0.22, h)
                                        ctx.lineTo(r, h); ctx.arcTo(0, h, 0, h - r, r)
                                        ctx.lineTo(0, r); ctx.arcTo(0, 0, r, 0, r)
                                        ctx.closePath(); ctx.fill()
                                    }
                                }
                            }

                            Text {
                                anchors.horizontalCenter: parent.horizontalCenter
                                text: "Select a chat to start messaging"
                                font.pixelSize: 14
                                color: appRoot.textSecondary
                            }
                        }
                    }
                }
            }
        }

        NewChatDialog {
            id: newChatDialog
            parent: appRoot.contentItem
            darkMode: appRoot.darkMode
            bgColor: appRoot.bgColor
            surfaceColor: appRoot.surfaceColor
            surfaceColorHover: appRoot.surfaceColorHover
            textColor: appRoot.textColor
            textSecondary: appRoot.textSecondary
            borderColor: appRoot.borderColor
            accentColor: appRoot.accentColor
            onlineColor: appRoot.onlineColor
        }

        Connections {
            target: chatService

            function onDirectChatReady(chatId, chatName) {
                chatViewLoader.source = "qrc:/qml/ChatView.qml"
                chatViewLoader.item.darkMode = appRoot.darkMode
                chatViewLoader.item.bgColor = appRoot.bgColor
                chatViewLoader.item.surfaceColor = appRoot.surfaceColor
                chatViewLoader.item.textColor = appRoot.textColor
                chatViewLoader.item.textSecondary = appRoot.textSecondary
                chatViewLoader.item.borderColor = appRoot.borderColor
                chatViewLoader.item.accentColor = appRoot.accentColor
                chatViewLoader.item.onlineColor = appRoot.onlineColor
                chatViewLoader.item.currentChatId = chatId
                chatViewLoader.item.currentChatName = chatName
            }

            function onSearchError(error) {
                console.log("[NewChat] Error:", error)
            }
        }

        Connections {
            target: websocketService

            function onMessageReceived(chatId, message) {
                if (message.senderId === authService.currentUserId) return

                var viewingThisChat = appRoot.active && chatViewLoader.item
                                       && chatViewLoader.item.currentChatId === chatId
                if (viewingThisChat) return

                var text = chatService.decryptMessage(chatId, message.content, message.encrypted === true)
                trayNotifier.showMessage(chatList.chatNameFor(chatId), text)
            }
        }

        // Logout confirmation dialog
        Popup {
            id: logoutDialog
            parent: appRoot.contentItem
            modal: true
            focus: true
            width: 340
            x: (parent ? parent.width - width : 0) / 2
            y: (parent ? parent.height - height : 0) / 2
            closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
            padding: 0

            background: Rectangle {
                color: appRoot.surfaceColor
                radius: 14
                border.color: appRoot.borderColor
                border.width: 1
            }

            contentItem: ColumnLayout {
                spacing: 0

                Text {
                    Layout.fillWidth: true
                    Layout.topMargin: 22
                    Layout.leftMargin: 22
                    Layout.rightMargin: 22
                    text: "Log out?"
                    font.pixelSize: 17
                    font.weight: Font.Bold
                    color: appRoot.textColor
                }

                Text {
                    Layout.fillWidth: true
                    Layout.topMargin: 8
                    Layout.leftMargin: 22
                    Layout.rightMargin: 22
                    text: "You'll need to sign in again to access your chats."
                    font.pixelSize: 13
                    color: appRoot.textSecondary
                    wrapMode: Text.WordWrap
                }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.margins: 22
                    Layout.topMargin: 20
                    spacing: 10

                    Item { Layout.fillWidth: true }

                    Rectangle {
                        Layout.preferredWidth: 92
                        Layout.preferredHeight: 40
                        radius: 11
                        color: cancelMouse.pressed ? appRoot.borderColor
                               : (cancelMouse.containsMouse ? appRoot.surfaceColorHover : "transparent")
                        border.color: appRoot.borderColor
                        border.width: 1
                        Behavior on color { ColorAnimation { duration: 120 } }

                        Text {
                            anchors.centerIn: parent
                            text: "Cancel"
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: appRoot.textColor
                        }
                        MouseArea {
                            id: cancelMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: logoutDialog.close()
                        }
                    }

                    Rectangle {
                        Layout.preferredWidth: 92
                        Layout.preferredHeight: 40
                        radius: 11
                        color: confirmLogoutMouse.pressed ? Qt.darker("#E74C3C", 1.15)
                               : (confirmLogoutMouse.containsMouse ? Qt.lighter("#E74C3C", 1.08) : "#E74C3C")
                        Behavior on color { ColorAnimation { duration: 120 } }

                        Text {
                            anchors.centerIn: parent
                            text: "Log out"
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                            color: "#FFFFFF"
                        }
                        MouseArea {
                            id: confirmLogoutMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                logoutDialog.close()
                                chatViewLoader.source = ""
                                authService.logout()
                            }
                        }
                    }
                }
            }
        }
    }

    function startNativeDrag() {
        // Handle window dragging for frameless window
    }
}
