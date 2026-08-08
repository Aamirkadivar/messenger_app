import QtQuick 2.15
import QtQuick.Controls 2.15

Item {
    id: bubbleRoot

    property string messageText: ""
    property string messageTime: ""
    property bool isMine: true
    property bool isRead: false
    property string senderName: ""
    property bool isEncrypted: true
    property bool showSender: false
    property bool darkMode: true
    property color accentColor: "#6C63FF"

    // Attachment fields, shared across voice/image/file - voiceUrl empty
    // means this is a plain text bubble. contentType picks which of the
    // three renders; voiceUrl/voiceEncrypted double as the generic file
    // url/encrypted flag regardless of type (kept named after voice since
    // that was built first, not because the other two are special-cased).
    property string messageId: ""
    property string senderId: ""
    property int keyVersion: 0
    property string voiceUrl: ""
    property real voiceDurationMs: 0
    property bool voiceEncrypted: false
    property string contentType: ""
    property string fileName: ""
    property real fileSize: 0
    property string chatId: ""
    /** Poster frame for a round video; empty for every other type. */
    property string thumbnailUrl: ""
    /** Raised when a round video starts playing, so the list can reveal it. */
    signal videoPlaybackStarted()
    /** Right-click anywhere on the bubble - the view owns the actual menu. */
    signal requestDelete()
    /** True while the chat is in multi-select. */
    property bool selectionMode: false
    property bool selected: false
    signal toggleSelected()
    /** Display-only forward attribution from the server. */
    property bool isForwarded: false
    property string forwardedFromName: ""
    property string replyToId: ""
    property string replySenderName: ""
    property string replyPreview: ""
    property bool replyAvailable: false
    signal replyQuoteClicked()
    /** Long text starts collapsed; user toggles Show more / Show less. */
    property bool textExpanded: false
    readonly property int collapsedMaxLines: 8
    signal textExpandToggled(bool expanded)
    readonly property bool isVoiceMessage: voiceUrl && voiceUrl.length > 0 && contentType === "audio"
    readonly property bool isImageMessage: voiceUrl && voiceUrl.length > 0 && contentType === "image"
    readonly property bool isFileMessage: voiceUrl && voiceUrl.length > 0 && contentType === "file"
    readonly property bool isVideoNote: voiceUrl && voiceUrl.length > 0 && contentType === "video_note"
    readonly property bool hasAttachment: isVoiceMessage || isImageMessage || isFileMessage || isVideoNote
    readonly property real imageContentSize: 220

    // Local decrypt-prep state for image/file attachments (voice has its own
    // separate voicePreparing/voiceLocalPath/voiceError below, predating this).
    property bool filePreparing: false
    property string fileLocalPath: ""
    property string fileError: ""

    function formatFileSize(bytes) {
        if (bytes <= 0) return ""
        if (bytes < 1024) return bytes + " B"
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB"
        return (bytes / (1024 * 1024)).toFixed(1) + " MB"
    }

    function ensureFileLoaded() {
        if (bubbleRoot.fileLocalPath.length > 0) {
            if (bubbleRoot.isFileMessage) Qt.openUrlExternally("file:///" + bubbleRoot.fileLocalPath)
            return
        }
        if (bubbleRoot.filePreparing) return
        bubbleRoot.filePreparing = true
        bubbleRoot.fileError = ""
        chatService.prepareAttachment(bubbleRoot.chatId, bubbleRoot.messageId, bubbleRoot.voiceUrl,
                                       bubbleRoot.voiceEncrypted, bubbleRoot.fileName,
                                       bubbleRoot.senderId, bubbleRoot.keyVersion)
    }

    // Images load automatically (a thumbnail is the point); files wait for a
    // tap, since fetching an arbitrary-sized document just to show an icon
    // would be wasted bandwidth.
    onIsImageMessageChanged: if (isImageMessage) ensureFileLoaded()
    Component.onCompleted: if (isImageMessage) ensureFileLoaded()

    // "Loaded" = this bubble is the one currently in the player (playing or
    // paused); "playing" narrows that to actually producing sound.
    readonly property bool isThisLoaded: typeof voiceService !== "undefined"
                                          && voiceService.playingMessageId === bubbleRoot.messageId
    readonly property bool isThisPlaying: bubbleRoot.isThisLoaded && voiceService.isPlaying

    property color myMessageBg: "#6C63FF"
    property color theirMessageBg: darkMode ? "#26264A" : "#EDEDF2"
    property color myTextColor: "#FFFFFF"
    property color theirTextColor: darkMode ? "#EDEDF2" : "#1A1A2E"
    // "Their" bubbles are glass (translucent, over ChatView's AmbientGlow);
    // "my" bubbles stay solid gold - keeps the one clear visual hierarchy a
    // chat thread needs (which side is mine) instead of two competing glass
    // surfaces that would blur together.
    readonly property color theirGlassFill: Qt.rgba(theirMessageBg.r, theirMessageBg.g, theirMessageBg.b, darkMode ? 0.5 : 0.72)
    readonly property color theirGlassBorder: darkMode ? Qt.rgba(1, 1, 1, 0.09) : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.10)

    property real maxWidth: parent ? parent.width * 0.68 : 400
    property real minContentWidth: 68
    readonly property real voiceContentWidth: 208

    // Local playback-prep state, keyed to this specific message.
    property bool voicePreparing: false
    property string voiceLocalPath: ""
    property string voiceError: ""

    function formatDuration(ms) {
        var totalSec = Math.max(0, Math.round(ms / 1000))
        var m = Math.floor(totalSec / 60)
        var s = totalSec % 60
        return m + ":" + (s < 10 ? "0" : "") + s
    }

    Connections {
        target: typeof chatService !== "undefined" ? chatService : null
        function onVoiceReadyForPlayback(msgId, localFilePath) {
            if (msgId !== bubbleRoot.messageId) return
            // Only auto-start playback when this bubble kicked off the prepare
            // (a forward queue shares the same signals and must not hijack play).
            var wasPreparing = bubbleRoot.voicePreparing
            bubbleRoot.voicePreparing = false
            bubbleRoot.voiceLocalPath = localFilePath
            bubbleRoot.voiceError = ""
            if (wasPreparing)
                voiceService.togglePlayback(bubbleRoot.messageId, localFilePath)
        }
        function onVoicePlaybackError(msgId, error) {
            if (msgId !== bubbleRoot.messageId) return
            bubbleRoot.voicePreparing = false
            bubbleRoot.voiceError = error
        }
        function onAttachmentReady(msgId, localFilePath) {
            if (msgId !== bubbleRoot.messageId) return
            var wasPreparing = bubbleRoot.filePreparing
            bubbleRoot.filePreparing = false
            bubbleRoot.fileLocalPath = localFilePath
            bubbleRoot.fileError = ""
            // A file attachment was fetched because of a tap - open it right
            // away. An image was fetched proactively just to show a
            // thumbnail, so it should NOT jump to opening in another app.
            // Forward prepare must not open either (filePreparing is false).
            if (wasPreparing && bubbleRoot.isFileMessage)
                Qt.openUrlExternally("file:///" + localFilePath)
        }
        function onAttachmentError(msgId, error) {
            if (msgId !== bubbleRoot.messageId) return
            bubbleRoot.filePreparing = false
            bubbleRoot.fileError = error
        }
    }

    onMessageIdChanged: textExpanded = false

    width: parent ? parent.width : 400
    height: bubbleRoot.isVideoNote
            ? (roundVideoLoader.height
               + (bubbleRoot.replyToId.length > 0 ? videoReplyQuote.height + 6 : 0)
               + (bubbleRoot.isForwarded ? 22 : 0) + 8)
            : (bubble.height + 4)

    // A tinted band behind the whole row, so a selected message reads as
    // selected even when the bubble itself is a bare circle (round video) or
    // a translucent glass panel.
    Rectangle {
        anchors.fill: parent
        anchors.margins: -2
        visible: bubbleRoot.selected
        color: Qt.rgba(bubbleRoot.accentColor.r, bubbleRoot.accentColor.g,
                        bubbleRoot.accentColor.b, 0.18)
        radius: 8
        z: -1
    }

    // Right-click always available. The left button is only claimed while
    // selecting - otherwise this would swallow taps meant for the voice and
    // video controls inside the bubble.
    MouseArea {
        anchors.fill: parent
        acceptedButtons: bubbleRoot.selectionMode ? (Qt.LeftButton | Qt.RightButton) : Qt.RightButton
        onClicked: function(mouse) {
            if (bubbleRoot.selectionMode && mouse.button === Qt.LeftButton) {
                bubbleRoot.toggleSelected()
                return
            }
            bubbleRoot.requestDelete()
        }
    }

    // Checkbox, on the outer edge of the row so it never covers bubble content.
    Rectangle {
        visible: bubbleRoot.selectionMode
        width: 18
        height: 18
        radius: 9
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: bubbleRoot.isMine ? undefined : parent.left
        anchors.right: bubbleRoot.isMine ? parent.right : undefined
        color: bubbleRoot.selected ? bubbleRoot.accentColor : "transparent"
        border.width: 1.5
        border.color: bubbleRoot.selected ? bubbleRoot.accentColor
                                           : Qt.rgba(1, 1, 1, bubbleRoot.darkMode ? 0.35 : 0.25)
        Text {
            anchors.centerIn: parent
            visible: bubbleRoot.selected
            text: "✓"
            color: "#FFFFFF"
            font.pixelSize: 11
            font.bold: true
        }
    }

    // A round video gets no bubble chrome - a rounded rectangle behind a
    // circle just boxes it in. Loaded on demand so a chat full of text does
    // not instantiate a MediaPlayer per message.
    Rectangle {
        id: videoReplyQuote
        visible: bubbleRoot.isVideoNote && bubbleRoot.replyToId.length > 0
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        anchors.top: parent.top
        width: Math.min(220, parent.width - 24)
        height: videoReplyCol.implicitHeight + 10
        radius: 8
        color: darkMode ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(0, 0, 0, 0.06)
        Row {
            anchors.fill: parent
            anchors.margins: 6
            spacing: 8
            Rectangle {
                width: 3
                height: parent.height
                radius: 1.5
                color: bubbleRoot.accentColor
            }
            Column {
                id: videoReplyCol
                width: parent.width - 11
                spacing: 2
                Text {
                    width: parent.width
                    text: bubbleRoot.replyAvailable
                          ? (bubbleRoot.replySenderName.length > 0 ? bubbleRoot.replySenderName : "Message")
                          : "Original message unavailable"
                    font.pixelSize: 11
                    font.bold: true
                    color: bubbleRoot.accentColor
                    elide: Text.ElideRight
                }
                Text {
                    width: parent.width
                    visible: bubbleRoot.replyAvailable && bubbleRoot.replyPreview.length > 0
                    text: bubbleRoot.replyPreview
                    font.pixelSize: 12
                    color: bubbleRoot.darkMode ? Qt.rgba(1, 1, 1, 0.7) : Qt.rgba(0, 0, 0, 0.65)
                    elide: Text.ElideRight
                    maximumLineCount: 2
                    wrapMode: Text.Wrap
                }
            }
        }
        MouseArea {
            anchors.fill: parent
            enabled: bubbleRoot.replyAvailable
            cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
            onClicked: bubbleRoot.replyQuoteClicked()
        }
    }

    Text {
        id: videoForwardLabel
        visible: bubbleRoot.isVideoNote && bubbleRoot.isForwarded
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        anchors.top: videoReplyQuote.visible ? videoReplyQuote.bottom : parent.top
        anchors.topMargin: videoReplyQuote.visible ? 4 : 0
        text: bubbleRoot.forwardedFromName.length > 0
              ? ("Forwarded from " + bubbleRoot.forwardedFromName)
              : "Forwarded"
        font.pixelSize: 11
        font.italic: true
        color: bubbleRoot.darkMode ? Qt.rgba(1, 1, 1, 0.55) : Qt.rgba(0, 0, 0, 0.45)
    }

    Loader {
        id: roundVideoLoader
        active: bubbleRoot.isVideoNote
        visible: active
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        anchors.top: videoForwardLabel.visible ? videoForwardLabel.bottom
                     : (videoReplyQuote.visible ? videoReplyQuote.bottom : parent.top)
        anchors.topMargin: (videoForwardLabel.visible || videoReplyQuote.visible) ? 4 : 0
        sourceComponent: RoundVideoBubble {
            messageId: bubbleRoot.messageId
            chatId: bubbleRoot.chatId
            fileUrl: bubbleRoot.voiceUrl
            thumbnailUrl: bubbleRoot.thumbnailUrl
            encrypted: bubbleRoot.voiceEncrypted
            senderId: bubbleRoot.senderId
            keyVersion: bubbleRoot.keyVersion
            durationMs: bubbleRoot.voiceDurationMs
            accentColor: bubbleRoot.myMessageBg
            onPlaybackStarted: bubbleRoot.videoPlaybackStarted()
        }
    }

    Rectangle {
        id: bubble
        visible: !bubbleRoot.isVideoNote
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        width: (bubbleRoot.isVoiceMessage || bubbleRoot.isFileMessage)
               ? bubbleRoot.voiceContentWidth + 28
               : bubbleRoot.isImageMessage
               ? bubbleRoot.imageContentSize + 20
               : Math.min(
                     Math.max(contentText.width, timeRow.implicitWidth,
                              bubbleRoot.isForwarded ? forwardLabel.implicitWidth : 0,
                              bubbleRoot.minContentWidth) + 28,
                     bubbleRoot.maxWidth
                 )
        height: contentColumn.implicitHeight + 20
        radius: 16
        color: isMine ? bubbleRoot.myMessageBg : bubbleRoot.theirGlassFill
        border.width: isMine ? 0 : 1
        border.color: bubbleRoot.theirGlassBorder
        antialiasing: true

        Column {
            id: contentColumn
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 10
            spacing: 3

            Text {
                visible: bubbleRoot.showSender && !bubbleRoot.isMine
                text: bubbleRoot.senderName
                font.pixelSize: 12
                font.bold: true
                color: bubbleRoot.accentColor
                elide: Text.ElideRight
                width: parent.width
            }

            Text {
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

            // Invisible metrics for bubble width with wrap off; the visible
            // Text below wraps and would report a width that depends on its
            // assigned width (circular for layout).
            Text {
                id: contentMeasure
                visible: false
                text: bubbleRoot.messageText
                font.pixelSize: 14
            }

            // Full wrapped height probe — decides whether Show more is needed.
            Text {
                id: contentHeightProbe
                visible: false
                width: Math.min(
                           Math.max(contentMeasure.implicitWidth, bubbleRoot.minContentWidth),
                           bubbleRoot.maxWidth - 28
                       )
                text: bubbleRoot.messageText
                wrapMode: Text.Wrap
                font.pixelSize: 14
            }

            readonly property bool textNeedsCollapse: !bubbleRoot.hasAttachment
                    && contentHeightProbe.lineCount > bubbleRoot.collapsedMaxLines
            // Clip height instead of maximumLineCount — Qt often fails to
            // relayout when maximumLineCount shrinks, so Show less looked broken.
            readonly property real collapsedTextHeight: {
                var lines = Math.max(1, contentHeightProbe.lineCount)
                return contentHeightProbe.implicitHeight
                        * bubbleRoot.collapsedMaxLines / lines
            }

            Item {
                id: textBody
                visible: !bubbleRoot.hasAttachment
                width: contentHeightProbe.width
                height: (contentColumn.textNeedsCollapse && !bubbleRoot.textExpanded)
                        ? contentColumn.collapsedTextHeight
                        : contentText.implicitHeight
                clip: true

                Text {
                    id: contentText
                    width: parent.width
                    text: bubbleRoot.messageText
                    wrapMode: Text.Wrap
                    font.pixelSize: 14
                    color: isMine ? bubbleRoot.myTextColor : bubbleRoot.theirTextColor
                }
            }

            Text {
                id: expandToggle
                visible: contentColumn.textNeedsCollapse
                text: bubbleRoot.textExpanded ? "Show less" : "Show more"
                font.pixelSize: 12
                font.bold: true
                color: isMine ? Qt.rgba(1, 1, 1, 0.9) : bubbleRoot.accentColor
                MouseArea {
                    anchors.fill: parent
                    anchors.margins: -6
                    cursorShape: Qt.PointingHandCursor
                    preventStealing: true
                    onClicked: {
                        bubbleRoot.textExpanded = !bubbleRoot.textExpanded
                        bubbleRoot.textExpandToggled(bubbleRoot.textExpanded)
                    }
                }
            }

            Item {
                id: imageContent
                visible: bubbleRoot.isImageMessage
                width: bubbleRoot.imageContentSize
                height: bubbleRoot.imageContentSize

                Rectangle {
                    anchors.fill: parent
                    radius: 10
                    color: bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.1) : (darkMode ? Qt.rgba(1, 1, 1, 0.06) : Qt.rgba(0, 0, 0, 0.04))
                    visible: bubbleRoot.fileLocalPath.length === 0

                    BusyIndicator {
                        anchors.centerIn: parent
                        running: bubbleRoot.filePreparing
                        visible: bubbleRoot.filePreparing
                    }
                    Text {
                        anchors.centerIn: parent
                        anchors.margins: 12
                        width: parent.width - 24
                        visible: bubbleRoot.fileError.length > 0
                        text: bubbleRoot.fileError
                        font.pixelSize: 11
                        color: "#FF6B6B"
                        wrapMode: Text.Wrap
                        horizontalAlignment: Text.AlignHCenter
                    }
                }

                // Not clipped to the bubble's rounded corners (see Avatar.qml -
                // plain Item/Rectangle clip does not respect radius, and this
                // sits inside an already-rounded bubble with padding around
                // it, so the mismatch is far less noticeable than it would be
                // on a small circular avatar).
                Image {
                    anchors.fill: parent
                    visible: bubbleRoot.fileLocalPath.length > 0
                    source: bubbleRoot.fileLocalPath.length > 0 ? "file:///" + bubbleRoot.fileLocalPath : ""
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    smooth: true
                }

                Text {
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                    anchors.margins: 6
                    visible: bubbleRoot.voiceEncrypted && bubbleRoot.fileLocalPath.length > 0
                    text: "🔒"
                    font.pixelSize: 12
                }

                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: if (bubbleRoot.fileLocalPath.length > 0) Qt.openUrlExternally("file:///" + bubbleRoot.fileLocalPath)
                }
            }

            // A plain Item, not a Row - Row explicitly forbids vertical/fill
            // anchors on its children (Qt Quick warns "Row will not function"
            // and the layout breaks down, which is exactly what was making
            // the icon and text overlap instead of sitting side by side).
            // fileIconBg/the text Column/the MouseArea below all needed
            // anchors Row can't host, so this is an Item with an explicit
            // left-to-right anchor chain instead.
            Item {
                id: fileRow
                visible: bubbleRoot.isFileMessage
                width: bubbleRoot.voiceContentWidth
                height: 44

                Rectangle {
                    id: fileIconBg
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    width: 36
                    height: 36
                    radius: 10
                    color: bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.22) : bubbleRoot.accentColor

                    BusyIndicator {
                        anchors.centerIn: parent
                        width: 18
                        height: 18
                        running: bubbleRoot.filePreparing
                        visible: bubbleRoot.filePreparing
                    }

                    Canvas {
                        anchors.centerIn: parent
                        width: 16
                        height: 18
                        visible: !bubbleRoot.filePreparing
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.strokeStyle = "#FFFFFF"
                            ctx.lineWidth = 1.3
                            ctx.lineCap = "round"
                            ctx.lineJoin = "round"
                            ctx.beginPath()
                            ctx.moveTo(2, 1); ctx.lineTo(10, 1); ctx.lineTo(14, 5); ctx.lineTo(14, 17); ctx.lineTo(2, 17); ctx.closePath()
                            ctx.stroke()
                            ctx.beginPath(); ctx.moveTo(10, 1); ctx.lineTo(10, 5); ctx.lineTo(14, 5); ctx.stroke()
                            ctx.beginPath(); ctx.moveTo(4, 9); ctx.lineTo(12, 9); ctx.stroke()
                            ctx.beginPath(); ctx.moveTo(4, 12); ctx.lineTo(10, 12); ctx.stroke()
                        }
                    }
                }

                Column {
                    anchors.left: fileIconBg.right
                    anchors.leftMargin: 10
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        width: parent.width
                        text: bubbleRoot.fileName.length > 0 ? bubbleRoot.fileName : "File"
                        font.pixelSize: 13
                        font.weight: Font.Medium
                        elide: Text.ElideMiddle
                        color: bubbleRoot.isMine ? bubbleRoot.myTextColor : bubbleRoot.theirTextColor
                    }
                    Text {
                        width: parent.width
                        text: bubbleRoot.fileError.length > 0 ? bubbleRoot.fileError
                              : bubbleRoot.formatFileSize(bubbleRoot.fileSize) + (bubbleRoot.voiceEncrypted ? "  🔒" : "")
                        font.pixelSize: 11
                        elide: Text.ElideRight
                        color: bubbleRoot.fileError.length > 0 ? "#FF6B6B"
                               : (bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.75) : bubbleRoot.theirTextColor)
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    enabled: !bubbleRoot.filePreparing
                    onClicked: bubbleRoot.ensureFileLoaded()
                }
            }

            // Same fix as fileRow above - Item instead of Row, explicit anchor chain.
            Item {
                id: voiceRow
                visible: bubbleRoot.isVoiceMessage
                width: bubbleRoot.voiceContentWidth
                height: 36

                Rectangle {
                    id: playButton
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    width: 32
                    height: 32
                    radius: 16
                    color: bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.22) : bubbleRoot.accentColor

                    BusyIndicator {
                        anchors.centerIn: parent
                        width: 18
                        height: 18
                        running: bubbleRoot.voicePreparing
                        visible: bubbleRoot.voicePreparing
                    }

                    Canvas {
                        anchors.centerIn: parent
                        width: 14
                        height: 14
                        visible: !bubbleRoot.voicePreparing
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            ctx.fillStyle = "#FFFFFF"
                            if (bubbleRoot.isThisPlaying) {
                                ctx.fillRect(2, 1, 4, 12)
                                ctx.fillRect(8, 1, 4, 12)
                            } else {
                                ctx.beginPath()
                                ctx.moveTo(2, 1); ctx.lineTo(13, 7); ctx.lineTo(2, 13)
                                ctx.closePath(); ctx.fill()
                            }
                        }
                        Connections {
                            target: bubbleRoot
                            function onIsThisPlayingChanged() { parent.requestPaint() }
                        }
                    }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        enabled: !bubbleRoot.voicePreparing
                        onClicked: {
                            if (bubbleRoot.isThisLoaded) {
                                voiceService.togglePlayback(bubbleRoot.messageId, bubbleRoot.voiceLocalPath)
                                return
                            }
                            bubbleRoot.voicePreparing = true
                            bubbleRoot.voiceError = ""
                            chatService.preparePlayableVoice(bubbleRoot.chatId, bubbleRoot.messageId,
                                                              bubbleRoot.voiceUrl, bubbleRoot.voiceEncrypted,
                                                              bubbleRoot.senderId, bubbleRoot.keyVersion)
                        }
                    }
                }

                Column {
                    anchors.left: playButton.right
                    anchors.leftMargin: 10
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 4

                    Rectangle {
                        width: parent.width
                        height: 3
                        radius: 1.5
                        color: bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.3) : (darkMode ? Qt.rgba(1, 1, 1, 0.15) : Qt.rgba(0, 0, 0, 0.12))

                        Rectangle {
                            height: parent.height
                            radius: parent.radius
                            color: bubbleRoot.isMine ? "#FFFFFF" : bubbleRoot.accentColor
                            width: {
                                if (!bubbleRoot.isThisLoaded || voiceService.playbackDurationMs <= 0) return 0
                                return parent.width * Math.min(1, voiceService.playbackPositionMs / voiceService.playbackDurationMs)
                            }
                        }
                    }

                    Text {
                        text: bubbleRoot.voiceError.length > 0 ? bubbleRoot.voiceError
                              : (bubbleRoot.isThisLoaded && voiceService.playbackDurationMs > 0
                                 ? bubbleRoot.formatDuration(voiceService.playbackPositionMs)
                                 : bubbleRoot.formatDuration(bubbleRoot.voiceDurationMs))
                              + (bubbleRoot.voiceEncrypted ? "  🔒" : "")
                        font.pixelSize: 11
                        color: bubbleRoot.voiceError.length > 0 ? "#FF6B6B"
                               : (bubbleRoot.isMine ? Qt.rgba(1, 1, 1, 0.75) : bubbleRoot.theirTextColor)
                    }
                }
            }

            // A plain Item wrapper, not a Row/Column child anchored directly -
            // Column is a positioner and silently breaks (or ignores) anchors
            // on its direct children, same class of bug as Layout-managed
            // children ignoring anchors.fill elsewhere in this app. Anchoring
            // timeRow straight to contentColumn (a Column) is what left the
            // timestamp floating disconnected from the file/voice row instead
            // of sitting right-aligned under it.
            Item {
                width: parent.width
                height: timeRow.implicitHeight

                Row {
                    id: timeRow
                    anchors.right: parent.right
                    spacing: 4
                    topPadding: 2

                    // y bindings instead of anchors.verticalCenter: Row (like
                    // Column) forbids anchors that touch the axis it manages
                    // itself - for Row that's vertical anchors, and using one
                    // anyway is what broke this row's layout the same way the
                    // fileRow/voiceRow ones above did.
                    Text {
                        y: (parent.height - height) / 2
                        text: bubbleRoot.messageTime
                        font.pixelSize: 11
                        color: isMine ? Qt.rgba(1, 1, 1, 0.65) : (darkMode ? "#8B8B9E" : "#6B6B7B")
                    }

                    Canvas {
                        id: checkmarkCanvas
                        y: (parent.height - height) / 2
                        width: 14
                        height: 10
                        visible: bubbleRoot.isMine
                        onPaint: {
                            var ctx = getContext("2d")
                            ctx.reset()
                            // Grey = sent but not yet seen; gold = the other
                            // person has read it (matches the accent in both themes).
                            ctx.strokeStyle = bubbleRoot.isRead ? (darkMode ? "#C9A961" : "#A6803A") : "rgba(255,255,255,0.75)"
                            ctx.lineWidth = 1.4
                            ctx.lineCap = "round"
                            ctx.lineJoin = "round"
                            ctx.beginPath()
                            ctx.moveTo(0, 5); ctx.lineTo(3, 8); ctx.lineTo(8, 2)
                            ctx.stroke()
                            ctx.beginPath()
                            ctx.moveTo(5, 5); ctx.lineTo(8, 8); ctx.lineTo(14, 1)
                            ctx.stroke()
                        }

                        Connections {
                            target: bubbleRoot
                            function onIsReadChanged() { checkmarkCanvas.requestPaint() }
                        }
                    }
                }
            }
        }
    }
}
