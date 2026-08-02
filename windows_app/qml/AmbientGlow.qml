import QtQuick 2.15

// Soft, static colour blobs painted behind the app's glass surfaces -
// glassmorphism needs *something* with colour/light behind the glass for
// the translucency in GlassPanel to actually read as glass instead of a
// plain dimmed rectangle. Painted directly with Canvas radial gradients
// rather than blurring real content, for the same reason GlassPanel avoids
// QtQuick.Effects: MultiEffect rendered nothing at all when tried elsewhere
// in this app (see Avatar.qml).
//
// Static by design (repainted only on resize/colour change, no per-frame
// animation) - same performance philosophy ChatBackground.qml already
// documents for its own decorative texture: this is atmosphere, not content,
// and isn't worth a continuous repaint cost.
Item {
    id: root

    property color baseColor: "#0A0A0F"
    property color primaryGlow: "#C9A961"
    property color secondaryGlow: "#7A5C22"
    property real intensity: 1.0

    Rectangle {
        anchors.fill: parent
        color: root.baseColor
    }

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()

            function blob(cx, cy, r, color, alpha) {
                var grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, r)
                grad.addColorStop(0, Qt.rgba(color.r, color.g, color.b, alpha))
                grad.addColorStop(1, Qt.rgba(color.r, color.g, color.b, 0))
                ctx.fillStyle = grad
                ctx.beginPath()
                ctx.arc(cx, cy, r, 0, Math.PI * 2)
                ctx.fill()
            }

            blob(width * 0.18, height * 0.08, Math.max(width, height) * 0.42,
                 root.primaryGlow, 0.16 * root.intensity)
            blob(width * 0.92, height * 0.55, Math.max(width, height) * 0.34,
                 root.secondaryGlow, 0.12 * root.intensity)
            blob(width * 0.35, height * 1.02, Math.max(width, height) * 0.30,
                 root.primaryGlow, 0.08 * root.intensity)
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onPrimaryGlowChanged: canvas.requestPaint()
    onSecondaryGlowChanged: canvas.requestPaint()
    onIntensityChanged: canvas.requestPaint()
}
