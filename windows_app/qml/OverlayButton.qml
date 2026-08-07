import QtQuick

// Round control used by the round-video recorder overlay. Kept separate so the
// three hands-free actions share one hit area, hover and press treatment.
Rectangle {
    id: root

    property string glyph: ""
    property string tooltipText: ""
    property bool accent: false
    property color accentColor: "#C9A961"

    signal activated()

    width: 52
    height: 52
    radius: 26
    color: accent
           ? (mouse.pressed ? Qt.darker(accentColor, 1.2)
                            : (mouse.containsMouse ? Qt.lighter(accentColor, 1.1) : accentColor))
           : (mouse.pressed ? Qt.rgba(1, 1, 1, 0.32)
                            : (mouse.containsMouse ? Qt.rgba(1, 1, 1, 0.24) : Qt.rgba(1, 1, 1, 0.16)))
    Behavior on color { ColorAnimation { duration: 100 } }

    Text {
        anchors.centerIn: parent
        text: root.glyph
        color: "#FFFFFF"
        font.pixelSize: 20
    }

    Text {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.bottom
        anchors.topMargin: 6
        text: root.tooltipText
        color: Qt.rgba(1, 1, 1, 0.75)
        font.pixelSize: 11
    }

    MouseArea {
        id: mouse
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: root.activated()
    }
}
