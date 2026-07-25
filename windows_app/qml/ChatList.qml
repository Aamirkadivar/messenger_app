import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Rectangle {
    id: chatListRoot

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
    signal chatSelected(string chatId, string chatName)
    signal newChatClicked()

    property bool searchVisible: false
    property var onlineUsers: []
    property var originalChats: []
    property bool chatsLoaded: false
    // Whichever chat is currently open in the main window (set by main.qml),
    // so a live-arriving message for it doesn't also bump its own unread badge.
    property string activeChatId: ""

    // Avatar color palette for users
    property var avatarPalette: ["#6C63FF", "#4CAF50", "#FF9800", "#E91E63", "#9C27B0", "#00BCD4", "#FF5722", "#795548", "#607D8B", "#3F51B5"]

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
                Layout.fillWidth: true
                text: "Chats"
                font.pixelSize: 24
                font.weight: Font.Bold
                font.letterSpacing: -0.3
                color: chatListRoot.textColor
            }

            Rectangle {
                Layout.preferredWidth: 38
                Layout.preferredHeight: 38
                radius: 11
                scale: searchMouse.pressed ? 0.94 : 1.0
                color: searchMouse.pressed ? Qt.rgba(108/255, 99/255, 255/255, 0.22)
                       : (searchVisible || searchMouse.containsMouse) ? Qt.rgba(108/255, 99/255, 255/255, 0.12) : "transparent"
                Behavior on color { ColorAnimation { duration: 150; easing.type: Easing.OutCubic } }
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

        // Search bar (expanded)
        Rectangle {
            id: searchBar
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: searchVisible ? 16 : 0
            Layout.preferredHeight: searchVisible ? 42 : 0
            radius: 12
            color: darkMode ? "rgba(255,255,255,0.05)" : "rgba(0,0,0,0.03)"
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
            Behavior on color { ColorAnimation { duration: 150; easing.type: Easing.OutCubic } }
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
                            Behavior on color { ColorAnimation { duration: 130; easing.type: Easing.OutCubic } }

                            MouseArea {
                                id: itemMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: chatListRoot.chatSelected(model.chatId, model.chatName)
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

                                    Rectangle {
                                        anchors.fill: parent
                                        radius: 24
                                        color: model.avatarColor !== "" ? model.avatarColor : chatListRoot.accentColor

                                        Text {
                                            anchors.centerIn: parent
                                            text: (model.chatName && model.chatName.length > 0) ? model.chatName.substring(0, 1).toUpperCase() : "?"
                                            font.pixelSize: 18
                                            font.weight: Font.DemiBold
                                            color: "#FFFFFF"
                                        }
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
        var lastMessageData = chatData.last_message || {}
        var avatarColor = chatData.avatar_url !== undefined ? chatData.avatar_url : ""
        var isOnline = chatData.is_online || false

        // For direct chats, always prefer the other user's name - chat.name is
        // just a generic "Direct Chat" placeholder set at creation time, never
        // actually empty, so it can't be used as an "unset" signal here.
        if (chatType === "direct" && Object.keys(otherUser).length > 0) {
            chatName = otherUser.display_name || otherUser.username || chatName || "Unknown"
            isOnline = otherUser.is_online || isOnline
        }

        // Generate avatar color based on name
        if (avatarColor === "") {
            var hash = 0
            for (var i = 0; i < chatName.length; i++) {
                hash = chatName.charCodeAt(i) + ((hash << 5) - hash)
            }
            var index = Math.abs(hash) % avatarPalette.length
            avatarColor = avatarPalette[index]
        }

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
            lastMessage: lastMessage,
            timestamp: timestamp,
            unreadCount: unreadCount,
            online: isOnline,
            avatarColor: avatarColor,
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
        populateChatModel([])
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
        }
    }
}
