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
    property string voiceUrl: ""
    property real voiceDurationMs: 0
    property bool voiceEncrypted: false
    property string contentType: ""
    property string fileName: ""
    property real fileSize: 0
    property string chatId: ""
    readonly property bool isVoiceMessage: voiceUrl && voiceUrl.length > 0 && contentType === "audio"
    readonly property bool isImageMessage: voiceUrl && voiceUrl.length > 0 && contentType === "image"
    readonly property bool isFileMessage: voiceUrl && voiceUrl.length > 0 && contentType === "file"
    readonly property bool hasAttachment: isVoiceMessage || isImageMessage || isFileMessage
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
                                       bubbleRoot.voiceEncrypted, bubbleRoot.fileName)
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
            bubbleRoot.voicePreparing = false
            bubbleRoot.voiceLocalPath = localFilePath
            bubbleRoot.voiceError = ""
            voiceService.togglePlayback(bubbleRoot.messageId, localFilePath)
        }
        function onVoicePlaybackError(msgId, error) {
            if (msgId !== bubbleRoot.messageId) return
            bubbleRoot.voicePreparing = false
            bubbleRoot.voiceError = error
        }
        function onAttachmentReady(msgId, localFilePath) {
            if (msgId !== bubbleRoot.messageId) return
            bubbleRoot.filePreparing = false
            bubbleRoot.fileLocalPath = localFilePath
            bubbleRoot.fileError = ""
            // A file attachment was fetched because of a tap - open it right
            // away. An image was fetched proactively just to show a
            // thumbnail, so it should NOT jump to opening in another app.
            if (bubbleRoot.isFileMessage) Qt.openUrlExternally("file:///" + localFilePath)
        }
        function onAttachmentError(msgId, error) {
            if (msgId !== bubbleRoot.messageId) return
            bubbleRoot.filePreparing = false
            bubbleRoot.fileError = error
        }
    }

    width: parent ? parent.width : 400
    height: bubble.height + 4

    Rectangle {
        id: bubble
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        width: (bubbleRoot.isVoiceMessage || bubbleRoot.isFileMessage)
               ? bubbleRoot.voiceContentWidth + 28
               : bubbleRoot.isImageMessage
               ? bubbleRoot.imageContentSize + 20
               : Math.min(
                     Math.max(contentText.implicitWidth, timeRow.implicitWidth, bubbleRoot.minContentWidth) + 28,
                     bubbleRoot.maxWidth
                 )
        height: contentColumn.implicitHeight + 20
        radius: 16
        color: isMine ? bubbleRoot.myMessageBg : bubbleRoot.theirMessageBg

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
                color: "#8B84FF"
                elide: Text.ElideRight
                width: parent.width
            }

            Text {
                id: contentText
                width: parent.width
                visible: !bubbleRoot.hasAttachment
                text: bubbleRoot.messageText
                font.pixelSize: 14
                color: isMine ? bubbleRoot.myTextColor : bubbleRoot.theirTextColor
                wrapMode: Text.Wrap
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

            Row {
                id: fileRow
                visible: bubbleRoot.isFileMessage
                width: bubbleRoot.voiceContentWidth
                height: 44
                spacing: 10

                Rectangle {
                    id: fileIconBg
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
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - fileIconBg.width - parent.spacing
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

            Row {
                id: voiceRow
                visible: bubbleRoot.isVoiceMessage
                width: bubbleRoot.voiceContentWidth
                height: 36
                spacing: 10

                Rectangle {
                    id: playButton
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
                                                              bubbleRoot.voiceUrl, bubbleRoot.voiceEncrypted)
                        }
                    }
                }

                Column {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - playButton.width - parent.spacing
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

            Row {
                id: timeRow
                anchors.right: parent.right
                spacing: 4
                topPadding: 2

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: bubbleRoot.messageTime
                    font.pixelSize: 11
                    color: isMine ? Qt.rgba(1, 1, 1, 0.65) : (darkMode ? "#8B8B9E" : "#6B6B7B")
                }

                Canvas {
                    id: checkmarkCanvas
                    anchors.verticalCenter: parent.verticalCenter
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
