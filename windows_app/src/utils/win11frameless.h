#pragma once

#include <QtGlobal>

#ifdef Q_OS_WIN

#include <QAbstractNativeEventFilter>
#include <QByteArray>
#include <QObject>
#include <QPointer>

class QQuickItem;
class QQuickWindow;

/**
 * Telegram-style custom frame on a Qt.FramelessWindowHint window.
 *
 * Keep a normal WS_OVERLAPPEDWINDOW HWND so Aero Snap, snap-to-top,
 * Snap Layouts, and shake-to-minimize still work. WM_NCCALCSIZE hides the
 * native caption/border. WM_NCHITTEST reports caption, resize edges, and
 * HTMAXBUTTON (required for the Win11 Snap Layouts flyout).
 *
 * Maximized size is the monitor work area so a pinned taskbar stays usable;
 * auto-hide taskbars keep a 1px sliver so they can be summoned.
 */
class Win11Frameless : public QObject, public QAbstractNativeEventFilter {
    Q_OBJECT
    Q_PROPERTY(bool maximizeHovered READ maximizeHovered NOTIFY maximizeHoveredChanged)

public:
    explicit Win11Frameless(QObject* parent = nullptr);

    void applyTo(QQuickWindow* window);

    Q_INVOKABLE void bindChrome(QQuickItem* titleBar,
                                QQuickItem* settingsButton,
                                QQuickItem* minimizeButton,
                                QQuickItem* maximizeButton,
                                QQuickItem* closeButton);

    bool maximizeHovered() const { return m_maximizeHovered; }

    bool nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result) override;

signals:
    void maximizeHoveredChanged();

private:
    qintptr hitTest(void* message) const;
    void applyWorkArea(void* nccalcParams) const;
    void applyMaxTrackSize(void* minMaxInfo) const;
    void updateCornerPreference(bool maximized) const;
    void extendDwmFrame() const;
    bool isOurWindow(void* hwnd) const;
    void setMaximizeHovered(bool hovered);

    QPointer<QQuickWindow> m_window;
    QPointer<QQuickItem> m_titleBar;
    QPointer<QQuickItem> m_settingsButton;
    QPointer<QQuickItem> m_minimizeButton;
    QPointer<QQuickItem> m_maximizeButton;
    QPointer<QQuickItem> m_closeButton;
    quintptr m_hwnd = 0;
    bool m_maximizeHovered = false;
};

#endif // Q_OS_WIN
