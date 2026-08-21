#include "win11frameless.h"

#ifdef Q_OS_WIN

#include <QQuickItem>
#include <QQuickWindow>
#include <QTimer>
#include <QMetaObject>
#include <dwmapi.h>
#include <shellapi.h>
#include <windows.h>
#include <windowsx.h>

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
#ifndef DWMWCP_ROUND
#define DWMWCP_ROUND 2
#endif
#ifndef DWMWCP_DONOTROUND
#define DWMWCP_DONOTROUND 1
#endif

namespace {

int resizeBorderX() {
    return ::GetSystemMetrics(SM_CXSIZEFRAME) + ::GetSystemMetrics(SM_CXPADDEDBORDER);
}

int resizeBorderY() {
    return ::GetSystemMetrics(SM_CYSIZEFRAME) + ::GetSystemMetrics(SM_CXPADDEDBORDER);
}

bool hasAutoHideTaskbar(UINT edge, const RECT& monitorRect) {
    APPBARDATA data{};
    data.cbSize = sizeof(data);
    data.uEdge = edge;
    data.rc = monitorRect;
    return ::SHAppBarMessage(ABM_GETAUTOHIDEBAREX, &data) != 0;
}

bool itemContainsWindowPoint(QQuickItem* item, const QPointF& windowPos) {
    if (!item || !item->isVisible() || item->width() <= 0 || item->height() <= 0)
        return false;
    const QPointF local = item->mapFromItem(nullptr, windowPos);
    return item->contains(local);
}

} // namespace

Win11Frameless::Win11Frameless(QObject* parent)
    : QObject(parent) {}

void Win11Frameless::applyTo(QQuickWindow* window) {
    if (!window) return;
    auto hwnd = reinterpret_cast<HWND>(window->winId());
    if (!hwnd) return;

    m_window = window;
    m_hwnd = reinterpret_cast<quintptr>(hwnd);

    LONG_PTR style = ::GetWindowLongPtr(hwnd, GWL_STYLE);
    style |= WS_OVERLAPPEDWINDOW;
    ::SetWindowLongPtr(hwnd, GWL_STYLE, style);

    extendDwmFrame();
    updateCornerPreference(::IsZoomed(hwnd));

    ::SetWindowPos(hwnd, nullptr, 0, 0, 0, 0,
                   SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

    const auto syncMaximizedState = [window, hwnd]() {
        if (window->visibility() == QWindow::Maximized && !::IsZoomed(hwnd))
            ::ShowWindow(hwnd, SW_MAXIMIZE);
    };
    syncMaximizedState();
    QTimer::singleShot(0, window, syncMaximizedState);
}

void Win11Frameless::bindChrome(QQuickItem* titleBar,
                                QQuickItem* settingsButton,
                                QQuickItem* minimizeButton,
                                QQuickItem* maximizeButton,
                                QQuickItem* closeButton) {
    m_titleBar = titleBar;
    m_settingsButton = settingsButton;
    m_minimizeButton = minimizeButton;
    m_maximizeButton = maximizeButton;
    m_closeButton = closeButton;
}

void Win11Frameless::extendDwmFrame() const {
    if (!m_hwnd) return;
    auto hwnd = reinterpret_cast<HWND>(m_hwnd);
    MARGINS margins{1, 1, 1, 1};
    ::DwmExtendFrameIntoClientArea(hwnd, &margins);
}

void Win11Frameless::updateCornerPreference(bool maximized) const {
    if (!m_hwnd) return;
    auto hwnd = reinterpret_cast<HWND>(m_hwnd);
    DWORD preference = maximized ? DWMWCP_DONOTROUND : DWMWCP_ROUND;
    ::DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &preference, sizeof(preference));
}

bool Win11Frameless::isOurWindow(void* hwnd) const {
    return m_hwnd && reinterpret_cast<quintptr>(hwnd) == m_hwnd;
}

