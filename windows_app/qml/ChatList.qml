import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15

Rectangle {
    id: chatListRoot

    // The Window attached type only attaches to Item-derived elements, so it
    // can't be referenced directly from inside a Behavior - resolve it once
    // here instead and have the Behaviors below read this plain property.
    property bool instantThemeActive: Window.window ? Window.window.instantTheme : false

    // External properties from parent
    property bool darkMode: true
    property color bgColor: "#15152B"
    property color surfaceColor: "#1B1B36"
    property color surfaceColorHover: "#22224A"
    property color textColor: "#EDEDF2"
    property color textSecondary: "#9494AC"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#6C63FF"
    property color onlineColor: "#4CAF50"
    property color offlineColor: "#9E9E9E"

    color: bgColor

    // Signals
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
                color: newGroupMouse.pressed ? Qt.rgba(108/255, 99/255, 255/255, 0.22)
                       : (newGroupMouse.containsMouse ? Qt.rgba(108/255, 99/255, 255/255, 0.12) : "transparent")
                Behavior on color {
                    enabled: !chatListRoot.instantThemeActive
                    ColorAnimation { duration: 150; easing.type: Easing.OutCubic }
                }
                Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

                Canvas {
                    anchors.centerIn: parent
                    width: 17
                    height: 14
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        ctx.strokeStyle = chatListRoot.textSecondary
                        ctx.fillStyle = chatListRoot.textSecondary
                        ctx.lineWidth = 1.5
                        // Two overlapping person glyphs to read as "group"
                        ctx.beginPath(); ctx.arc(5.5, 3.5, 2.6, 0, Math.PI * 2); ctx.fill()
                        ctx.beginPath(); ctx.arc(11, 4.2, 2.1, 0, Math.PI * 2); ctx.fill()
                        ctx.beginPath()
                        ctx.moveTo(0.5, 13.5); ctx.arcTo(0.5, 8, 5.5, 7, 5); ctx.arcTo(10.5, 8, 10.5, 13.5, 5)
                        ctx.fill()
                        ctx.beginPath()
                        ctx.moveTo(9, 13.5); ctx.arcTo(9, 9, 11, 7.5, 4); ctx.arcTo(16.5, 9, 16.5, 13.5, 4)
                        ctx.fill()
                        // plus badge
                        ctx.strokeStyle = chatListRoot.accentColor
                        ctx.lineWidth = 1.8
                        ctx.beginPath(); ctx.moveTo(14, 1); ctx.lineTo(14, 5); ctx.stroke()
                        ctx.beginPath(); ctx.moveTo(12, 3); ctx.lineTo(16, 3); ctx.stroke()
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
                color: searchMouse.pressed ? Qt.rgba(108/255, 99/255, 255/255, 0.22)
                       : (searchVisible || searchMouse.containsMouse) ? Qt.rgba(108/255, 99/255, 255/255, 0.12) : "transparent"
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
                        height: 76

                        Rectangle {
                            anchors.fill: parent
                            anchors.leftMargin: 8
                            anchors.rightMargin: 8
                            anchors.topMargin: 2
                            anchors.bottomMargin: 2
                            radius: 14
                            color: itemMouse.pressed ? Qt.rgba(108/255, 99/255, 255/255, 0.16)
                                   : (itemMouse.containsMouse ? chatListRoot.surfaceColorHover : "transparent")
                            Behavior on color {
                                enabled: !chatListRoot.instantThemeActive
                                ColorAnimation { duration: 130; easing.type: Easing.OutCubic }
                            }

                            MouseArea {
                                id: itemMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: chatListRoot.chatSelected(model.chatId, model.chatName, model.otherUserId, model.online, model.chatType, model.avatarUrl)
                            }

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 14
                                spacing: 12

                                // Avatar
                                Item {
                                    Layout.preferredWidth: 48
                                    Layout.preferredHeight: 48

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
                                    }
                                }

                                // Name + last message
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 4

                                    Text {
                                        Layout.fillWidth: true
                                        text: model.chatName
                                        font.pixelSize: 15
                                        font.weight: Font.DemiBold
                                        color: chatListRoot.textColor
                                        elide: Text.ElideRight
                                    }

                                    Text {
                                        Layout.fillWidth: true
                                        text: model.lastMessage
                                        font.pixelSize: 13
                                        color: model.unreadCount > 0 ? chatListRoot.textColor : chatListRoot.textSecondary
                                        font.weight: model.unreadCount > 0 ? Font.Medium : Font.Normal
                                        elide: Text.ElideRight
                                    }
                                }

                                // Time + unread badge
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

                                    Rectangle {
                                        Layout.alignment: Qt.AlignRight
                                        Layout.preferredWidth: Math.max(20, unreadText.implicitWidth + 11)
                                        Layout.preferredHeight: 20
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

                // Empty state
                Item {
                    width: parent.width
                    height: chatRepeater.count === 0 ? 220 : 0
                    visible: chatRepeater.count === 0

                    ColumnLayout {
                        anchors.centerIn: parent
                        spacing: 14

                        Rectangle {
                            Layout.alignment: Qt.AlignHCenter
                            width: 64
                            height: 64
                            radius: 32
                            color: chatListRoot.surfaceColor

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
        var isOnline = chatData.is_online || false

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
        if (chatId === chatListRoot.activeChatId) return

        var preview = chatService.decryptMessage(chatId, message.content, message.encrypted === true)

        var found = false
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].chatId === chatId) {
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
                chatModel.setProperty(j, "unreadCount", (chatModel.get(j).unreadCount || 0) + 1)
                chatModel.setProperty(j, "lastMessage", preview)
                break
            }
        }
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
        for (var i = 0; i < originalChats.length; i++) {
            if (originalChats[i].otherUserId === userId) originalChats[i].online = online
        }
        for (var j = 0; j < chatModel.count; j++) {
            if (chatModel.get(j).otherUserId === userId) chatModel.setProperty(j, "online", online)
        }
    }

    Component.onCompleted: {
        // Connect to chat service signals
        if (chatService !== undefined && chatService !== null) {
            chatService.chatsFetched.connect(onChatsFetched)
            chatService.chatError.connect(onChatError)
            chatService.chatRead.connect(onChatRead)
            loadChatsFromAPI()
        } else {
            populateChatModel([])
        }
        if (websocketService !== undefined && websocketService !== null) {
            websocketService.messageReceived.connect(onMessageReceived)
            websocketService.presenceChanged.connect(onPresenceChanged)
            websocketService.connected.connect(onWsReconnected)
        }
    }
}
