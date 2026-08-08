from pathlib import Path

# MessageBubble.qml - reply properties + quote UI
mb = Path(r"D:\messenger_app\windows_app\qml\MessageBubble.qml")
t = mb.read_text(encoding="utf-8")
if "replyToId" not in t:
    t = t.replace(
        '    property string forwardedFromName: ""\n'
        '    /** Long text starts collapsed; user toggles Show more / Show less. */\n',
        '    property string forwardedFromName: ""\n'
        '    property string replyToId: ""\n'
        '    property string replySenderName: ""\n'
        '    property string replyPreview: ""\n'
        '    property bool replyAvailable: false\n'
        '    signal replyQuoteClicked()\n'
        '    /** Long text starts collapsed; user toggles Show more / Show less. */\n',
        1,
    )
    # Insert quote after forwardLabel
    old = '''            Text {
                id: forwardLabel
                visible: bubbleRoot.isForwarded
                text: bubbleRoot.forwardedFromName.length > 0
                      ? ("Forwarded from " + bubbleRoot.forwardedFromName)
                      : "Forwarded"
                font.pixelSize: 11
                font.italic: true
                color: isMine ? Qt.rgba(1, 1, 1, 0.75) : bubbleRoot.accentColor
                elide: Text.ElideRight
                width: parent.width
            }
'''
    new = old + '''
            Rectangle {
                id: replyQuote
                visible: bubbleRoot.replyToId.length > 0
                width: parent.width
                height: replyQuoteCol.implicitHeight + 10
                radius: 8
                color: isMine ? Qt.rgba(1, 1, 1, 0.14) : Qt.rgba(bubbleRoot.accentColor.r, bubbleRoot.accentColor.g, bubbleRoot.accentColor.b, 0.12)
                Row {
                    anchors.fill: parent
                    anchors.margins: 6
                    spacing: 8
                    Rectangle {
                        width: 3
                        height: parent.height
                        radius: 1.5
                        color: isMine ? Qt.rgba(1, 1, 1, 0.85) : bubbleRoot.accentColor
                    }
                    Column {
                        id: replyQuoteCol
                        width: parent.width - 11
                        spacing: 2
                        Text {
                            width: parent.width
                            text: bubbleRoot.replyAvailable
                                  ? (bubbleRoot.replySenderName.length > 0 ? bubbleRoot.replySenderName : "Message")
                                  : "Original message unavailable"
                            font.pixelSize: 11
                            font.bold: true
                            color: isMine ? "#FFFFFF" : bubbleRoot.accentColor
                            elide: Text.ElideRight
                        }
                        Text {
                            width: parent.width
                            visible: bubbleRoot.replyAvailable && bubbleRoot.replyPreview.length > 0
                            text: bubbleRoot.replyPreview
                            font.pixelSize: 12
                            color: isMine ? Qt.rgba(1, 1, 1, 0.8) : (darkMode ? "#C8C8D8" : "#555566")
                            elide: Text.ElideRight
                            maximumLineCount: 2
                            wrapMode: Text.Wrap
                        }
                    }
                }
                MouseArea {
                    anchors.fill: parent
                    enabled: bubbleRoot.replyAvailable
                    cursorShape: Qt.PointingHandCursor
                    onClicked: bubbleRoot.replyQuoteClicked()
                }
            }
'''
    if "id: replyQuote" not in t:
        if old not in t:
            raise SystemExit("forwardLabel block not found in MessageBubble")
        t = t.replace(old, new, 1)
    mb.write_text(t, encoding="utf-8", newline="\n")
    print("MessageBubble ok")

# ChatView.qml - large patch
cv = Path(r"D:\messenger_app\windows_app\qml\ChatView.qml")
t = cv.read_text(encoding="utf-8")

# pending reply properties near selection
if "pendingReplyId" not in t:
    # after selectionMode property or selectedIds
    anchor = "    property bool selectionMode: false\n"
    if anchor not in t:
        # try another
        idx = t.find("property var selectedIds")
        print("selection anchor search", idx)
    else:
        t = t.replace(anchor,
            anchor +
            "    property string pendingReplyId: \"\"\n"
            "    property string pendingReplyName: \"\"\n"
            "    property string pendingReplyPreview: \"\"\n",
            1)

