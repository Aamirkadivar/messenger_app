#include "traynotifier.h"
#include <QAction>
#include <QCoreApplication>
#include <QIcon>

TrayNotifier::TrayNotifier(QObject* parent)
    : QObject(parent)
{
    m_notificationsEnabled = m_settings.value(QStringLiteral("notifications/enabled"), true).toBool();

    // The real app icon rather than the old placeholder mark, so the tray
    // entry matches the taskbar and the window. The .ico is used because it
    // ships several sizes and Windows can pick the one the notification area
    // actually asks for.
    m_trayIcon = new QSystemTrayIcon(QIcon(QStringLiteral(":/icons/app_icon.ico")), this);
    m_trayIcon->setToolTip(QStringLiteral("Messenger"));

    m_menu = new QMenu();
    QAction* openAction = m_menu->addAction(QStringLiteral("Open Messenger"));
    QAction* quitAction = m_menu->addAction(QStringLiteral("Quit"));

    connect(openAction, &QAction::triggered, this, &TrayNotifier::openRequested);
    connect(quitAction, &QAction::triggered, qApp, &QCoreApplication::quit);

    m_trayIcon->setContextMenu(m_menu);

    connect(m_trayIcon, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger || reason == QSystemTrayIcon::DoubleClick) {
            emit openRequested();
        }
    });

    m_trayIcon->show();
}

void TrayNotifier::showMessage(const QString& title, const QString& body) {
    if (!m_notificationsEnabled) return;
    if (m_trayIcon) {
        m_trayIcon->showMessage(title, body, QSystemTrayIcon::Information, 5000);
    }
}

void TrayNotifier::setNotificationsEnabled(bool enabled) {
    if (m_notificationsEnabled == enabled) return;
    m_notificationsEnabled = enabled;
    m_settings.setValue(QStringLiteral("notifications/enabled"), enabled);
    emit notificationsEnabledChanged();
}
