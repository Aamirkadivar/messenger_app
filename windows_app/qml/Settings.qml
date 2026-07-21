import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Transitions 1.15

Rectangle {
    id: settingsRoot
    radius: 0

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#1A1A2E"
    property color surfaceColor: "#16213E"
    property color textColor: "#E8E8E8"
    property color textSecondary: "#8B8B9E"
    property color borderColor: "#2A2A4A"
    property color accentColor: "#6C63FF"
    property string username: ""
    property string email: ""
    property string avatar: ""

    // Signals
    signal darkModeToggled(bool enabled)
    signal logoutClicked()
    signal backClicked()

    color: bgColor

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Header
        Item {
            Layout.fillWidth: true
            height: 64

            RowLayout {
                anchors.fill: parent
                anchors.margins: 20
                spacing: 16

                Button {
                    width: 40
                    height: 40
                    background: Rectangle {
                        radius: 8
                        color: mouseArea.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.3)" : "rgba(108, 99, 255, 0.1)") : "transparent"
                        MouseArea { id: mouseArea; anchors.fill: parent; hoverEnabled: true; onClicked: settingsRoot.backClicked() }
                    }
                    Image {
                        anchors.centerIn: parent
                        source: "qrc:/icons/back.svg"
                        width: 20
                        height: 20
                    }
                }

                Text {
                    text: "Settings"
                    font.pixelSize: 24
                    font.bold: true
                    color: settingsRoot.textColor
                    Layout.fillWidth: true
                }
            }
        }

        // Divider
        Rectangle {
            Layout.fillWidth: true
            height: 1
            color: borderColor
        }

        // Profile section
        Rectangle {
            Layout.fillWidth: true
            height: 120
            color: darkMode ? "rgba(22, 33, 62, 0.5)" : "rgba(255, 255, 255, 0.3)"

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 20
                spacing: 12

                // Avatar
                Rectangle {
                    Layout.preferredWidth: 64
                    Layout.preferredHeight: 64
                    Layout.alignment: Qt.AlignHCenter
                    radius: 32
                    color: accentColor

                    Text {
                        anchors.centerIn: parent
                        text: username ? username.substring(0, 1).toUpperCase() : "U"
                        font.pixelSize: 28
                        font.bold: true
                        color: "#FFFFFF"
                    }
                }

                // Username
                Text {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignHCenter
                    text: username || "User"
                    font.pixelSize: 18
                    font.bold: true
                    color: textColor
                    elide: Text.ElideMiddle
                }

                // Email
                Text {
                    Layout.fillWidth: true
                    Layout.alignment: Qt.AlignHCenter
                    text: email || ""
                    font.pixelSize: 13
                    color: textSecondary
                    elide: Text.ElideMiddle
                }
            }
        }

        // Settings list
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: bgColor

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 0
                spacing: 0

                // Dark mode toggle
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: dmMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                    MouseArea {
                        id: dmMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }

                    Row {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Image {
                            source: "qrc:/icons/darkmode.svg"
                            width: 20
                            height: 20
                        }

                        Column {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Dark Mode"
                                font.pixelSize: 15
                                font.bold: true
                                color: darkMode ? "#E8E8E8" : "#1A1A2E"
                            }

                            Text {
                                text: darkMode ? "On" : "Off"
                                font.pixelSize: 12
                                color: darkMode ? "#8B8B9E" : "#666666"
                            }
                        }

                        Toggle {
                            checked: darkMode
                            onCheckedChanged: settingsRoot.darkModeToggled(checked)
                            indicator: Rectangle {
                                implicitWidth: 48
                                implicitHeight: 26
                                radius: 13
                                color: parent.checked ? accentColor : "#CCCCCC"
                                Rectangle {
                                    width: 22
                                    height: 22
                                    radius: 11
                                    color: "white"
                                    x: parent.checked ? 22 : 2
                                    y: 2
                                    Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutBack } }
                                }
                            }
                        }
                    }

                    // Separator
                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 1
                        color: darkMode ? "#2A2A4A" : "#E0E0E5"
                    }
                }

                // Notifications
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: notifMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                    MouseArea {
                        id: notifMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }

                    Row {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Image {
                            source: "qrc:/icons/notification.svg"
                            width: 20
                            height: 20
                        }

                        Column {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Notifications"
                                font.pixelSize: 15
                                font.bold: true
                                color: darkMode ? "#E8E8E8" : "#1A1A2E"
                            }

                            Text {
                                text: "Manage notification settings"
                                font.pixelSize: 12
                                color: darkMode ? "#8B8B9E" : "#666666"
                            }
                        }
                    }

                    // Separator
                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 1
                        color: darkMode ? "#2A2A4A" : "#E0E0E5"
                    }
                }

                // Privacy
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: privacyMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                    MouseArea {
                        id: privacyMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }

                    Row {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Image {
                            source: "qrc:/icons/privacy.svg"
                            width: 20
                            height: 20
                        }

                        Column {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Privacy"
                                font.pixelSize: 15
                                font.bold: true
                                color: darkMode ? "#E8E8E8" : "#1A1A2E"
                            }

                            Text {
                                text: "Encryption, block list"
                                font.pixelSize: 12
                                color: darkMode ? "#8B8B9E" : "#666666"
                            }
                        }
                    }

                    // Separator
                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 1
                        color: darkMode ? "#2A2A4A" : "#E0E0E5"
                    }
                }

                // Security
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: securityMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                    MouseArea {
                        id: securityMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }

                    Row {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Image {
                            source: "qrc:/icons/security.svg"
                            width: 20
                            height: 20
                        }

                        Column {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Security"
                                font.pixelSize: 15
                                font.bold: true
                                color: darkMode ? "#E8E8E8" : "#1A1A2E"
                            }

                            Text {
                                text: "Two-factor authentication"
                                font.pixelSize: 12
                                color: darkMode ? "#8B8B9E" : "#666666"
                            }
                        }
                    }

                    // Separator
                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 1
                        color: darkMode ? "#2A2A4A" : "#E0E0E5"
                    }
                }

                // About
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: aboutMouse.containsPress ? (darkMode ? "rgba(108, 99, 255, 0.08)" : "rgba(108, 99, 255, 0.05)") : "transparent"

                    MouseArea {
                        id: aboutMouse
                        anchors.fill: parent
                        hoverEnabled: true
                    }

                    Row {
                        anchors.fill: parent
                        anchors.margins: 16
                        spacing: 14

                        Image {
                            source: "qrc:/icons/info.svg"
                            width: 20
                            height: 20
                        }

                        Column {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "About"
                                font.pixelSize: 15
                                font.bold: true
                                color: darkMode ? "#E8E8E8" : "#1A1A2E"
                            }

                            Text {
                                text: "Version 1.0.0"
                                font.pixelSize: 12
                                color: darkMode ? "#8B8B9E" : "#666666"
                            }
                        }
                    }
                }

                Item { Layout.fillHeight: true }

                // Logout button
                Rectangle {
                    Layout.fillWidth: true
                    height: 56
                    color: logoutMouse.containsPress ? "rgba(244, 67, 54, 0.1)" : "transparent"

                    MouseArea {
                        id: logoutMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: settingsRoot.logoutClicked()
                    }

                    RowLayout {
                        anchors.centerIn: parent
                        spacing: 12

                        Image {
                            source: "qrc:/icons/logout.svg"
                            width: 20
                            height: 20
                        }

                        Text {
                            text: "Logout"
                            font.pixelSize: 15
                            font.bold: true
                            color: "#F44336"
                        }
                    }
                }
            }
        }
    }
}