# helpers for reply preview
helper = '''
    function replyPreviewForMessage(m) {
        if (!m) return ""
        var text = (m.messageText || "").toString().replace(/\\n/g, " ").trim()
        if (text.length > 0) return text.substring(0, 120)
        var ct = (m.contentType || "").toString()
        if (ct === "audio") return "Voice message"
        if (ct === "video_note") return "Video message"
        if (ct === "image") return "Photo"
        if (ct === "file") return (m.fileName || "File")
        return "Message"
    }

    function beginReply(messageId) {
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageId === messageId && row.messageKind !== "system") {
                pendingReplyId = messageId
                pendingReplyName = row.isMine ? "You" : (row.senderName || "Message")
                pendingReplyPreview = replyPreviewForMessage(row)
                chatService.pendingReplyToId = messageId
                clearSelection()
                return
            }
        }
    }

    function clearReply() {
        pendingReplyId = ""
        pendingReplyName = ""
        pendingReplyPreview = ""
        chatService.pendingReplyToId = ""
    }

    function jumpToMessage(messageId) {
        if (!messageId || messageId.length === 0) return
        for (var i = 0; i < messagesModel.count; i++) {
            if (messagesModel.get(i).messageId === messageId) {
                smoothRevealIndex(i)
                return
            }
        }
    }

    function resolveReplyFields(replyToId) {
        var out = { available: false, name: "", preview: "" }
        if (!replyToId || replyToId.length === 0) return out
        for (var i = 0; i < messagesModel.count; i++) {
            var row = messagesModel.get(i)
            if (row.messageId === replyToId) {
                out.available = true
                out.name = row.isMine ? "You" : (row.senderName || "Message")
                out.preview = replyPreviewForMessage(row)
                return out
            }
        }
        return out
    }

'''
if "function beginReply(" not in t:
    # insert before addMessage
    t = t.replace("    function addMessage(senderId, senderName, text, time, isMine, isRead, fileUrl, fileDurationMs, fileEncrypted,",
                  helper + "    function addMessage(senderId, senderName, text, time, isMine, isRead, fileUrl, fileDurationMs, fileEncrypted,",
                  1)

# Extend addMessage signature and append
old_add_sig = "                         rawContent, rawEncrypted, rawKeyVersion, isForwarded, forwardedFromName) {"
new_add_sig = "                         rawContent, rawEncrypted, rawKeyVersion, isForwarded, forwardedFromName, replyToId) {"
if "replyToId) {" not in t.split("function addMessage")[1][:400]:
    t = t.replace(old_add_sig, new_add_sig, 1)

if "replyToId: replyToId || \"\"" not in t and 'replyToId: replyToId' not in t:
    t = t.replace(
        "            isForwarded: isForwarded === true,\n"
        "            forwardedFromName: forwardedFromName || \"\"",
        "            isForwarded: isForwarded === true,\n"
        "            forwardedFromName: forwardedFromName || \"\",\n"
        "            replyToId: replyToId || \"\"",
        1,
    )

# Update call sites that pass isForwarded - add replyToId from message maps
# Common pattern: message.isForwarded === true, message.forwardedFromName || ""
t = t.replace(
    "message.isForwarded === true, message.forwardedFromName || \"\")",
    "message.isForwarded === true, message.forwardedFromName || \"\", message.replyToId || \"\")",
)
t = t.replace(
    "m.isForwarded === true, m.forwardedFromName || \"\")",
    "m.isForwarded === true, m.forwardedFromName || \"\", m.replyToId || \"\")",
)

# system message append isForwarded: false - add replyToId
t = t.replace(
    "            isForwarded: false,\n",
    "            isForwarded: false,\n            replyToId: \"\",\n",
)

# Selection Reply button
if 'text: "Reply"' not in t:
    t = t.replace(
        '''                ToolButton {
                    text: "Forward"
                    enabled: chatViewRoot.selectedCount > 0
                    onClicked: chatViewRoot.openForwardPicker(chatViewRoot.selectedIds.slice())
                }
''',
        '''                ToolButton {
                    text: "Reply"
                    enabled: chatViewRoot.selectedCount === 1
                    onClicked: chatViewRoot.beginReply(chatViewRoot.selectedIds[0])
                }

                ToolButton {
                    text: "Forward"
                    enabled: chatViewRoot.selectedCount > 0
                    onClicked: chatViewRoot.openForwardPicker(chatViewRoot.selectedIds.slice())
                }
''',
        1,
    )

# Context menu Reply
if 'text: "Reply"' not in t.split("id: messageMenu")[1][:800]:
    # insert after Copy menu item
    pass

