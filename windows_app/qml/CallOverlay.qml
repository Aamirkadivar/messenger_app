import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import Messenger 1.0

// Full-window call overlay - shown above the whole app (see main.qml)
// whenever callService.isActive, mirroring the Android app's CallOverlay:
// same states (incoming/outgoing/connecting/connected/ended), same status
// text, same "server never sees media" guarantee underneath (this is purely
// presentation over CallService's WebRTC/signaling logic).
Item {
    id: root
    property bool darkMode: true
    property color accentColor: "#C9A961"

    readonly property color bgColor: darkMode ? "#050403" : "#FAF6EE"
    readonly property color textColor: darkMode ? "#F0EAD6" : "#2B2418"
    readonly property color textSecondary: darkMode ? "#A39A8A" : "#7A6F5C"
    readonly property color errorColor: "#FF6B6B"
    readonly property color successColor: "#4CAF50"

    readonly property bool groupVideoActive: callService.isGroupCall && callService.isVideoCall

    // Ticks the elapsed-time label once a second while connected.
    // real, not int: QML's int is 32-bit (max ~2.1e9) while Date.now() is
    // ~1.78e12 ms since epoch, so an int silently overflows on every tick and
    // the elapsed-time arithmetic produces nonsense - the call timer never
    // advanced at all.
    property real nowMs: 0
    Timer {
        interval: 1000
        running: callService.status === "connected"
        repeat: true
        onTriggered: root.nowMs = Date.now()
        Component.onCompleted: root.nowMs = Date.now()
    }

    function formatElapsed() {
        if (callService.connectedAtMs <= 0) return "Connected"
        var totalSec = Math.max(0, Math.floor((root.nowMs - callService.connectedAtMs) / 1000))
        var m = Math.floor(totalSec / 60)
        var s = totalSec % 60
        return m + ":" + (s < 10 ? "0" : "") + s
    }

    function statusLabel() {
        switch (callService.status) {
            case "outgoing_ringing": return callService.isGroupCall ? "Calling group…" : "Ringing…"
            case "incoming_ringing":
                if (callService.isGroupCall)
                    return callService.isVideoCall ? "Incoming group video call" : "Incoming group call"
                return callService.isVideoCall ? "Incoming video call" : "Incoming call"
            case "connecting": return "Connecting…"
            case "connected": return formatElapsed()
            case "ended":
                switch (callService.endReason) {
                    case "declined": return "Call declined"
                    case "offline": case "unreachable": return "Couldn't connect"
                    case "busy": return "User busy"
                    case "failed": return "Call failed"
                    default: return "Call ended"
                }
            default: return ""
        }
    }

    function participantSummary() {
        var list = callService.participants || []
        if (!list.length) return ""
        var names = []
        for (var i = 0; i < list.length; i++) {
            var n = list[i].name || list[i].userId || "?"
            if (list[i].connected) n = n + " ●"
            names.push(n)
        }
        return names.join(" · ")
    }

    function presentPeerFrame(peerId, frame) {
        for (var i = 0; i < peerRepeater.count; i++) {
            var item = peerRepeater.itemAt(i)
            if (item && item.peerId === peerId) {
                item.present(frame)
                return
            }
        }
    }

    function clearPeerFrames() {
        for (var i = 0; i < peerRepeater.count; i++) {
            var item = peerRepeater.itemAt(i)
            if (item) item.clear()
        }
    }

    Timer {
        // Auto-dismiss the brief "call ended" screen.
        interval: 1500
        running: callService.status === "ended"
        onTriggered: callService.dismissEnded()
    }

    Rectangle {
        anchors.fill: parent
        color: root.bgColor
    }

    // Swallow clicks so nothing behind this overlay is reachable while a call is up.
    MouseArea { anchors.fill: parent; hoverEnabled: true }

    // ---- Video layers (video calls only) ----
    // Painted below the ColumnLayout so name/status/buttons stay on top.

    // Remote feed once connected (1:1). Fit letterboxes a portrait phone
    // feed (black side bars) instead of centre-cropping the face away.
    VideoFrame {
        id: remoteView
        anchors.fill: parent
        fillMode: VideoFrame.Fit
        visible: callService.isVideoCall && !callService.isGroupCall && hasFrame && callService.remoteCameraOn
                 && (callService.status === "connected" || callService.status === "connecting")
    }

    // Group video: Meet-style 16:9 tile grid. VideoFrame uses Fit so a
    // vertical phone camera keeps its full frame with black side bars.
    // Self is a tile in the grid (not a floating PiP).
    Item {
        id: groupVideoArea
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        anchors.topMargin: 80
        anchors.bottomMargin: 148
        visible: root.groupVideoActive
                 && (callService.status === "connecting" || callService.status === "connected")

        readonly property real aspect: 16 / 9
        readonly property real gap: 10

        readonly property int remoteCount: (callService.participants || []).length
        // Remotes + self as the last tile.
        readonly property int count: remoteCount + 1
        // Meet-ish column rules: 1 alone, 2 side-by-side, 3–4 as 2×2.
        readonly property int cols: count <= 1 ? 1 : 2
        readonly property int rows: Math.max(1, Math.ceil(count / cols))

        readonly property real tileW: {
            if (width <= 0 || height <= 0 || count === 0) return 0
            var aw = width - gap * Math.max(0, cols - 1)
            var ah = height - gap * Math.max(0, rows - 1)
            var byWidth = aw / cols
            var byHeight = (ah / rows) * aspect
            return Math.floor(Math.min(byWidth, byHeight))
        }
        readonly property real tileH: tileW / aspect
        readonly property real gridW: cols * tileW + gap * Math.max(0, cols - 1)
        readonly property real gridH: rows * tileH + gap * Math.max(0, rows - 1)
        readonly property real originX: (width - gridW) / 2
        readonly property real originY: (height - gridH) / 2

        function tileAt(index) {
            if (index < 0 || index >= count) return null
            if (index >= remoteCount) {
                return {
                    userId: "__local__",
                    name: "You",
                    cameraOn: callService.cameraOn,
                    connected: true,
                    isSelf: true
                }
            }
            var p = (callService.participants || [])[index] || {}
            return {
                userId: p.userId || "",
                name: p.name || p.userId || "?",
                cameraOn: p.cameraOn !== false,
                connected: p.connected === true,
                isSelf: false
            }
        }

        Repeater {
            id: peerRepeater
            model: groupVideoArea.count

            delegate: Item {
                id: peerCell
                readonly property var tile: groupVideoArea.tileAt(index)
                property string peerId: tile ? tile.userId : ""
                property string peerName: tile ? tile.name : ""
                property bool peerCameraOn: tile ? tile.cameraOn : false
                property bool peerConnected: tile ? tile.connected : false
                property bool isSelf: tile ? tile.isSelf : false

                x: groupVideoArea.originX
                   + (index % groupVideoArea.cols) * (groupVideoArea.tileW + groupVideoArea.gap)
                y: groupVideoArea.originY
                   + Math.floor(index / groupVideoArea.cols) * (groupVideoArea.tileH + groupVideoArea.gap)
                width: groupVideoArea.tileW
                height: groupVideoArea.tileH
                visible: groupVideoArea.tileW > 0

                function present(frame) { peerVideo.present(frame) }
                function clear() { peerVideo.clear() }

                Rectangle {
                    anchors.fill: parent
                    radius: 12
                    color: root.darkMode ? "#1A1A28" : "#E8E4DC"
                    clip: true

                    VideoFrame {
                        id: peerVideo
                        anchors.fill: parent
                        radius: 12
                        fillMode: VideoFrame.Fit
                        mirror: peerCell.isSelf
                        visible: peerCell.peerCameraOn && hasFrame
                    }

                    Rectangle {
                        anchors.fill: parent
                        visible: !peerVideo.visible
                        color: "transparent"

                        Rectangle {
                            anchors.centerIn: parent
                            width: Math.min(parent.width, parent.height) * 0.32
                            height: width
                            radius: width / 2
                            color: root.accentColor

                            Text {
                                anchors.centerIn: parent
                                text: (peerCell.peerName.length > 0 ? peerCell.peerName.charAt(0) : "?").toUpperCase()
                                font.pixelSize: Math.max(16, parent.width * 0.4)
                                font.bold: true
                                color: "#FFFFFF"
                            }
                        }
                    }

                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 28
                        color: "#80000000"

                        Text {
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 10
                            verticalAlignment: Text.AlignVCenter
                            elide: Text.ElideRight
                            text: peerCell.peerName + (peerCell.peerConnected || peerCell.isSelf ? "" : " …")
                            font.pixelSize: 12
                            color: "#FFFFFF"
                        }
                    }
                }
            }
        }
    }

    // Our own camera, full-bleed while the outgoing call is still ringing
    // (the "check your hair" preview), before the remote feed exists.
    VideoFrame {
        id: localFullView
        anchors.fill: parent
        mirror: true
        fillMode: VideoFrame.Fit
        visible: callService.isVideoCall && hasFrame && callService.status === "outgoing_ringing"
    }

    // Legibility scrim over whichever full-bleed video is showing.
    Rectangle {
        anchors.fill: parent
        visible: remoteView.visible || localFullView.visible
        color: "#30000000"
    }

    // Self-view PiP for 1:1 only (group puts self in the 16:9 grid).
    VideoFrame {
        id: localPip
        width: 176
        height: 99
        radius: 12
        mirror: true
        fillMode: VideoFrame.Fit
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.margins: 20
        visible: callService.isVideoCall && !callService.isGroupCall && hasFrame && callService.cameraOn
                 && callService.status !== "outgoing_ringing"
                 && callService.status !== "incoming_ringing"
        z: 10
    }

    Connections {
        target: callService
        function onLocalVideoFrame(frame) {
            localFullView.present(frame)
            localPip.present(frame)
            // Feed the self tile in the group grid.
            for (var i = 0; i < peerRepeater.count; i++) {
                var item = peerRepeater.itemAt(i)
                if (item && item.isSelf) {
                    item.present(frame)
                    break
                }
            }
        }
        function onRemoteVideoFrame(frame) {
            remoteView.present(frame)
        }
        function onRemoteVideoFrameFromPeer(peerId, frame) {
            root.presentPeerFrame(peerId, frame)
        }
        function onStateChanged() {
            // Never leave the last frame of a finished call around to flash
            // up at the start of the next one.
            if (callService.status === "ended" || callService.status === "idle") {
                remoteView.clear()
                localFullView.clear()
                localPip.clear()
                root.clearPeerFrames()
            }
        }
    }

    // Over live video the beige/dark theme text is unreadable - force white.
    readonly property bool overVideo: remoteView.visible || localFullView.visible ||
                                      (root.groupVideoActive && callService.status !== "incoming_ringing")

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 32
        spacing: 8
        z: 5

        Item { Layout.preferredHeight: 72 }

        // Caller/callee avatar - initial letter, same palette convention as
        // Avatar.qml. Hidden while full-bleed video is showing (invisible
        // items take no space in a ColumnLayout, so the header moves up).
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            visible: !root.overVideo
            width: 120; height: 120
            radius: 60
            color: root.accentColor

            Text {
                anchors.centerIn: parent
                text: (callService.peerName.length > 0 ? callService.peerName.charAt(0) : "?").toUpperCase()
                font.pixelSize: 48
                font.bold: true
                color: "#FFFFFF"
            }
        }

        Item { Layout.preferredHeight: 20; visible: !root.overVideo }

        Text {
            Layout.alignment: Qt.AlignHCenter
            text: callService.peerName.length > 0 ? callService.peerName : "Unknown"
            font.pixelSize: 24
            font.bold: true
            color: root.overVideo ? "#FFFFFF" : root.textColor
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.maximumWidth: parent.width - 48
            visible: callService.isGroupCall && !root.groupVideoActive && root.participantSummary().length > 0
            text: root.participantSummary()
            font.pixelSize: 13
            wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            color: root.overVideo ? "#D0FFFFFF" : root.textSecondary
        }

        Item { Layout.preferredHeight: 8 }

        Text {
            Layout.alignment: Qt.AlignHCenter
            text: root.statusLabel()
            font.pixelSize: 16
            color: root.overVideo ? "#D0FFFFFF" : root.textSecondary
        }

        Item { Layout.fillHeight: true }

        // Incoming: decline / accept
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            visible: callService.status === "incoming_ringing"
            spacing: 64

            CallActionButton {
                glyph: "✕"
                label: "Decline"
                bgColor: root.errorColor
                onClicked: callService.rejectCall()
            }
            CallActionButton {
                glyph: "📞"
                label: "Accept"
                bgColor: root.successColor
                onClicked: callService.acceptCall()
            }
        }

        // Outgoing/connecting/connected: mute / end / (speaker placeholder - desktop has no speakerphone toggle)
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            visible: callService.status === "outgoing_ringing" || callService.status === "connecting" || callService.status === "connected"
            spacing: 32

            CallActionButton {
                glyph: callService.isMuted ? "🔇" : "🎤"
                label: callService.isMuted ? "Unmute" : "Mute"
                bgColor: callService.isMuted ? root.accentColor : (root.darkMode ? "#12100C" : "#F0EAD6")
                glyphColor: callService.isMuted ? "#FFFFFF" : root.textColor
                size: 56
                onClicked: callService.toggleMute()
            }
            CallActionButton {
                visible: callService.isVideoCall
                glyph: callService.cameraOn ? "🎥" : "🚫"
                label: callService.cameraOn ? "Camera" : "Cam off"
                bgColor: callService.cameraOn ? (root.darkMode ? "#12100C" : "#F0EAD6") : root.accentColor
                glyphColor: callService.cameraOn ? root.textColor : "#FFFFFF"
                size: 56
                onClicked: callService.toggleCamera()
            }
            CallActionButton {
                glyph: "☎"
                label: "End"
                bgColor: root.errorColor
                onClicked: callService.endCall()
            }
        }

        Item { Layout.preferredHeight: 48 }
    }
}
