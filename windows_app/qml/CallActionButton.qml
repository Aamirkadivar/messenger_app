import QtQuick 2.15
import QtQuick.Layouts 1.15

// Round glyph button used by CallOverlay for accept/decline/mute/end-call -
// this app draws most of its icons on Canvas for crispness, but call-state
// glyphs are simple enough (and change rarely enough) that a plain emoji
// glyph is a reasonable shortcut, matching the 🔒 security-notice precedent
// already used elsewhere in this app.
ColumnLayout {
    id: root
    property string glyph: ""
    property string label: ""
    property color bgColor: "#4CAF50"
    property color glyphColor: "#FFFFFF"
    property real size: 64
    signal clicked()

    spacing: 6

    Rectangle {
        Layout.alignment: Qt.AlignHCenter
        width: root.size; height: root.size
        radius: root.size / 2
        color: root.bgColor

        Text {
            anchors.centerIn: parent
            text: root.glyph
            font.pixelSize: root.size * 0.36
            color: root.glyphColor
        }

        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: root.clicked()
        }
    }

    Text {
        Layout.alignment: Qt.AlignHCenter
        text: root.label
        font.pixelSize: 12
        color: "#9E9E9E"
    }
}
