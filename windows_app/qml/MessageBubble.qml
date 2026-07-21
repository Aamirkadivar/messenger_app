import QtQuick 2.15
import QtQuick.Controls 2.15

Rectangle {
    id: bubbleRoot

    property string messageText: ""
    property string messageTime: ""
    property bool isMine: true
    property string senderName: ""
    property bool isEncrypted: true
    property bool showSender: true

    // Colors
    property color bgColor: isMine ? myMessageBg : theirMessageBg
    property color textColor: isMine ? "#FFFFFF" : (darkMode ? "#E8E8E8" : "#1A1A2E")
    property color myMessageBg: "#6C63FF"
    property color theirMessageBg: "#2A2A4A"
    property color timeColor: isMine ? "rgba(255,255,255,0.6)" : "#8B8B9E"

    // Layout
    property real maxWidth: parent ? parent.width - 32 : 400

    width: Math.min(contentItem.implicitWidth + 24, maxWidth)
    height: contentColumn.height + 16
    radius: 16
    color: bgColor

    // Easing
    property easing outEasing: Easing.OutBack

    Column {
        id: contentColumn
        anchors.fill: parent
        anchors.margins: 12
        spacing: 4
        verticalAlignment: isMine ? Qt.AlignBottom : Qt.AlignTop

        // Sender name
        Text {
            id: senderText
            visible: showSender && !isMine
            text: senderName
            font.pixelSize: 11
            font.bold: true
            color: isMine ? "rgba(255,255,255,0.7)" : "#6C63FF"
            elide: Text.ElideRight
        }

        // Message content
        Text {
            id: contentItem
            anchors.right: parent.right
            text: messageText
            font.pixelSize: 14
            color: textColor
            wrapMode: Text.Wrap
            elide: Text.ElideMiddle
            style: isEncrypted ? Text.Normal : Text.Normal
            styleColor: "transparent"
        }

        // Time stamp
        Row {
            anchors.right: parent.right
            spacing: 4
            anchors.bottom: parent.bottom
            anchors.bottomMargin: -2

            Text {
                text: messageTime
                font.pixelSize: 10
                color: timeColor
            }

            // Read receipt for sent messages
            Image {
                width: 12
                height: 12
                source: isMine ? "qrc:/icons/doublecheck.svg" : ""
                visible: isMine
            }
        }
    }

    // Shadow effect
    layer.enabled: true
    layer.effect: DropShadow {
        horizontalOffset: 0
        verticalOffset: 2
        radius: 8
        samples: 16
        color: isMine ? "rgba(108, 99, 255, 0.3)" : "rgba(0, 0, 0, 0.2)"
    }

    // Hover effect
    property bool isHovered: hoverArea.containsMouse
    Rectangle {
        anchors.fill: parent
        radius: bubbleRoot.radius
        color: bubbleRoot.isHovered ? Qt.lighter(bubbleRoot.bgColor, 1.05) : "transparent"
        opacity: 0.3
        MouseArea {
            id: hoverArea
            anchors.fill: parent
            hoverEnabled: true
        }
    }
}