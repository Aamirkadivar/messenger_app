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
#include <QDateTime>

#include "utils/config.h"
#include "utils/appconfig.h"
#include "utils/credentialmanager.h"
#include "crypto/encryption.h"
#include "crypto/doubleratchetv4.h"
#include "crypto/mlscore.h"
#ifdef HAVE_MLSPP
#include "crypto/mlsgroupcrypto.h"
#endif
#include "utils/traynotifier.h"
#include "services/authservice.h"
#include "services/websocketservice.h"
#include "services/chatservice.h"
#include "services/groupservice.h"
#include "services/voiceservice.h"
#include "services/roundvideoservice.h"
#include "utils/circularvideoitem.h"
#include "utils/videoframeitem.h"
#include "utils/pairingqrscanner.h"
#include "services/callservice.h"

#ifdef Q_OS_WIN
// windows.h must come FIRST: win11frameless.h uses LRESULT/HWND without
// including it itself. Rounded corners live inside Win11Frameless::applyTo.
#include <windows.h>
#include <dwmapi.h>
#include "utils/win11frameless.h"
#endif


// Windows has no logcat, and launching from Explorer discards stderr - which is
// why the Windows half of MLS stayed undiagnosable for an entire session while
// Android's failures were plainly visible. Mirror every qDebug/qInfo/qWarning
// into a file so the log survives a normal double-click launch.
static void mlsFileLogger(QtMsgType type, const QMessageLogContext&, const QString& msg) {
    static QFile logFile(QDir::temp().filePath(QStringLiteral("messenger-app.log")));
    static bool opened = logFile.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text);
    const char* level = "INFO";
    switch (type) {
        case QtDebugMsg:    level = "DEBUG"; break;
        case QtWarningMsg:  level = "WARN";  break;
        case QtCriticalMsg: level = "CRIT";  break;
        case QtFatalMsg:    level = "FATAL"; break;
        default: break;
    }
    const QString line = QStringLiteral("%1 [%2] %3\n")
        .arg(QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz")))
        .arg(QLatin1String(level), msg);
    if (opened) {
        logFile.write(line.toUtf8());
        logFile.flush();
    }
    fputs(line.toUtf8().constData(), stderr);
}

