#include "pairingqrscanner.h"

#include <QCamera>
#include <QCameraDevice>
#include <QDebug>
#include <QMediaCaptureSession>
#include <QMediaDevices>
#include <QVideoFrame>
#include <QVideoSink>
#include <cstring>

extern "C" {
#include "../../third_party/quirc/quirc.h"
}

namespace {
constexpr int kMaxDecodeEdge = 480;
constexpr qint64 kDecodeIntervalMs = 90;
}

PairingQrScanner::PairingQrScanner(QObject* parent) : QObject(parent) {
    m_cameraAvailable = !QMediaDevices::videoInputs().isEmpty();
    m_clock.start();
}

PairingQrScanner::~PairingQrScanner() {
    stop();
}

void PairingQrScanner::setError(const QString& message) {
    if (m_error == message) return;
    m_error = message;
    emit errorChanged();
}

void PairingQrScanner::start() {
    if (m_scanning) return;
    m_handled = false;
    setError({});

    const QList<QCameraDevice> devices = QMediaDevices::videoInputs();
    const bool available = !devices.isEmpty();
    if (available != m_cameraAvailable) {
        m_cameraAvailable = available;
        emit cameraAvailableChanged();
    }
    if (!available) {
        setError(QStringLiteral("No camera found. Paste the pairing code instead."));
        return;
    }

    m_session = new QMediaCaptureSession(this);
    m_sink = new QVideoSink(this);
    m_camera = new QCamera(devices.first(), this);
    m_session->setCamera(m_camera);
    m_session->setVideoSink(m_sink);

    connect(m_sink, &QVideoSink::videoFrameChanged, this,
            &PairingQrScanner::onVideoFrame, Qt::QueuedConnection);

    m_camera->start();
    if (m_camera->error() != QCamera::NoError) {
        setError(m_camera->errorString().isEmpty()
                     ? QStringLiteral("Could not start the camera")
                     : m_camera->errorString());
        stop();
        return;
    }

    m_scanning = true;
    emit scanningChanged();
}

void PairingQrScanner::stop() {
    if (m_sink) {
        disconnect(m_sink, nullptr, this, nullptr);
    }
    if (m_camera) {
        m_camera->stop();
        m_camera->deleteLater();
        m_camera = nullptr;
    }
    if (m_session) {
        m_session->deleteLater();
        m_session = nullptr;
    }
    if (m_sink) {
        m_sink->deleteLater();
        m_sink = nullptr;
    }
    if (m_scanning) {
        m_scanning = false;
        emit scanningChanged();
    }
}

void PairingQrScanner::onVideoFrame(const QVideoFrame& frame) {
    if (!m_scanning || m_handled || !frame.isValid()) return;
    const qint64 now = m_clock.elapsed();
    if (now - m_lastDecodeMs < kDecodeIntervalMs) return;
    m_lastDecodeMs = now;

    QImage img = frame.toImage();
    if (img.isNull()) return;

    if (img.width() > kMaxDecodeEdge || img.height() > kMaxDecodeEdge) {
        img = img.scaled(kMaxDecodeEdge, kMaxDecodeEdge, Qt::KeepAspectRatio,
                         Qt::FastTransformation);
    }
    emit previewFrame(QVariant::fromValue(img));
    decodeImage(img);
}

void PairingQrScanner::decodeImage(const QImage& rgb) {
    QImage gray = rgb.convertToFormat(QImage::Format_Grayscale8);
    if (gray.isNull()) return;

    struct quirc* q = quirc_new();
    if (!q) return;
    if (quirc_resize(q, gray.width(), gray.height()) < 0) {
        quirc_destroy(q);
        return;
    }

    int w = 0, h = 0;
    uint8_t* buf = quirc_begin(q, &w, &h);
    if (!buf) {
        quirc_destroy(q);
        return;
    }
    for (int y = 0; y < h; ++y) {
        memcpy(buf + static_cast<size_t>(y) * w, gray.constScanLine(y),
               static_cast<size_t>(w));
    }
    quirc_end(q);

    const int n = quirc_count(q);
    for (int i = 0; i < n && !m_handled; ++i) {
        struct quirc_code code {};
        struct quirc_data data {};
        quirc_extract(q, i, &code);
        quirc_decode_error_t err = quirc_decode(&code, &data);
        if (err != QUIRC_SUCCESS) {
            quirc_flip(&code);
            err = quirc_decode(&code, &data);
        }
        if (err != QUIRC_SUCCESS) continue;
        const QString payload = QString::fromUtf8(
            reinterpret_cast<const char*>(data.payload), data.payload_len).trimmed();
        if (payload.startsWith(QStringLiteral("mp1."))) {
            m_handled = true;
            emit codeFound(payload);
            stop();
            break;
        }
    }
    quirc_destroy(q);
}
