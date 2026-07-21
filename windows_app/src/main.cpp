#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QIcon>
#include <QDir>
#include <QStandardPaths>
#include <QDesktopServices>
#include <QSettings>
#include <QScreen>
#include <QFontDatabase>
#include <QFile>
#include <QLoggingCategory>

#include "utils/config.h"
#include "utils/credentialmanager.h"
#include "services/authservice.h"
#include "services/websocketservice.h"

int main(int argc, char* argv[]) {
    // Use the Basic style so custom background/indicator/contentItem overrides
    // throughout the QML actually apply - the native platform style silently
    // ignores them (this was the root cause of custom checkbox/button styling
    // not rendering).
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    QGuiApplication app(argc, argv);
    app.setOrganizationName(QStringLiteral("MessengerApp"));
    app.setApplicationName(QStringLiteral("Messenger"));
    app.setApplicationVersion(QStringLiteral("1.0.0"));

    // Enable high DPI scaling
    app.setAttribute(Qt::AA_EnableHighDpiScaling, true);
    app.setAttribute(Qt::AA_UseHighDpiPixmaps, true);

    // Initialize credential manager
    CredentialManager::instance();

    // Create services
    AuthService authService;
    WebSocketService websocketService;

    // Check for auto-login
    QString savedToken = CredentialManager::instance().getToken(QStringLiteral("access_token"));
    QString savedUserId = CredentialManager::instance().getToken(QStringLiteral("current_user"));

    // Setup network access manager for auth service
    // Connect auth to websocket
    QObject::connect(&authService, &AuthService::loginSuccess,
                     &websocketService, [&websocketService](const QString& userId, const QString& username) {
        Q_UNUSED(userId);
        Q_UNUSED(username);
        QString token = CredentialManager::instance().getToken(QStringLiteral("access_token"));
        if (!token.isEmpty()) {
            websocketService.connectToServer(token);
        }
    });

    QObject::connect(&authService, &AuthService::loginFailed,
                     &websocketService, [&websocketService](const QString&) {
        websocketService.disconnectFromServer();
    });

    QObject::connect(&websocketService, &WebSocketService::connected,
                     &authService, [&authService](const QString& userId) {
        Q_UNUSED(userId);
        // WebSocket connected, auth service can use it
    });

    QQmlApplicationEngine engine;

    // Register types
    engine.rootContext()->setContextProperty(QStringLiteral("authService"), &authService);
    engine.rootContext()->setContextProperty(QStringLiteral("websocketService"), &websocketService);
    engine.rootContext()->setContextProperty(QStringLiteral("credentialManager"), &CredentialManager::instance());

    // Load QML
    const QString qmlFile = QStringLiteral("qrc:/qml/main.qml");
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);
    engine.load(qmlFile);

    return app.exec();
}