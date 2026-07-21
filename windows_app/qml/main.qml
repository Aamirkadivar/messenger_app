import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15
import Qt.labs.platform 1.1

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
    property color bgColor: darkMode ? "#1A1A2E" : "#F5F5F7"
    property color surfaceColor: darkMode ? "#16213E" : "#FFFFFF"
    property color surfaceColorHover: darkMode ? "#1A2744" : "#F0F0F2"
    property color primaryColor: "#6C63FF"
    property color primaryColorDark: "#5A52D5"
    property color textColor: darkMode ? "#E8E8E8" : "#1A1A2E"
    property color textSecondary: darkMode ? "#8B8B9E" : "#6B6B7B"
    property color borderColor: darkMode ? "#2A2A4A" : "#E0E0E5"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"

    // Hide title bar for custom design
    flags: Qt.FramelessWindowHint | Qt.Window

    // Background
    Rectangle {
        anchors.fill: parent
        color: appRoot.bgColor
    }

    // Main content
    Item {
        anchors.fill: parent

        // Login/Register screens
        Loader {
            id: authLoader
            anchors.fill: parent
            source: isLoggedIn ? "" : "qrc:/qml/Login.qml"
        }

        // Main app (when logged in)
        Rectangle {
            id: mainApp
            anchors.fill: parent
            color: "transparent"
            visible: isLoggedIn

            RowLayout {
                anchors.fill: parent
                spacing: 0

                // Sidebar
                ChatList {
                    id: chatList
                    Layout.preferredWidth: 340
                    Layout.fillHeight: true
                    width: 340
                    darkMode: appRoot.darkMode
                    bgColor: appRoot.bgColor
                    surfaceColor: appRoot.surfaceColor
                    textColor: appRoot.textColor
                    textSecondary: appRoot.textSecondary
                    borderColor: appRoot.borderColor
                    accentColor: appRoot.accentColor
                    onlineColor: appRoot.onlineColor
                    offlineColor: appRoot.offlineColor

                    onChatSelected: {
                        chatViewLoader.source = "qrc:/qml/ChatView.qml"
                        chatViewLoader.item.chatId = chatId
                        chatViewLoader.item.chatName = chatName
                    }

                    onNewChatClicked: {
                        // Show new chat dialog
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
                }
            }
        }

        // Window controls (top-right)
        Item {
            anchors.top: parent.top
            anchors.right: parent.right
            anchors.margins: 0
            width: windowControls.width
            height: windowControls.height

            Row {
                id: windowControls
                anchors.top: parent.top
                anchors.right: parent.right
                spacing: 0
                height: 48

                // Theme toggle button
                Button {
                    width: 48
                    height: 48
                    background: Rectangle {
                        color: mouseArea.containsPress ? (darkMode ? "#2A2A4A" : "#E0E0E5") : "transparent"
                        radius: 0
                    }
                    MouseArea {
                        id: mouseArea
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: {
                            appRoot.darkMode = !appRoot.darkMode
                        }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: darkMode ? "qrc:/icons/sun.svg" : "qrc:/icons/moon.svg"
                        width: 20
                        height: 20
                    }
                }

                // Minimize
                Button {
                    width: 48
                    height: 48
                    background: Rectangle {
                        color: mouseArea.containsPress ? (darkMode ? "#2A2A4A" : "#E0E0E5") : "transparent"
                        radius: 0
                    }
                    MouseArea {
                        id: mouseArea2
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: appRoot.visibility = Window.Minimized
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/minimize.svg"
                        width: 16
                        height: 16
                    }
                }

                // Close
                Button {
                    width: 48
                    height: 48
                    background: Rectangle {
                        color: mouseArea.containsPress ? "#E74C3C" : "transparent"
                        radius: 0
                    }
                    MouseArea {
                        id: mouseArea3
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: Qt.quit()
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/close.svg"
                        width: 16
                        height: 16
                    }
                }
            }
        }
    }

    // Dragging for frameless window (title-bar strip only, so it doesn't
    // swallow clicks meant for the login form, chat UI, or window buttons)
    MouseArea {
        z: -1
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 48
        onPressed: {
            appRoot.startNativeDrag()
        }
    }

    function startNativeDrag() {
        // Handle window dragging for frameless window
    }
}