import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Dialogs

// Full-window settings layer (not a Popup). Popup Overlay could not reliably
// block hover on the chat behind; this Item sits at z:10000 and owns its own
// scrim so only the panel stays interactive.
Item {
    id: settingsRoot
    anchors.fill: parent
    visible: false
    z: 10000
    focus: visible

    property bool darkMode: true
    property color bgColor: "#050403"
    property color surfaceColor: "#0C0A08"
    property color surfaceColorHover: "#16120E"
    property color textColor: "#F0EAD6"
    property color textSecondary: "#A39A8A"
    property color borderColor: Qt.rgba(1, 1, 1, 0.12)
    property color accentColor: "#C9A961"

    readonly property color toggleOffColor: darkMode ? "#2A261F" : "#D9CFB8"
    readonly property color panelHoverColor: darkMode ? "#16120E" : surfaceColorHover
    readonly property string displayFont: "Georgia"
    readonly property string bodyFont: "Segoe UI"
    readonly property bool opened: visible

    signal darkModeToggled()
    signal logoutRequested()

    property string cacheSizeText: "..."
    property var blockedUsers: []

    function open() {
        visible = true
        forceActiveFocus()
        refreshCacheSize()
        refreshBlockedUsers()
    }
    function close() {
        visible = false
    }

    function refreshCacheSize() {
        if (typeof chatService === "undefined") return
        var bytes = chatService.cacheSizeBytes()
        cacheSizeText = bytes < 1024 ? bytes + " B"
                        : bytes < 1024 * 1024 ? (bytes / 1024).toFixed(1) + " KB"
                        : (bytes / (1024 * 1024)).toFixed(1) + " MB"
    }

    function refreshBlockedUsers() {
        if (typeof chatService === "undefined") return
        chatService.fetchBlockedUsers()
    }

    function displayBlockedName(u) {
        if (!u) return "Unknown"
        return u.display_name || u.username || "Unknown"
    }

    Keys.onEscapePressed: settingsRoot.close()

    Connections {
        target: typeof chatService !== "undefined" ? chatService : null
        function onBlockedUsersFetched(users) {
            settingsRoot.blockedUsers = users || []
        }
        function onUserUnblocked(userId) {
            var next = []
            for (var i = 0; i < settingsRoot.blockedUsers.length; i++) {
                if (settingsRoot.blockedUsers[i].id !== userId)
                    next.push(settingsRoot.blockedUsers[i])
            }
            settingsRoot.blockedUsers = next
        }
        function onUserBlocked(userId, chatId) {
            // Refresh so a just-blocked contact appears under Privacy.
            settingsRoot.refreshBlockedUsers()
        }
    }

    Rectangle {
        anchors.fill: parent
        color: settingsRoot.darkMode ? Qt.rgba(0, 0, 0, 0.68)
                                     : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.52)
        MouseArea {
            anchors.fill: parent
            hoverEnabled: true
            preventStealing: true
            acceptedButtons: Qt.AllButtons
            cursorShape: Qt.ArrowCursor
            onPressed: function(mouse) {
                mouse.accepted = true
                settingsRoot.close()
            }
            onWheel: function(wheel) { wheel.accepted = true }
        }
    }

    GlassPanel {
        id: panel
        width: 400
        height: Math.min(680, settingsRoot.height - 48)
        anchors.centerIn: parent
        darkMode: settingsRoot.darkMode
        elevated: true
        radius: 16
        MouseArea {
            anchors.fill: parent
            z: -1
            acceptedButtons: Qt.AllButtons
            onPressed: function(mouse) { mouse.accepted = true }
        }

ColumnLayout {
        anchors.fill: parent
        anchors.margins: 0
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 24
            Layout.rightMargin: 24
            Layout.topMargin: 20
            Layout.bottomMargin: 8

            Text {
                Layout.fillWidth: true
                text: "Settings"
                font.family: settingsRoot.displayFont
                font.pixelSize: 20
                font.weight: Font.Medium
                color: settingsRoot.textColor
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                color: closeSettingsMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.family: settingsRoot.bodyFont
                    font.pixelSize: 13
                    color: settingsRoot.textSecondary
                }
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
                width: panel.width
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
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: authService.currentUserEmail || ""
                        font.family: settingsRoot.bodyFont
                        font.pixelSize: 13
                        color: settingsRoot.textSecondary
                    }

                    Text {
                        id: avatarError
                        anchors.horizontalCenter: parent.horizontalCenter
                        width: parent.width - 40
                        horizontalAlignment: Text.AlignHCenter
                        text: ""
                        font.family: settingsRoot.bodyFont
                        font.pixelSize: 13
                        color: "#E74C3C"
                        wrapMode: Text.WordWrap
                        visible: text.length > 0
                    }
                }

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                // Section header - serif like Android Tokens.Type.sectionHeader
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 28
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "Display"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                }

                // ---- Dark mode ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: darkModeMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        spacing: 14

                        ColumnLayout {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.fillWidth: true
                            Layout.preferredWidth: 0
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Dark Mode"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 16
                                color: settingsRoot.textColor
                            }
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: settingsRoot.darkMode ? "On" : "Off"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                color: settingsRoot.textSecondary
                            }
                        }

                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.preferredWidth: 46
                            Layout.preferredHeight: 28
                            radius: 14
                            color: settingsRoot.darkMode ? settingsRoot.accentColor : settingsRoot.toggleOffColor

                            Rectangle {
                                width: 22
                                height: 22
                                radius: 11
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

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 28
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "Notifications"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                }

                // ---- Notifications ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: notifSettingsMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        spacing: 14

                        ColumnLayout {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.fillWidth: true
                            Layout.preferredWidth: 0
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Notifications"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 16
                                color: settingsRoot.textColor
                            }
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Tray pop-ups for new messages"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                color: settingsRoot.textSecondary
                            }
                        }

                        Rectangle {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.preferredWidth: 46
                            Layout.preferredHeight: 28
                            radius: 14
                            color: trayNotifier.notificationsEnabled ? settingsRoot.accentColor : settingsRoot.toggleOffColor

                            Rectangle {
                                width: 22
                                height: 22
                                radius: 11
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

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 28
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "Privacy"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                    Text {
                        text: "Blocked users stay out of your chat list"
                        font.family: settingsRoot.bodyFont
                        font.pixelSize: 13
                        color: settingsRoot.textSecondary
                    }
                }

                Text {
                    Layout.fillWidth: true
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.topMargin: 8
                    visible: settingsRoot.blockedUsers.length === 0
                    text: "No blocked users"
                    font.family: settingsRoot.bodyFont
                    font.pixelSize: 13
                    color: settingsRoot.textSecondary
                }

                Repeater {
                    model: settingsRoot.blockedUsers
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        height: 56
                        color: blockRowMouse.containsMouse ? settingsRoot.panelHoverColor : "transparent"

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 24
                            anchors.rightMargin: 24
                            spacing: 14

                            ColumnLayout {
                                Layout.fillWidth: true
                                Layout.preferredWidth: 0
                                Layout.alignment: Qt.AlignVCenter
                                spacing: 2
                                Text {
                                    Layout.fillWidth: true
                                    elide: Text.ElideRight
                                    text: settingsRoot.displayBlockedName(modelData)
                                    font.family: settingsRoot.bodyFont
                                    font.pixelSize: 15
                                    color: settingsRoot.textColor
                                }
                                Text {
                                    Layout.fillWidth: true
                                    elide: Text.ElideRight
                                    visible: !!(modelData.username)
                                    text: modelData.username ? ("@" + modelData.username) : ""
                                    font.family: settingsRoot.bodyFont
                                    font.pixelSize: 12
                                    color: settingsRoot.textSecondary
                                }
                            }

                            Text {
                                text: "Unblock"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                font.weight: Font.Medium
                                color: settingsRoot.accentColor
                                MouseArea {
                                    anchors.fill: parent
                                    anchors.margins: -8
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: chatService.unblockUser(modelData.id)
                                }
                            }
                        }

                        MouseArea {
                            id: blockRowMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            acceptedButtons: Qt.NoButton
                        }
                    }
                }

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 28
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "Storage and data"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                }

                // ---- Storage ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        spacing: 14

                        ColumnLayout {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.fillWidth: true
                            Layout.preferredWidth: 0
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Storage"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 16
                                color: settingsRoot.textColor
                            }
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: settingsRoot.cacheSizeText + " used for cached chats"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                color: settingsRoot.textSecondary
                            }
                        }

                        Text {
                            text: "Clear"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 13
                            font.weight: Font.Medium
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

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 28
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "About"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                }

                // ---- About ----
                Rectangle {
                    Layout.fillWidth: true
                    height: 64
                    color: "transparent"

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        spacing: 14

                        ColumnLayout {
                            Layout.alignment: Qt.AlignVCenter
                            Layout.fillWidth: true
                            Layout.preferredWidth: 0
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Version"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 16
                                color: settingsRoot.textColor
                            }
                            Text {
                                Layout.fillWidth: true
                                elide: Text.ElideRight
                                text: "Messenger for Windows — 1.0.0"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                color: settingsRoot.textSecondary
                            }
                        }
                    }
                }

                Item { Layout.preferredHeight: 16 }

                Rectangle { Layout.fillWidth: true; Layout.leftMargin: 24; Layout.rightMargin: 24; height: 1; color: settingsRoot.borderColor }

                // ---- Logout ----
                Rectangle {
                    Layout.fillWidth: true
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.topMargin: 16
                    Layout.bottomMargin: 24
                    height: 48
                    radius: 12
                    color: settingsLogoutMouse.containsMouse ? Qt.rgba(231/255, 76/255, 60/255, 0.15) : "transparent"
                    border.color: "#E74C3C"
                    border.width: 1

                    Text {
                        anchors.centerIn: parent
                        text: "Log out"
                        font.family: settingsRoot.bodyFont
                        font.pixelSize: 16
                        font.weight: Font.Medium
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
}
