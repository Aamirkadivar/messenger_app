import QtQuick 2.15
import QtQuick.Controls 2.15

Item {
    id: bubbleRoot

    property string messageText: ""
    property string messageTime: ""
    property bool isMine: true
    property bool isRead: false
    property string senderName: ""
    property bool isEncrypted: true
    property bool showSender: false
    property bool darkMode: true

    property color myMessageBg: "#6C63FF"
    property color theirMessageBg: darkMode ? "#26264A" : "#EDEDF2"
    property color myTextColor: "#FFFFFF"
    property color theirTextColor: darkMode ? "#EDEDF2" : "#1A1A2E"

    property real maxWidth: parent ? parent.width * 0.68 : 400
    property real minContentWidth: 68

    width: parent ? parent.width : 400
    height: bubble.height + 4

    Rectangle {
        id: bubble
        anchors.right: isMine ? parent.right : undefined
        anchors.left: isMine ? undefined : parent.left
        width: Math.min(
                   Math.max(contentText.implicitWidth, timeRow.implicitWidth, bubbleRoot.minContentWidth) + 28,
                   bubbleRoot.maxWidth
               )
        height: contentColumn.implicitHeight + 20
        radius: 16
        color: isMine ? bubbleRoot.myMessageBg : bubbleRoot.theirMessageBg

        Column {
            id: contentColumn
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 10
            spacing: 3

            Text {
                visible: bubbleRoot.showSender && !bubbleRoot.isMine
                text: bubbleRoot.senderName
                font.pixelSize: 12
                font.bold: true
                color: "#8B84FF"
                elide: Text.ElideRight
                width: parent.width
            }

            Text {
                id: contentText
                width: parent.width
                text: bubbleRoot.messageText
                font.pixelSize: 14
                color: isMine ? bubbleRoot.myTextColor : bubbleRoot.theirTextColor
                wrapMode: Text.Wrap
            }

            Row {
                id: timeRow
                anchors.right: parent.right
                spacing: 4
                topPadding: 2

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: bubbleRoot.messageTime
                    font.pixelSize: 11
                    color: isMine ? Qt.rgba(1, 1, 1, 0.65) : (darkMode ? "#8B8B9E" : "#6B6B7B")
                }

                Canvas {
                    id: checkmarkCanvas
                    anchors.verticalCenter: parent.verticalCenter
                    width: 14
                    height: 10
                    visible: bubbleRoot.isMine
                    onPaint: {
                        var ctx = getContext("2d")
                        ctx.reset()
                        // Grey = sent but not yet seen; gold = the other
                        // person has read it (matches the accent in both themes).
                        ctx.strokeStyle = bubbleRoot.isRead ? (darkMode ? "#C9A961" : "#A6803A") : "rgba(255,255,255,0.75)"
                        ctx.lineWidth = 1.4
                        ctx.lineCap = "round"
                        ctx.lineJoin = "round"
                        ctx.beginPath()
                        ctx.moveTo(0, 5); ctx.lineTo(3, 8); ctx.lineTo(8, 2)
                        ctx.stroke()
                        ctx.beginPath()
                        ctx.moveTo(5, 5); ctx.lineTo(8, 8); ctx.lineTo(14, 1)
                        ctx.stroke()
                    }

                    Connections {
                        target: bubbleRoot
                        function onIsReadChanged() { checkmarkCanvas.requestPaint() }
                    }
                }
            }
        }
    }
}
