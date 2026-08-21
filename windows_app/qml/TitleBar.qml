import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Window 2.15

GlassPanel {
    id: titleBar
    height: 44
    radius: 0
    sheen: false

    property bool darkMode: true
    property color textColor: "#F0EAD6"
    property color textSecondary: "#9A9180"
    property color surfaceColorHover: "#16120E"
    property color borderColor: "#333"
    property bool instantTheme: false
    property bool loggedIn: false
    property bool windowMaximized: false

    // Window controls need hover feedback that reads at a glance. The shared
    // surfaceColorHover lands ~10 RGB units from the near-black bar it covers,
    // which is invisible in dark mode - hovering a control looked like nothing
    // happened at all. A translucent overlay lifts off whatever it sits on
    // instead, the same reasoning that made surfaceColorHover a tint rather
    // than a flat hex in light mode.
    readonly property color controlHoverBg: darkMode ? Qt.rgba(1, 1, 1, 0.12)
                                                     : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.10)
    readonly property color controlPressedBg: darkMode ? Qt.rgba(1, 1, 1, 0.22)
                                                       : Qt.rgba(43 / 255, 36 / 255, 24 / 255, 0.18)

    signal settingsClicked()
    signal minimizeClicked()
    signal closeClicked()

    MouseArea {
        anchors.fill: parent
        onPressed: {
            if (Window.window)
                Window.window.startSystemMove()
        }
        onDoubleClicked: {
            var w = Window.window
            if (!w) return
            if (w.visibility === Window.Maximized || w.visibility === Window.FullScreen)
                w.showNormal()
            else
                w.showMaximized()
        }
    }

    RowLayout {
        z: 1
        anchors.fill: parent
        anchors.leftMargin: 16
        spacing: 8

        Image {
            Layout.preferredWidth: 22
            Layout.preferredHeight: 22
            source: "qrc:/icons/app_icon.png"
            sourceSize: Qt.size(64, 64)
            fillMode: Image.PreserveAspectFit
            smooth: true
        }

        Text {
            text: "Messenger"
            font.pixelSize: 13
            font.bold: true
            color: titleBar.textColor
        }

        Item { Layout.fillWidth: true }

        Rectangle {
            id: settingsBtn
            Layout.preferredWidth: 36
            Layout.preferredHeight: 36
            radius: 8
            visible: titleBar.loggedIn
            color: settingsMouse.containsPress ? titleBar.controlPressedBg
                 : (settingsMouse.containsMouse ? titleBar.controlHoverBg : "transparent")
            Behavior on color {
                enabled: !titleBar.instantTheme
                ColorAnimation { duration: 100 }
            }

            Canvas {
                anchors.centerIn: parent
                width: 16
                height: 16
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    ctx.fillStyle = titleBar.textSecondary
                    var cx = 8, cy = 8
                    var bodyR = 4.6
                    var toothLen = 2.1
                    var toothW = 2.0
                    var teeth = 8
                    ctx.beginPath()
                    ctx.arc(cx, cy, bodyR, 0, Math.PI * 2)
                    ctx.fill()
                    for (var i = 0; i < teeth; i++) {
                        var angle = (i / teeth) * Math.PI * 2
                        ctx.save()
                        ctx.translate(cx, cy)
                        ctx.rotate(angle)
                        ctx.fillRect(-toothW / 2, -(bodyR + toothLen), toothW, toothLen + 0.5)
                        ctx.restore()
                    }
                    ctx.globalCompositeOperation = "destination-out"
                    ctx.beginPath()
                    ctx.arc(cx, cy, 1.9, 0, Math.PI * 2)
                    ctx.fill()
                    ctx.globalCompositeOperation = "source-over"
                }
            }

            ToolTip.visible: settingsMouse.containsMouse
            ToolTip.text: "Settings"
            ToolTip.delay: 400

            MouseArea {
                id: settingsMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: titleBar.settingsClicked()
            }
        }

        Rectangle {
            id: minBtn
            Layout.preferredWidth: 36
            Layout.preferredHeight: 36
            radius: 8
            color: minMouse.containsPress ? titleBar.controlPressedBg
                 : (minMouse.containsMouse ? titleBar.controlHoverBg : "transparent")
            Behavior on color {
                enabled: !titleBar.instantTheme
                ColorAnimation { duration: 100 }
            }

            Rectangle {
                anchors.centerIn: parent
                width: 10
                height: 1.4
                color: titleBar.textSecondary
            }
            MouseArea {
                id: minMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: titleBar.minimizeClicked()
            }
        }

        Rectangle {
            id: maxBtn
            Layout.preferredWidth: 36
            Layout.preferredHeight: 36
            radius: 8
            color: maxMouse.containsPress ? titleBar.controlPressedBg
                 : (maxMouse.containsMouse ? titleBar.controlHoverBg : "transparent")
            Behavior on color {
                enabled: !titleBar.instantTheme
                ColorAnimation { duration: 100 }
            }

            Canvas {
                id: maxIcon
                anchors.centerIn: parent
                width: 12
                height: 12
                property bool maximized: titleBar.windowMaximized
                // A Canvas does not repaint just because a color it read in
                // onPaint changed, so the glyph kept the previous theme's
                // color until something else forced a repaint.
                property color fg: titleBar.textSecondary
                onMaximizedChanged: requestPaint()
                onFgChanged: requestPaint()
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    ctx.strokeStyle = fg
                    ctx.lineWidth = 1.4
                    if (maximized) {
                        // Restore: two stacked windows. The rear one is drawn
                        // first and then has the front one's footprint punched
                        // out of it - without that the squares' edges cross
                        // and the glyph reads as a lattice rather than as one
                        // window in front of another.
                        ctx.strokeRect(3.5, 0.5, 8, 8)
                        ctx.globalCompositeOperation = "destination-out"
                        ctx.fillRect(-1, 2, 11, 11)
                        ctx.globalCompositeOperation = "source-over"
                        ctx.strokeRect(0.5, 3.5, 8, 8)
                    } else {
                        ctx.strokeRect(0.5, 0.5, 11, 11)
                    }
                }
            }
            ToolTip.visible: maxMouse.containsMouse
            ToolTip.text: titleBar.windowMaximized ? "Restore down" : "Maximize"
            ToolTip.delay: 400

            // Handled here rather than natively: the HTMAXBUTTON route that
            // used to own this button's clicks and hover crashes the process
            // once it actually engages (see Win11Frameless::hitTest).
            MouseArea {
                id: maxMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: {
                    var w = Window.window
                    if (!w) return
                    if (titleBar.windowMaximized)
                        w.showNormal()
                    else
                        w.showMaximized()
                }
            }
        }

        Rectangle {
            id: closeBtn
            Layout.preferredWidth: 36
            Layout.preferredHeight: 36
            radius: 8
            color: closeMouse.containsMouse ? "#E74C3C" : "transparent"
            Behavior on color {
                enabled: !titleBar.instantTheme
                ColorAnimation { duration: 100 }
            }

            Canvas {
                anchors.centerIn: parent
                width: 12
                height: 12
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    ctx.strokeStyle = closeMouse.containsMouse ? "#FFFFFF" : titleBar.textSecondary
                    ctx.lineWidth = 1.4
                    ctx.lineCap = "round"
                    ctx.beginPath(); ctx.moveTo(1, 1); ctx.lineTo(11, 11); ctx.stroke()
                    ctx.beginPath(); ctx.moveTo(11, 1); ctx.lineTo(1, 11); ctx.stroke()
                }
            }
            MouseArea {
                id: closeMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: titleBar.closeClicked()
            }
        }
    }

    Component.onCompleted: bindNativeChrome()
    onVisibleChanged: bindNativeChrome()

    function bindNativeChrome() {
        if (typeof win11Frameless === "undefined")
            return
        win11Frameless.bindChrome(titleBar, settingsBtn, minBtn, maxBtn, closeBtn)
    }
}
