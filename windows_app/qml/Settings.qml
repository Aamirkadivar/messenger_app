import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Dialogs
import Messenger 1.0

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
    property var e2eeDevices: []
    property string passwordChangeError: ""
    property string linkDeviceError: ""
    property string totpError: ""
    property string totpSecret: ""
    property var totpBackupCodes: []
    property bool totpBusy: false

    function open() {
        visible = true
        forceActiveFocus()
        refreshCacheSize()
        refreshBlockedUsers()
        refreshE2EEDevices()
        if (typeof authService !== "undefined")
            authService.fetchTotpStatus()
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

    function refreshE2EEDevices() {
        if (typeof authService === "undefined") return
        authService.fetchE2EEDevices()
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

    Connections {
        target: typeof authService !== "undefined" ? authService : null
        function onE2eeDevicesLoaded(devices) {
            settingsRoot.e2eeDevices = devices || []
        }
        function onE2eeDeviceRevoked(deviceId) {
            var next = []
            for (var i = 0; i < settingsRoot.e2eeDevices.length; i++) {
                var d = Object.assign({}, settingsRoot.e2eeDevices[i])
                if (d.device_id === deviceId) d.revoked = true
                next.push(d)
            }
            settingsRoot.e2eeDevices = next
        }
        function onPasswordChangeSucceeded() {
            changePasswordDialog.close()
            settingsRoot.passwordChangeError = ""
            currentPasswordField.text = ""
            newPasswordField.text = ""
            confirmPasswordField.text = ""
        }
        function onPasswordChangeFailed(message) {
            settingsRoot.passwordChangeError = message || "Password change failed"
        }
        function onTotpStatusChanged() {
            settingsRoot.totpBusy = false
            settingsRoot.totpError = ""
            if (!authService.totpEnabled)
                totpDisableDialog.close()
        }
        function onTotpConfirmSucceeded(codes) {
            settingsRoot.totpBusy = false
            settingsRoot.totpError = ""
            settingsRoot.totpSecret = ""
            settingsRoot.totpBackupCodes = codes || []
            totpRegenDialog.close()
            if (settingsRoot.totpBackupCodes.length > 0)
                totpSetupDialog.open()
        }
        function onTotpSetupReady(secret, otpauthUrl) {
            settingsRoot.totpBusy = false
            settingsRoot.totpSecret = secret || ""
            settingsRoot.totpError = ""
        }
        function onTotpFailed(message) {
            settingsRoot.totpBusy = false
            settingsRoot.totpError = message || "Authenticator request failed"
        }
        function onDevicePairingSucceeded() {
            pairingQrScanner.stop()
            linkDeviceDialog.close()
            settingsRoot.linkDeviceError = ""
            linkCodeField.text = ""
            scanPreview.clear()
        }
        function onDevicePairingFailed(message) {
            // Only show in link dialog when it's open (approve path).
            if (linkDeviceDialog.visible)
                settingsRoot.linkDeviceError = message || "Link failed"
        }
    }

    Dialog {
        id: linkDeviceDialog
        modal: true
        anchors.centerIn: parent
        title: "Link a device"
        standardButtons: Dialog.Cancel | Dialog.Ok
        onClosed: {
            pairingQrScanner.stop()
            scanPreview.clear()
        }
        onAccepted: {
            pairingQrScanner.stop()
            settingsRoot.linkDeviceError = ""
            if (typeof authService !== "undefined")
                authService.approveDevicePairing(linkCodeField.text)
            Qt.callLater(function() { linkDeviceDialog.open() })
        }
        contentItem: ColumnLayout {
            spacing: 10
            Label {
                text: "Point the camera at the QR on the new device, or paste the code."
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
                Layout.preferredWidth: 360
            }
            VideoFrame {
                id: scanPreview
                Layout.fillWidth: true
                Layout.preferredHeight: pairingQrScanner.scanning ? 220 : 0
                visible: pairingQrScanner.scanning
                fillMode: VideoFrame.Cover
                clip: true
            }
            Label {
                visible: pairingQrScanner.error.length > 0
                text: pairingQrScanner.error
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Button {
                text: pairingQrScanner.scanning ? "Stop camera" : "Scan QR"
                onClicked: {
                    if (pairingQrScanner.scanning) {
                        pairingQrScanner.stop()
                        scanPreview.clear()
                    } else {
                        settingsRoot.linkDeviceError = ""
                        pairingQrScanner.start()
                    }
                }
            }
            TextField {
                id: linkCodeField
                Layout.fillWidth: true
                placeholderText: "mp1...."
            }
            Label {
                visible: settingsRoot.linkDeviceError.length > 0
                text: settingsRoot.linkDeviceError
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
        }
    }

    PairingQrScanner {
        id: pairingQrScanner
        onPreviewFrame: function(image) { scanPreview.present(image) }
        onCodeFound: function(code) {
            linkCodeField.text = code
            settingsRoot.linkDeviceError = ""
            if (typeof authService !== "undefined")
                authService.approveDevicePairing(code)
        }
    }

    Dialog {
        id: changePasswordDialog
        modal: true
        anchors.centerIn: parent
        title: "Change password"
        standardButtons: Dialog.Cancel | Dialog.Ok
        onOpened: {
            currentPasswordField.forceActiveFocus()
        }
        onAccepted: {
            if (newPasswordField.text.length < 8) {
                settingsRoot.passwordChangeError = "New password must be at least 8 characters"
                Qt.callLater(function() { changePasswordDialog.open() })
                return
            }
            if (newPasswordField.text !== confirmPasswordField.text) {
                settingsRoot.passwordChangeError = "New passwords do not match"
                Qt.callLater(function() { changePasswordDialog.open() })
                return
            }
            settingsRoot.passwordChangeError = ""
            if (typeof authService !== "undefined") {
                authService.changePassword(currentPasswordField.text, newPasswordField.text)
            }
            // Stay open until passwordChangeSucceeded / Failed.
            Qt.callLater(function() { changePasswordDialog.open() })
        }
        contentItem: ColumnLayout {
            spacing: 10
            TextField {
                id: currentPasswordField
                Layout.fillWidth: true
                placeholderText: "Current password"
                echoMode: TextInput.Password
                passwordCharacter: "•"
            }
            TextField {
                id: newPasswordField
                Layout.fillWidth: true
                placeholderText: "New password"
                echoMode: TextInput.Password
                passwordCharacter: "•"
            }
            TextField {
                id: confirmPasswordField
                Layout.fillWidth: true
                placeholderText: "Confirm new password"
                echoMode: TextInput.Password
                passwordCharacter: "•"
            }
            Label {
                visible: settingsRoot.passwordChangeError.length > 0
                text: settingsRoot.passwordChangeError
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
        }
    }

    Dialog {
        id: totpSetupDialog
        modal: true
        anchors.centerIn: parent
        title: "Authenticator app"
        standardButtons: Dialog.Cancel | Dialog.Ok
        onOpened: {
            totpCodeField.forceActiveFocus()
        }
        onRejected: {
            settingsRoot.totpSecret = ""
            settingsRoot.totpError = ""
            settingsRoot.totpBusy = false
            settingsRoot.totpBackupCodes = []
        }
        onAccepted: {
            if (settingsRoot.totpBackupCodes.length > 0) {
                settingsRoot.totpBackupCodes = []
                return
            }
            if (settingsRoot.totpBusy) {
                Qt.callLater(function() { totpSetupDialog.open() })
                return
            }
            if (totpCodeField.text.trim().length !== 6) {
                settingsRoot.totpError = "Enter the 6-digit code from your app"
                Qt.callLater(function() { totpSetupDialog.open() })
                return
            }
            settingsRoot.totpError = ""
            settingsRoot.totpBusy = true
            if (typeof authService !== "undefined")
                authService.confirmTotp(totpCodeField.text)
            Qt.callLater(function() { totpSetupDialog.open() })
        }
        contentItem: ColumnLayout {
            spacing: 10
            Label {
                visible: settingsRoot.totpBackupCodes.length === 0
                text: "Add this secret in Google Authenticator or similar, then enter a code to confirm."
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
                Layout.preferredWidth: 360
            }
            Label {
                visible: settingsRoot.totpBackupCodes.length > 0
                text: "Save these backup codes. Each works once if you lose the authenticator."
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
                Layout.preferredWidth: 360
            }
            Label {
                visible: settingsRoot.totpBackupCodes.length > 0
                text: settingsRoot.totpBackupCodes.join("\n")
                font.family: "Consolas"
                Layout.fillWidth: true
            }
            Label {
                visible: settingsRoot.totpSecret.length > 0 && settingsRoot.totpBackupCodes.length === 0
                text: settingsRoot.totpSecret
                wrapMode: Text.WrapAnywhere
                font.family: "Consolas"
                Layout.fillWidth: true
            }
            Label {
                visible: settingsRoot.totpSecret.length === 0 && settingsRoot.totpBackupCodes.length === 0
                text: settingsRoot.totpBusy ? "Generating secret…" : "Waiting for secret…"
                color: settingsRoot.textSecondary
            }
            TextField {
                id: totpCodeField
                visible: settingsRoot.totpBackupCodes.length === 0
                Layout.fillWidth: true
                placeholderText: "6-digit code"
                inputMethodHints: Qt.ImhDigitsOnly
            }
            Label {
                visible: settingsRoot.totpError.length > 0
                text: settingsRoot.totpError
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
        }
    }

    Dialog {
        id: totpDisableDialog
        modal: true
        anchors.centerIn: parent
        title: "Disable authenticator"
        standardButtons: Dialog.Cancel | Dialog.Ok
        onAccepted: {
            if (settingsRoot.totpBusy) {
                Qt.callLater(function() { totpDisableDialog.open() })
                return
            }
            settingsRoot.totpError = ""
            settingsRoot.totpBusy = true
            if (typeof authService !== "undefined")
                authService.disableTotp(totpDisablePasswordField.text, totpDisableCodeField.text)
            Qt.callLater(function() { totpDisableDialog.open() })
        }
        contentItem: ColumnLayout {
            spacing: 10
            TextField {
                id: totpDisablePasswordField
                Layout.fillWidth: true
                placeholderText: "Account password"
                echoMode: TextInput.Password
                passwordCharacter: "•"
            }
            TextField {
                id: totpDisableCodeField
                Layout.fillWidth: true
                placeholderText: "Authenticator or backup code"
            }
            Label {
                visible: settingsRoot.totpError.length > 0
                text: settingsRoot.totpError
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
        }
    }

    Dialog {
        id: totpRegenDialog
        modal: true
        anchors.centerIn: parent
        title: "New backup codes"
        standardButtons: Dialog.Cancel | Dialog.Ok
        onAccepted: {
            if (settingsRoot.totpBusy) {
                Qt.callLater(function() { totpRegenDialog.open() })
                return
            }
            settingsRoot.totpError = ""
            settingsRoot.totpBusy = true
            if (typeof authService !== "undefined")
                authService.regenerateTotpBackupCodes(totpRegenPasswordField.text, totpRegenCodeField.text)
            Qt.callLater(function() { totpRegenDialog.open() })
        }
        contentItem: ColumnLayout {
            spacing: 10
            Label {
                text: "Old unused codes stop working. Enter your password and authenticator (or a remaining backup code)."
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
                Layout.preferredWidth: 360
            }
            TextField {
                id: totpRegenPasswordField
                Layout.fillWidth: true
                placeholderText: "Account password"
                echoMode: TextInput.Password
                passwordCharacter: "•"
            }
            TextField {
                id: totpRegenCodeField
                Layout.fillWidth: true
                placeholderText: "Authenticator or backup code"
            }
            Label {
                visible: settingsRoot.totpError.length > 0
                text: settingsRoot.totpError
                color: "#C45C4A"
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
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
                        text: "Security"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                    Text {
                        text: "Paste or scan the pairing code from a new device"
                        font.family: settingsRoot.bodyFont
                        font.pixelSize: 13
                        color: settingsRoot.textSecondary
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    height: 52
                    color: "transparent"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        Text {
                            Layout.fillWidth: true
                            text: "Change password"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 15
                            color: settingsRoot.textColor
                        }
                        Text {
                            text: "Update"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: settingsRoot.accentColor
                        }
                    }
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            settingsRoot.passwordChangeError = ""
                            currentPasswordField.text = ""
                            newPasswordField.text = ""
                            confirmPasswordField.text = ""
                            changePasswordDialog.open()
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    height: 52
                    color: "transparent"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        Text {
                            Layout.fillWidth: true
                            text: (typeof authService !== "undefined" && authService.totpEnabled)
                                  ? "Disable authenticator"
                                  : "Authenticator app"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 15
                            color: settingsRoot.textColor
                        }
                        Text {
                            text: (typeof authService !== "undefined" && authService.totpEnabled)
                                  ? "On"
                                  : "Set up"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: settingsRoot.accentColor
                        }
                    }
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            settingsRoot.totpError = ""
                            totpCodeField.text = ""
                            totpDisablePasswordField.text = ""
                            totpDisableCodeField.text = ""
                            if (typeof authService !== "undefined" && authService.totpEnabled) {
                                totpDisableDialog.open()
                            } else {
                                settingsRoot.totpSecret = ""
                                settingsRoot.totpBusy = true
                                totpSetupDialog.open()
                                if (typeof authService !== "undefined")
                                    authService.startTotpSetup()
                            }
                        }
                    }
                }

                Rectangle {
                    visible: typeof authService !== "undefined" && authService.totpEnabled
                    Layout.fillWidth: true
                    height: visible ? 52 : 0
                    color: "transparent"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        Text {
                            Layout.fillWidth: true
                            text: "Backup codes"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 15
                            color: settingsRoot.textColor
                        }
                        Text {
                            text: "Replace"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: settingsRoot.accentColor
                        }
                    }
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            settingsRoot.totpError = ""
                            totpRegenPasswordField.text = ""
                            totpRegenCodeField.text = ""
                            totpRegenDialog.open()
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    height: 52
                    color: "transparent"
                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 24
                        anchors.rightMargin: 24
                        Text {
                            Layout.fillWidth: true
                            text: "Link a device"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 15
                            color: settingsRoot.textColor
                        }
                        Text {
                            text: "Approve"
                            font.family: settingsRoot.bodyFont
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: settingsRoot.accentColor
                        }
                    }
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            settingsRoot.linkDeviceError = ""
                            linkCodeField.text = ""
                            linkDeviceDialog.open()
                        }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 16
                    Layout.leftMargin: 24
                    Layout.rightMargin: 24
                    Layout.bottomMargin: 4
                    Text {
                        text: "Linked devices"
                        font.family: settingsRoot.displayFont
                        font.pixelSize: 16
                        font.weight: Font.Medium
                        color: settingsRoot.textColor
                    }
                    Text {
                        text: "Devices that can unlock your encrypted history"
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
                    visible: settingsRoot.e2eeDevices.length === 0
                    text: "No registered devices yet"
                    font.family: settingsRoot.bodyFont
                    font.pixelSize: 13
                    color: settingsRoot.textSecondary
                }

                Repeater {
                    model: settingsRoot.e2eeDevices
                    delegate: Rectangle {
                        Layout.fillWidth: true
                        height: 56
                        color: "transparent"

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
                                    text: modelData.name || modelData.device_id || "Device"
                                    font.family: settingsRoot.bodyFont
                                    font.pixelSize: 14
                                    color: settingsRoot.textColor
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                                Text {
                                    text: {
                                        var s = modelData.platform || "unknown"
                                        if (modelData.is_current) s += " · this device"
                                        if (modelData.revoked) s += " · revoked"
                                        return s
                                    }
                                    font.family: settingsRoot.bodyFont
                                    font.pixelSize: 12
                                    color: settingsRoot.textSecondary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }

                            Text {
                                visible: !modelData.is_current && !modelData.revoked
                                text: "Revoke"
                                font.family: settingsRoot.bodyFont
                                font.pixelSize: 13
                                font.weight: Font.Medium
                                color: settingsRoot.accentColor
                                MouseArea {
                                    anchors.fill: parent
                                    anchors.margins: -8
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: authService.revokeE2EEDevice(modelData.device_id)
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
