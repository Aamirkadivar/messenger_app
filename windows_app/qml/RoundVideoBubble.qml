import QtQuick
import QtMultimedia
import Messenger 1.0

// A Telegram-style round video message.
//
// Circularity comes from CircularVideo (QQuickPaintedItem + a QPainter ellipse
// clip). QtQuick.Effects renders nothing in this Qt/MinGW/GPU combination and
// Rectangle.clip ignores radius, and a painted "mask" only occludes when it is
// opaque - over a translucent background it leaves the corners showing, which
// is what made the first attempt look like a circle inside a square.
Item {
    id: bubbleRoot

    property string messageId: ""
    property string chatId: ""
    property string fileUrl: ""
    property string thumbnailUrl: ""
    property bool encrypted: false
    property string senderId: ""
    property int keyVersion: 0
    property real durationMs: 0
    property color maskColor: "#12121A"
    property color accentColor: "#C9A961"

    // Filled in as the pieces arrive from ChatService.
    property string localPath: ""
    property string thumbPath: ""
    property bool downloading: false
    property bool playing: false

    signal playbackStarted()

    // Grows while playing, the way Telegram's does, so the active message
    // stands out from the static ones around it.
    //
    // The real width/height animate rather than scale: a scaled item keeps its
    // original bounds, so the enlarged circle would overlap its neighbours and
    // the list would have no idea it needs more room to show it.
    readonly property int baseSize: 190
    readonly property real playingScale: 1.12
    width: playing ? baseSize * playingScale : baseSize
    height: width
    Behavior on width { NumberAnimation { duration: 220; easing.type: Easing.OutBack } }

    // Poster frame: shown from the moment the message arrives, so the bubble
    // is never an empty circle waiting on a multi-megabyte download.
    // The poster needs the same circular treatment as the video, or the
    // bubble would be square until playback starts.
    Canvas {
        id: poster
        anchors.fill: parent
        visible: bubbleRoot.thumbPath !== "" && !bubbleRoot.playing
        property var img: null
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            ctx.beginPath()
            ctx.arc(width / 2, height / 2, width / 2, 0, Math.PI * 2)
            ctx.clip()
            if (bubbleRoot.thumbPath !== "") {
                ctx.drawImage("file:///" + bubbleRoot.thumbPath, 0, 0, width, height)
            }
        }
        onVisibleChanged: if (visible) requestPaint()
        Connections {
            target: bubbleRoot
            function onThumbPathChanged() {
                if (bubbleRoot.thumbPath === "") return
                poster.loadImage("file:///" + bubbleRoot.thumbPath)
            }
        }
        onImageLoaded: requestPaint()
    }

    Canvas {
        anchors.fill: parent
        visible: bubbleRoot.thumbPath === "" && !bubbleRoot.playing
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            ctx.fillStyle = Qt.rgba(1, 1, 1, 0.08)
            ctx.beginPath()
            ctx.arc(width / 2, height / 2, width / 2, 0, Math.PI * 2)
            ctx.fill()
        }
    }

    MediaPlayer {
        id: player
        // The sink itself, not the item. videoOutput takes a QObject and
        // will accept a QVideoSink directly; relying on it to discover the
        // item's videoSink property left the player with no sink at all, so
        // the circle stayed blank while the audio played.
        videoOutput: videoOut.videoSink
        audioOutput: AudioOutput { id: audioOut }
        // Round videos loop, like Telegram's.
        loops: MediaPlayer.Infinite
        onPlaybackStateChanged: {
            var wasPlaying = bubbleRoot.playing
            bubbleRoot.playing = (playbackState === MediaPlayer.PlayingState)
            if (bubbleRoot.playing && !wasPlaying) bubbleRoot.playbackStarted()
        }
    }

    // Centre-cropped inside the circle, so a 4:3 or 16:9 source - whichever
    // client recorded it - fills rather than letterboxes.
    CircularVideo {
        id: videoOut
        anchors.fill: parent
        visible: bubbleRoot.playing
    }

    // Progress ring, doubling as the scrubber - there is nowhere to put a
    // horizontal timeline on a circle.
    Canvas {
        id: ring
        anchors.fill: parent
        property real progress: (player.duration > 0) ? player.position / player.duration : 0
        onProgressChanged: requestPaint()
        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            var r = width / 2 - 2
            ctx.lineWidth = 3
            ctx.lineCap = "round"
            ctx.strokeStyle = Qt.rgba(1, 1, 1, 0.25)
            ctx.beginPath(); ctx.arc(width / 2, height / 2, r, 0, Math.PI * 2); ctx.stroke()
            if (progress > 0) {
                ctx.strokeStyle = "#FFFFFF"
                ctx.beginPath()
                ctx.arc(width / 2, height / 2, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * progress)
                ctx.stroke()
            }
        }
    }

    // Play affordance / download progress.
    Rectangle {
        anchors.centerIn: parent
        width: 44; height: 44; radius: 22
        color: Qt.rgba(0, 0, 0, 0.4)
        visible: !bubbleRoot.playing
        Text {
            anchors.centerIn: parent
            text: bubbleRoot.downloading ? "…" : "▶"
            color: "#FFFFFF"
            font.pixelSize: 18
        }
    }

    Text {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 14
        text: bubbleRoot.formatDuration(
            bubbleRoot.playing && player.duration > 0
                ? (player.duration - player.position)
                : bubbleRoot.durationMs)
        color: "#FFFFFF"
        font.pixelSize: 11
    }

    MouseArea {
        anchors.fill: parent
        cursorShape: Qt.PointingHandCursor
        onClicked: bubbleRoot.toggle()
        onPositionChanged: function(mouse) {
            if (!pressed || player.duration <= 0) return
            // Only scrub near the rim, so a click-and-slip in the middle does
            // not seek by accident.
            var cx = width / 2, cy = height / 2
            var dx = mouse.x - cx, dy = mouse.y - cy
            var dist = Math.sqrt(dx * dx + dy * dy)
            if (dist < (width / 2) * 0.6) return
            var angle = Math.atan2(dx, -dy) * 180 / Math.PI
            if (angle < 0) angle += 360
            player.position = player.duration * (angle / 360)
        }
    }

    function toggle() {
        if (bubbleRoot.playing) {
            player.pause()
            return
        }
        if (bubbleRoot.localPath !== "") {
            player.play()
            return
        }
        bubbleRoot.downloading = true
        chatService.preparePlayableVideoNote(bubbleRoot.chatId, bubbleRoot.messageId,
                                              bubbleRoot.fileUrl, bubbleRoot.encrypted,
                                              bubbleRoot.senderId, bubbleRoot.keyVersion)
    }

    Connections {
        target: chatService

        function onVideoNoteReadyForPlayback(messageId, path) {
            if (messageId !== bubbleRoot.messageId) return
            bubbleRoot.downloading = false
            bubbleRoot.localPath = path
            player.source = "file:///" + path
            player.play()
        }

        function onVideoNoteThumbnailReady(messageId, path) {
            if (messageId !== bubbleRoot.messageId) return
            bubbleRoot.thumbPath = path
        }

        function onVideoNotePlaybackError(messageId, error) {
            if (messageId !== bubbleRoot.messageId) return
            bubbleRoot.downloading = false
            console.log("[RoundVideoBubble] playback error:", error)
        }
    }

    function formatDuration(ms) {
        var total = Math.max(0, Math.floor(ms / 1000))
        var m = Math.floor(total / 60)
        var s = total % 60
        return m + ":" + (s < 10 ? "0" : "") + s
    }

    Component.onCompleted: {
        if (thumbnailUrl !== "") {
            chatService.prepareVideoNoteThumbnail(chatId, messageId, thumbnailUrl, encrypted,
                                                   senderId, keyVersion)
        }
    }
}
