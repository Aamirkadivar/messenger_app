import QtQuick 2.15

// Circular avatar: shows the loaded picture when there is one and the network
// fetch succeeds, and a coloured initial otherwise - covering "no picture",
// "still loading" and "failed to load" with the same fallback so a slow or
// broken image never leaves a blank hole in a chat row or bubble header.
Item {
    id: avatarRoot

    property string name: ""
    property string avatarUrl: ""
    property int size: 40
    // Stable per-name colour, so the same person keeps the same fallback
    // colour everywhere they appear.
    readonly property var palette: ["#C9A961", "#A6803A", "#7A5C22", "#4CAF50", "#FF9800", "#E07A5F", "#00A896", "#795548", "#A39A8A", "#5C4A20"]
    readonly property color fallbackColor: {
        if (!name || name.length === 0) return palette[0]
        var hash = 0
        for (var i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
        return palette[Math.abs(hash) % palette.length]
    }
    // Resolves a server-relative path ("/uploads/avatars/<uuid>.jpg") against
    // the API host, the same way ChatService/GroupService avatar uploads
    // return it - the server never bakes in an absolute URL since its host/IP
    // changes between networks.
    readonly property string resolvedUrl: {
        if (!avatarUrl || avatarUrl.length === 0) return ""
        if (typeof appConfig === "undefined") return avatarUrl
        return appConfig.resolveUrl(avatarUrl)
    }

    width: size
    height: size

    Rectangle {
        anchors.fill: parent
        radius: size / 2
        color: avatarRoot.fallbackColor
        visible: image.status !== Image.Ready

        Text {
            anchors.centerIn: parent
            text: avatarRoot.name && avatarRoot.name.length > 0 ? avatarRoot.name.substring(0, 1).toUpperCase() : "?"
            font.pixelSize: avatarRoot.size * 0.42
            font.weight: Font.DemiBold
            color: "#FFFFFF"
        }
    }

    Image {
        id: image
        anchors.fill: parent
        source: avatarRoot.resolvedUrl
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        cache: true
        visible: false

        onStatusChanged: {
            if (status === Image.Ready) repaintRetryTimer.start()
        }
        // onStatusChanged only fires on a *change* - a cache hit can make
        // status Ready from the very first evaluation, with no transition
        // for that handler to ever see. Cover that case explicitly too.
        Component.onCompleted: if (status === Image.Ready) repaintRetryTimer.start()
    }

    // Plain Item.clip only clips to the bounding box, not a rounded shape -
    // it does NOT respect Rectangle.radius despite how that sounds like it
    // should work. Drawing through a circular Canvas path (destination-in
    // compositing) is the dependency-free way to actually clip the photo to
    // a circle, unlike QtQuick.Effects/MultiEffect which turned out to
    // render nothing at all in this build.
    Canvas {
        id: circleCanvas
        anchors.fill: parent
        visible: image.status === Image.Ready
        onPaint: {
            if (image.status !== Image.Ready) return
            var ctx = getContext("2d")
            ctx.reset()
            ctx.save()
            ctx.beginPath()
            ctx.arc(width / 2, height / 2, width / 2, 0, Math.PI * 2)
            ctx.closePath()
            ctx.clip()
            ctx.drawImage(image, 0, 0, width, height)
            ctx.restore()
        }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }

    // A single requestPaint() right when status flips to Ready isn't
    // reliable here: when many avatars are created together (the whole chat
    // list rebuilds in one go on every refresh), an image resolving straight
    // from cache can report Ready before its texture is actually usable by
    // Canvas.drawImage - that one paint draws nothing, and nothing was left
    // to prompt a retry. Repainting a few times over the following second is
    // a blunt fix, but a guaranteed one: whichever attempt lands after the
    // texture is actually ready is enough, and a handful of no-op repaints
    // of a 40-90px canvas costs nothing.
    Timer {
        id: repaintRetryTimer
        interval: 80
        repeat: true
        property int attempts: 0
        onTriggered: {
            circleCanvas.requestPaint()
            attempts++
            if (attempts >= 10) { stop(); attempts = 0 }
        }
        onRunningChanged: if (!running) attempts = 0
    }
}
