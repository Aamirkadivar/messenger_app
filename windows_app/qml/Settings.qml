import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Dialogs

Popup {
    id: settingsRoot

    property bool darkMode: true
    property color bgColor: "#15152B"
    property color surfaceColor: "#1B1B36"
    property color surfaceColorHover: "#22224A"
    property color textColor: "#EDEDF2"
    property color textSecondary: "#9494AC"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#6C63FF"

    // The "off" state of a toggle still needs to read as neutral/inactive,
    // but a flat iOS-grey (#8E8E93) clashes with the rest of the app's warm
    // ivory/bronze palette - this stays in the same warm family instead.
    readonly property color toggleOffColor: darkMode ? "#4A4438" : "#D9CFB8"

    // The shared surfaceColor (#14141F) has a cool, blue-leaning near-black
    // (its blue channel runs well above red/green) that barely registers as
    // blue against the smaller surfaces it's normally used on - but filling
    // this whole panel with it makes that cast obvious. A warmer near-black
    // (blue channel now the lowest, not the highest) keeps the same depth
    // without the tint, without touching the shared color everywhere else
    // in the app still relies on.
    readonly property color panelSurfaceColor: darkMode ? "#17140F" : surfaceColor
    readonly property color panelHoverColor: darkMode ? "#221E15" : surfaceColorHover

    signal darkModeToggled()
    signal logoutRequested()

    property string cacheSizeText: "…"

    modal: true
    focus: true
    width: 380
    height: 600
    x: (parent ? parent.width - width : 0) / 2
    y: (parent ? parent.height - height : 0) / 2
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    padding: 0

    onOpened: refreshCacheSize()

    function refreshCacheSize() {
        if (typeof chatService === "undefined") return
        var bytes = chatService.cacheSizeBytes()
        cacheSizeText = bytes < 1024 ? bytes + " B"
                        : bytes < 1024 * 1024 ? (bytes / 1024).toFixed(1) + " KB"
                        : (bytes / (1024 * 1024)).toFixed(1) + " MB"
    }

    background: Rectangle {
        color: settingsRoot.panelSurfaceColor
        radius: 12
        border.color: settingsRoot.borderColor
        border.width: 1
    }

    FileDialog {
        id: avatarPicker
        title: "Choose a profile photo"
        nameFilters: ["Images (*.png *.jpg *.jpeg *.webp)"]
        onAccepted: authService.uploadAvatar(selectedFile.toString())
    }

    Connections {
        target: authService
        function onAvatarUploadFailed(message) { avatarError.text = message }
        function onAvatarUploaded() { avatarError.text = "" }
    }

    contentItem: ColumnLayout {
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 16
            Layout.bottomMargin: 8

            Text {
                Layout.fillWidth: true
                text: "Settings"
                font.pixelSize: 17
                font.bold: true
                color: settingsRoot.textColor
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                color: closeSettingsMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"
                Text { anchors.centerIn: parent; text: "✕"; font.pixelSize: 13; color: settingsRoot.textSecondary }
                MouseArea {
                    id: closeSettingsMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: settingsRoot.close()
                }
            }
        }

        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            ColumnLayout {
                width: settingsRoot.width
                spacing: 0

                // ---- Profile ----
                // A plain Column, not ColumnLayout: every child is centered
                // via anchors.horizontalCenter, which is straightforward on a
                // Column's children - ColumnLayout's Layout.alignment was not
                // reliably centering these under a ScrollView's content item.
                Column {
                    // Being a ColumnLayout child, this needs Layout.fillWidth
                    // - without it, ColumnLayout shrinks the Column down to
                    // its content's own implicit width (just wide enough for
                    // the avatar) and left-aligns that narrow box, so every
                    // "centered" child below was only centered within that
                    // sliver, not the actual 380px panel.
                    Layout.fillWidth: true
                    topPadding: 6
                    bottomPadding: 16
                    spacing: 10

                    Item {
                        anchors.horizontalCenter: parent.horizontalCenter
                        width: 84
                        height: 84

                        Avatar {
                            anchors.fill: parent
                            name: authService.currentUsername
                            avatarUrl: authService.currentUserAvatarUrl || ""
                            size: 84
                        }

                        Rectangle {
                            anchors.bottom: parent.bottom
                            anchors.right: parent.right
                            width: 28
                            height: 28
                            radius: 14
                            color: settingsRoot.accentColor
                            border.color: settingsRoot.surfaceColor
                            border.width: 2

                            Canvas {
                                anchors.centerIn: parent
                                width: 13
                                height: 13
                                onPaint: {
                                    var ctx = getContext("2d")
                                    ctx.reset()
                                    ctx.strokeStyle = "#FFFFFF"
                                    ctx.lineWidth = 1.4
                                    ctx.lineCap = "round"
                                    ctx.lineJoin = "round"
                                    ctx.strokeRect(1, 3.5, 11, 8)
                                    ctx.beginPath(); ctx.arc(6.5, 7.5, 2.4, 0, Math.PI * 2); ctx.stroke()
                                    ctx.beginPath(); ctx.moveTo(4, 3.5); ctx.lineTo(5, 1.5); ctx.lineTo(8, 1.5); ctx.lineTo(9, 3.5); ctx.stroke()
                                }
                            }
                            MouseArea {
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                onClicked: avatarPicker.open()
                            }
                        }
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: authService.currentUsername || "User"
                        font.pixelSize: 18
                        font.bold: true
                        color: settingsRoot.textColor
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: authService.currentUserEmail || ""
                        font.pixelSize: 13
                        color: settingsRoot.textSecondary
                    }

                    Text {
                        id: avatarError
                        anchors.horizontalCenter: parent.horizontalCenter
                        width: parent.width - 40
                        horizontalAlignment: Text.AlignHCenter
                        text: ""
                        font.pixelSize: 12
                        color: "#E74C3C"
                        wrapMode: Text.WordWrap
                        visible: text.length > 0
                    }
                }

                Rectangle { Layout.fillWidth: true; height: 1; color: settingsRoot.borderColor }

                // ---- Dark mode ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 60
                    color: darkModeMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 14

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text { text: "Dark Mode"; font.pixelSize: 14; font.weight: Font.DemiBold; color: settingsRoot.textColor }
                            Text { text: settingsRoot.darkMode ? "On" : "Off"; font.pixelSize: 12; color: settingsRoot.textSecondary }
                        }

                        Rectangle {
                            Layout.preferredWidth: 46
                            Layout.preferredHeight: 26
                            radius: 13
                            color: settingsRoot.darkMode ? settingsRoot.accentColor : settingsRoot.toggleOffColor

                            Rectangle {
                                width: 20
                                height: 20
                                radius: 10
                                color: "#FFFFFF"
                                y: 3
                                x: settingsRoot.darkMode ? parent.width - width - 3 : 3
                                Behavior on x { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                            }
                        }
                    }

                    MouseArea {
                        id: darkModeMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: settingsRoot.darkModeToggled()
                    }
                }

                Rectangle { Layout.fillWidth: true; height: 1; color: settingsRoot.borderColor }

                // ---- Notifications ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 60
                    color: notifSettingsMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 14

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text { text: "Notifications"; font.pixelSize: 14; font.weight: Font.DemiBold; color: settingsRoot.textColor }
                            Text { text: "Tray pop-ups for new messages"; font.pixelSize: 12; color: settingsRoot.textSecondary }
                        }

                        Rectangle {
                            Layout.preferredWidth: 46
                            Layout.preferredHeight: 26
                            radius: 13
                            color: trayNotifier.notificationsEnabled ? settingsRoot.accentColor : settingsRoot.toggleOffColor

                            Rectangle {
                                width: 20
                                height: 20
                                radius: 10
                                color: "#FFFFFF"
                                y: 3
                                x: trayNotifier.notificationsEnabled ? parent.width - width - 3 : 3
                                Behavior on x { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                            }
                        }
                    }

                    MouseArea {
                        id: notifSettingsMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: trayNotifier.notificationsEnabled = !trayNotifier.notificationsEnabled
                    }
                }

                Rectangle { Layout.fillWidth: true; height: 1; color: settingsRoot.borderColor }

                // ---- Storage ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 60
                    color: "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 14

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text { text: "Storage"; font.pixelSize: 14; font.weight: Font.DemiBold; color: settingsRoot.textColor }
                            Text { text: settingsRoot.cacheSizeText + " used for cached chats"; font.pixelSize: 12; color: settingsRoot.textSecondary }
                        }

                        Text {
                            text: "Clear"
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                            color: settingsRoot.accentColor
                            MouseArea {
                                anchors.fill: parent
                                anchors.margins: -8
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    chatService.clearCache()
                                    settingsRoot.refreshCacheSize()
                                }
                            }
                        }
                    }
                }

                Rectangle { Layout.fillWidth: true; height: 1; color: settingsRoot.borderColor }

                // ---- About ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 60
                    color: "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 16
                        anchors.rightMargin: 16
                        spacing: 14

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text { text: "About"; font.pixelSize: 14; font.weight: Font.DemiBold; color: settingsRoot.textColor }
                            Text { text: "Messenger for Windows — version 1.0.0"; font.pixelSize: 12; color: settingsRoot.textSecondary }
                        }
                    }
                }

                Item { Layout.preferredHeight: 8 }

                Rectangle { Layout.fillWidth: true; height: 1; color: settingsRoot.borderColor }

                // ---- Logout ----
                Rectangle {
                    Layout.fillWidth: true
                    Layout.margins: 16
                    height: 42
                    radius: 11
                    color: settingsLogoutMouse.containsMouse ? Qt.rgba(231/255, 76/255, 60/255, 0.15) : "transparent"
                    border.color: "#E74C3C"
                    border.width: 1

                    Text {
                        anchors.centerIn: parent
                        text: "Log out"
                        font.pixelSize: 13
                        font.weight: Font.DemiBold
                        color: "#E74C3C"
                    }

                    MouseArea {
                        id: settingsLogoutMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            settingsRoot.close()
                            settingsRoot.logoutRequested()
                        }
                    }
                }
            }
        }
    }
}
