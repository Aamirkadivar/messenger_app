#pragma once

// Before the Q_OS_WIN test, not after: that macro comes from Qt itself, so
// guarding on it while it is still undefined compiles this whole file away.
#include <QtGlobal>

#ifdef Q_OS_WIN

#include <QAbstractNativeEventFilter>
#include <QByteArray>

class QQuickWindow;

/**
 * Restores native Windows 11 window management to a frameless Qt window.
 *
 * Qt.FramelessWindowHint gets a borderless window by removing WS_THICKFRAME
 * and WS_MAXIMIZEBOX from it - but those are exactly the styles the shell
 * looks for before it offers Aero Snap, snap-to-top-edge-to-maximize, Snap
 * Layouts (the flyout when hovering maximize), and shake-to-minimize. So a
 * frameless window silently loses all of them no matter how the drag is
 * implemented; startSystemMove() is necessary but not sufficient.
 *
 * The fix, the same one Windows Terminal and VS Code use: keep the window a
 * completely ordinary styled window as far as the OS is concerned, then
 * answer WM_NCCALCSIZE with "the client area is the whole window", which
 * removes the frame visually while leaving every shell behaviour attached to
 * it intact.
 */
class Win11Frameless : public QAbstractNativeEventFilter {
public:
    /**
     * Re-adds the native styles to [window]'s HWND. Call once, after the
     * window exists (winId() has to be valid).
     */
    static void applyTo(QQuickWindow* window);

    bool nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result) override;
};

#endif // Q_OS_WIN
