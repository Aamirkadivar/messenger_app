import QtQuick 2.15

// Liquid glass surface without QtQuick.Effects (MultiEffect is a no-op on this
// MinGW/GPU stack). Layers a dark frost plate, specular sheen, soft rim light,
// and hairline edge so AmbientGlow reads through like frosted glass.
Rectangle {
    id: root

    property bool darkMode: true
    // True for floating dialogs (Settings, New Group, …): denser plate so the
    // panel reads solid against the frosted scrim behind it.
    property bool elevated: false
    // Frost wash over the base plate.
    property real fillOpacity: {
        if (elevated) return darkMode ? 0.06 : 0.36
        return darkMode ? 0.04 : 0.28
    }
    // Specular highlight along the top edge - the "liquid" catch-light.
    property real sheenOpacity: {
        if (elevated) return darkMode ? 0.18 : 0.48
        return darkMode ? 0.14 : 0.42
    }
    property color borderColor: darkMode ? Qt.rgba(1, 1, 1, 0.16) : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.14)
    property bool sheen: true

    // Dark mode: near-black plate. Elevated dialogs are more opaque so content
    // behind the frosted scrim does not bleed through the panel itself.
    property color baseColor: {
        if (darkMode) {
            return elevated ? Qt.rgba(12 / 255, 10 / 255, 8 / 255, 0.92)
                            : Qt.rgba(8 / 255, 7 / 255, 5 / 255, 0.72)
        }
        return elevated ? Qt.rgba(1, 1, 1, 0.92) : Qt.rgba(1, 1, 1, 0.78)
    }

    radius: 18
    color: root.baseColor
    border.color: root.borderColor
    border.width: 1
    antialiasing: true

    // Inner frost - slight white lift so the plate reads as glass, not matte.
    Rectangle {
        anchors.fill: parent
        anchors.margins: 1
        radius: Math.max(0, root.radius - 1)
        color: root.darkMode ? Qt.rgba(1, 1, 1, root.fillOpacity)
                             : Qt.rgba(1, 1, 1, root.fillOpacity)
        antialiasing: true
    }

    // Top specular band - brighter and taller in dark mode so glass still
    // "catches light" against a darker plate (liquid-glass cue).
    Rectangle {
        visible: root.sheen
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 1
        height: Math.min(parent.height * (root.darkMode ? 0.42 : 0.5), root.darkMode ? 56 : 48)
        topLeftRadius: Math.max(0, root.radius - 1)
        topRightRadius: Math.max(0, root.radius - 1)
        bottomLeftRadius: 0
        bottomRightRadius: 0
        antialiasing: true
        gradient: Gradient {
            GradientStop {
                position: 0.0
                color: root.darkMode ? Qt.rgba(1, 1, 1, root.sheenOpacity)
                                     : Qt.rgba(1, 1, 1, root.sheenOpacity)
            }
            GradientStop {
                position: 0.35
                color: root.darkMode ? Qt.rgba(1, 1, 1, root.sheenOpacity * 0.35)
                                     : Qt.rgba(1, 1, 1, root.sheenOpacity * 0.4)
            }
            GradientStop { position: 1.0; color: Qt.rgba(1, 1, 1, 0) }
        }
    }

    // Soft bottom reflection - faint upward wash, keeps panels from looking flat.
    Rectangle {
        visible: root.sheen && parent.height > 64
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 1
        height: Math.min(parent.height * 0.22, 28)
        topLeftRadius: 0
        topRightRadius: 0
        bottomLeftRadius: Math.max(0, root.radius - 1)
        bottomRightRadius: Math.max(0, root.radius - 1)
        antialiasing: true
        gradient: Gradient {
            GradientStop { position: 0.0; color: Qt.rgba(1, 1, 1, 0) }
            GradientStop {
                position: 1.0
                color: root.darkMode ? Qt.rgba(1, 1, 1, 0.04) : Qt.rgba(1, 1, 1, 0.18)
            }
        }
    }

    // Bright rim along the top edge only - liquid glass "bevel".
    Rectangle {
        visible: root.sheen
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 1
        anchors.leftMargin: Math.max(2, root.radius * 0.35)
        anchors.rightMargin: Math.max(2, root.radius * 0.35)
        height: 1
        radius: 0.5
        color: root.darkMode ? Qt.rgba(1, 1, 1, 0.28) : Qt.rgba(1, 1, 1, 0.55)
    }
}
