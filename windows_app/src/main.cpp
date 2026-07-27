#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
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
#include "utils/traynotifier.h"
#include "services/authservice.h"
#include "services/websocketservice.h"
#include "services/chatservice.h"

#ifdef Q_OS_WIN
#include <windows.h>
#include <dwmapi.h>

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
#ifndef DWMWCP_ROUND
#define DWMWCP_ROUND 2
#endif

// Rounded window corners like Windows 11's native chrome (the Telegram/
// Windows Terminal look) - a frameless window doesn't get this from DWM
// automatically, it has to be requested explicitly per-HWND.
static void applyRoundedCorners(QQuickWindow* window) {
    auto hwnd = reinterpret_cast<HWND>(window->winId());
    DWORD preference = DWMWCP_ROUND;
    DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &preference, sizeof(preference));
}
#endif

int main(int argc, char* argv[]) {
    // Use the Basic style so custom background/indicator/contentItem overrides
    // throughout the QML actually apply - the native platform style silently
    // ignores them (this was the root cause of custom checkbox/button styling
    // not rendering).
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    QApplication app(argc, argv);
    app.setOrganizationName(QStringLiteral("MessengerApp"));
    app.setApplicationName(QStringLiteral("Messenger"));
    app.setApplicationVersion(QStringLiteral("1.0.0"));

    // Enable high DPI scaling
    app.setAttribute(Qt::AA_EnableHighDpiScaling, true);
    app.setAttribute(Qt::AA_UseHighDpiPixmaps, true);
    app.setQuitOnLastWindowClosed(false);

    // Initialize credential manager
    CredentialManager::instance();

    // Create services
    AuthService authService;
    WebSocketService websocketService;
    ChatService chatService(&authService);

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

    // Fetch chats whenever a token becomes available (fresh login or restored session)
    QObject::connect(&authService, &AuthService::tokenReady,
                     &chatService, [&chatService]() {
        chatService.fetchChats();
    });

    // Connect chat service signals
    QObject::connect(&chatService, &ChatService::chatError,
                     [](const QString& error) {
                         qWarning() << "[ChatService] Error:" << error;
                     });

    TrayNotifier trayNotifier;

    QQmlApplicationEngine engine;

    // Register types
    engine.rootContext()->setContextProperty(QStringLiteral("authService"), &authService);
    engine.rootContext()->setContextProperty(QStringLiteral("websocketService"), &websocketService);
    engine.rootContext()->setContextProperty(QStringLiteral("chatService"), &chatService);
    engine.rootContext()->setContextProperty(QStringLiteral("credentialManager"), &CredentialManager::instance());
    engine.rootContext()->setContextProperty(QStringLiteral("trayNotifier"), &trayNotifier);

    // Load QML
    const QString qmlFile = QStringLiteral("qrc:/qml/main.qml");
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);
    engine.load(qmlFile);

    // Restore a previously saved login, if any, now that the QML UI actually
    // exists and has connected its listeners - restoreSession() emits
    // tokenReady synchronously, which chains into chatService.fetchChats(),
    // which emits its cache-based chatsFetched signal immediately too. Doing
    // this any earlier meant that emission fired before ChatList.qml's
    // Component.onCompleted had run, so nothing was listening yet and the
    // cached chat list was silently lost - Qt signals aren't replayed for
    // listeners that connect after the fact.
    authService.restoreSession();

    // Clicking the tray icon (or its "Open Messenger" menu item) should
    // bring the main window back to the front.
    if (!engine.rootObjects().isEmpty()) {
        if (auto* window = qobject_cast<QQuickWindow*>(engine.rootObjects().first())) {
            QObject::connect(&trayNotifier, &TrayNotifier::openRequested, window, [window]() {
                window->show();
                window->raise();
                window->requestActivate();
            });
#ifdef Q_OS_WIN
            applyRoundedCorners(window);
#endif
        }
    }

    return app.exec();
}