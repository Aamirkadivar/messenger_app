import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Popup {
    id: newGroupDialog

    property bool darkMode: true
    property color bgColor: "#15152B"
    property color surfaceColor: "#1B1B36"
    property color surfaceColorHover: "#22224A"
    property color textColor: "#EDEDF2"
    property color textSecondary: "#9494AC"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#6C63FF"
    property color onlineColor: "#4CAF50"

    // Emitted once the server confirms creation, so the caller can jump
    // straight into the new group's chat.
    signal groupCreated(string chatId, string chatName, string avatarUrl)

    property var selectedMembers: [] // [{id, name}]

    modal: true
    focus: true
    width: 440
    height: 580
    x: (parent ? parent.width - width : 0) / 2
    y: (parent ? parent.height - height : 0) / 2
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    onOpened: {
        nameField.text = ""
        searchField.text = ""
        selectedMembers = []
        resultsModel.clear()
        emptyState.text = "Start typing to search for people"
        creating = false
    }

    property bool creating: false

    background: Rectangle {
        color: newGroupDialog.surfaceColor
        radius: 12
        border.color: newGroupDialog.borderColor
        border.width: 1
    }

    Connections {
        target: chatService
        function onUsersFound(users) {
            resultsModel.clear()
            for (var i = 0; i < users.length; i++) {
                // Skip anyone already picked
                var already = false
                for (var j = 0; j < newGroupDialog.selectedMembers.length; j++) {
                    if (newGroupDialog.selectedMembers[j].id === users[i].id) { already = true; break }
                }
                if (!already) resultsModel.append(users[i])
            }
            searchSpinner.running = false
            emptyState.text = resultsModel.count === 0 ? "No users found" : ""
        }
        function onSearchError(error) {
            searchSpinner.running = false
            emptyState.text = error
        }
    }

    Connections {
        target: groupService
        function onGroupCreated(group) {
            newGroupDialog.creating = false
            newGroupDialog.close()
            newGroupDialog.groupCreated(group.id, group.name, group.avatarUrl)
            chatService.fetchChats()
        }
        function onGroupError(message) {
            newGroupDialog.creating = false
            errorText.text = message
        }
    }

    function addMember(id, name) {
        var arr = selectedMembers.slice()
        arr.push({ id: id, name: name })
        selectedMembers = arr
        for (var i = 0; i < resultsModel.count; i++) {
            if (resultsModel.get(i).id === id) { resultsModel.remove(i); break }
        }
    }

    function removeMember(id) {
        selectedMembers = selectedMembers.filter(function(m) { return m.id !== id })
    }

    contentItem: ColumnLayout {
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 16
            Layout.bottomMargin: 12

            Text {
                Layout.fillWidth: true
                text: "New Group"
                font.pixelSize: 18
                font.bold: true
                color: newGroupDialog.textColor
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                color: closeMouse.containsMouse ? newGroupDialog.surfaceColorHover : "transparent"

                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 13
                    color: newGroupDialog.textSecondary
                }

                MouseArea {
                    id: closeMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: newGroupDialog.close()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: 10
            height: 40
            radius: 10
            color: newGroupDialog.bgColor
            border.color: nameField.activeFocus ? newGroupDialog.accentColor : "transparent"
            border.width: 1

            TextField {
                id: nameField
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                verticalAlignment: TextInput.AlignVCenter
                placeholderText: "Group name"
                placeholderTextColor: newGroupDialog.textSecondary
                color: newGroupDialog.textColor
                font.pixelSize: 14
                background: Item {}
            }
        }

        // Selected member chips
        Flow {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: newGroupDialog.selectedMembers.length > 0 ? 10 : 0
            spacing: 6

            Repeater {
                model: newGroupDialog.selectedMembers
                Rectangle {
                    height: 28
                    width: chipText.implicitWidth + 30
                    radius: 14
                    color: Qt.rgba(108/255, 99/255, 255/255, 0.16)

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 6
                        spacing: 4

                        Text {
                            id: chipText
                            text: modelData.name
                            font.pixelSize: 12
                            color: newGroupDialog.textColor
                        }

                        Text {
                            text: "✕"
                            font.pixelSize: 10
                            color: newGroupDialog.textSecondary
                            MouseArea {
                                anchors.fill: parent
                                anchors.margins: -4
                                cursorShape: Qt.PointingHandCursor
                                onClicked: newGroupDialog.removeMember(modelData.id)
                            }
                        }
                    }
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
            color: newGroupDialog.bgColor
            border.color: searchField.activeFocus ? newGroupDialog.accentColor : "transparent"
            border.width: 1

            TextField {
                id: searchField
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                verticalAlignment: TextInput.AlignVCenter
                placeholderText: "Add people…"
                placeholderTextColor: newGroupDialog.textSecondary
                color: newGroupDialog.textColor
                font.pixelSize: 14
                background: Item {}
                onTextChanged: searchDebounce.restart()
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
                        height: 60
                        color: resultMouse.pressed ? Qt.rgba(108/255, 99/255, 255/255, 0.14)
                               : (resultMouse.containsMouse ? newGroupDialog.surfaceColorHover : "transparent")

                        MouseArea {
                            id: resultMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                var name = model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                newGroupDialog.addMember(model.id, name)
                            }
                        }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 16
                            anchors.rightMargin: 16
                            spacing: 12

                            Avatar {
                                Layout.preferredWidth: 38
                                Layout.preferredHeight: 38
                                name: model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                avatarUrl: model.avatarUrl || ""
                                size: 38
                            }

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    Layout.fillWidth: true
                                    text: model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                    font.pixelSize: 14
                                    font.bold: true
                                    color: newGroupDialog.textColor
                                    elide: Text.ElideRight
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: "@" + model.username
                                    font.pixelSize: 12
                                    color: newGroupDialog.textSecondary
                                    elide: Text.ElideRight
                                }
                            }
                        }
                    }
                }

                Item {
                    width: parent.width
                    height: (resultsModel.count === 0) ? 120 : 0
                    visible: resultsModel.count === 0

                    Column {
                        id: emptyState
                        property alias text: emptyText.text
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.top: parent.top
                        anchors.topMargin: 20
                        spacing: 10

                        BusyIndicator {
                            id: searchSpinner
                            anchors.horizontalCenter: parent.horizontalCenter
                            running: false
                            visible: running
                            width: 26
                            height: 26
                        }

                        Text {
                            id: emptyText
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: "Start typing to search for people"
                            font.pixelSize: 13
                            color: newGroupDialog.textSecondary
                            visible: !searchSpinner.running
                        }
                    }
                }
            }
        }

        Text {
            id: errorText
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.topMargin: text.length > 0 ? 6 : 0
            text: ""
            font.pixelSize: 12
            color: "#E74C3C"
            wrapMode: Text.WordWrap
            visible: text.length > 0
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.margins: 16
            Layout.topMargin: 10
            height: 44
            radius: 12
            property bool canCreate: nameField.text.trim().length > 0 && newGroupDialog.selectedMembers.length > 0 && !newGroupDialog.creating
            color: !canCreate ? (newGroupDialog.darkMode ? "#2A2A4A" : "#E0E0E5")
                   : createMouse.pressed ? Qt.darker(newGroupDialog.accentColor, 1.15) : (createMouse.containsMouse ? Qt.lighter(newGroupDialog.accentColor, 1.08) : newGroupDialog.accentColor)

            RowLayout {
                anchors.centerIn: parent
                spacing: 8

                BusyIndicator {
                    running: newGroupDialog.creating
                    visible: newGroupDialog.creating
                    width: 18
                    height: 18
                }

                Text {
                    text: newGroupDialog.creating ? "Creating…" : "Create Group"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                    color: "#FFFFFF"
                }
            }

            MouseArea {
                id: createMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: parent.canCreate ? Qt.PointingHandCursor : Qt.ArrowCursor
                onClicked: {
                    if (!parent.canCreate) return
                    newGroupDialog.creating = true
                    errorText.text = ""
                    var ids = newGroupDialog.selectedMembers.map(function(m) { return m.id })
                    groupService.createGroup(nameField.text.trim(), "", ids)
                }
            }
        }
    }
}
