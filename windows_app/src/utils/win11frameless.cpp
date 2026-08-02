#include "win11frameless.h"

#ifdef Q_OS_WIN

#include <QQuickWindow>
#include <QTimer>
#include <windows.h>
#include <windowsx.h>
#include <shellapi.h>

namespace {

// Thickness of the invisible resize border Windows keeps around a
// WS_THICKFRAME window. Needed to inset the client area when maximized -
// otherwise a maximized frameless window overhangs the monitor on every side
// by this much and the edges of the UI are cut off.
int resizeBorderX() {
    return ::GetSystemMetrics(SM_CXSIZEFRAME) + ::GetSystemMetrics(SM_CXPADDEDBORDER);
}
int resizeBorderY() {
    return ::GetSystemMetrics(SM_CYSIZEFRAME) + ::GetSystemMetrics(SM_CXPADDEDBORDER);
}

// True when an auto-hiding taskbar is docked on [edge] of the monitor the
// window is on. A maximized window has to leave a sliver of space there, or
// the taskbar can never be re-summoned by pointing at that edge.
bool hasAutoHideTaskbar(UINT edge, const RECT& monitorRect) {
    APPBARDATA data{};
    data.cbSize = sizeof(data);
    data.uEdge = edge;
    data.rc = monitorRect;
    // Returns the auto-hide bar's HWND as a UINT_PTR, 0 when there is none.
    return ::SHAppBarMessage(ABM_GETAUTOHIDEBAREX, &data) != 0;
}

} // namespace

void Win11Frameless::applyTo(QQuickWindow* window) {
    if (!window) return;
    auto hwnd = reinterpret_cast<HWND>(window->winId());
    if (!hwnd) return;

    // Put back everything FramelessWindowHint took away. WS_CAPTION looks
    // wrong to add to a frameless window, but nothing is ever drawn from it -
    // WM_NCCALCSIZE below collapses the non-client area to nothing. It has to
    // be present for the shell to treat this as a real, snappable window.
    LONG_PTR style = ::GetWindowLongPtr(hwnd, GWL_STYLE);
    style |= WS_OVERLAPPEDWINDOW; // CAPTION | SYSMENU | THICKFRAME | MIN/MAXIMIZEBOX
    ::SetWindowLongPtr(hwnd, GWL_STYLE, style);

    // Force the frame to be recalculated now, so WM_NCCALCSIZE runs against
    // the new style rather than at some arbitrary later point.
    ::SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
                   SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

    // Re-assert maximized state if Qt believes the window is maximized but
    // Windows no longer agrees. Swapping the window style above drops the
    // native maximized flag, while Qt goes on reporting Maximized - and that
    // split makes the maximize button need two clicks: the first only flips
    // Qt's property (so the icon changes but nothing moves), and the second
    // finally performs the real transition.
    //
    // Deferred to the next event-loop pass as well as checked immediately:
    // ApplicationWindow's declarative `visibility: "Maximized"` is applied
    // asynchronously, so when this runs straight after engine.load() the
    // property is often not Maximized yet and an immediate check alone
    // silently does nothing.
    const auto syncMaximizedState = [window, hwnd]() {
        if (window->visibility() == QWindow::Maximized && !::IsZoomed(hwnd)) {
            ::ShowWindow(hwnd, SW_MAXIMIZE);
        }
    };

    syncMaximizedState();
    QTimer::singleShot(0, window, syncMaximizedState);
}

bool Win11Frameless::nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result) {
    if (eventType != QByteArrayLiteral("windows_generic_MSG")) return false;

    auto* msg = static_cast<MSG*>(message);
    if (!msg || !msg->hwnd) return false;

    switch (msg->message) {
    case WM_NCCALCSIZE: {
        // wParam FALSE means only a rect is being proposed, nothing to strip.
        if (msg->wParam == FALSE) return false;

        auto* params = reinterpret_cast<NCCALCSIZE_PARAMS*>(msg->lParam);
        RECT& clientRect = params->rgrc[0];

        const bool maximized = ::IsZoomed(msg->hwnd);
        if (maximized) {
            // A maximized WS_THICKFRAME window is deliberately sized larger
            // than the monitor by the resize border. Left as-is on a
            // frameless window that just means the outer edges of our own UI
            // hang off-screen, so pull the client area back in by that much.
            clientRect.left += resizeBorderX();
            clientRect.right -= resizeBorderX();
            clientRect.bottom -= resizeBorderY();
            clientRect.top += resizeBorderY();

            // Keep a 1px sliver against any auto-hide taskbar edge; a window
            // flush to the edge blocks the taskbar from being summoned back.
            if (HMONITOR monitor = ::MonitorFromWindow(msg->hwnd, MONITOR_DEFAULTTONEAREST)) {
                MONITORINFO mi{};
                mi.cbSize = sizeof(mi);
                if (::GetMonitorInfoW(monitor, &mi)) {
                    if (hasAutoHideTaskbar(ABE_TOP, mi.rcMonitor)) clientRect.top += 1;
                    if (hasAutoHideTaskbar(ABE_BOTTOM, mi.rcMonitor)) clientRect.bottom -= 1;
                    if (hasAutoHideTaskbar(ABE_LEFT, mi.rcMonitor)) clientRect.left += 1;
                    if (hasAutoHideTaskbar(ABE_RIGHT, mi.rcMonitor)) clientRect.right -= 1;
                }
            }
        }
        // Returning 0 with the rect otherwise untouched tells Windows the
        // client area occupies the entire window - i.e. draw no frame, no
        // caption - while the window itself stays fully styled.
        *result = 0;
        return true;
    }

    case WM_NCHITTEST: {
        // The QML side owns hit-testing: it has its own edge/corner
        // MouseAreas calling startSystemResize(), and a title bar calling
        // startSystemMove(). Report plain client area so those keep working
        // and Windows doesn't claim the edges for itself.
        *result = HTCLIENT;
        return true;
    }

    default:
        return false;
    }
}

#endif // Q_OS_WIN