# Find Copy MenuItem and add Reply after it
copy_block = '''            text: "Copy"
            enabled: messageMenu.targetMessageText.length > 0
            onTriggered: chatViewRoot.copyTextToClipboard(messageMenu.targetMessageText)
'''
# Need more context - MenuItem { ... }
idx = t.find('text: "Copy"')
if idx > 0 and 'beginReply(messageMenu.targetMessageId)' not in t:
    # find MenuItem containing Copy - insert another MenuItem after its closing
    end = t.find("}", idx)
    # find full MenuItem end - rough: next MenuItem
    next_mi = t.find("MenuItem {", idx + 5)
    insert = '''
        MenuItem {
            text: "Reply"
            enabled: messageMenu.targetMessageId.length > 0
            onTriggered: chatViewRoot.beginReply(messageMenu.targetMessageId)
        }
'''
    if next_mi > 0:
        t = t[:next_mi] + insert + t[next_mi:]

# Bubble bindings for reply
if "replyToId: model.replyToId" not in t:
    t = t.replace(
        "                    isForwarded: model.isForwarded === true\n"
        "                    forwardedFromName: model.forwardedFromName || \"\"\n",
        "                    isForwarded: model.isForwarded === true\n"
        "                    forwardedFromName: model.forwardedFromName || \"\"\n"
        "                    replyToId: model.replyToId || \"\"\n"
        "                    replySenderName: {\n"
        "                        var r = chatViewRoot.resolveReplyFields(model.replyToId || \"\")\n"
        "                        return r.name\n"
        "                    }\n"
        "                    replyPreview: {\n"
        "                        var r = chatViewRoot.resolveReplyFields(model.replyToId || \"\")\n"
        "                        return r.preview\n"
        "                    }\n"
        "                    replyAvailable: {\n"
        "                        var r = chatViewRoot.resolveReplyFields(model.replyToId || \"\")\n"
        "                        return r.available\n"
        "                    }\n"
        "                    onReplyQuoteClicked: chatViewRoot.jumpToMessage(model.replyToId || \"\")\n",
        1,
    )

# Composer reply bar - find GlassPanel composer / message input area
# Insert above the input RowLayout inside composer
composer_anchor = "            RowLayout {\n                id: inputRow\n"
if "pendingReplyBar" not in t and composer_anchor in t:
    bar = '''            Rectangle {
                id: pendingReplyBar
                visible: chatViewRoot.pendingReplyId.length > 0
                Layout.fillWidth: true
                Layout.preferredHeight: visible ? 48 : 0
                color: Qt.rgba(chatViewRoot.accentColor.r, chatViewRoot.accentColor.g, chatViewRoot.accentColor.b, 0.12)
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 8
                    spacing: 8
                    Column {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: chatViewRoot.pendingReplyName
                            color: chatViewRoot.accentColor
                            font.pixelSize: 12
                            font.bold: true
                            elide: Text.ElideRight
                            width: parent.width
                        }
                        Text {
                            text: chatViewRoot.pendingReplyPreview
                            color: chatViewRoot.textSecondary
                            font.pixelSize: 12
                            elide: Text.ElideRight
                            width: parent.width
                        }
                    }
                    ToolButton {
                        text: "✕"
                        onClicked: chatViewRoot.clearReply()
                    }
                }
            }

'''
    # composer uses ColumnLayout children - find parent of inputRow
    # Looking at structure: GlassPanel { ... RowLayout id: inputRow
    # Need ColumnLayout wrapping. Check if composer has ColumnLayout
    if "id: composerBar" in t:
        # Insert before inputRow but need Layout. - the GlassPanel might use anchors for RowLayout
        # Read structure - if RowLayout anchors.fill parent, wrap differently.
        pass

# sendButton.trigger - clear reply after send (service takes pending)
t = t.replace(
    """                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true, false)
                        chatService.sendMessage(chatViewRoot.currentChatId, text, chatViewRoot.currentChatType)
                        chatViewRoot.sendMessage(text)
                        messageInput.text = ""
                    }
""",
    """                    function trigger() {
                        if (!canSend) return
                        var text = messageInput.text.trim()
                        var replyId = chatViewRoot.pendingReplyId
                        chatViewRoot.addMessage(authService.currentUserId, "Me", text, chatViewRoot.formatTime(new Date().toISOString()), true, false,
                                                 "", 0, false, "", "", "", 0, "",
                                                 "", false, 0, false, "", replyId)
                        chatService.sendMessage(chatViewRoot.currentChatId, text, chatViewRoot.currentChatType)
                        chatViewRoot.clearReply()
                        chatViewRoot.sendMessage(text)
                        messageInput.text = ""
                    }
""",
    1,
)

cv.write_text(t, encoding="utf-8", newline="\n")
print("ChatView patched")
print("beginReply", "beginReply" in t)
print("pendingReplyBar", "pendingReplyBar" in t)
print("Reply menu", "beginReply(messageMenu" in t)