void Win11Frameless::setMaximizeHovered(bool hovered) {
    if (m_maximizeHovered == hovered) return;
    m_maximizeHovered = hovered;
    // Do not emit from WM_NCHITTEST: updating QML (color/layout) on that
    // stack re-enters the scene graph and can abort the process.
    QMetaObject::invokeMethod(this, [this]() {
        emit maximizeHoveredChanged();
    }, Qt::QueuedConnection);
}

void Win11Frameless::applyWorkArea(void* nccalcParams) const {
    auto* params = static_cast<NCCALCSIZE_PARAMS*>(nccalcParams);
    RECT& clientRect = params->rgrc[0];
    auto hwnd = reinterpret_cast<HWND>(m_hwnd);

    HMONITOR monitor = ::MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    if (!monitor) {
        clientRect.left += resizeBorderX();
        clientRect.right -= resizeBorderX();
        clientRect.top += resizeBorderY();
        clientRect.bottom -= resizeBorderY();
        return;
    }

    MONITORINFO mi{};
    mi.cbSize = sizeof(mi);
    if (!::GetMonitorInfoW(monitor, &mi)) {
        clientRect.left += resizeBorderX();
        clientRect.right -= resizeBorderX();
        clientRect.top += resizeBorderY();
        clientRect.bottom -= resizeBorderY();
        return;
    }

    clientRect = mi.rcWork;
    if (hasAutoHideTaskbar(ABE_TOP, mi.rcMonitor) && clientRect.top <= mi.rcMonitor.top)
        clientRect.top += 1;
    if (hasAutoHideTaskbar(ABE_BOTTOM, mi.rcMonitor) && clientRect.bottom >= mi.rcMonitor.bottom)
        clientRect.bottom -= 1;
    if (hasAutoHideTaskbar(ABE_LEFT, mi.rcMonitor) && clientRect.left <= mi.rcMonitor.left)
        clientRect.left += 1;
    if (hasAutoHideTaskbar(ABE_RIGHT, mi.rcMonitor) && clientRect.right >= mi.rcMonitor.right)
        clientRect.right -= 1;
}

void Win11Frameless::applyMaxTrackSize(void* minMaxInfo) const {
    auto hwnd = reinterpret_cast<HWND>(m_hwnd);
    auto* info = static_cast<MINMAXINFO*>(minMaxInfo);
    HMONITOR monitor = ::MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    if (!monitor) return;

    MONITORINFO mi{};
    mi.cbSize = sizeof(mi);
    if (!::GetMonitorInfoW(monitor, &mi)) return;

    info->ptMaxPosition.x = mi.rcWork.left - mi.rcMonitor.left;
    info->ptMaxPosition.y = mi.rcWork.top - mi.rcMonitor.top;
    info->ptMaxSize.x = mi.rcWork.right - mi.rcWork.left;
    info->ptMaxSize.y = mi.rcWork.bottom - mi.rcWork.top;

    if (m_window) {
        const qreal dpr = m_window->devicePixelRatio();
        info->ptMinTrackSize.x = qRound(m_window->minimumWidth() * dpr);
        info->ptMinTrackSize.y = qRound(m_window->minimumHeight() * dpr);
    }
}