int main(int argc, char* argv[]) {
    // Prefer Direct3D 11 for Qt Quick Effects (MultiEffect blur). Must be set
    // before QGuiApplication so the scene graph picks this RHI backend —
    // software/OpenGL backends were why frosted glass often rendered blank.
    qInstallMessageHandler(mlsFileLogger);
    // Qt drops qInfo() for the default category unless info is enabled, so the
    // whole [mls-ensure]/[mls-invite] trace was being discarded before it ever
    // reached the handler above - only qWarning survived. Turn info on.
    QLoggingCategory::setFilterRules(QStringLiteral("*.info=true"));

    qputenv("QSG_RHI_BACKEND", "d3d11");

    // Use the Basic style so custom background/indicator/contentItem overrides
    // throughout the QML actually apply - the native platform style silently
    // ignores them (this was the root cause of custom checkbox/button styling
    // not rendering).
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    QApplication app(argc, argv);
    app.setOrganizationName(QStringLiteral("MessengerApp"));
    app.setApplicationName(QStringLiteral("Messenger"));
    app.setApplicationVersion(QStringLiteral("1.0.0"));
    // Live window / taskbar-button icon. The .rc compiled into the exe covers
    // how the *file* looks in Explorer; this covers the running window.
    app.setWindowIcon(QIcon(QStringLiteral(":/icons/app_icon.png")));

    app.setQuitOnLastWindowClosed(false);

    // Initialize credential manager
    CredentialManager::instance();

    // Report the Rust core's presence once at startup. Failure is non-fatal:
    // group chats still run on the existing path until the cutover.
    if (MlsCore::instance().load()) {
        qInfo().noquote() << "[mls-core] available, version"
                          << MlsCore::instance().coreVersion();
    } else {
        qWarning().noquote() << "[mls-core] unavailable:"
                             << MlsCore::instance().lastError();
    }

    // Dev-only: verify the v4 ratchet port against the Go/Kotlin vectors.
    // Run with E2EE_SELFTEST=1 to check and exit; never runs in normal use.
    if (qEnvironmentVariableIsSet("E2EE_MLS_EXPORT")) {
        Encryption::init();
#ifdef HAVE_MLSPP
        const bool ok = MlsGroupCrypto::exportInteropVectors(
            qEnvironmentVariable("E2EE_MLS_EXPORT"));
        return ok ? 0 : 1;
#else
        return 1;
#endif
    }

    if (qEnvironmentVariableIsSet("E2EE_SELFTEST")) {
        Encryption::init();
        bool ok = DoubleRatchetV4::selfTest();
        // Phase 0 of the Rust core: proves the C ABI, buffer ownership, panic
        // containment and the log channel before any MLS exists.
        ok = MlsCore::instance().selfTest() && ok;
#ifdef HAVE_MLSPP
        ok = MlsGroupCrypto::selfTest() && ok;
#else
        qWarning("[mls-selftest] SKIPPED - built without mlspp");
#endif
        return ok ? 0 : 1;
    }

    // Create services
    AuthService authService;
    WebSocketService websocketService;
    // GroupService before ChatService: ChatService needs it (group member
    // list + public keys) to establish/distribute group Sender Keys.
    GroupService groupService(&authService);
    ChatService chatService(&authService, &groupService);
    VoiceService voiceService;
    RoundVideoService roundVideoService;
    CallService callService(&authService, &websocketService);
    AppConfig appConfig;

    // Connect auth to websocket
    QObject::connect(&authService, &AuthService::loginSuccess,
                     &websocketService, [&websocketService, &authService](const QString& userId, const QString& username) {
        Q_UNUSED(userId);
        Q_UNUSED(username);
        QString token = CredentialManager::instance().getToken(QStringLiteral("access_token"));
        if (!token.isEmpty()) {
            websocketService.setDeviceId(authService.getOrCreateDeviceId());
            websocketService.connectToServer(token);
        }
    });

    QObject::connect(&authService, &AuthService::loginFailed,
                     &websocketService, [&websocketService](const QString&) {
        websocketService.disconnectFromServer();
    });

    // A stale access token (the common case: the app was closed longer than
    // the token's lifetime) makes every WebSocket connect attempt fail the
    // same way forever, since connectToServer() keeps retrying with that
    // same dead token - the sidebar gets stuck on "Connecting…" and nothing
    // ever tells AuthService to get a new one. Ask for a fresh token after a
    // failed attempt and hand it straight to the socket.
    static qint64 lastTokenRefreshAttemptMs = 0;
    QObject::connect(&websocketService, &WebSocketService::connectionAttemptFailed,
                     &authService, [&authService]() {
        qint64 now = QDateTime::currentMSecsSinceEpoch();
        // Cooldown so the reconnect timer's retries (a few seconds apart)
        // don't spam the refresh endpoint - one attempt is enough to know
        // whether a fresh token fixes it.
        if (now - lastTokenRefreshAttemptMs < 10000) return;
        lastTokenRefreshAttemptMs = now;
        authService.refreshToken();
    });

    QObject::connect(&authService, &AuthService::tokenReady,
                     &websocketService, [&websocketService, &authService](const QString& token) {
        if (!websocketService.isConnected()) {
            websocketService.setDeviceId(authService.getOrCreateDeviceId());
            websocketService.connectToServer(token);
        }
    });

    // Fetch chats whenever a token becomes available (fresh login or restored session)
    QObject::connect(&authService, &AuthService::tokenReady,
                     &chatService, [&chatService]() {
        chatService.fetchChats();
        chatService.mlsV2Bootstrap();
#ifdef HAVE_MLSPP
        // v1 (mlspp) KeyPackages share the DS namespace with OpenMLS. Publishing
        // them while v2 is live poisons the pool: the other client claims a
        // package it cannot parse and the device can never be added.
        if (!chatService.mlsV2Ready()) {
        chatService.mlsRestoreGroups();
        chatService.mlsEnsureKeyPackages();
        chatService.mlsProcessWelcomes();
        }
#endif
    });

    QObject::connect(&websocketService, &WebSocketService::mlsCommitReceived,
                     &chatService, [&chatService](const QString& chatId) {
        if (chatService.mlsV2Ready()) {
            chatService.mlsV2OnCommit(chatId);
            return;
        }
#ifdef HAVE_MLSPP
        chatService.mlsProcessWelcomes();
        chatService.mlsSyncHandshakes(chatId);
#endif
    });

    QObject::connect(&websocketService, &WebSocketService::connected,
                     &authService, [&authService]() {
        authService.pullAndMergeVault(true);
    });

    QObject::connect(&groupService, &GroupService::groupError,
                     [](const QString& error) {
                         qWarning() << "[GroupService] Error:" << error;
                     });

    // Connect chat service signals
    QObject::connect(&chatService, &ChatService::chatError,
                     [](const QString& error) {
                         qWarning() << "[ChatService] Error:" << error;
                     });

    TrayNotifier trayNotifier;

#ifdef Q_OS_WIN
    // Must be installed before the window is shown so the very first
    // WM_NCCALCSIZE is ours - see Win11Frameless for why a frameless window
    // otherwise loses Aero Snap, snap-to-top-to-maximize and Snap Layouts.
    static Win11Frameless win11Frameless;
    app.installNativeEventFilter(&win11Frameless);
#endif

    QQmlApplicationEngine engine;

    // Circular video surface, shared by the recorder overlay and the message
    // bubbles. Registered as a type rather than a context property because
    // each instance owns its own video sink.
    qmlRegisterType<CircularVideoItem>("Messenger", 1, 0, "CircularVideo");

    // Rectangular video surface for calls (remote feed + local self-view),
    // fed decoded QImage frames by CallService.
    qmlRegisterType<VideoFrameItem>("Messenger", 1, 0, "VideoFrame");
    qmlRegisterType<PairingQrScanner>("Messenger", 1, 0, "PairingQrScanner");

    // Register types
    engine.rootContext()->setContextProperty(QStringLiteral("authService"), &authService);
    engine.rootContext()->setContextProperty(QStringLiteral("websocketService"), &websocketService);
    engine.rootContext()->setContextProperty(QStringLiteral("chatService"), &chatService);
    engine.rootContext()->setContextProperty(QStringLiteral("groupService"), &groupService);
    engine.rootContext()->setContextProperty(QStringLiteral("voiceService"), &voiceService);
    engine.rootContext()->setContextProperty(QStringLiteral("roundVideoService"), &roundVideoService);
    engine.rootContext()->setContextProperty(QStringLiteral("callService"), &callService);
    engine.rootContext()->setContextProperty(QStringLiteral("appConfig"), &appConfig);
    engine.rootContext()->setContextProperty(QStringLiteral("credentialManager"), &CredentialManager::instance());
    engine.rootContext()->setContextProperty(QStringLiteral("trayNotifier"), &trayNotifier);
#ifdef Q_OS_WIN
    engine.rootContext()->setContextProperty(QStringLiteral("win11Frameless"), &win11Frameless);
#endif

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
            win11Frameless.applyTo(window);
#endif
        }
    }

    return app.exec();
}