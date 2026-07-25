#pragma once

#include <QObject>
#include <QSystemTrayIcon>
#include <QMenu>

// Native Win32 tray icon + balloon notifications via QSystemTrayIcon
// (QtWidgets). Qt.labs.platform's QML SystemTrayIcon was tried first but
// never actually created a visible tray icon on this system despite
// reporting available=true - QSystemTrayIcon is the mature, long-proven
// class thousands of desktop apps rely on for this.
class TrayNotifier : public QObject {
    Q_OBJECT

public:
    explicit TrayNotifier(QObject* parent = nullptr);

    Q_INVOKABLE void showMessage(const QString& title, const QString& body);

signals:
    void openRequested();

private:
    QSystemTrayIcon* m_trayIcon = nullptr;
    QMenu* m_menu = nullptr;
};
