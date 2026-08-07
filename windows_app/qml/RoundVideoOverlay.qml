import QtQuick
import QtQuick.Controls
import QtMultimedia
import Messenger 1.0

// Full-screen capture surface for a round video message.
//
// The preview is a CircularVideo item (QQuickPaintedItem + QPainter ellipse
// clip). QtQuick.Effects renders nothing in this Qt/MinGW/GPU combination and
// Rectangle.clip ignores radius, so neither of the usual ways to round a video
// surface is available - and painting a mask over a square VideoOutput only
// occludes if the mask is opaque, which it is not over this scrim.
Item {
    id: overlayRoot
    anchors.fill: parent

    property bool darkMode: true
    property color accentColor: "#C9A961"
    // Drag offsets from the capture button, in pixels, driven by ChatView.
    property real dragX: 0
    property real dragY: 0
    property bool willCancel: false
    property bool willLock: false
    property bool isLocked: false

    signal stopRequested()
    signal cancelRequested()
    signal pauseRequested()
    signal flipRequested()

    readonly property int circleSize: 260
    readonly property real cancelProgress: Math.max(0, Math.min(1, -dragX / 220))

    // Swallows clicks so nothing behind the scrim reacts.
    MouseArea { anchors.fill: parent }

    Rectangle {
        anchors.fill: parent
        color: Qt.rgba(0, 0, 0, 0.62)
    }

    Column {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        anchors.verticalCenterOffset: -40
        spacing: 18

        Item {
            id: circleWrap
            width: overlayRoot.circleSize
            height: overlayRoot.circleSize
            anchors.horizontalCenter: parent.horizontalCenter

            // Shrinks and dims as the pointer nears the cancel threshold, so
            // the gesture reads as "about to discard" before it commits.
            scale: overlayRoot.willCancel ? 0.82 : 1.0 - overlayRoot.cancelProgress * 0.1
            opacity: overlayRoot.willCancel ? 0.45 : 1.0
            Behavior on scale { NumberAnimation { duration: 130; easing.type: Easing.OutCubic } }
            Behavior on opacity { NumberAnimation { duration: 130 } }

            transform: Translate {
                x: overlayRoot.dragX * 0.22
                y: overlayRoot.dragY * 0.22
            }

            // Genuinely circular - QPainter clips to an ellipse path. The
            // earlier approach painted a "mask" over a square VideoOutput,
            // which cannot work over a translucent scrim: a see-through
            // colour occludes nothing, so the video's corners stayed visible
            // and it read as a circle inside a square.
            CircularVideo {
                id: videoOut
                anchors.fill: parent
            }

            // Rim
            Canvas {
                anchors.fill: parent
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    ctx.strokeStyle = Qt.rgba(1, 1, 1, 0.35)
                    ctx.lineWidth = 2
                    ctx.beginPath()
                    ctx.arc(width / 2, height / 2, width / 2 - 1, 0, Math.PI * 2)
                    ctx.stroke()
                }
            }

            // Camera switch, only when more than one is attached and only
            // before locking (rebinding would end the take).
            Rectangle {
                visible: roundVideoService.cameraCount > 1 && !overlayRoot.isLocked
                width: 34; height: 34; radius: 17
                color: flipMouse.containsMouse ? Qt.rgba(1, 1, 1, 0.28) : Qt.rgba(1, 1, 1, 0.16)
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 18
                Text {
                    anchors.centerIn: parent
                    text: "↻"
                    color: "#FFFFFF"
                    font.pixelSize: 17
                }
                MouseArea {
                    id: flipMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: overlayRoot.flipRequested()
                }
            }
        }

        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 8

            Rectangle {
                width: 9; height: 9; radius: 4.5
                anchors.verticalCenter: parent.verticalCenter
                color: roundVideoService.isPaused ? Qt.rgba(1, 1, 1, 0.5) : "#E74C3C"
                SequentialAnimation on opacity {
                    running: roundVideoService.isRecording && !roundVideoService.isPaused
                    loops: Animation.Infinite
                    NumberAnimation { to: 0.3; duration: 600 }
                    NumberAnimation { to: 1.0; duration: 600 }
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: overlayRoot.formatDuration(roundVideoService.elapsedMs)
                color: "#FFFFFF"
                font.pixelSize: 16
                font.bold: true
            }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: !overlayRoot.isLocked
            text: overlayRoot.willLock ? "Release to keep recording"
                                       : "← Drag to cancel  ·  Drag up to lock"
            color: Qt.rgba(1, 1, 1, Math.max(0.35, 1 - overlayRoot.cancelProgress))
            font.pixelSize: 12
        }
    }

    // Hands-free controls, once locked. Discard / pause / send - three
    // distinct actions, deliberately not two buttons that both stop.
    Row {
        visible: overlayRoot.isLocked
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 46
        spacing: 26

        OverlayButton {
            glyph: "✕"
            tooltipText: "Discard"
            onActivated: overlayRoot.cancelRequested()
        }
        OverlayButton {
            glyph: roundVideoService.isPaused ? "▶" : "‖"
            tooltipText: roundVideoService.isPaused ? "Resume" : "Pause"
            onActivated: overlayRoot.pauseRequested()
        }
        OverlayButton {
            glyph: "➤"
            tooltipText: "Send"
            accent: true
            accentColor: overlayRoot.accentColor
            onActivated: overlayRoot.stopRequested()
        }
    }

    function bindOutput() {
        roundVideoService.bindVideoOutput(videoOut.videoSink)
    }

    function formatDuration(ms) {
        var total = Math.max(0, Math.floor(ms / 1000))
        var m = Math.floor(total / 60)
        var s = total % 60
        return m + ":" + (s < 10 ? "0" : "") + s
    }

    Component.onCompleted: bindOutput()
}
