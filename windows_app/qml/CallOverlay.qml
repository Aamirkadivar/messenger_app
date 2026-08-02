import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

// Full-window call overlay - shown above the whole app (see main.qml)
// whenever callService.isActive, mirroring the Android app's CallOverlay:
// same states (incoming/outgoing/connecting/connected/ended), same status
// text, same "server never sees media" guarantee underneath (this is purely
// presentation over CallService's WebRTC/signaling logic).
Item {
    id: root
    property bool darkMode: true
    property color accentColor: "#C9A961"

    readonly property color bgColor: darkMode ? "#0A0A0F" : "#FAF6EE"
    readonly property color textColor: darkMode ? "#F0EAD6" : "#2B2418"
    readonly property color textSecondary: darkMode ? "#A39A8A" : "#7A6F5C"
    readonly property color errorColor: "#FF6B6B"
    readonly property color successColor: "#4CAF50"

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
            case "outgoing_ringing": return "Ringing…"
            case "incoming_ringing": return "Incoming call"
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

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 32
        spacing: 8

        Item { Layout.preferredHeight: 72 }

        // Caller/callee avatar - initial letter, same palette convention as Avatar.qml.
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
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

        Item { Layout.preferredHeight: 20 }

        Text {
            Layout.alignment: Qt.AlignHCenter
            text: callService.peerName.length > 0 ? callService.peerName : "Unknown"
            font.pixelSize: 24
            font.bold: true
            color: root.textColor
        }

        Item { Layout.preferredHeight: 8 }

        Text {
            Layout.alignment: Qt.AlignHCenter
            text: root.statusLabel()
            font.pixelSize: 16
            color: root.textSecondary
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
                bgColor: callService.isMuted ? root.accentColor : (root.darkMode ? "#26264A" : "#EDEDF2")
                glyphColor: callService.isMuted ? "#FFFFFF" : root.textColor
                size: 56
                onClicked: callService.toggleMute()
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
