#pragma once

#include <QObject>
#include <QString>
#include <QMediaCaptureSession>
#include <QMediaRecorder>
#include <QCamera>
#include <QAudioInput>
#include <QCameraDevice>
#include <QVideoSink>

// Local camera capture for Telegram-style round video messages.
//
// Mirrors VoiceService's split: this owns nothing but the capture pipeline and
// a temp file, while ChatService owns encrypt/upload/download - the same
// boundary the Android client draws between RoundVideoRecorder and
// RoundVideoRepository.
//
// Two deliberate differences from the Android implementation:
//
//  - No transcode pass. Android has to crop its camera frame to a square on
//    the GPU; here the recorder is simply asked for a small, low-bitrate
//    stream up front, and both clients centre-crop to the circle at playback
//    time (QML VideoOutput does it with PreserveAspectCrop). That keeps the
//    wire format tolerant of whatever aspect either camera produces.
//
//  - "Front/back" is not a desktop concept. QCameraDevice::position() is
//    almost always UnspecifiedPosition on a PC, so switchCamera() cycles
//    through every attached camera rather than toggling two faces.
class RoundVideoService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isPreviewing READ isPreviewing NOTIFY previewStateChanged)
    Q_PROPERTY(bool isRecording READ isRecording NOTIFY recordingStateChanged)
    Q_PROPERTY(bool isPaused READ isPaused NOTIFY recordingStateChanged)
    Q_PROPERTY(qint64 elapsedMs READ elapsedMs NOTIFY elapsedChanged)
    Q_PROPERTY(int cameraCount READ cameraCount NOTIFY camerasChanged)
    Q_PROPERTY(QString cameraName READ cameraName NOTIFY camerasChanged)

public:
    explicit RoundVideoService(QObject* parent = nullptr);
    ~RoundVideoService() override;

    // Matches the Android limits so a note recorded on either client behaves
    // the same way and lands inside the server's 25MB cap.
    static constexpr qint64 kMinDurationMs = 900;
    static constexpr qint64 kMaxDurationMs = 60 * 1000;

    bool isPreviewing() const { return m_isPreviewing; }
    bool isRecording() const { return m_isRecording; }
    bool isPaused() const { return m_isPaused; }
    qint64 elapsedMs() const { return m_elapsedMs; }
    int cameraCount() const { return m_devices.size(); }
    QString cameraName() const;

    // Opens the camera and starts feeding the bound video output. Called when
    // the recorder overlay appears; the camera is released again on stop.
    Q_INVOKABLE bool startPreview();
    Q_INVOKABLE void stopPreview();

    // Points the capture session at a QVideoSink - the one owned by the
    // QML CircularVideo item, which is what makes the preview round.
    Q_INVOKABLE void bindVideoOutput(QVideoSink* sink);

    Q_INVOKABLE bool startRecording();
    // Stops and, once the file is actually flushed, emits recordingFinished -
    // or recordingTooShort for an accidental tap. The file is NOT valid until
    // the recorder reports StoppedState, which is why this cannot just return
    // a path.
    Q_INVOKABLE void stopRecording();
    Q_INVOKABLE void cancelRecording();

    // Pause keeps the same file open and resumes into it, so a paused take
    // stays one message rather than becoming two.
    Q_INVOKABLE void togglePause();

    // Cycles to the next attached camera. Safe to call while previewing; a
    // recording in progress is stopped first, because the capture session has
    // to be rebuilt around the new device.
    Q_INVOKABLE void switchCamera();

signals:
    void previewStateChanged();
    void recordingStateChanged();
    void elapsedChanged();
    void camerasChanged();

    void recordingFinished(const QString& localFilePath, qint64 durationMs);
    void recordingTooShort();
    void recordingFailed(const QString& error);

private:
    void buildSession();
    void teardownSession();
    void refreshDevices();

    QMediaCaptureSession m_session;
    QCamera* m_camera = nullptr;
    QAudioInput* m_audioInput = nullptr;
    QMediaRecorder* m_recorder = nullptr;
    QVideoSink* m_videoSink = nullptr;

    QList<QCameraDevice> m_devices;
    int m_deviceIndex = 0;

    QString m_outputFile;
    bool m_isPreviewing = false;
    bool m_isRecording = false;
    bool m_isPaused = false;
    bool m_cancelling = false;
    qint64 m_elapsedMs = 0;
};
