import QtQuick 2.15

// Reusable "glass" surface for the app's glassmorphism redesign: translucent
// fill + hairline border + a soft top sheen (the classic "light catching an
// edge" cue that reads as glass rather than a plain dimmed panel).
//
// Deliberately NOT using real-time GPU blur (QtQuick.Effects/MultiEffect) -
// that was tried for Avatar.qml's circular photo clip and rendered nothing
// at all in this build/GPU combination (see Avatar.qml's comments), so nothing
// here depends on it. The "glass" read instead comes from layering this
// translucency over AmbientGlow's soft colour blobs - painting the blurred
// light source directly rather than blurring real content behind it.
Rectangle {
    id: root

    property bool darkMode: true
    // Content behind a glass surface should show through faintly - too
    // opaque and it just looks like a solid panel, too transparent and text
    // on top loses contrast. These defaults are tuned against the app's near-
    // black / warm-ivory bases specifically, not generic glass values.
    // A faint white tint on top of the base below. This used to carry the
    // whole surface on its own, which is why panels read as barely-there and
    // the content behind them competed for attention.
    property real fillOpacity: darkMode ? 0.035 : 0.22
    property real sheenOpacity: darkMode ? 0.06 : 0.32
    property color borderColor: darkMode ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.12)
    // Whether to draw the top sheen at all - off for small elements (chips,
    // small buttons) where it would just read as noise.
    property bool sheen: true

    // Tinted backing painted *under* the white fill above - this is what makes
    // a panel read as a surface rather than a faint haze.
    //
    // Raising fillOpacity is not a substitute: that fill is white, so pushing
    // it up washes the panel pale instead of making it solid. Kept partly
    // translucent on purpose, so AmbientGlow's colour still shows through and
    // the glass character survives - just not so far that whatever sits
    // behind a panel stays legible through it and makes the UI hard to read.
    property color baseColor: darkMode ? Qt.rgba(20 / 255, 20 / 255, 31 / 255, 0.72)
                                       : Qt.rgba(1, 1, 1, 0.76)

    radius: 18
    color: root.baseColor
    border.color: root.borderColor
    border.width: 1
    antialiasing: true

    // The glass tint itself - above any opaque base, below the sheen. Inset by
    // the border width so it never paints over the hairline edge.
    Rectangle {
        anchors.fill: parent
        anchors.margins: 1
        radius: Math.max(0, root.radius - 1)
        color: Qt.rgba(1, 1, 1, root.fillOpacity)
        antialiasing: true
    }

    Rectangle {
        visible: root.sheen
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.margins: 1
        height: Math.min(parent.height * 0.5, 48)
        topLeftRadius: Math.max(0, root.radius - 1)
        topRightRadius: Math.max(0, root.radius - 1)
        bottomLeftRadius: 0
        bottomRightRadius: 0
        antialiasing: true
        gradient: Gradient {
            GradientStop { position: 0.0; color: Qt.rgba(1, 1, 1, root.sheenOpacity) }
            GradientStop { position: 1.0; color: Qt.rgba(1, 1, 1, 0) }
        }
    }
}