qintptr Win11Frameless::hitTest(void* message) const {
    auto* msg = static_cast<MSG*>(message);
    auto hwnd = reinterpret_cast<HWND>(m_hwnd);
    const bool maximized = ::IsZoomed(hwnd);

    POINT native{GET_X_LPARAM(msg->lParam), GET_Y_LPARAM(msg->lParam)};
    POINT clientPt = native;
    ::ScreenToClient(hwnd, &clientPt);

    RECT rc{};
    ::GetClientRect(hwnd, &rc);

    // WM_NCHITTEST reports physical screen pixels, while QQuickItem geometry -
    // and anything mapFromGlobal() would hand back - is in logical pixels. The
    // two only agree at 100% scaling; at 125% the unconverted point landed a
    // quarter of the way past the window's right edge, so no caption button
    // ever matched. That silently disabled the whole native-chrome path: no
    // HTMAXBUTTON, so no hover state on the maximize button and no Snap
    // Layouts flyout, and no WM_NCLBUTTONDOWN either - since that button
    // deliberately takes no clicks in QML, only the title bar's own
    // double-click-to-maximize was left working.
    // clientPt is already relative to the client area, which WM_NCCALCSIZE has
    // made the whole window, so scaling it is all the conversion needed.
    QPointF windowPos;
    if (m_window) {
        const qreal dpr = m_window->devicePixelRatio();
        windowPos = QPointF(clientPt.x / dpr, clientPt.y / dpr);
    }

    // Caption buttons beat the resize strip so they stay clickable along the
    // top edge.
    //
    // The maximize button reports HTCLIENT rather than HTMAXBUTTON, which
    // costs the Win11 Snap Layouts flyout. HTMAXBUTTON hands the pointer to
    // Windows' caption-button machinery, and that path crashes the process
    // (0xc0000005 inside this filter) within seconds of the button first
    // being hovered. It went unnoticed because the hit test used to compare a
    // physical-pixel point against logical-pixel item geometry, so on any
    // scaled display no button ever matched and HTMAXBUTTON was never
    // returned - the native chrome was dead code. Fixing the coordinates woke
    // it up, along with the crash. Until that is diagnosed against a debug
    // build, QML owns the button: it handles its own hover and clicks.
    if (itemContainsWindowPoint(m_maximizeButton, windowPos)
        || itemContainsWindowPoint(m_minimizeButton, windowPos)
        || itemContainsWindowPoint(m_closeButton, windowPos)
        || itemContainsWindowPoint(m_settingsButton, windowPos))
        return HTCLIENT;

    if (!maximized) {
        const int bx = resizeBorderX();
        const int by = resizeBorderY();
        const bool left = clientPt.x < bx;
        const bool right = clientPt.x >= rc.right - bx;
        const bool top = clientPt.y < by;
        const bool bottom = clientPt.y >= rc.bottom - by;
        if (top && left) return HTTOPLEFT;
        if (top && right) return HTTOPRIGHT;
        if (bottom && left) return HTBOTTOMLEFT;
        if (bottom && right) return HTBOTTOMRIGHT;
        if (left) return HTLEFT;
        if (right) return HTRIGHT;
        if (top) return HTTOP;
        if (bottom) return HTBOTTOM;
    }

    return HTCLIENT;
}

bool Win11Frameless::nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result) {
    if (eventType != QByteArrayLiteral("windows_generic_MSG")) return false;

    auto* msg = static_cast<MSG*>(message);
    if (!msg || !isOurWindow(msg->hwnd)) return false;

    switch (msg->message) {
    case WM_NCCALCSIZE:
        if (msg->wParam == FALSE) return false;
        if (::IsZoomed(reinterpret_cast<HWND>(m_hwnd)))
            applyWorkArea(reinterpret_cast<void*>(msg->lParam));
        *result = 0;
        return true;

    case WM_NCHITTEST: {
        const qintptr ht = hitTest(message);
        *result = ht;
        setMaximizeHovered(ht == HTMAXBUTTON);
        return true;
    }

    case WM_NCMOUSELEAVE:
    case WM_MOUSELEAVE:
        setMaximizeHovered(false);
        return false;

    case WM_NCLBUTTONDOWN:
        if (hitTest(message) == HTMAXBUTTON) {
            QMetaObject::invokeMethod(this, [this]() {
                if (!m_hwnd) return;
                auto hwnd = reinterpret_cast<HWND>(m_hwnd);
                ::ShowWindow(hwnd, ::IsZoomed(hwnd) ? SW_RESTORE : SW_MAXIMIZE);
            }, Qt::QueuedConnection);
            *result = 0;
            return true;
        }
        return false;

    case WM_GETMINMAXINFO:
        applyMaxTrackSize(reinterpret_cast<void*>(msg->lParam));
        *result = 0;
        return true;

    case WM_SIZE:
        updateCornerPreference(msg->wParam == SIZE_MAXIMIZED);
        return false;

    default:
        return false;
    }
}

#endif // Q_OS_WIN
