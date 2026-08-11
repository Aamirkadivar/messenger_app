import QtQuick 2.15

// Full-window dim behind Settings / New Group / etc.
//
// Covers the entire Overlay — including the title-bar strip — so only the
// dialog itself stays interactive. Do not import QtQuick.Effects: MultiEffect
// is a no-op here and a failed Effects load used to leave this Overlay empty.
Item {
    id: root

    property bool darkMode: true

    signal dismissed()

    Rectangle {
        anchors.fill: parent
        color: root.darkMode ? Qt.rgba(0, 0, 0, 0.68)
                             : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.52)
    }

    // Sink every pointer event under the dialog (hover, click, wheel).
    MouseArea {
        anchors.fill: parent
        hoverEnabled: true
        preventStealing: true
        acceptedButtons: Qt.AllButtons
        cursorShape: Qt.ArrowCursor
        onPressed: function(mouse) {
            mouse.accepted = true
            root.dismissed()
        }
        onWheel: function(wheel) { wheel.accepted = true }
    }
}
