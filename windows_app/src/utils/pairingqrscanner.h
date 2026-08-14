#pragma once

#include <QElapsedTimer>
#include <QImage>
#include <QObject>
#include <QString>
#include <QVariant>

class QCamera;
class QMediaCaptureSession;
class QVideoFrame;
class QVideoSink;

// Camera QR scanner for device pairing (`mp1....`).
// Preview frames are QImages so QML can paint them with VideoFrame (VideoOutput
// / QtQuick.Effects are unreliable on this Qt/MinGW/GPU stack). Decode uses
// vendored quirc.
class PairingQrScanner : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool scanning READ scanning NOTIFY scanningChanged)
    Q_PROPERTY(bool cameraAvailable READ cameraAvailable NOTIFY cameraAvailableChanged)
    Q_PROPERTY(QString error READ error NOTIFY errorChanged)

public:
    explicit PairingQrScanner(QObject* parent = nullptr);
    ~PairingQrScanner() override;

    bool scanning() const { return m_scanning; }
    bool cameraAvailable() const { return m_cameraAvailable; }
    QString error() const { return m_error; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();

signals:
    void scanningChanged();
    void cameraAvailableChanged();
    void errorChanged();
    void previewFrame(const QVariant& image);
    void codeFound(const QString& code);

private:
    void setError(const QString& message);
    void onVideoFrame(const QVideoFrame& frame);
    void decodeImage(const QImage& rgb);

    QMediaCaptureSession* m_session = nullptr;
    QCamera* m_camera = nullptr;
    QVideoSink* m_sink = nullptr;
    bool m_scanning = false;
    bool m_cameraAvailable = false;
    bool m_handled = false;
    QString m_error;
    QElapsedTimer m_clock;
    qint64 m_lastDecodeMs = 0;
};
