#include "voiceservice.h"
#include <QDir>
#include <QStandardPaths>
#include <QUuid>
#include <QFile>
#include <QMediaDevices>
#include <QAudioDevice>
#include <QMediaFormat>
#include <QDebug>

VoiceService::VoiceService(QObject* parent) : QObject(parent) {
    m_player = new QMediaPlayer(this);
    m_audioOutput = new QAudioOutput(this);
    m_player->setAudioOutput(m_audioOutput);

    connect(m_player, &QMediaPlayer::positionChanged, this, [this](qint64 pos) {
        m_playbackPositionMs = pos;
        emit playbackPositionChanged();
    });
    connect(m_player, &QMediaPlayer::durationChanged, this, [this](qint64 dur) {
        m_playbackDurationMs = dur;
        emit playbackDurationChanged();
    });
    connect(m_player, &QMediaPlayer::playbackStateChanged, this, [this](QMediaPlayer::PlaybackState state) {
        bool nowPlaying = (state == QMediaPlayer::PlayingState);
        if (state == QMediaPlayer::StoppedState) {
            // Reached the end (or was stopped) - reset so the bubble shows a
            // fresh "play" affordance rather than a paused-at-the-end one.
            m_playingMessageId.clear();
            m_playbackPositionMs = 0;
            emit playbackPositionChanged();
        }
        if (nowPlaying != m_isPlaying) {
            m_isPlaying = nowPlaying;
            emit playbackStateChanged();
        }
    });
    connect(m_player, &QMediaPlayer::errorOccurred, this,
            [this](QMediaPlayer::Error, const QString& errorString) {
        emit playbackFailed(errorString);
    });
}

bool VoiceService::startRecording() {
    if (m_isRecording) return false;

    const QAudioDevice inputDevice = QMediaDevices::defaultAudioInput();
    if (inputDevice.isNull()) {
        emit recordingFailed(QStringLiteral("No microphone is available"));
        return false;
    }

    QString dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    QDir().mkpath(dir);
    m_outputFile = dir + QStringLiteral("/voice_%1.m4a").arg(QUuid::createUuid().toString(QUuid::Id128));

    m_captureSession = new QMediaCaptureSession(this);
    m_audioInput = new QAudioInput(inputDevice, this);
    m_captureSession->setAudioInput(m_audioInput);

    m_recorder = new QMediaRecorder(this);
    m_captureSession->setRecorder(m_recorder);

    QMediaFormat format(QMediaFormat::Mpeg4Audio);
    format.setAudioCodec(QMediaFormat::AudioCodec::AAC);
    m_recorder->setMediaFormat(format);
    // Voice, not music: keeps notes small enough that encrypting and
    // uploading them stays quick.
    m_recorder->setAudioBitRate(32000);
    m_recorder->setAudioChannelCount(1);
    m_recorder->setAudioSampleRate(44100);
    m_recorder->setOutputLocation(QUrl::fromLocalFile(m_outputFile));

    connect(m_recorder, &QMediaRecorder::durationChanged, this, [this](qint64 duration) {
        m_recordingElapsedMs = duration;
        emit recordingElapsedChanged();
        if (duration >= kMaxDurationMs) stopRecording();
    });

    connect(m_recorder, &QMediaRecorder::recorderStateChanged, this,
            [this](QMediaRecorder::RecorderState state) {
        if (state != QMediaRecorder::StoppedState) return;

        qint64 finalDuration = m_recordingElapsedMs;
        bool wasCancelling = m_cancelling;
        teardownRecorder(wasCancelling);

        m_isRecording = false;
        m_recordingElapsedMs = 0;
        emit recordingStateChanged();
        emit recordingElapsedChanged();

        if (wasCancelling) {
            m_cancelling = false;
            return;
        }
        if (finalDuration < kMinDurationMs) {
            QFile::remove(m_outputFile);
            emit recordingTooShort();
            return;
        }
        emit recordingFinished(m_outputFile, finalDuration);
    });

    connect(m_recorder, &QMediaRecorder::errorOccurred, this,
            [this](QMediaRecorder::Error, const QString& errorString) {
        qWarning() << "[VoiceService] Recorder error:" << errorString;
        emit recordingFailed(errorString);
        teardownRecorder(true);
        m_isRecording = false;
        m_recordingElapsedMs = 0;
        emit recordingStateChanged();
        emit recordingElapsedChanged();
    });

    m_recorder->record();
    m_isRecording = true;
    m_recordingElapsedMs = 0;
    emit recordingStateChanged();
    emit recordingElapsedChanged();
    return true;
}

void VoiceService::cancelRecording() {
    if (!m_isRecording || !m_recorder) return;
    m_cancelling = true;
    m_recorder->stop();
}

void VoiceService::stopRecording() {
    if (!m_isRecording || !m_recorder) return;
    m_recorder->stop();
}

void VoiceService::teardownRecorder(bool deleteFile) {
    if (deleteFile && !m_outputFile.isEmpty()) {
        QFile::remove(m_outputFile);
    }
    if (m_captureSession) m_captureSession->setRecorder(nullptr);
    if (m_recorder) { m_recorder->deleteLater(); m_recorder = nullptr; }
    if (m_audioInput) { m_audioInput->deleteLater(); m_audioInput = nullptr; }
    if (m_captureSession) { m_captureSession->deleteLater(); m_captureSession = nullptr; }
}

void VoiceService::togglePlayback(const QString& messageId, const QString& localFilePath) {
    if (m_playingMessageId == messageId && m_player->playbackState() != QMediaPlayer::StoppedState) {
        if (m_isPlaying) {
            m_player->pause();
        } else {
            m_player->play();
        }
        return;
    }

    m_player->stop();
    m_playingMessageId = messageId;
    m_player->setSource(QUrl::fromLocalFile(localFilePath));
    m_player->play();
    emit playbackStateChanged();
}

void VoiceService::stopPlayback() {
    m_player->stop();
    m_playingMessageId.clear();
    emit playbackStateChanged();
}
