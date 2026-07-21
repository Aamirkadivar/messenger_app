import QtQuick 2.15
import QtQuick.Controls 2.15
import QtQuick.Layouts 1.15

Item {
    id: registerPage
    anchors.fill: parent

    property bool isLoading: false
    property int passwordStrengthLevel: 0
    readonly property var strengthColors: ["#FF6B6B", "#FFA726", "#FFEB3B", "#4CAF50"]
    readonly property var strengthLabels: ["Weak", "Fair", "Good", "Strong"]

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
            color: Qt.rgba(76/255, 175/255, 80/255, 0.045 + Math.random() * 0.04)
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

    // Register card
    Rectangle {
        id: registerCard
        anchors.centerIn: parent
        width: Math.min(420, parent.width * 0.9)
        height: formLayout.y + formLayout.implicitHeight + 40
        color: darkMode ? "rgba(22, 33, 62, 0.95)" : "rgba(255, 255, 255, 0.95)"
        radius: 24
        border.color: darkMode ? "rgba(255, 255, 255, 0.06)" : "rgba(0, 0, 0, 0.04)"
        border.width: 1
        opacity: 0
        scale: 0.94

        NumberAnimation on opacity { running: registerPage.visible; duration: 350; to: 1 }
        NumberAnimation on scale { running: registerPage.visible; duration: 350; to: 1; easing.type: Easing.OutCubic }

        // Header icon
        Item {
            id: iconArea
            anchors.top: parent.top
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.topMargin: 32
            width: 64
            height: 64

            Rectangle {
                anchors.fill: parent
                radius: 18
                color: "#4CAF50"

                SequentialAnimation on opacity {
                    running: true
                    loops: Animation.Infinite
                    NumberAnimation { duration: 1600; from: 1; to: 0.85; easing.type: Easing.InOutSine }
                    NumberAnimation { duration: 1600; from: 0.85; to: 1; easing.type: Easing.InOutSine }
                }
            }

            // Person + plus glyph
            Canvas {
                anchors.centerIn: parent
                width: 30
                height: 30
                onPaint: {
                    var ctx = getContext("2d")
                    ctx.reset()
                    ctx.fillStyle = "#FFFFFF"
                    ctx.beginPath()
                    ctx.arc(width * 0.36, height * 0.32, height * 0.15, 0, Math.PI * 2)
                    ctx.fill()

                    ctx.beginPath()
                    ctx.moveTo(width * 0.08, height * 0.86)
                    ctx.quadraticCurveTo(width * 0.08, height * 0.56, width * 0.36, height * 0.56)
                    ctx.quadraticCurveTo(width * 0.62, height * 0.56, width * 0.62, height * 0.82)
                    ctx.lineTo(width * 0.62, height * 0.86)
                    ctx.closePath()
                    ctx.fill()

                    ctx.strokeStyle = "#FFFFFF"
                    ctx.lineWidth = 3
                    ctx.lineCap = "round"
                    var cx = width * 0.8, cy = height * 0.32, s = height * 0.13
                    ctx.beginPath(); ctx.moveTo(cx - s, cy); ctx.lineTo(cx + s, cy); ctx.stroke()
                    ctx.beginPath(); ctx.moveTo(cx, cy - s); ctx.lineTo(cx, cy + s); ctx.stroke()
                }
            }
        }

        Text {
            id: titleArea
            anchors.top: iconArea.bottom
            anchors.topMargin: 16
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Create Account"
            font.pixelSize: 24
            font.bold: true
            color: darkMode ? "#F2F2F5" : "#1A1A2E"
        }

        Text {
            id: subtitleArea
            anchors.top: titleArea.bottom
            anchors.topMargin: 6
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Join Messenger today"
            font.pixelSize: 13
            color: darkMode ? "#9494AC" : "#6B6B7B"
        }

        // Form
        ColumnLayout {
            id: formLayout
            anchors.top: subtitleArea.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.topMargin: 22
            anchors.leftMargin: 32
            anchors.rightMargin: 32
            spacing: 14

            // Username
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "Username"
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    color: darkMode ? "#B8B8CC" : "#4A4A5A"
                }

                Rectangle {
                    Layout.fillWidth: true
                    height: 46
                    radius: 12
                    color: darkMode ? "rgba(42, 42, 74, 0.5)" : "rgba(240, 240, 245, 1)"
                    border.color: usernameField.activeFocus ? "#4CAF50" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
                    border.width: usernameField.activeFocus ? 2 : 1
                    Behavior on border.color { ColorAnimation { duration: 150 } }

                    TextField {
                        id: usernameField
                        anchors.fill: parent
                        leftPadding: 14
                        rightPadding: 14
                        verticalAlignment: TextInput.AlignVCenter
                        background: Item {}
                        placeholderText: "Pick a username"
                        placeholderTextColor: darkMode ? "#5A5A7A" : "#9E9E9E"
                        font.pixelSize: 14
                        color: darkMode ? "#E8E8E8" : "#1A1A2E"
                        selectByMouse: true
                        onTextChanged: usernameError.text = ""
                        Keys.onReturnPressed: emailField.forceActiveFocus()
                    }
                }

                Text {
                    id: usernameError
                    Layout.fillWidth: true
                    text: ""
                    color: "#FF6B6B"
                    font.pixelSize: 12
                    visible: text !== ""
                }
            }

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
                    border.color: emailField.activeFocus ? "#4CAF50" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
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
                        validator: RegularExpressionValidator {
                            regularExpression: /^[^\s]+@[^\s]+\.[^\s]+$/
                        }
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
                    border.color: passwordField.activeFocus ? "#4CAF50" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
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
                        placeholderText: "At least 8 characters"
                        placeholderTextColor: darkMode ? "#5A5A7A" : "#9E9E9E"
                        font.pixelSize: 14
                        echoMode: passwordBg.passwordVisible ? TextInput.Normal : TextInput.Password
                        color: darkMode ? "#E8E8E8" : "#1A1A2E"
                        passwordCharacter: "•"
                        selectByMouse: true
                        onTextChanged: {
                            passwordError.text = ""
                            registerPage.passwordStrengthLevel = registerPage.getPasswordStrength(text)
                        }
                        Keys.onReturnPressed: confirmPasswordField.forceActiveFocus()
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
                                ctx.strokeStyle = "#4CAF50"
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

                // Strength meter
                RowLayout {
                    Layout.fillWidth: true
                    Layout.topMargin: 2
                    spacing: 4
                    visible: passwordField.text.length > 0

                    Repeater {
                        model: 4
                        Rectangle {
                            Layout.fillWidth: true
                            height: 3
                            radius: 1.5
                            color: index < registerPage.passwordStrengthLevel
                                   ? registerPage.strengthColors[registerPage.passwordStrengthLevel - 1]
                                   : (darkMode ? "#2A2A4A" : "#E0E0E5")
                            Behavior on color { ColorAnimation { duration: 150 } }
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    Layout.topMargin: 2
                    text: registerPage.passwordStrengthLevel > 0
                          ? registerPage.strengthLabels[registerPage.passwordStrengthLevel - 1]
                          : ""
                    color: registerPage.passwordStrengthLevel > 0
                           ? registerPage.strengthColors[registerPage.passwordStrengthLevel - 1]
                           : (darkMode ? "#8B8B9E" : "#6B6B7B")
                    font.pixelSize: 11
                    visible: passwordField.text.length > 0
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

            // Confirm password
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 6

                Text {
                    text: "Confirm Password"
                    font.pixelSize: 13
                    font.weight: Font.Medium
                    color: darkMode ? "#B8B8CC" : "#4A4A5A"
                }

                Rectangle {
                    id: confirmBg
                    Layout.fillWidth: true
                    height: 46
                    radius: 12
                    color: darkMode ? "rgba(42, 42, 74, 0.5)" : "rgba(240, 240, 245, 1)"
                    border.color: confirmPasswordField.activeFocus ? "#4CAF50" : (darkMode ? "rgba(255, 255, 255, 0.08)" : "rgba(224, 224, 229, 1)")
                    border.width: confirmPasswordField.activeFocus ? 2 : 1
                    Behavior on border.color { ColorAnimation { duration: 150 } }

                    property bool passwordVisible: false

                    TextField {
                        id: confirmPasswordField
                        anchors.fill: parent
                        anchors.rightMargin: 40
                        leftPadding: 14
                        verticalAlignment: TextInput.AlignVCenter
                        background: Item {}
                        placeholderText: "Re-enter your password"
                        placeholderTextColor: darkMode ? "#5A5A7A" : "#9E9E9E"
                        font.pixelSize: 14
                        echoMode: confirmBg.passwordVisible ? TextInput.Normal : TextInput.Password
                        color: darkMode ? "#E8E8E8" : "#1A1A2E"
                        passwordCharacter: "•"
                        selectByMouse: true
                        onTextChanged: confirmError.text = ""
                        Keys.onReturnPressed: registerButton.clicked()
                    }

                    MouseArea {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.rightMargin: 12
                        width: 24
                        height: 24
                        cursorShape: Qt.PointingHandCursor
                        onClicked: confirmBg.passwordVisible = !confirmBg.passwordVisible

                        Canvas {
                            anchors.centerIn: parent
                            width: 20
                            height: 20
                            visible: !confirmBg.passwordVisible
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
                            visible: confirmBg.passwordVisible
                            onPaint: {
                                var ctx = getContext("2d")
                                ctx.reset()
                                ctx.strokeStyle = "#4CAF50"
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
                    id: confirmError
                    Layout.fillWidth: true
                    text: ""
                    color: "#FF6B6B"
                    font.pixelSize: 12
                    visible: text !== ""
                }
            }

            // Terms checkbox
            CheckBox {
                id: termsCheck
                Layout.fillWidth: true
                Layout.topMargin: 2
                font.pixelSize: 12
                indicator: Rectangle {
                    x: 0
                    y: parent.height / 2 - width / 2
                    width: 18
                    height: 18
                    radius: 5
                    border.color: termsCheck.checked ? "#4CAF50" : (darkMode ? "#5A5A7A" : "#9E9E9E")
                    border.width: 1.5
                    color: termsCheck.checked ? "#4CAF50" : "transparent"
                    Behavior on color { ColorAnimation { duration: 120 } }

                    Canvas {
                        anchors.centerIn: parent
                        width: 12
                        height: 12
                        visible: termsCheck.checked
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
                contentItem: Text {
                    text: "<span style='color: " + (darkMode ? "#9494AC" : "#6B6B7B") + ";'>I agree to the </span><span style='color: #4CAF50;'>Terms of Service</span>"
                    font: termsCheck.font
                    leftPadding: termsCheck.indicator.width + 8
                    verticalAlignment: Text.AlignVCenter
                    wrapMode: Text.WordWrap
                }
            }

            // Register button
            Button {
                id: registerButton
                Layout.fillWidth: true
                Layout.topMargin: 6
                height: 48
                text: isLoading ? "Creating account…" : "Create Account"
                font.pixelSize: 15
                font.bold: true
                contentItem: Text {
                    text: registerButton.text
                    font: registerButton.font
                    color: "#FFFFFF"
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                enabled: !isLoading &&
                         usernameField.text.length >= 3 &&
                         emailField.text.length > 0 &&
                         passwordField.text.length >= 8 &&
                         confirmPasswordField.text === passwordField.text &&
                         termsCheck.checked
                background: Rectangle {
                    radius: 13
                    color: !registerButton.enabled ? (darkMode ? "#2A2A4A" : "#E0E0E5")
                           : registerMouse.pressed ? "#3D8B40" : (registerMouse.containsMouse ? "#5CBF60" : "#4CAF50")
                    Behavior on color { ColorAnimation { duration: 120 } }

                    MouseArea {
                        id: registerMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: registerButton.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: registerButton.clicked()
                    }
                }
                onClicked: {
                    isLoading = true
                    authService.register(usernameField.text, emailField.text, passwordField.text)
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

            // Sign in
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                Layout.bottomMargin: 4
                spacing: 4

                Text {
                    text: "Already have an account?"
                    color: darkMode ? "#9494AC" : "#6B6B7B"
                    font.pixelSize: 13
                }

                Text {
                    text: "Sign In"
                    font.pixelSize: 13
                    font.bold: true
                    color: signInArea.containsMouse ? "#6FCF73" : "#4CAF50"
                    Behavior on color { ColorAnimation { duration: 120 } }

                    MouseArea {
                        id: signInArea
                        anchors.fill: parent
                        anchors.margins: -6
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            registerCard.visible = false
                            loginPage.visible = true
                        }
                    }
                }
            }
        }
    }

    // Login page (hidden by default, and not instantiated at all until shown
    // - otherwise this and Login.qml's own back-reference Loader recurse forever)
    Loader {
        id: loginPage
        anchors.fill: parent
        visible: false
        active: visible
        source: "qrc:/qml/Login.qml"
    }

    function getPasswordStrength(password) {
        let strength = 0
        if (password.length >= 8) strength++
        if (/[a-z]/.test(password) && /[A-Z]/.test(password)) strength++
        if (/[0-9]/.test(password)) strength++
        if (/[^a-zA-Z0-9]/.test(password)) strength++
        return strength
    }

    Component.onCompleted: {
        confirmPasswordField.textChanged.connect(function() {
            if (confirmPasswordField.text !== passwordField.text && confirmPasswordField.text.length > 0) {
                confirmError.text = "Passwords do not match"
            } else {
                confirmError.text = ""
            }
        })

        authService.registerSuccess.connect(function(userId) {
            isLoading = false
            authService.login(emailField.text, passwordField.text)
        })

        authService.registerFailed.connect(function(message) {
            isLoading = false
            confirmError.text = message
        })
    }
}
