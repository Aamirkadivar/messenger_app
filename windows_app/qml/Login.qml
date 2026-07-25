import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Item {
    id: loginPage
    anchors.fill: parent

    property bool isLoading: false

    // Background
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: darkMode ? "#0F0F23" : "#667eea" }
            GradientStop { position: 1.0; color: darkMode ? "#16213E" : "#764ba2" }
        }
    }

    // Ambient background circles
    Repeater {
        model: 4
        Rectangle {
            width: 200 + Math.random() * 300
            height: width
            radius: width / 2
            color: Qt.rgba(108/255, 99/255, 255/255, 0.05 + Math.random() * 0.04)
            x: Math.random() * parent.width
            y: Math.random() * parent.height

            NumberAnimation {
                target: parent
                property: "y"
                to: y + (Math.random() - 0.5) * 50
                duration: 5000 + Math.random() * 5000
                running: true
                loops: Animation.Infinite
                easing.type: Easing.InOutSine
            }
        }
    }

    // Login card
    Rectangle {
        id: loginCard
        anchors.centerIn: parent
        width: Math.min(420, parent.width * 0.9)
        height: formLayout.y + formLayout.implicitHeight + 40
        color: darkMode ? "rgba(22, 33, 62, 0.95)" : "rgba(255, 255, 255, 0.95)"
        radius: 24
        border.color: darkMode ? "rgba(255, 255, 255, 0.06)" : "rgba(0, 0, 0, 0.04)"
        border.width: 1
        opacity: 0
        scale: 0.94

        NumberAnimation on opacity { running: true; duration: 350; to: 1 }
        NumberAnimation on scale { running: true; duration: 350; to: 1; easing.type: Easing.OutCubic }

        // Header icon
        Item {
            id: iconArea
            anchors.top: parent.top
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.topMargin: 36
            width: 64
            height: 64

            Rectangle {
                anchors.fill: parent
                radius: 18
                color: "#6C63FF"

                SequentialAnimation on opacity {
                    running: true
                    loops: Animation.Infinite
                    NumberAnimation { duration: 1600; from: 1; to: 0.85; easing.type: Easing.InOutSine }
                    NumberAnimation { duration: 1600; from: 0.85; to: 1; easing.type: Easing.InOutSine }
                }
            }

            // Chat bubble glyph
            Canvas {
                anchors.centerIn: parent
                width: 30
                height: 30
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    var w = width, h = height * 0.72, r = 6
                    ctx.fillStyle = "#FFFFFF"
                    ctx.beginPath()
                    ctx.moveTo(r, 0)
                    ctx.lineTo(w - r, 0)
                    ctx.arcTo(w, 0, w, r, r)
                    ctx.lineTo(w, h - r)
                    ctx.arcTo(w, h, w - r, h, r)
                    ctx.lineTo(w * 0.32, h)
                    ctx.lineTo(w * 0.18, h + height * 0.2)
                    ctx.lineTo(w * 0.22, h)
                    ctx.lineTo(r, h)
                    ctx.arcTo(0, h, 0, h - r, r)
                    ctx.lineTo(0, r)
                    ctx.arcTo(0, 0, r, 0, r)
                    ctx.closePath()
                    ctx.fill()

                    ctx.fillStyle = "#6C63FF"
                    var dotY = h / 2
                    var xs = [w * 0.3, w * 0.5, w * 0.7]
                    for (var i = 0; i < xs.length; i++) {
                        ctx.beginPath()
                        ctx.arc(xs[i], dotY, 1.8, 0, Math.PI * 2)
                        ctx.fill()
                    }
                }
            }
        }

        Text {
            id: titleArea
            anchors.top: iconArea.bottom
            anchors.topMargin: 18
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Welcome Back"
            font.pixelSize: 24
            font.bold: true
            color: darkMode ? "#F2F2F5" : "#1A1A2E"
        }

        Text {
            id: subtitleArea
            anchors.top: titleArea.bottom
            anchors.topMargin: 6
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Sign in to continue to Messenger"
            font.pixelSize: 13
            color: darkMode ? "#9494AC" : "#6B6B7B"
        }

        // Form
        ColumnLayout {
            id: formLayout
            anchors.top: subtitleArea.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.topMargin: 28
            anchors.leftMargin: 32
            anchors.rightMargin: 32
            spacing: 16

            // Email
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "Email"
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    color: darkMode ? "#B8B8CC" : "#4A4A5A"
                }

                Rectangle {
                    Layout.fillWidth: true
                    height: 46
                    radius: 12
                    color: darkMode ? "rgba(42, 42, 74, 0.5)" : "rgba(240, 240, 245, 1)"
                    border.color: emailField.activeFocus ? "#6C63FF" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
                    border.width: emailField.activeFocus ? 2 : 1
                    Behavior on border.color { ColorAnimation { duration: 150 } }

                    TextField {
                        id: emailField
                        anchors.fill: parent
                        leftPadding: 14
                        rightPadding: 14
                        verticalAlignment: TextInput.AlignVCenter
                        background: Item {}
                        placeholderText: "you@example.com"
                        placeholderTextColor: darkMode ? "#5A5A7A" : "#9E9E9E"
                        font.pixelSize: 14
                        color: darkMode ? "#E8E8E8" : "#1A1A2E"
                        selectByMouse: true
                        onTextChanged: emailError.text = ""
                        Keys.onReturnPressed: passwordField.forceActiveFocus()
                    }
                }

                Text {
                    id: emailError
                    Layout.fillWidth: true
                    text: ""
                    color: "#FF6B6B"
                    font.pixelSize: 12
                    visible: text !== ""
                }
            }

            // Password
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "Password"
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    color: darkMode ? "#B8B8CC" : "#4A4A5A"
                }

                Rectangle {
                    id: passwordBg
                    Layout.fillWidth: true
                    height: 46
                    radius: 12
                    color: darkMode ? "rgba(42, 42, 74, 0.5)" : "rgba(240, 240, 245, 1)"
                    border.color: passwordField.activeFocus ? "#6C63FF" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
                    border.width: passwordField.activeFocus ? 2 : 1
                    Behavior on border.color { ColorAnimation { duration: 150 } }

                    property bool passwordVisible: false

                    TextField {
                        id: passwordField
                        anchors.fill: parent
                        anchors.rightMargin: 40
                        leftPadding: 14
                        verticalAlignment: TextInput.AlignVCenter
                        background: Item {}
                        placeholderText: "Enter your password"
                        placeholderTextColor: darkMode ? "#5A5A7A" : "#9E9E9E"
                        font.pixelSize: 14
                        echoMode: passwordBg.passwordVisible ? TextInput.Normal : TextInput.Password
                        color: darkMode ? "#E8E8E8" : "#1A1A2E"
                        passwordCharacter: "•"
                        selectByMouse: true
                        onTextChanged: passwordError.text = ""
                        Keys.onReturnPressed: loginButton.clicked()
                    }

                    MouseArea {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.rightMargin: 12
                        width: 24
                        height: 24
                        cursorShape: Qt.PointingHandCursor
                        onClicked: passwordBg.passwordVisible = !passwordBg.passwordVisible

                        Canvas {
                            anchors.centerIn: parent
                            width: 20
                            height: 20
                            visible: !passwordBg.passwordVisible
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = darkMode ? "#8B8B9E" : "#6B6B7B"
                                ctx.lineWidth = 1.6
                                ctx.beginPath()
                                ctx.moveTo(2, 10)
                                ctx.quadraticCurveTo(10, 2, 18, 10)
                                ctx.quadraticCurveTo(10, 18, 2, 10)
                                ctx.stroke()
                                ctx.beginPath()
                                ctx.arc(10, 10, 3, 0, Math.PI * 2)
                                ctx.stroke()
                            }
                        }
                        Canvas {
                            anchors.centerIn: parent
                            width: 20
                            height: 20
                            visible: passwordBg.passwordVisible
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = "#6C63FF"
                                ctx.lineWidth = 1.6
                                ctx.beginPath()
                                ctx.moveTo(2, 10)
                                ctx.quadraticCurveTo(10, 2, 18, 10)
                                ctx.quadraticCurveTo(10, 18, 2, 10)
                                ctx.stroke()
                                ctx.beginPath()
                                ctx.arc(10, 10, 3, 0, Math.PI * 2)
                                ctx.stroke()
                                ctx.beginPath()
                                ctx.moveTo(3, 17)
                                ctx.lineTo(17, 3)
                                ctx.stroke()
                            }
                        }
                    }
                }

                Text {
                    id: passwordError
                    Layout.fillWidth: true
                    text: ""
                    color: "#FF6B6B"
                    font.pixelSize: 12
                    visible: text !== ""
                }
            }

            // Remember me & Forgot password
            RowLayout {
                Layout.fillWidth: true
                Layout.topMargin: 2

                CheckBox {
                    id: rememberCheck
                    font.pixelSize: 13
                    indicator: Rectangle {
                        x: 0
                        y: parent.height / 2 - width / 2
                        width: 18
                        height: 18
                        radius: 5
                        border.color: rememberCheck.checked ? "#6C63FF" : (darkMode ? "#5A5A7A" : "#9E9E9E")
                        border.width: 1.5
                        color: rememberCheck.checked ? "#6C63FF" : "transparent"
                        Behavior on color { ColorAnimation { duration: 120 } }

                        Canvas {
                            anchors.centerIn: parent
                            width: 12
                            height: 12
                            visible: rememberCheck.checked
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = "#FFFFFF"
                                ctx.lineWidth = 2
                                ctx.lineCap = "round"
                                ctx.lineJoin = "round"
                                ctx.beginPath()
                                ctx.moveTo(2, 6)
                                ctx.lineTo(5, 9)
                                ctx.lineTo(10, 3)
                                ctx.stroke()
                            }
                        }
                    }
                    text: "Remember me"
                    contentItem: Text {
                        text: rememberCheck.text
                        color: darkMode ? "#9494AC" : "#6B6B7B"
                        font: rememberCheck.font
                        leftPadding: rememberCheck.indicator.width + 8
                        verticalAlignment: Text.AlignVCenter
                    }
                }

                Item { Layout.fillWidth: true }

                Text {
                    text: "Forgot password?"
                    font.pixelSize: 13
                    color: forgotArea.containsMouse ? "#8B84FF" : "#6C63FF"
                    Behavior on color { ColorAnimation { duration: 120 } }

                    MouseArea {
                        id: forgotArea
                        anchors.fill: parent
                        anchors.margins: -6
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            // Handle forgot password
                        }
                    }
                }
            }

            // Sign in button
            Button {
                id: loginButton
                Layout.fillWidth: true
                Layout.topMargin: 6
                height: 48
                text: isLoading ? "Signing in…" : "Sign In"
                font.pixelSize: 15
                font.bold: true
                enabled: !isLoading && emailField.text.length > 0 && passwordField.text.length > 0
                contentItem: Text {
                    text: loginButton.text
                    font: loginButton.font
                    color: "#FFFFFF"
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    radius: 13
                    color: !loginButton.enabled ? (darkMode ? "#2A2A4A" : "#E0E0E5")
                           : loginMouse.pressed ? "#5B52E5" : (loginMouse.containsMouse ? "#7A72FF" : "#6C63FF")
                    Behavior on color { ColorAnimation { duration: 120 } }

                    MouseArea {
                        id: loginMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: loginButton.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: loginButton.clicked()
                    }
                }
                onClicked: {
                    isLoading = true
                    authService.login(emailField.text, passwordField.text)
                }
            }

            BusyIndicator {
                Layout.alignment: Qt.AlignHCenter
                running: isLoading
                visible: isLoading
                width: 26
                height: 26
            }

            // Divider
            RowLayout {
                Layout.fillWidth: true
                Layout.topMargin: 4
                spacing: 12

                Rectangle {
                    Layout.fillWidth: true
                    height: 1
                    color: darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)"
                }
                Text {
                    text: "or"
                    color: darkMode ? "#5A5A7A" : "#9E9E9E"
                    font.pixelSize: 12
                }
                Rectangle {
                    Layout.fillWidth: true
                    height: 1
                    color: darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)"
                }
            }

            // Sign up
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                Layout.bottomMargin: 4
                spacing: 4

                Text {
                    text: "Don't have an account?"
                    color: darkMode ? "#9494AC" : "#6B6B7B"
                    font.pixelSize: 13
                }

                Text {
                    text: "Sign Up"
                    font.pixelSize: 13
                    font.bold: true
                    color: signUpArea.containsMouse ? "#8B84FF" : "#6C63FF"
                    Behavior on color { ColorAnimation { duration: 120 } }

                    MouseArea {
                        id: signUpArea
                        anchors.fill: parent
                        anchors.margins: -6
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            loginCard.visible = false
                            registerPage.visible = true
                        }
                    }
                }
            }
        }
    }

    // Register page (hidden by default, and not instantiated at all until shown
    // - otherwise this and Register.qml's own back-reference Loader recurse forever)
    Loader {
        id: registerPage
        anchors.fill: parent
        visible: false
        active: visible
        source: "qrc:/qml/Register.qml"
    }

    Component.onCompleted: {
        authService.loginFailed.connect(function(message) {
            isLoading = false
            passwordError.text = message
        })
        authService.loginSuccess.connect(function(userId, username) {
            isLoading = false
        })
    }
}
