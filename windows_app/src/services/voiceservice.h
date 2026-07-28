#pragma once

#include <QObject>
#include <QString>
#include <QUrl>
#include <QMediaCaptureSession>
#include <QAudioInput>
#include <QMediaRecorder>
#include <QMediaPlayer>
#include <QAudioOutput>

// Local audio only: recording a voice note to a temp file, and playing back an
// already-decrypted local file. No networking and no crypto here - ChatService
// owns the upload/download/encrypt/decrypt path, matching the same split used
// on the Android client (VoiceRecorder/VoicePlayer vs VoiceRepository).
//
// One caveat versus the Android version: Qt6 Multimedia has no supported way
// to read live input levels off a QMediaRecorder (QAudioProbe, which provided
// this in Qt5, was removed). The recording indicator here shows an elapsed
// timer only, not an audio-reactive level meter.
class VoiceService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isRecording READ isRecording NOTIFY recordingStateChanged)
    Q_PROPERTY(qint64 recordingElapsedMs READ recordingElapsedMs NOTIFY recordingElapsedChanged)
    Q_PROPERTY(QString playingMessageId READ playingMessageId NOTIFY playbackStateChanged)
    Q_PROPERTY(bool isPlaying READ isPlaying NOTIFY playbackStateChanged)
    Q_PROPERTY(qint64 playbackPositionMs READ playbackPositionMs NOTIFY playbackPositionChanged)
    Q_PROPERTY(qint64 playbackDurationMs READ playbackDurationMs NOTIFY playbackDurationChanged)

public:
    explicit VoiceService(QObject* parent = nullptr);

    static constexpr qint64 kMinDurationMs = 800;
    static constexpr qint64 kMaxDurationMs = 5 * 60 * 1000;

    bool isRecording() const { return m_isRecording; }
    qint64 recordingElapsedMs() const { return m_recordingElapsedMs; }
    const QString& playingMessageId() const { return m_playingMessageId; }
    bool isPlaying() const { return m_isPlaying; }
    qint64 playbackPositionMs() const { return m_playbackPositionMs; }
    qint64 playbackDurationMs() const { return m_playbackDurationMs; }

    // Starts recording to a fresh temp file. Returns false if the input
    // device couldn't be opened (e.g. no microphone, or access denied).
    Q_INVOKABLE bool startRecording();
    // Discards the in-progress recording; the temp file is deleted.
    Q_INVOKABLE void cancelRecording();
    // Stops recording. Fires recordingFinished (with the temp file path and
    // duration) once the file is safely flushed, or recordingTooShort if the
    // whole take was under kMinDurationMs (an accidental tap, most likely).
    Q_INVOKABLE void stopRecording();

    // Plays a decrypted local file for a given message id, or toggles
    // play/pause if that message is already the one loaded.
    Q_INVOKABLE void togglePlayback(const QString& messageId, const QString& localFilePath);
    Q_INVOKABLE void stopPlayback();

signals:
    void recordingStateChanged();
    void recordingElapsedChanged();
    void recordingFinished(const QString& filePath, qint64 durationMs);
    void recordingTooShort();
    void recordingFailed(const QString& error);

    void playbackStateChanged();
    void playbackPositionChanged();
    void playbackDurationChanged();
    void playbackFailed(const QString& error);

private:
    void teardownRecorder(bool deleteFile);

    QMediaCaptureSession* m_captureSession = nullptr;
    QAudioInput* m_audioInput = nullptr;
    QMediaRecorder* m_recorder = nullptr;
    QString m_outputFile;
    bool m_isRecording = false;
    bool m_cancelling = false;
    bool m_stopping = false;
    qint64 m_recordingElapsedMs = 0;

    QMediaPlayer* m_player = nullptr;
    QAudioOutput* m_audioOutput = nullptr;
    QString m_playingMessageId;
    bool m_isPlaying = false;
    qint64 m_playbackPositionMs = 0;
    qint64 m_playbackDurationMs = 0;
};
