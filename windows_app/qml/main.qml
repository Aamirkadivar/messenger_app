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
    // Opens windowed at the width/height above; the user can maximise from
    // the title bar or by snapping.

    // Treat FullScreen as maximized. Removing the frame via WM_NCCALCSIZE
    // (see Win11Frameless) leaves the client area spanning the whole screen,
    // and Qt then reports the window as FullScreen rather than Maximized.
    // Comparing against Maximized alone made the title-bar button show the
    // wrong glyph and swallow its first click - showMaximized() on a window
    // already filling the screen does nothing, so only the second click,
    // which finally restored it, appeared to work.
    readonly property bool windowMaximized: appRoot.visibility === Window.Maximized
                                            || appRoot.visibility === Window.FullScreen

    // True while a modal dialog owns the window — chrome above Overlay
    // (resize edges) must not stay interactive.
    readonly property bool modalDialogOpen: (typeof settingsPanel !== "undefined" && settingsPanel.opened)
                                            || (typeof newChatDialog !== "undefined" && newChatDialog.opened)
                                            || (typeof newGroupDialog !== "undefined" && newGroupDialog.opened)
                                            || (typeof groupInfoPanel !== "undefined" && groupInfoPanel.opened)
                                            || (typeof logoutDialog !== "undefined" && logoutDialog.opened)

    property bool darkMode: true
    // Briefly true right when the theme flips, so every hover Behavior on
    // color across the app can skip its transition for that one change -
    // otherwise a hovered element animates through the full dark<->light
    // jump instead of just snapping, which reads as a jarring flash.
    property bool instantTheme: false
    // Both themes share one luxury palette: warm ivory/cream in light mode,
    // near-black in dark mode, champagne-gold as the main brand colour
    // (bronze-gold in light mode for contrast on pale backgrounds).
    property string accentColor: darkMode ? "#C9A961" : "#A6803A"
    property bool isLoggedIn: authService.isLoggedIn

    // Colors - dark mode stays deep near-black; glass plates float over glow.
    property color bgColor: darkMode ? "#050403" : "#FAF6EE"
    property color surfaceColor: darkMode ? "#0C0A08" : "#FFFFFF"
    property color surfaceColorHover: darkMode ? "#16120E" : Qt.rgba(43/255, 36/255, 24/255, 0.06)
    property color primaryColor: darkMode ? "#C9A961" : "#A6803A"
    property color primaryColorDark: darkMode ? "#A6863F" : "#8A6A2E"
    property color textColor: darkMode ? "#F0EAD6" : "#2B2418"
    property color textSecondary: darkMode ? "#9A9180" : "#7A6F5C"
    property color borderColor: darkMode ? Qt.rgba(1, 1, 1, 0.12) : "#E6DFD0"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"
    // Deep bronze-gold bubbles - darker than chrome accent so white text reads.
    property color myMessageBg: darkMode ? "#6B511C" : "#A6803A"
    property color theirMessageBg: darkMode ? "#12100C" : "#F1EBDD"

    flags: Qt.FramelessWindowHint | Qt.Window

    // Scene captured by FrostedScrim's MultiEffect GPU blur (must not include
    // the Popup Overlay, or blur would recurse into itself).
    property alias blurSource: frostedBlurSource

    Item {
        id: frostedBlurSource
        anchors.fill: parent
        // While Settings (or another modal) owns the Overlay, freeze the whole
        // scene behind it — no hover, no clicks, no scroll.
        enabled: !appRoot.modalDialogOpen

    // Ambient colour behind every glass surface - restrained in dark mode so
    // panels stay dark while still catching a liquid gold glow at the edges.
    AmbientGlow {
        anchors.fill: parent
        baseColor: appRoot.bgColor
        primaryGlow: appRoot.accentColor
        secondaryGlow: appRoot.primaryColorDark
        intensity: appRoot.darkMode ? 0.85 : 0.7
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        TitleBar {
            id: titleBar
            Layout.fillWidth: true
            darkMode: appRoot.darkMode
            textColor: appRoot.textColor
            textSecondary: appRoot.textSecondary
            surfaceColorHover: appRoot.surfaceColorHover
            borderColor: appRoot.borderColor
            instantTheme: appRoot.instantTheme
            loggedIn: appRoot.isLoggedIn
            windowMaximized: appRoot.windowMaximized
            onSettingsClicked: settingsPanel.open()
            onMinimizeClicked: appRoot.visibility = Window.Minimized
            onCloseClicked: Qt.quit()
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

        // Login/Register screens
        Loader {
            id: authLoader
            anchors.fill: parent
            source: isLoggedIn ? "" : "qrc:/qml/Login.qml"

            // Login.qml (and Register.qml via its own back-reference) had no
            // darkMode binding at all before this redesign - every color in
            // both screens silently evaluated the ternaries against
            // undefined and fell back to their light-mode branch regardless
            // of the app's actual theme.
            Binding { target: authLoader.item; property: "darkMode"; value: appRoot.darkMode; when: authLoader.status === Loader.Ready }
        }

        // Main app (when logged in)
        ColumnLayout {
            anchors.fill: parent
            spacing: 0
            visible: isLoggedIn

            // Custom title bar

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

                    // Close the thread if the chat being deleted is the open
                    // one, otherwise it lingers showing a conversation that
                    // is no longer in the list.
                    onChatDeleted: (chatId) => {
                        if (chatViewLoader.item && chatViewLoader.item.currentChatId === chatId) {
                            chatViewLoader.source = ""
                        }
                    }

                    onChatSelected: (chatId, chatName, otherUserId, online, chatType, avatarUrl) => {
                        chatViewLoader.source = "qrc:/qml/ChatView.qml"
                        // Theme/color properties are kept live via the Binding
                        // elements on chatViewLoader below - only this chat's
                        // own identity needs setting here.
                        chatViewLoader.item.currentChatId = chatId
                        chatViewLoader.item.currentChatName = chatName
                        chatViewLoader.item.otherUserId = otherUserId
                        chatViewLoader.item.isOnline = online
                        chatViewLoader.item.currentChatType = chatType || "direct"
                        chatViewLoader.item.currentChatAvatarUrl = avatarUrl || ""
                    }

                    onNewChatClicked: {
                        newChatDialog.open()
                    }

                    onNewGroupClicked: {
                        newGroupDialog.open()
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

                    // Keep the loaded ChatView's theme/colors live instead of a
                    // one-time push at chat-selection time - otherwise toggling
                    // the theme while a chat is already open leaves it frozen
                    // on the old colors (and message-bubble backgrounds stuck
                    // on defaults meant for the other theme, making text
                    // unreadable once darkMode itself did catch up).
                    Binding { target: chatViewLoader.item; property: "darkMode"; value: appRoot.darkMode; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "bgColor"; value: appRoot.bgColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "surfaceColor"; value: appRoot.surfaceColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "textColor"; value: appRoot.textColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "textSecondary"; value: appRoot.textSecondary; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "borderColor"; value: appRoot.borderColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "accentColor"; value: appRoot.accentColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "onlineColor"; value: appRoot.onlineColor; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "myMessageBg"; value: appRoot.myMessageBg; when: chatViewLoader.status === Loader.Ready }
                    Binding { target: chatViewLoader.item; property: "theirMessageBg"; value: appRoot.theirMessageBg; when: chatViewLoader.status === Loader.Ready }

                    Connections {
                        target: chatViewLoader.item
                        function onOpenChatInfo(chatId) {
                            groupInfoPanel.chatId = chatId
                            groupInfoPanel.open()
                        }
                        // The header's back button emitted this into the
                        // void until now - closes the open chat and returns
                        // focus to the sidebar (found while auditing every
                        // button's wiring, not just its look).
                        function onBackClicked() {
                            chatViewLoader.source = ""
                        }
                    }

                    Rectangle {
                        anchors.fill: parent
                        visible: chatViewLoader.status !== Loader.Ready
                        color: "transparent"

                        Column {
                            anchors.centerIn: parent
                            spacing: 10

                            GlassPanel {
                                anchors.horizontalCenter: parent.horizontalCenter
                                width: 64
                                height: 64
                                radius: 32
                                darkMode: appRoot.darkMode

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

        NewGroupDialog {
            id: newGroupDialog
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

            onGroupCreated: (chatId, chatName, avatarUrl) => {
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
                chatViewLoader.item.currentChatType = "group"
                chatViewLoader.item.currentChatAvatarUrl = avatarUrl || ""
            }
        }

        GroupInfoPanel {
            id: groupInfoPanel
            parent: appRoot.contentItem
            darkMode: appRoot.darkMode
            bgColor: appRoot.bgColor
            surfaceColor: appRoot.surfaceColor
            surfaceColorHover: appRoot.surfaceColorHover
            textColor: appRoot.textColor
            textSecondary: appRoot.textSecondary
            borderColor: appRoot.borderColor
            accentColor: appRoot.accentColor

            onGroupGone: (chatId) => {
                if (chatViewLoader.item && chatViewLoader.item.currentChatId === chatId) {
                    chatViewLoader.source = ""
                }
            }
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
                chatViewLoader.item.currentChatType = "direct"
                chatViewLoader.item.currentChatAvatarUrl = ""
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

                var text = chatService.decryptMessage(chatId, message.content, message.encrypted === true,
                                                      message.senderId || "", message.keyVersion || 0,
                                                      message.encryptionVersion || 1, message.senderDeviceId || "")
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

            background: GlassPanel {
                darkMode: appRoot.darkMode
                radius: 14
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
                        Behavior on color {
                            enabled: !appRoot.instantTheme
                            ColorAnimation { duration: 120 }
                        }

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
                        Behavior on color {
                            enabled: !appRoot.instantTheme
                            ColorAnimation { duration: 120 }
                        }

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
    }
    } // frostedBlurSource
    // Above the main scene and resize chrome so the scrim truly blocks hover.
    Settings {
        id: settingsPanel
        anchors.fill: parent
        darkMode: appRoot.darkMode
        bgColor: appRoot.bgColor
        surfaceColor: appRoot.surfaceColor
        surfaceColorHover: appRoot.surfaceColorHover
        textColor: appRoot.textColor
        textSecondary: appRoot.textSecondary
        borderColor: appRoot.borderColor
        accentColor: appRoot.accentColor

        onDarkModeToggled: {
            appRoot.instantTheme = true
            appRoot.darkMode = !appRoot.darkMode
            Qt.callLater(function() { appRoot.instantTheme = false })
        }

        onLogoutRequested: logoutDialog.open()
    }

    // Call overlay fills the content area under the title bar so move /
    // minimize / maximize / close keep working.
    Loader {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.top: parent.top
        anchors.topMargin: 44
        z: 2000
        active: typeof callService !== "undefined" && callService.isActive
        sourceComponent: CallOverlay {
            darkMode: appRoot.darkMode
            accentColor: appRoot.accentColor
        }
    }

    function toggleMaximize() {
        // showMaximized()/showNormal() rather than assigning `visibility`.
        // Assigning the property is a no-op when Qt already believes the
        // window is in that state, which is exactly what happens if its idea
        // of the state has drifted from the real one - the click then appears
        // to do nothing (bar the icon flipping) and a second click is needed.
        // These call through to the platform window every time.
        if (appRoot.windowMaximized) {
            appRoot.showNormal()
        } else {
            appRoot.showMaximized()
        }
    }

    // ---- Unlock encrypted messages ----
    // Shown when this device is signed in but holds no E2EE identity - the
    // normal state after a QR sign-in, which grants a session but deliberately
    // not message access. Without this the user gets a working app whose sends
    // fail for no visible reason.
    Dialog {
        id: vaultUnlockDialog
        anchors.centerIn: parent
        width: Math.min(400, parent.width * 0.9)
        modal: true
        closePolicy: Popup.NoAutoClose
        title: "Unlock your messages"

        property string errorText: ""
        property bool busy: false

        background: Rectangle {
            radius: 18
            color: appRoot.darkMode ? "#1A1714" : "#FFFFFF"
            border.width: 1
            border.color: appRoot.darkMode ? "#2A2418" : "#E5E5EA"
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                font.pixelSize: 13
                color: appRoot.darkMode ? "#C9C2B4" : "#3C3C43"
                text: "You're signed in, but this device can't read your " +
                      "encrypted messages yet. Enter your account password to " +
                      "unlock them here."
            }

            TextField {
                id: vaultPasswordField
                Layout.fillWidth: true
                echoMode: TextInput.Password
                placeholderText: "Account password"
                enabled: !vaultUnlockDialog.busy
                onAccepted: vaultUnlockDialog.submit()
            }

            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                visible: vaultUnlockDialog.errorText !== ""
                text: vaultUnlockDialog.errorText
                color: "#D9544F"
                font.pixelSize: 12
            }

            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                font.pixelSize: 11
                color: appRoot.darkMode ? "#8A8175" : "#8E8E93"
                // Be explicit that this is separate from signing in, and that
                // there is a second route if the password no longer works.
                text: "If you've reset your password, use your recovery key instead."
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: 8

                Button {
                    text: "Use recovery key"
                    flat: true
                    enabled: !vaultUnlockDialog.busy
                    onClicked: {
                        vaultUnlockDialog.errorText = ""
                        recoveryKeyDialog.open()
                        vaultUnlockDialog.close()
                    }
                }
                Item { Layout.fillWidth: true }
                Button {
                    text: "Later"
                    flat: true
                    enabled: !vaultUnlockDialog.busy
                    onClicked: vaultUnlockDialog.close()
                }
                Button {
                    text: vaultUnlockDialog.busy ? "Unlocking…" : "Unlock"
                    enabled: !vaultUnlockDialog.busy && vaultPasswordField.text.length > 0
                    onClicked: vaultUnlockDialog.submit()
                }
            }
        }

        function submit() {
            if (vaultPasswordField.text.length === 0) return
            vaultUnlockDialog.busy = true
            vaultUnlockDialog.errorText = ""
            authService.unlockVaultWithPassword(vaultPasswordField.text)
        }
    }

    Dialog {
        id: recoveryKeyDialog
        anchors.centerIn: parent
        width: Math.min(420, parent.width * 0.9)
        modal: true
        title: "Enter recovery key"

        background: Rectangle {
            radius: 18
            color: appRoot.darkMode ? "#1A1714" : "#FFFFFF"
            border.width: 1
            border.color: appRoot.darkMode ? "#2A2418" : "#E5E5EA"
        }

        contentItem: ColumnLayout {
            spacing: 12
            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                font.pixelSize: 13
                color: appRoot.darkMode ? "#C9C2B4" : "#3C3C43"
                text: "Paste the recovery key you saved when encryption was set up."
            }
            TextField {
                id: recoveryKeyField
                Layout.fillWidth: true
                placeholderText: "Recovery key"
            }
            RowLayout {
                Layout.fillWidth: true
                Item { Layout.fillWidth: true }
                Button {
                    text: "Cancel"
                    flat: true
                    onClicked: recoveryKeyDialog.close()
                }
                Button {
                    text: "Unlock"
                    enabled: recoveryKeyField.text.length > 0
                    onClicked: {
                        authService.unlockWithRecoveryKey(recoveryKeyField.text.trim())
                        recoveryKeyDialog.close()
                    }
                }
            }
        }
    }

    Connections {
        target: authService

        // Signed in with no usable identity - prompt rather than let sends fail.
        function onVaultUnlockRequired() {
            vaultPasswordField.text = ""
            vaultUnlockDialog.errorText = ""
            vaultUnlockDialog.busy = false
            vaultUnlockDialog.open()
        }
        function onVaultUnlocked() {
            vaultUnlockDialog.busy = false
            vaultUnlockDialog.close()
        }
        function onVaultNeedsRecovery(message) {
            vaultUnlockDialog.busy = false
            vaultUnlockDialog.errorText = message
        }
    }

}
