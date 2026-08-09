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

    property color baseColor: "#050403"
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
                grad.addColorStop(0.55, Qt.rgba(color.r, color.g, color.b, alpha * 0.35))
                grad.addColorStop(1, Qt.rgba(color.r, color.g, color.b, 0))
                ctx.fillStyle = grad
                ctx.beginPath()
                ctx.arc(cx, cy, r, 0, Math.PI * 2)
                ctx.fill()
            }

            // Softer, larger blooms so glass panels catch light at edges
            // without flooding the darker dark-mode base.
            blob(width * 0.12, height * 0.0, Math.max(width, height) * 0.48,
                 root.primaryGlow, 0.14 * root.intensity)
            blob(width * 0.95, height * 0.48, Math.max(width, height) * 0.38,
                 root.secondaryGlow, 0.10 * root.intensity)
            blob(width * 0.42, height * 1.05, Math.max(width, height) * 0.36,
                 root.primaryGlow, 0.07 * root.intensity)
            blob(width * 0.7, height * 0.12, Math.max(width, height) * 0.22,
                 root.primaryGlow, 0.06 * root.intensity)
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onPrimaryGlowChanged: canvas.requestPaint()
    onSecondaryGlowChanged: canvas.requestPaint()
    onIntensityChanged: canvas.requestPaint()
}
