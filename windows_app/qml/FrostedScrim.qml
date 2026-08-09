import QtQuick 2.15
import QtQuick.Window 2.15
import QtQuick.Effects

// Modal backdrop for Settings / New Group / etc.
//
// MultiEffect blur is a no-op on this Qt/MinGW/GPU stack (see CLAUDE.md), so
// the always-visible dim plate is the real "shadow". Blur is attempted
// underneath as a progressive enhancement when it actually paints.
//
// Title-bar strip (44px) stays clear so drag / min / max / close still work.
Item {
    id: root

    property bool darkMode: true
    property int titleBarInset: 44
    property Item blurSource: {
        var w = Window.window
        return (w && w.blurSource) ? w.blurSource : null
    }

    signal dismissed()

    readonly property bool hasBlur: blurSource !== null && blurSource.width > 0

    Item {
        id: blurHost
        anchors.fill: parent
        anchors.topMargin: root.titleBarInset
        clip: true

        ShaderEffectSource {
            id: backdropSrc
            anchors.fill: parent
            visible: false
            live: root.visible && root.hasBlur
            hideSource: false
            sourceItem: root.hasBlur ? root.blurSource : null
            sourceRect: {
                if (!root.hasBlur) return Qt.rect(0, 0, 0, 0)
                var p = blurHost.mapToItem(root.blurSource, 0, 0)
                return Qt.rect(p.x, p.y, blurHost.width, blurHost.height)
            }
        }

        MultiEffect {
            anchors.fill: parent
            source: backdropSrc
            visible: root.hasBlur
            autoPaddingEnabled: false
            blurEnabled: true
            blur: 1.0
            blurMax: 80
            blurMultiplier: 1.0
            brightness: root.darkMode ? -0.18 : -0.06
            contrast: root.darkMode ? -0.05 : 0.0
            saturation: root.darkMode ? 0.75 : 0.9
        }

        // Reliable scrim — must read as a shadow even when MultiEffect paints nothing.
        Rectangle {
            anchors.fill: parent
            color: root.darkMode ? Qt.rgba(0, 0, 0, 0.62)
                                 : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.45)
        }
    }

    // Blocks hover/clicks on everything behind the dialog; outside press closes.
    MouseArea {
        anchors.fill: parent
        anchors.topMargin: root.titleBarInset
        hoverEnabled: true
        preventStealing: true
        acceptedButtons: Qt.LeftButton | Qt.RightButton | Qt.MiddleButton
        cursorShape: Qt.ArrowCursor
        onPressed: function(mouse) {
            mouse.accepted = true
            root.dismissed()
        }
        // Swallow wheel so the chat list behind cannot scroll while open.
        onWheel: function(wheel) { wheel.accepted = true }
    }
}
