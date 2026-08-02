import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Popup {
    id: newChatDialog

    property bool darkMode: true
    property color bgColor: "#15152B"
    property color surfaceColor: "#1B1B36"
    property color surfaceColorHover: "#22224A"
    property color textColor: "#EDEDF2"
    property color textSecondary: "#9494AC"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#6C63FF"
    property color onlineColor: "#4CAF50"

    signal userSelected(string userId, string userName)

    modal: true
    focus: true
    width: 420
    height: 520
    x: (parent ? parent.width - width : 0) / 2
    y: (parent ? parent.height - height : 0) / 2
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    onOpened: {
        searchField.text = ""
        searchField.forceActiveFocus()
        resultsModel.clear()
        emptyState.text = "Start typing to search for people"
    }

    background: GlassPanel {
        darkMode: newChatDialog.darkMode
        radius: 12
    }

    Connections {
        target: chatService

        function onUsersFound(users) {
            resultsModel.clear()
            for (var i = 0; i < users.length; i++) {
                resultsModel.append(users[i])
            }
            searchSpinner.running = false
            emptyState.text = resultsModel.count === 0 ? "No users found" : ""
        }

        function onSearchError(error) {
            searchSpinner.running = false
            emptyState.text = error
        }
    }

    contentItem: ColumnLayout {
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 16
            Layout.bottomMargin: 12

            Text {
                Layout.fillWidth: true
                text: "New Chat"
                font.pixelSize: 18
                font.bold: true
                color: newChatDialog.textColor
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                color: closeMouse.containsMouse ? newChatDialog.surfaceColorHover : "transparent"

                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 13
                    color: newChatDialog.textSecondary
                }

                MouseArea {
                    id: closeMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: newChatDialog.close()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 12
            height: 40
            radius: 10
            color: newChatDialog.bgColor
            border.color: searchField.activeFocus ? newChatDialog.accentColor : "transparent"
            border.width: 1

            TextField {
                id: searchField
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                verticalAlignment: TextInput.AlignVCenter
                placeholderText: "Search by name, username or email..."
                placeholderTextColor: newChatDialog.textSecondary
                color: newChatDialog.textColor
                font.pixelSize: 14
                background: Item {}

                onTextChanged: {
                    searchDebounce.restart()
                }
            }

            Timer {
                id: searchDebounce
                interval: 300
                onTriggered: {
                    var q = searchField.text.trim()
                    if (q.length === 0) {
                        resultsModel.clear()
                        emptyState.text = "Start typing to search for people"
                        searchSpinner.running = false
                        return
                    }
                    searchSpinner.running = true
                    chatService.searchUsers(q)
                }
            }
        }

        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            Column {
                width: parent.width

                Repeater {
                    id: resultsRepeater
                    model: ListModel { id: resultsModel }

                    Rectangle {
                        width: parent.width
                        height: 64
                        color: resultMouse.pressed ? Qt.rgba(newChatDialog.accentColor.r, newChatDialog.accentColor.g, newChatDialog.accentColor.b, 0.14)
                               : (resultMouse.containsMouse ? newChatDialog.surfaceColorHover : "transparent")

                        MouseArea {
                            id: resultMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                var name = model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                newChatDialog.userSelected(model.id, name)
                                chatService.startDirectChat(model.id, name)
                                newChatDialog.close()
                            }
                        }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 16
                            anchors.rightMargin: 16
                            spacing: 12

                            Item {
                                Layout.preferredWidth: 40
                                Layout.preferredHeight: 40

                                Rectangle {
                                    anchors.fill: parent
                                    radius: 20
                                    color: newChatDialog.accentColor

                                    Text {
                                        anchors.centerIn: parent
                                        text: {
                                            var n = model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                            return n && n.length > 0 ? n.substring(0, 1).toUpperCase() : "?"
                                        }
                                        font.pixelSize: 16
                                        font.bold: true
                                        color: "#FFFFFF"
                                    }
                                }

                                Rectangle {
                                    anchors.bottom: parent.bottom
                                    anchors.right: parent.right
                                    width: 11
                                    height: 11
                                    radius: 5.5
                                    color: newChatDialog.onlineColor
                                    border.color: newChatDialog.surfaceColor
                                    border.width: 2
                                    visible: model.isOnline
                                }
                            }

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    Layout.fillWidth: true
                                    text: model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                    font.pixelSize: 14
                                    font.bold: true
                                    color: newChatDialog.textColor
                                    elide: Text.ElideRight
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: "@" + model.username
                                    font.pixelSize: 12
                                    color: newChatDialog.textSecondary
                                    elide: Text.ElideRight
                                }
                            }
                        }
                    }
                }

                Item {
                    id: emptyStateContainer
                    width: parent.width
                    height: (resultsModel.count === 0) ? 140 : 0
                    visible: resultsModel.count === 0

                    Column {
                        id: emptyState
                        property alias text: emptyText.text
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.top: parent.top
                        anchors.topMargin: 24
                        spacing: 10

                        BusyIndicator {
                            id: searchSpinner
                            anchors.horizontalCenter: parent.horizontalCenter
                            running: false
                            visible: running
                            width: 28
                            height: 28
                        }

                        Text {
                            id: emptyText
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: "Start typing to search for people"
                            font.pixelSize: 13
                            color: newChatDialog.textSecondary
                            visible: !searchSpinner.running
                        }
                    }
                }
            }
        }
    }
}
