import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15
import QtQuick.Dialogs

Popup {
    id: panel

    property bool darkMode: true
    property color bgColor: "#050403"
    property color surfaceColor: "#0C0A08"
    property color surfaceColorHover: "#16120E"
    property color textColor: "#F0EAD6"
    property color textSecondary: "#A39A8A"
    property color borderColor: Qt.rgba(1, 1, 1, 0.08)
    property color accentColor: "#C9A961"

    property string chatId: ""
    property var groupData: ({ id: "", name: "", avatarUrl: "", description: "", ownerId: "", members: [] })

    // Emitted so main.qml can close the open chat view when this group is
    // gone (deleted, or the local user left/was removed from it).
    signal groupGone(string chatId)

    readonly property string myUserId: authService.currentUserId
    readonly property var myMember: {
        for (var i = 0; i < groupData.members.length; i++) {
            if (groupData.members[i].id === myUserId) return groupData.members[i]
        }
        return null
    }
    readonly property string myRole: myMember ? myMember.role : "member"
    readonly property bool isOwner: myRole === "owner"
    readonly property bool isAdmin: isOwner || myRole === "admin"

    property bool addingMembers: false
    property bool editingName: false
    property string errorMessage: ""
    property var pendingAction: null // { kind: "leave"|"delete"|"remove", memberId, memberName }

    // Modal so Overlay dims and blocks hover behind the panel.
    modal: true
    dim: false
    focus: true
    width: 400
    height: 600
    x: (parent ? parent.width - width : 0) / 2
    y: (parent ? parent.height - height : 0) / 2
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    padding: 0

    Overlay.modal: FrostedScrim {
        darkMode: panel.darkMode
        onDismissed: panel.close()
    }

    onOpened: {
        addingMembers = false
        editingName = false
        errorMessage = ""
        pendingAction = null
        if (chatId.length > 0) groupService.getGroupInfo(chatId)
    }

    background: GlassPanel {
        darkMode: panel.darkMode
        elevated: true
        radius: 16
    }

    FileDialog {
        id: avatarPicker
        title: "Choose a group photo"
        nameFilters: ["Images (*.png *.jpg *.jpeg *.webp)"]
        onAccepted: groupService.uploadGroupAvatar(panel.chatId, selectedFile.toString())
    }

    Connections {
        target: groupService

        function onGroupInfoFetched(group) {
            if (group.id !== panel.chatId) return
            panel.groupData = group
        }
        function onGroupUpdated(cid) {
            if (cid === panel.chatId) { panel.editingName = false; groupService.getGroupInfo(cid) }
        }
        function onMembersAdded(cid) {
            if (cid === panel.chatId) { panel.addingMembers = false; groupService.getGroupInfo(cid) }
        }
        function onMemberRemoved(cid, memberId) {
            if (cid !== panel.chatId) return
            if (memberId === panel.myUserId) {
                panel.groupGone(cid)
                panel.close()
                chatService.fetchChats()
                return
            }
            groupService.getGroupInfo(cid)
        }
        function onMemberRoleChanged(cid) {
            if (cid === panel.chatId) groupService.getGroupInfo(cid)
        }
        function onGroupDeleted(cid) {
            if (cid !== panel.chatId) return
            panel.groupGone(cid)
            panel.close()
            chatService.fetchChats()
        }
        function onGroupLeft(cid) {
            if (cid !== panel.chatId) return
            panel.groupGone(cid)
            panel.close()
            chatService.fetchChats()
        }
        function onGroupAvatarUploaded(cid, avatarUrl) {
            if (cid !== panel.chatId) return
            var g = panel.groupData
            g.avatarUrl = avatarUrl
            panel.groupData = g
            chatService.fetchChats()
        }
        function onGroupError(message) {
            panel.errorMessage = message
        }
    }

    contentItem: ColumnLayout {
        spacing: 0

        // ---- Header bar ----
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 16
            Layout.bottomMargin: 8

            Text {
                Layout.fillWidth: true
                text: panel.addingMembers ? "Add Members" : "Group Info"
                font.pixelSize: 17
                font.bold: true
                color: panel.textColor
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                visible: panel.addingMembers
                color: backFromAddMouse.containsMouse ? panel.surfaceColorHover : "transparent"
                Text { anchors.centerIn: parent; text: "‹"; font.pixelSize: 18; color: panel.textSecondary }
                MouseArea {
                    id: backFromAddMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: panel.addingMembers = false
                }
            }

            Rectangle {
                Layout.preferredWidth: 28
                Layout.preferredHeight: 28
                radius: 14
                color: closePanelMouse.containsMouse ? panel.surfaceColorHover : "transparent"
                Text { anchors.centerIn: parent; text: "✕"; font.pixelSize: 13; color: panel.textSecondary }
                MouseArea {
                    id: closePanelMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: panel.close()
                }
            }
        }

        Text {
            Layout.fillWidth: true
            Layout.leftMargin: 16
            Layout.rightMargin: 16
            Layout.bottomMargin: text.length > 0 ? 8 : 0
            text: panel.errorMessage
            font.pixelSize: 12
            color: "#E74C3C"
            wrapMode: Text.WordWrap
            visible: text.length > 0
        }

        // ---- Group details view ----
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !panel.addingMembers
            spacing: 0

            ColumnLayout {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignHCenter
                Layout.topMargin: 6
                Layout.bottomMargin: 16
                spacing: 10

                Item {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: 88
                    Layout.preferredHeight: 88

                    Avatar {
                        anchors.fill: parent
                        name: panel.groupData.name
                        avatarUrl: panel.groupData.avatarUrl || ""
                        size: 88
                    }

                    Rectangle {
                        visible: panel.isAdmin
                        anchors.bottom: parent.bottom
                        anchors.right: parent.right
                        width: 28
                        height: 28
                        radius: 14
                        color: panel.accentColor
                        border.color: panel.surfaceColor
                        border.width: 2

                        Canvas {
                            anchors.centerIn: parent
                            width: 13
                            height: 13
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = "#FFFFFF"
                                ctx.lineWidth = 1.4
                                ctx.lineCap = "round"
                                ctx.lineJoin = "round"
                                ctx.strokeRect(1, 3.5, 11, 8)
                                ctx.beginPath(); ctx.arc(6.5, 7.5, 2.4, 0, Math.PI * 2); ctx.stroke()
                                ctx.beginPath(); ctx.moveTo(4, 3.5); ctx.lineTo(5, 1.5); ctx.lineTo(8, 1.5); ctx.lineTo(9, 3.5); ctx.stroke()
                            }
                        }
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: avatarPicker.open()
                        }
                    }
                }

                RowLayout {
                    Layout.alignment: Qt.AlignHCenter
                    visible: !panel.editingName
                    spacing: 6

                    Text {
                        text: panel.groupData.name
                        font.pixelSize: 18
                        font.bold: true
                        color: panel.textColor
                    }

                    Text {
                        visible: panel.isAdmin
                        text: "✎"
                        font.pixelSize: 14
                        color: panel.textSecondary
                        MouseArea {
                            anchors.fill: parent
                            anchors.margins: -6
                            cursorShape: Qt.PointingHandCursor
                            onClicked: { nameEditField.text = panel.groupData.name; panel.editingName = true }
                        }
                    }
                }

                RowLayout {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.fillWidth: true
                    Layout.leftMargin: 30
                    Layout.rightMargin: 30
                    visible: panel.editingName
                    spacing: 8

                    Rectangle {
                        Layout.fillWidth: true
                        height: 36
                        radius: 9
                        color: panel.bgColor
                        border.color: panel.accentColor
                        border.width: 1

                        TextField {
                            id: nameEditField
                            anchors.fill: parent
                            anchors.leftMargin: 10
                            anchors.rightMargin: 10
                            verticalAlignment: TextInput.AlignVCenter
                            background: Item {}
                            font.pixelSize: 13
                            color: panel.textColor
                            selectByMouse: true
                        }
                    }

                    Text {
                        text: "Save"
                        font.pixelSize: 13
                        font.weight: Font.DemiBold
                        color: panel.accentColor
                        MouseArea {
                            anchors.fill: parent
                            anchors.margins: -6
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                if (nameEditField.text.trim().length === 0) return
                                groupService.updateGroup(panel.chatId, nameEditField.text.trim(), panel.groupData.description || "")
                            }
                        }
                    }
                    Text {
                        text: "Cancel"
                        font.pixelSize: 13
                        color: panel.textSecondary
                        MouseArea {
                            anchors.fill: parent
                            anchors.margins: -6
                            cursorShape: Qt.PointingHandCursor
                            onClicked: panel.editingName = false
                        }
                    }
                }

                Text {
                    Layout.alignment: Qt.AlignHCenter
                    text: (panel.groupData.memberCount || panel.groupData.members.length) + " members"
                    font.pixelSize: 12
                    color: panel.textSecondary
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: panel.borderColor }

            // Add members entry
            Rectangle {
                Layout.fillWidth: true
                visible: panel.isAdmin
                height: 52
                color: addMembersMouse.containsMouse ? panel.surfaceColorHover : "transparent"

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    anchors.rightMargin: 16
                    spacing: 12

                    Rectangle {
                        Layout.preferredWidth: 34
                        Layout.preferredHeight: 34
                        radius: 17
                        color: Qt.rgba(panel.accentColor.r, panel.accentColor.g, panel.accentColor.b, 0.16)
                        Text { anchors.centerIn: parent; text: "+"; font.pixelSize: 18; color: panel.accentColor }
                    }
                    Text {
                        text: "Add members"
                        font.pixelSize: 14
                        color: panel.accentColor
                    }
                    Item { Layout.fillWidth: true }
                }

                MouseArea {
                    id: addMembersMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: { panel.addingMembers = true; addSearchModel.clear(); addSearchField.text = "" }
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
                        model: panel.groupData.members
                        Rectangle {
                            width: parent.width
                            height: 60
                            color: memberMouse.containsMouse ? panel.surfaceColorHover : "transparent"

                            MouseArea {
                                id: memberMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                acceptedButtons: Qt.NoButton
                            }

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 16
                                anchors.rightMargin: 12
                                spacing: 12

                                Avatar {
                                    Layout.preferredWidth: 38
                                    Layout.preferredHeight: 38
                                    name: modelData.bestName
                                    avatarUrl: modelData.avatarUrl || ""
                                    size: 38
                                }

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2

                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData.bestName + (modelData.id === panel.myUserId ? " (you)" : "")
                                        font.pixelSize: 14
                                        font.weight: Font.DemiBold
                                        color: panel.textColor
                                        elide: Text.ElideRight
                                    }

                                    Text {
                                        text: modelData.role === "owner" ? "Owner" : (modelData.role === "admin" ? "Admin" : "Member")
                                        font.pixelSize: 12
                                        color: panel.textSecondary
                                    }
                                }

                                Rectangle {
                                    Layout.preferredWidth: 30
                                    Layout.preferredHeight: 30
                                    radius: 8
                                    visible: panel.isAdmin && modelData.id !== panel.myUserId && modelData.role !== "owner"
                                    color: kebabMouse.containsMouse ? panel.surfaceColorHover : "transparent"

                                    Canvas {
                                        anchors.centerIn: parent
                                        width: 4
                                        height: 16
                                        onPaint: {
                                            var ctx = getContext("2d")
                                            ctx.reset()
                                            ctx.fillStyle = panel.textSecondary
                                            for (var i = 0; i < 3; i++) {
                                                ctx.beginPath(); ctx.arc(2, 2 + i * 6, 1.7, 0, Math.PI * 2); ctx.fill()
                                            }
                                        }
                                    }

                                    MouseArea {
                                        id: kebabMouse
                                        anchors.fill: parent
                                        hoverEnabled: true
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: memberMenu.popup()
                                    }

                                    Menu {
                                        id: memberMenu
                                        MenuItem {
                                            text: modelData.role === "admin" ? "Remove as admin" : "Make admin"
                                            onTriggered: groupService.setMemberRole(panel.chatId, modelData.id,
                                                                                     modelData.role === "admin" ? "member" : "admin")
                                        }
                                        MenuItem {
                                            text: "Remove from group"
                                            onTriggered: panel.pendingAction = { kind: "remove", memberId: modelData.id, memberName: modelData.bestName }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; height: 1; color: panel.borderColor }

            Rectangle {
                Layout.fillWidth: true
                Layout.margins: 16
                height: 42
                radius: 11
                color: leaveMouse.containsMouse ? Qt.rgba(231/255, 76/255, 60/255, 0.15) : "transparent"
                border.color: "#E74C3C"
                border.width: 1

                Text {
                    anchors.centerIn: parent
                    text: panel.isOwner ? "Delete Group" : "Leave Group"
                    font.pixelSize: 13
                    font.weight: Font.DemiBold
                    color: "#E74C3C"
                }

                MouseArea {
                    id: leaveMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: panel.pendingAction = panel.isOwner ? { kind: "delete" } : { kind: "leave" }
                }
            }
        }

        // ---- Add members view ----
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: panel.addingMembers
            spacing: 0

            Rectangle {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                Layout.bottomMargin: 10
                height: 40
                radius: 10
                color: panel.bgColor
                border.color: addSearchField.activeFocus ? panel.accentColor : "transparent"
                border.width: 1

                TextField {
                    id: addSearchField
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    verticalAlignment: TextInput.AlignVCenter
                    placeholderText: "Search by name, username or email…"
                    placeholderTextColor: panel.textSecondary
                    color: panel.textColor
                    font.pixelSize: 14
                    background: Item {}
                    onTextChanged: addSearchDebounce.restart()
                }

                Timer {
                    id: addSearchDebounce
                    interval: 300
                    onTriggered: {
                        var q = addSearchField.text.trim()
                        if (q.length === 0) { addSearchModel.clear(); return }
                        chatService.searchUsers(q)
                    }
                }
            }

            Connections {
                target: chatService
                function onUsersFound(users) {
                    if (!panel.addingMembers) return
                    addSearchModel.clear()
                    for (var i = 0; i < users.length; i++) {
                        var alreadyMember = false
                        for (var j = 0; j < panel.groupData.members.length; j++) {
                            if (panel.groupData.members[j].id === users[i].id) { alreadyMember = true; break }
                        }
                        if (!alreadyMember) addSearchModel.append(users[i])
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
                        model: ListModel { id: addSearchModel }
                        Rectangle {
                            width: parent.width
                            height: 58
                            color: addResultMouse.containsMouse ? panel.surfaceColorHover : "transparent"

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 16
                                anchors.rightMargin: 16
                                spacing: 12

                                Avatar {
                                    Layout.preferredWidth: 36
                                    Layout.preferredHeight: 36
                                    name: model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                    avatarUrl: model.avatarUrl || ""
                                    size: 36
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: model.displayName && model.displayName.length > 0 ? model.displayName : model.username
                                    font.pixelSize: 14
                                    color: panel.textColor
                                    elide: Text.ElideRight
                                }
                            }

                            MouseArea {
                                id: addResultMouse
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    groupService.addMembers(panel.chatId, [model.id])
                                    // MLS-invite too (no-op when this client
                                    // doesn't hold the group's MLS state).
                                    chatService.mlsAddMembers(panel.chatId, [model.id])
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Confirmation popup for leave / delete / remove-member ----
    Popup {
        id: confirmPopup
        parent: panel.parent
        modal: true
        focus: true
        visible: panel.pendingAction !== null
        width: 320
        x: (parent ? parent.width - width : 0) / 2
        y: (parent ? parent.height - height : 0) / 2
        padding: 0
        closePolicy: Popup.CloseOnEscape

        background: GlassPanel {
            darkMode: panel.darkMode
            radius: 14
        }

        contentItem: ColumnLayout {
            spacing: 0

            Text {
                Layout.fillWidth: true
                Layout.margins: 20
                Layout.bottomMargin: 8
                text: {
                    if (!panel.pendingAction) return ""
                    if (panel.pendingAction.kind === "delete") return "Delete this group?"
                    if (panel.pendingAction.kind === "leave") return "Leave this group?"
                    return "Remove " + panel.pendingAction.memberName + "?"
                }
                font.pixelSize: 16
                font.bold: true
                color: panel.textColor
                wrapMode: Text.WordWrap
            }

            Text {
                Layout.fillWidth: true
                Layout.leftMargin: 20
                Layout.rightMargin: 20
                text: {
                    if (!panel.pendingAction) return ""
                    if (panel.pendingAction.kind === "delete") return "This removes the group for everyone and can't be undone."
                    if (panel.pendingAction.kind === "leave") return "You'll need to be re-invited to rejoin."
                    return "They'll be removed from the group immediately."
                }
                font.pixelSize: 12
                color: panel.textSecondary
                wrapMode: Text.WordWrap
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 20
                Layout.topMargin: 18
                spacing: 10

                Item { Layout.fillWidth: true }

                Rectangle {
                    Layout.preferredWidth: 88
                    Layout.preferredHeight: 38
                    radius: 10
                    color: cancelConfirmMouse.containsMouse ? panel.surfaceColorHover : "transparent"
                    border.color: panel.borderColor
                    border.width: 1
                    Text { anchors.centerIn: parent; text: "Cancel"; font.pixelSize: 13; color: panel.textColor }
                    MouseArea {
                        id: cancelConfirmMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: panel.pendingAction = null
                    }
                }

                Rectangle {
                    Layout.preferredWidth: 88
                    Layout.preferredHeight: 38
                    radius: 10
                    color: confirmActionMouse.containsMouse ? Qt.lighter("#E74C3C", 1.08) : "#E74C3C"
                    Text { anchors.centerIn: parent; text: "Confirm"; font.pixelSize: 13; font.weight: Font.DemiBold; color: "#FFFFFF" }
                    MouseArea {
                        id: confirmActionMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            var action = panel.pendingAction
                            panel.pendingAction = null
                            if (!action) return
                            if (action.kind === "delete") groupService.deleteGroup(panel.chatId)
                            else if (action.kind === "leave") groupService.leaveGroup(panel.chatId)
                            else if (action.kind === "remove") groupService.removeMember(panel.chatId, action.memberId)
                        }
                    }
                }
            }
        }
    }
}
