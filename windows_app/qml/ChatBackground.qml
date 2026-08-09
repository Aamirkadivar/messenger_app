import QtQuick 2.15

// A subtle diamond-lattice wallpaper behind the message list, matching the
// app's luxury palette. Kept very low-opacity and redrawn only on resize/
// theme change (not per-frame) since it's just decorative texture.
Item {
    id: root

    property color baseColor: "#050403"
    property color patternColor: "#C9A961"
    property real patternOpacity: 0.05
    property real tileSize: 48
    // Lets a caller layer this over something else (e.g. AmbientGlow) instead
    // of a fully opaque base - defaults to fully opaque so every existing
    // usage is unaffected.
    property real baseOpacity: 1.0

    Rectangle {
        anchors.fill: parent
        color: root.baseColor
        opacity: root.baseOpacity
    }

    Canvas {
        id: canvas
        anchors.fill: parent
        opacity: root.patternOpacity

        onPaint: {
            var ctx = getContext("2d")
            ctx.reset()
            ctx.strokeStyle = root.patternColor
            ctx.fillStyle = root.patternColor
            ctx.lineWidth = 1

            var s = root.tileSize
            var r = s * 0.28
            var cols = Math.ceil(width / s) + 1
            var rows = Math.ceil(height / s) + 1

            for (var row = 0; row < rows; row++) {
                for (var col = 0; col < cols; col++) {
                    var cx = col * s
                    var cy = row * s

                    ctx.beginPath()
                    ctx.moveTo(cx, cy - r)
                    ctx.lineTo(cx + r, cy)
                    ctx.lineTo(cx, cy + r)
                    ctx.lineTo(cx - r, cy)
                    ctx.closePath()
                    ctx.stroke()

                    ctx.beginPath()
                    ctx.arc(cx, cy, 1.2, 0, Math.PI * 2)
                    ctx.fill()
                }
            }
        }

        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    onBaseColorChanged: canvas.requestPaint()
    onPatternColorChanged: canvas.requestPaint()
}
