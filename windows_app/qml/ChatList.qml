import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15

Item {
    id: chatListRoot

    // The Window attached type only attaches to Item-derived elements, so it
    // can't be referenced directly from inside a Behavior - resolve it once
    // here instead and have the Behaviors below read this plain property.
    property bool instantThemeActive: Window.window ? Window.window.instantTheme : false

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#050403"
    property color surfaceColor: "#0C0A08"
    property color surfaceColorHover: "#16120E"
    property color textColor: "#F0EAD6"
    property color textSecondary: "#A39A8A"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#C9A961"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"

    // Sidebar as a glass surface over main.qml's AmbientGlow, rather than a
    // flat opaque fill - edge-to-edge (no radius/margin) since it docks
    // directly against the window's left edge and the title bar above it.
    GlassPanel {
        anchors.fill: parent
        radius: 0
        darkMode: chatListRoot.darkMode
    }

    // Soft top light wash - modern depth cue over the glass plate.
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 120
        z: 0
        gradient: Gradient {
            GradientStop {
                position: 0.0
                color: chatListRoot.darkMode ? Qt.rgba(1, 1, 1, 0.06)
                                             : Qt.rgba(1, 1, 1, 0.35)
            }
            GradientStop { position: 1.0; color: Qt.rgba(1, 1, 1, 0) }
        }
    }

    // Right-edge light seam separating sidebar from the chat pane.
    Rectangle {
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        width: 1
        z: 2
        gradient: Gradient {
            GradientStop {
                position: 0.0
                color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                               chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.22 : 0.18)
            }
            GradientStop {
                position: 0.5
                color: chatListRoot.darkMode ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.12)
            }
            GradientStop {
                position: 1.0
                color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                               chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.08 : 0.06)
            }
        }
    }

    // Soft shadow cast onto the chat view from the sidebar edge.
    Rectangle {
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.left: parent.right
        width: 18
        z: 1
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop {
                position: 0.0
                color: chatListRoot.darkMode ? Qt.rgba(0, 0, 0, 0.45) : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.14)
            }
            GradientStop { position: 1.0; color: Qt.rgba(0, 0, 0, 0) }
        }
    }

    // Signals
    // Right-click menu on a chat row, plus the confirmation it opens.
    Menu {
        id: chatContextMenu
        property string chatId: ""
        property string chatName: ""
        property bool isGroup: false

        background: GlassPanel {
            implicitWidth: 180
            darkMode: chatListRoot.darkMode
            radius: 8
            sheen: false
        }

        MenuItem {
            text: chatContextMenu.isGroup ? "Delete group chat" : "Delete chat"
            onTriggered: deleteChatConfirm.open()
            contentItem: Text {
                text: parent.text
                font.pixelSize: 13
                color: "#FF6B6B"
                verticalAlignment: Text.AlignVCenter
                leftPadding: 10
            }
            background: Rectangle {
                implicitHeight: 34
                color: parent.hovered ? Qt.rgba(1, 0.42, 0.42, 0.12) : "transparent"
                radius: 6
            }
        }
    }

    Dialog {
        id: deleteChatConfirm
        modal: true
        anchors.centerIn: Overlay.overlay
        width: 320
        padding: 20
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: GlassPanel {
            darkMode: chatListRoot.darkMode
            radius: 12
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                Layout.fillWidth: true
                text: "Delete chat?"
                font.pixelSize: 16
                font.bold: true
                color: chatListRoot.textColor
            }

            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                font.pixelSize: 13
                color: chatListRoot.textSecondary
                // Says plainly what this does and does not do - the backend
                // only stamps left_at on this user's own participant row.
                text: "\"" + chatContextMenu.chatName + "\" will be removed from your chat list. "
                      + (chatContextMenu.isGroup
                         ? "Other members keep the group and its messages."
                         : "The other person keeps their copy, and a new message will bring the chat back.")
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.topMargin: 4
                spacing: 10

                Item { Layout.fillWidth: true }

                Text {
                    text: "Cancel"
                    font.pixelSize: 13
                    color: chatListRoot.textSecondary
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -8
                        cursorShape: Qt.PointingHandCursor
                        onClicked: deleteChatConfirm.close()
                    }
                }

                Text {
                    text: "Delete"
                    font.pixelSize: 13
                    font.bold: true
                    color: "#FF6B6B"
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -8
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            chatService.deleteChat(chatContextMenu.chatId)
                            deleteChatConfirm.close()
                        }
                    }
                }
            }
        }
    }

    Connections {
        target: typeof chatService !== "undefined" ? chatService : null
        function onChatDeleted(chatId) {
            for (var i = 0; i < chatModel.count; i++) {
                if (chatModel.get(i).chatId === chatId) {
                    chatModel.remove(i)
                    break
                }
            }
            // originalChats backs the search filter; leaving the row there
            // would resurrect the chat the moment the user typed a query.
            for (var j = originalChats.length - 1; j >= 0; j--) {
                if (originalChats[j].chatId === chatId) originalChats.splice(j, 1)
            }
            chatListRoot.chatDeleted(chatId)
        }
    }

    signal chatDeleted(string chatId)
    signal chatSelected(string chatId, string chatName, string otherUserId, bool online, string chatType, string avatarUrl)
    signal newChatClicked()
    signal newGroupClicked()

    property bool searchVisible: false
    property var onlineUsers: []
    property var originalChats: []
    property bool chatsLoaded: false
    // Whichever chat is currently open in the main window (set by main.qml),
    // so a live-arriving message for it doesn't also bump its own unread badge.
    property string activeChatId: ""

    ListModel {
        id: chatModel
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Header
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.topMargin: 20
            Layout.bottomMargin: 16
            spacing: 8

            Text {
                id: chatsTitle
                Layout.fillWidth: true
                text: "Chats"
                font.pixelSize: 24
                font.weight: Font.Bold
                font.letterSpacing: -0.3
                color: chatListRoot.textColor
                Behavior on color {
                    enabled: !chatListRoot.instantThemeActive
                    ColorAnimation { duration: 200 }
                }
            }

            Rectangle {
                Layout.preferredWidth: 38
                Layout.preferredHeight: 38
                radius: 11
                scale: newGroupMouse.pressed ? 0.94 : 1.0
                color: newGroupMouse.pressed ? Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.22)
                       : (newGroupMouse.containsMouse ? Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.12) : "transparent")
                Behavior on color {
                    enabled: !chatListRoot.instantThemeActive
                    ColorAnimation { duration: 150; easing.type: Easing.OutCubic }
                }
                Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

                Canvas {
                    id: newGroupIcon
                    anchors.centerIn: parent
                    // 24x24 Material viewBox, scaled to toolbar size.
                    width: 20
                    height: 20
                    // Canvas does not repaint when a colour binding changes.
                    Connections {
                        target: chatListRoot
                        function onTextSecondaryChanged() { newGroupIcon.requestPaint() }
                    }
                    onPaint: {
                        // Faithful Material Icons Outlined "group_add" (the
                        // same glyph Android uses via Icons.Outlined.GroupAdd):
                        // two people on the left, a bare plus on the right,
                        // all one tint. Drawn from the 24x24 SVG paths.
                        var ctx = getContext("2d")
                        ctx.reset()
                        var s = width / 24
                        ctx.scale(s, s)
                        ctx.fillStyle = chatListRoot.textSecondary

                        // Plus
                        ctx.beginPath()
                        ctx.moveTo(22, 9)
                        ctx.lineTo(22, 7)
                        ctx.lineTo(20, 7)
                        ctx.lineTo(20, 9)
                        ctx.lineTo(18, 9)
                        ctx.lineTo(18, 11)
                        ctx.lineTo(20, 11)
                        ctx.lineTo(20, 13)
                        ctx.lineTo(22, 13)
                        ctx.lineTo(22, 11)
                        ctx.lineTo(24, 11)
                        ctx.lineTo(24, 9)
                        ctx.closePath()
                        ctx.fill()

                        // Front person head (ring via evenodd)
                        ctx.beginPath()
                        ctx.arc(8, 8, 4, 0, Math.PI * 2)
                        ctx.closePath()
                        ctx.arc(8, 8, 2, 0, Math.PI * 2, true)
                        ctx.closePath()
                        ctx.fill("evenodd")

                        // Front person body (outer silhouette + inner cutout)
                        ctx.beginPath()
                        ctx.moveTo(8, 13)
                        ctx.bezierCurveTo(5.33, 13, 0, 14.34, 0, 17)
                        ctx.lineTo(0, 20)
                        ctx.lineTo(16, 20)
                        ctx.lineTo(16, 17)
                        ctx.bezierCurveTo(16, 14.34, 10.67, 13, 8, 13)
                        ctx.closePath()
                        ctx.moveTo(14, 18)
                        ctx.lineTo(2, 18)
                        ctx.lineTo(2, 17.01)
                        ctx.bezierCurveTo(2.2, 16.29, 5.3, 15, 8, 15)
                        ctx.bezierCurveTo(10.7, 15, 13.8, 16.29, 14, 17)
                        ctx.lineTo(14, 18)
                        ctx.closePath()
                        ctx.fill("evenodd")

                        // Rear person head (crescent peeking behind)
                        ctx.beginPath()
                        ctx.moveTo(12.51, 4.05)
                        ctx.bezierCurveTo(13.43, 5.11, 14, 6.49, 14, 8)
                        ctx.bezierCurveTo(14, 9.51, 13.43, 10.89, 12.51, 11.95)
                        ctx.bezierCurveTo(14.47, 11.7, 16, 10.04, 16, 8)
                        ctx.bezierCurveTo(16, 5.96, 14.47, 4.3, 12.51, 4.05)
                        ctx.closePath()
                        ctx.fill()

                        // Rear person body
                        ctx.beginPath()
                        ctx.moveTo(16.53, 13.83)
                        ctx.bezierCurveTo(17.42, 14.66, 18, 15.7, 18, 17)
                        ctx.lineTo(18, 20)
                        ctx.lineTo(20, 20)
                        ctx.lineTo(20, 17)
                        ctx.bezierCurveTo(20, 15.55, 18.41, 14.49, 16.53, 13.83)
                        ctx.closePath()
                        ctx.fill()
                    }
                }

                ToolTip.visible: newGroupMouse.containsMouse
                ToolTip.text: "New group"
                ToolTip.delay: 400

                MouseArea {
                    id: newGroupMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: chatListRoot.newGroupClicked()
                }
            }

            Rectangle {
                Layout.preferredWidth: 38
                Layout.preferredHeight: 38
                radius: 11
                scale: searchMouse.pressed ? 0.94 : 1.0
                color: searchMouse.pressed ? Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.22)
                       : (searchVisible || searchMouse.containsMouse) ? Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.12) : "transparent"
                Behavior on color {
                    enabled: !chatListRoot.instantThemeActive
                    ColorAnimation { duration: 150; easing.type: Easing.OutCubic }
                }
                Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

                Canvas {
                    anchors.centerIn: parent
                    width: 16
                    height: 16
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = chatListRoot.searchVisible ? chatListRoot.accentColor : chatListRoot.textSecondary
                        ctx.lineWidth = 1.7
                        ctx.lineCap = "round"
                        ctx.beginPath(); ctx.arc(6.5, 6.5, 5, 0, Math.PI * 2); ctx.stroke()
                        ctx.beginPath(); ctx.moveTo(10.3, 10.3); ctx.lineTo(15, 15); ctx.stroke()
                    }
                }

                MouseArea {
                    id: searchMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        chatListRoot.searchVisible = !chatListRoot.searchVisible
                        if (chatListRoot.searchVisible) searchField.forceActiveFocus()
                        else { searchField.text = ""; filterChats("") }
                    }
                }
            }
        }

        // Connection status - a slim, dismissible-feeling banner rather than
        // replacing the "Chats" title text, so the header stays put and this
        // reads as "something in the background needs a moment" instead of
        // the whole page's identity flickering between two states.
        Rectangle {
            id: connectionBanner
            property string connState: websocketService !== undefined ? websocketService.connectionState : "connected"
            property bool connVisible: connState === "connecting" || connState === "reconnecting"

            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: connVisible ? 14 : 0
            Layout.preferredHeight: connVisible ? 38 : 0
            radius: 10
            color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.12)
            border.color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g, chatListRoot.accentColor.b, 0.35)
            border.width: 1
            clip: true
            opacity: connVisible ? 1 : 0
            Behavior on Layout.preferredHeight { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on Layout.bottomMargin { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
            Behavior on opacity { NumberAnimation { duration: 150 } }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 9

                Rectangle {
                    Layout.preferredWidth: 7
                    Layout.preferredHeight: 7
                    radius: 3.5
                    color: chatListRoot.accentColor
                    SequentialAnimation on opacity {
                        running: connectionBanner.connVisible
                        loops: Animation.Infinite
                        NumberAnimation { to: 0.25; duration: 600; easing.type: Easing.InOutSine }
                        NumberAnimation { to: 1.0; duration: 600; easing.type: Easing.InOutSine }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    text: connectionBanner.connState === "reconnecting" ? "Reconnecting…" : "Connecting…"
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    color: chatListRoot.accentColor
                    elide: Text.ElideRight
                }

                BusyIndicator {
                    Layout.preferredWidth: 16
                    Layout.preferredHeight: 16
                    running: connectionBanner.connVisible
                }
            }
        }

        // Search bar (expanded)
        Rectangle {
            id: searchBar
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: searchVisible ? 16 : 0
            Layout.preferredHeight: searchVisible ? 42 : 0
            radius: 12
            // Was a "rgba(r,g,b,a)" string literal - that syntax silently
            // drops the alpha channel in this Qt build (always resolves
            // fully opaque), which is why this rendered solid black in light
            // mode instead of a subtle tint. Qt.rgba() is the reliable form.
            color: darkMode ? Qt.rgba(1, 1, 1, 0.05) : Qt.rgba(43/255, 36/255, 24/255, 0.06)
            border.color: searchField.activeFocus ? chatListRoot.accentColor : Qt.rgba(1, 1, 1, 0.08)
            border.width: searchField.activeFocus ? 1.5 : 1
            clip: true
            opacity: searchVisible ? 1 : 0
            Behavior on Layout.preferredHeight { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            Behavior on Layout.bottomMargin { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            Behavior on opacity { NumberAnimation { duration: 150 } }
            Behavior on border.color { ColorAnimation { duration: 150 } }

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 8

                Canvas {
                    Layout.preferredWidth: 15
                    Layout.preferredHeight: 15
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = chatListRoot.textSecondary
                        ctx.lineWidth = 1.6
                        ctx.lineCap = "round"
                        ctx.beginPath(); ctx.arc(6, 6, 4.6, 0, Math.PI * 2); ctx.stroke()
                        ctx.beginPath(); ctx.moveTo(9.3, 9.3); ctx.lineTo(14, 14); ctx.stroke()
                    }
                }

                TextField {
                    id: searchField
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    leftPadding: 0
                    rightPadding: 0
                    verticalAlignment: TextInput.AlignVCenter
                    background: Item {}
                    placeholderText: "Search chats..."
                    placeholderTextColor: textSecondary
                    font.pixelSize: 13
                    color: textColor
                    selectByMouse: true
                    onTextChanged: filterChats(text)
                }
            }
        }

        // New chat button
        Rectangle {
            id: newChatButton
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: 20
            height: 46
            radius: 13
            scale: newChatMouse.pressed ? 0.98 : 1.0
            color: newChatMouse.pressed ? Qt.darker(chatListRoot.accentColor, 1.15)
                   : (newChatMouse.containsMouse ? Qt.lighter(chatListRoot.accentColor, 1.08) : chatListRoot.accentColor)
            Behavior on color {
                enabled: !chatListRoot.instantThemeActive
                ColorAnimation { duration: 150; easing.type: Easing.OutCubic }
            }
            Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

            // Soft accent glow behind the primary action
            Rectangle {
                anchors.fill: parent
                anchors.margins: -6
                radius: parent.radius + 6
                color: chatListRoot.accentColor
                opacity: newChatMouse.containsMouse ? 0.18 : 0.10
                z: -1
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }

            RowLayout {
                anchors.centerIn: parent
                spacing: 8

                Canvas {
                    width: 16
                    height: 16
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = "#FFFFFF"
                        ctx.lineWidth = 1.9
                        ctx.lineCap = "round"
                        ctx.beginPath(); ctx.moveTo(8, 1); ctx.lineTo(8, 15); ctx.stroke()
                        ctx.beginPath(); ctx.moveTo(1, 8); ctx.lineTo(15, 8); ctx.stroke()
                    }
                }
                Text {
                    text: "New Chat"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                    color: "#FFFFFF"
                }
            }

            MouseArea {
                id: newChatMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: chatListRoot.newChatClicked()
            }
        }

        // Online users
        Item {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: 8
            Layout.preferredHeight: onlineUsers.length > 0 ? 56 : 0
            visible: onlineUsers.length > 0

            ColumnLayout {
                anchors.fill: parent
                spacing: 8

                Text {
                    text: "ONLINE NOW"
                    font.pixelSize: 11
                    font.weight: Font.DemiBold
                    font.letterSpacing: 0.5
                    color: chatListRoot.textSecondary
                }

                RowLayout {
                    spacing: 12

                    Repeater {
                        model: onlineUsers.slice(0, 6)
                        Item {
                            width: 32
                            height: 32

                            // Gradient ring to signal "online" (story-ring style)
                            Rectangle {
                                anchors.fill: parent
                                radius: width / 2
                                gradient: Gradient {
                                    orientation: Gradient.Horizontal
                                    GradientStop { position: 0.0; color: chatListRoot.onlineColor }
                                    GradientStop { position: 1.0; color: chatListRoot.accentColor }
                                }
                            }

                            Rectangle {
                                anchors.centerIn: parent
                                width: parent.width - 4
                                height: parent.height - 4
                                radius: width / 2
                                color: Qt.darker(chatListRoot.accentColor, 1.3)

                                Text {
                                    anchors.centerIn: parent
                                    text: modelData ? modelData.substring(0, 1).toUpperCase() : "?"
                                    font.pixelSize: 11
                                    font.weight: Font.DemiBold
                                    color: "#FFFFFF"
                                }
                            }

                            ToolTip.visible: onlineHover.containsMouse
                            ToolTip.text: modelData || "Online"
                            MouseArea { id: onlineHover; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor }
                        }
                    }

                    Text {
                        text: "+" + (onlineUsers.length - 6)
                        font.pixelSize: 11
                        font.weight: Font.Medium
                        color: chatListRoot.textSecondary
                        visible: onlineUsers.length > 6
                    }

                    Item { Layout.fillWidth: true }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            height: 1
            color: chatListRoot.borderColor
        }

        // Chat list
        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            Column {
                width: parent.width
                topPadding: 6

                Repeater {
                    id: chatRepeater
                    model: chatModel

                    Item {
                        width: parent.width
                        height: 80
                        readonly property bool isActive: model.chatId === chatListRoot.activeChatId
                        readonly property bool lit: isActive || itemMouse.containsMouse

                        // Soft drop shadow under elevated / hovered rows.
                        Rectangle {
                            anchors.fill: card
                            anchors.topMargin: 5
                            anchors.leftMargin: 3
                            anchors.rightMargin: 3
                            anchors.bottomMargin: -3
                            radius: card.radius
                            color: chatListRoot.darkMode
                                   ? Qt.rgba(0, 0, 0, parent.lit ? 0.55 : 0.0)
                                   : Qt.rgba(43 / 255, 36 / 255, 24 / 255, parent.lit ? 0.16 : 0.0)
                            opacity: parent.lit ? 1 : 0
                            z: -2
                            Behavior on opacity { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                            Behavior on color { ColorAnimation { duration: 160 } }
                        }
                        Rectangle {
                            anchors.fill: card
                            anchors.margins: -1
                            anchors.topMargin: 1
                            radius: card.radius + 1
                            color: chatListRoot.darkMode
                                   ? Qt.rgba(0, 0, 0, parent.lit ? 0.28 : 0.0)
                                   : Qt.rgba(43 / 255, 36 / 255, 24 / 255, parent.lit ? 0.08 : 0.0)
                            opacity: parent.lit ? 1 : 0
                            z: -1
                            Behavior on opacity { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                        }

                        // Active-row gold ambient glow.
                        Rectangle {
                            anchors.fill: card
                            anchors.margins: -4
                            radius: card.radius + 4
                            visible: parent.isActive
                            color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                           chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.14 : 0.26)
                            z: -1
                        }

                        Rectangle {
                            id: card
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 10
                            anchors.topMargin: 3
                            anchors.bottomMargin: 3
                            radius: 16
                            color: {
                                if (parent.isActive)
                                    // Light cream needs a denser bronze wash — 0.12
                                    // washed out against #FAF6EE.
                                    return Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                   chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.16 : 0.30)
                                if (itemMouse.pressed)
                                    return Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                   chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.14 : 0.18)
                                if (itemMouse.containsMouse)
                                    return chatListRoot.darkMode ? Qt.rgba(1, 1, 1, 0.06)
                                                                 : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.07)
                                return "transparent"
                            }
                            border.width: parent.isActive ? (chatListRoot.darkMode ? 1 : 1.5)
                                          : (itemMouse.containsMouse ? 1 : 0)
                            border.color: parent.isActive
                                          ? Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                    chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.35 : 0.72)
                                          : (chatListRoot.darkMode ? Qt.rgba(1, 1, 1, 0.08)
                                                                   : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.10))
                            Behavior on color {
                                enabled: !chatListRoot.instantThemeActive
                                ColorAnimation { duration: 140; easing.type: Easing.OutCubic }
                            }
                            Behavior on border.color { ColorAnimation { duration: 140 } }

                            // Top catch-light on the card.
                            Rectangle {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                anchors.topMargin: 1
                                height: 1
                                radius: 0.5
                                visible: parent.parent.lit
                                color: chatListRoot.darkMode
                                       ? Qt.rgba(1, 1, 1, parent.parent.isActive ? 0.22 : 0.12)
                                       : Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                 chatListRoot.accentColor.b, parent.parent.isActive ? 0.55 : 0.28)
                            }

                            // Active indicator light bar.
                            Rectangle {
                                anchors.left: parent.left
                                anchors.top: parent.top
                                anchors.bottom: parent.bottom
                                anchors.leftMargin: 5
                                anchors.topMargin: 14
                                anchors.bottomMargin: 14
                                width: chatListRoot.darkMode ? 3 : 3.5
                                radius: 1.5
                                visible: parent.parent.isActive
                                color: chatListRoot.accentColor

                                Rectangle {
                                    anchors.centerIn: parent
                                    width: 10
                                    height: parent.height + 8
                                    radius: 5
                                    z: -1
                                    color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                   chatListRoot.accentColor.b, chatListRoot.darkMode ? 0.35 : 0.50)
                                }
                            }

                            MouseArea {
                                id: itemMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                acceptedButtons: Qt.LeftButton | Qt.RightButton
                                onClicked: function(mouse) {
                                    if (mouse.button === Qt.RightButton) {
                                        chatContextMenu.chatId = model.chatId
                                        chatContextMenu.chatName = model.chatName
                                        chatContextMenu.isGroup = model.chatType === "group"
                                        chatContextMenu.popup()
                                        return
                                    }
                                    chatListRoot.chatSelected(model.chatId, model.chatName, model.otherUserId, model.online, model.chatType, model.avatarUrl)
                                }
                            }

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 14
                                anchors.rightMargin: 14
                                spacing: 12

                                // Avatar
                                Item {
                                    Layout.preferredWidth: 48
                                    Layout.preferredHeight: 48

                                    // Soft avatar shadow.
                                    Rectangle {
                                        anchors.centerIn: parent
                                        anchors.verticalCenterOffset: 2
                                        width: parent.width - 2
                                        height: parent.height - 2
                                        radius: width / 2
                                        color: Qt.rgba(0, 0, 0, chatListRoot.darkMode ? 0.4 : 0.12)
                                        z: -1
                                    }

                                    Avatar {
                                        anchors.fill: parent
                                        name: model.chatName
                                        avatarUrl: model.avatarUrl || ""
                                        size: 48
                                    }

                                    Rectangle {
                                        anchors.bottom: parent.bottom
                                        anchors.right: parent.right
                                        width: 14
                                        height: 14
                                        radius: 7
                                        color: chatListRoot.onlineColor
                                        border.color: chatListRoot.bgColor
                                        border.width: 2.5
                                        visible: model.online

                                        Rectangle {
                                            anchors.centerIn: parent
                                            width: 18
                                            height: 18
                                            radius: 9
                                            z: -1
                                            color: Qt.rgba(chatListRoot.onlineColor.r, chatListRoot.onlineColor.g,
                                                           chatListRoot.onlineColor.b, 0.35)
                                            visible: parent.visible
                                        }
                                    }
                                }

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    Layout.preferredWidth: 0
                                    spacing: 4

                                    Text {
                                        Layout.fillWidth: true
                                        Layout.preferredWidth: 0
                                        text: model.chatName
                                        font.pixelSize: 15
                                        font.weight: Font.DemiBold
                                        color: chatListRoot.textColor
                                        elide: Text.ElideRight
                                        maximumLineCount: 1
                                    }

                                    Text {
                                        Layout.fillWidth: true
                                        Layout.preferredWidth: 0
                                        text: (model.lastMessage || "").replace(/\n/g, " ")
                                        font.pixelSize: 13
                                        color: model.unreadCount > 0 ? chatListRoot.textColor : chatListRoot.textSecondary
                                        font.weight: model.unreadCount > 0 ? Font.Medium : Font.Normal
                                        elide: Text.ElideRight
                                        maximumLineCount: 1
                                    }
                                }

                                ColumnLayout {
                                    Layout.alignment: Qt.AlignTop
                                    Layout.topMargin: 12
                                    spacing: 8

                                    Text {
                                        Layout.alignment: Qt.AlignRight
                                        text: model.timestamp
                                        font.pixelSize: 11
                                        font.weight: model.unreadCount > 0 ? Font.DemiBold : Font.Normal
                                        color: model.unreadCount > 0 ? chatListRoot.accentColor : chatListRoot.textSecondary
                                    }

                                    Item {
                                        Layout.alignment: Qt.AlignRight
                                        Layout.preferredWidth: unreadBadge.visible ? unreadBadge.width : 0
                                        Layout.preferredHeight: unreadBadge.visible ? unreadBadge.height : 0

                                        Rectangle {
                                            id: unreadGlow
                                            anchors.centerIn: unreadBadge
                                            width: unreadBadge.width + 10
                                            height: unreadBadge.height + 10
                                            radius: height / 2
                                            visible: unreadBadge.visible
                                            color: Qt.rgba(chatListRoot.accentColor.r, chatListRoot.accentColor.g,
                                                           chatListRoot.accentColor.b, 0.32)
                                        }

                                        Rectangle {
                                            id: unreadBadge
                                            anchors.right: parent.right
                                            width: Math.max(20, unreadText.implicitWidth + 11)
                                            height: 20
                                            radius: 10
                                            color: chatListRoot.accentColor
                                            visible: model.unreadCount > 0

                                            Text {
                                                id: unreadText
                                                anchors.centerIn: parent
                                                text: model.unreadCount > 99 ? "99+" : model.unreadCount
                                                font.pixelSize: 11
                                                font.weight: Font.DemiBold
                                                color: "#FFFFFF"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Empty state
                Item {
                    width: parent.width
                    height: chatRepeater.count === 0 ? 220 : 0
                    visible: chatRepeater.count === 0

                    ColumnLayout {
                        anchors.centerIn: parent
                        spacing: 14

                        GlassPanel {
                            Layout.alignment: Qt.AlignHCenter
                            width: 64
                            height: 64
                            radius: 32
                            darkMode: chatListRoot.darkMode

                            Canvas {
                                anchors.centerIn: parent
                                width: 26
                                height: 26
                                onPaint: {
                                    var ctx = getContext("2d")
                                    ctx.reset()
                                    var w = width, h = height * 0.72, r = 5
                                    ctx.fillStyle = chatListRoot.textSecondary
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
                            Layout.alignment: Qt.AlignHCenter
                            text: searchField.text.length > 0 ? "No chats match your search" : "No conversations yet"
                            font.pixelSize: 13
                            font.weight: Font.Medium
                            color: chatListRoot.textSecondary
                        }
                    }
                }
            }
        }
    }

    function filterChats(query) {
        chatModel.clear()
        const filtered = query.length === 0 ? originalChats : originalChats.filter(c =>
            c.chatName.toLowerCase().includes(query.toLowerCase())
        )
        for (let i = 0; i < filtered.length; i++) {
            chatModel.append(filtered[i])
        }
    }

    function chatNameFor(chatId) {
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatId === chatId) return originalChats[i].chatName
        }
        return "New message"
    }

    // Load chats from backend API
    function loadChatsFromAPI() {
        if (chatsLoaded) return
        if (chatService === undefined || chatService === null) {
            console.log("ChatService not available")
            populateChatModel([])
            return
        }

        if (chatService.isLoading) {
            console.log("Chats already loading...")
            return
        }

        console.log("Fetching chats from backend API...")
        chatService.fetchChats()
    }

    // Parse a chat item from the API response
    function parseChatItem(chatData) {
        var chatId = chatData.id || ""
        var chatName = chatData.name || ""
        var chatType = chatData.type || "direct"
        var otherUser = chatData.other_user || {}
        var otherUserId = otherUser.id || ""
        var lastMessageData = chatData.last_message || {}
        // A group has no presence of its own. The backend fills other_user (and
        // hence is_online) with an arbitrary member for group rows, so a green
        // dot on a group avatar was really reporting "one random participant
        // happens to be online" - which reads as a statement about the group
        // and means nothing.
        var isOnline = (chatType === "group") ? false : (chatData.is_online || false)

        // For direct chats, always prefer the other user's name - chat.name is
        // just a generic "Direct Chat" placeholder set at creation time, never
        // actually empty, so it can't be used as an "unset" signal here.
        if (chatType === "direct" && Object.keys(otherUser).length > 0) {
            chatName = otherUser.display_name || otherUser.username || chatName || "Unknown"
            isOnline = otherUser.is_online || isOnline
        }

        // A group's picture is its own; a direct chat's is the other person's -
        // there's no per-chat picture distinct from the two participants.
        var avatarUrl = chatType === "group" ? (chatData.avatar_url || "") : (otherUser.avatar_url || "")

        // Parse last message
        var lastMessage = lastMessageData.content || ""
        var timestamp = ""
        if (lastMessageData.created_at !== undefined) {
            var date = new Date(lastMessageData.created_at)
            if (!isNaN(date.getTime())) {
                var today = new Date()
                var yesterday = new Date(today)
                yesterday.setDate(yesterday.getDate() - 1)

                if (date.toDateString() === today.toDateString()) {
                    timestamp = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                } else if (date.toDateString() === yesterday.toDateString()) {
                    timestamp = "Yesterday"
                } else {
                    timestamp = date.toLocaleDateString([], { day: 'numeric', month: 'short' })
                }
            }
        }

        // Also check last_message_at as fallback
        if (timestamp === "" && chatData.last_message_at !== undefined) {
            var date = new Date(chatData.last_message_at)
            if (!isNaN(date.getTime())) {
                var today = new Date()
                var yesterday = new Date(today)
                yesterday.setDate(yesterday.getDate() - 1)

                if (date.toDateString() === today.toDateString()) {
                    timestamp = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                } else if (date.toDateString() === yesterday.toDateString()) {
                    timestamp = "Yesterday"
                } else {
                    timestamp = date.toLocaleDateString([], { day: 'numeric', month: 'short' })
                }
            }
        }

        var unreadCount = chatData.unread_count || 0

        return {
            chatId: chatId,
            chatName: chatName,
            otherUserId: otherUserId,
            lastMessage: lastMessage,
            timestamp: timestamp,
            unreadCount: unreadCount,
            online: isOnline,
            avatarUrl: avatarUrl,
            chatType: chatType,
            _rawData: chatData
        }
    }

    // Populate the chat model from parsed data
    function populateChatModel(chatsData) {
        originalChats = []
        chatModel.clear()

        for (var i = 0; i < chatsData.length; i++) {
            var parsed = parseChatItem(chatsData[i])
            originalChats.push(parsed)
            chatModel.append(parsed)
        }

        chatsLoaded = true
        console.log("Loaded", chatsData.length, "chats from API")
    }

    // Handle chat service response. chatsList entries already match the
    // backend's JSON shape (see ChatService::parseChatItem), so no
    // reconstruction is needed here.
    function onChatsFetched(chatsList) {
        if (!chatsList || chatsList.length === 0) {
            console.log("No chats yet")
            populateChatModel([])
            return
        }

        populateChatModel(chatsList)

        // Join every known chat's WebSocket room so real-time pushes (and
        // notifications) arrive even for conversations that aren't currently
        // open - joining only happens on-demand otherwise (see ChatView.qml).
        for (var i = 0; i < chatsList.length; i++) {
            if (chatsList[i].id) websocketService.joinChat(chatsList[i].id)
        }
    }

    // Handle chat service error
    function onChatError(errorMsg) {
        console.log("Error loading chats:", errorMsg)
        // Don't wipe out chats already on screen (e.g. loaded from cache)
        // just because the follow-up network refresh failed - only show the
        // empty state if we never had anything to show in the first place.
        if (originalChats.length === 0) {
            populateChatModel([])
        }
    }

    // Zero out a chat's unread badge immediately once it's been marked read,
    // instead of waiting for the next full chat-list refetch.
    function onChatRead(chatId) {
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatId === chatId) originalChats[i].unreadCount = 0
        }
        for (var j = 0; j < chatModel.count; j++) {
            if (chatModel.get(j).chatId === chatId) chatModel.setProperty(j, "unreadCount", 0)
        }
    }

    // Live-update the badge/preview when a message arrives via WebSocket for
    // a chat that isn't the one currently open - otherwise the list only
    // ever reflects unread state from the last full REST refetch.
    function onMessageReceived(chatId, message) {
        if (authService !== undefined && message.senderId === authService.currentUserId) return

        var preview = chatService.decryptMessage(chatId, message.content, message.encrypted === true)
        // Always refresh the snippet - even for the open chat. Skipping that
        // used to leave the sidebar stuck on whatever was last when the chat
        // was opened. Only the unread badge is suppressed for the active chat
        // (you're already looking at it, so it shouldn't count as unread).
        var isActive = chatId === chatListRoot.activeChatId

        var found = false
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatId === chatId) {
                if (!isActive)
                    originalChats[i].unreadCount = (originalChats[i].unreadCount || 0) + 1
                originalChats[i].lastMessage = preview
                found = true
                break
            }
        }

        if (!found) {
            // Unknown chat (e.g. a brand-new conversation) - refetch to pick it up
            chatService.fetchChats()
            return
        }

        for (var j = 0; j < chatModel.count; j++) {
            if (chatModel.get(j).chatId === chatId) {
                if (!isActive)
                    chatModel.setProperty(j, "unreadCount", (chatModel.get(j).unreadCount || 0) + 1)
                chatModel.setProperty(j, "lastMessage", preview)
                break
            }
        }
    }

    // Symmetric to onMessageReceived: after a delete (local or for_everyone),
    // replace the preview with whatever the cache now says is latest - empty
    // when the thread was cleared entirely.
    function onChatLastMessageChanged(chatId, preview) {
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatId === chatId) {
                originalChats[i].lastMessage = preview
                break
            }
        }
        for (var j = 0; j < chatModel.count; j++) {
            if (chatModel.get(j).chatId === chatId) {
                chatModel.setProperty(j, "lastMessage", preview)
                break
            }
        }
    }

    // for_everyone retraction over the socket - drop the cache row and refresh
    // the preview (ChatService's own DELETE path already does this).
    function onMessageDeletedRemotely(chatId, messageId) {
        if (chatService !== undefined) chatService.noteMessageDeleted(chatId, messageId)
    }

    // Refresh the whole list the moment we (re)connect - otherwise anything
    // sent or changed while we were offline is silently missed until the
    // app restarts or the user happens to navigate away and back.
    function onWsReconnected() {
        chatService.fetchChats()
    }

    // Live-update a contact's online dot the instant they connect/disconnect,
    // instead of only after the next full chat-list refetch.
    function onPresenceChanged(userId, online) {
        // Group rows are skipped: they carry an arbitrary member in
        // otherUserId, so without this a presence event for that member would
        // put the dot back on the group despite parseChat clearing it.
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatType === "group") continue
            if (originalChats[i].otherUserId === userId) originalChats[i].online = online
        }
        for (var j = 0; j < chatModel.count; j++) {
            if (chatModel.get(j).chatType === "group") continue
            if (chatModel.get(j).otherUserId === userId) chatModel.setProperty(j, "online", online)
        }
    }

    Component.onCompleted: {
        // Connect to chat service signals
        if (chatService !== undefined && chatService !== null) {
            chatService.chatsFetched.connect(onChatsFetched)
            chatService.chatError.connect(onChatError)
            chatService.chatRead.connect(onChatRead)
            chatService.chatLastMessageChanged.connect(onChatLastMessageChanged)
            loadChatsFromAPI()
        } else {
            populateChatModel([])
        }
        if (websocketService !== undefined && websocketService !== null) {
            websocketService.messageReceived.connect(onMessageReceived)
            websocketService.messageDeletedRemotely.connect(onMessageDeletedRemotely)
            websocketService.presenceChanged.connect(onPresenceChanged)
            websocketService.connected.connect(onWsReconnected)
        }
    }
}
