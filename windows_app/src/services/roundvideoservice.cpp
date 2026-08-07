#include "roundvideoservice.h"

#include <QDebug>
#include <QDir>
#include <QFile>
#include <QMediaDevices>
#include <QMediaFormat>
#include <QStandardPaths>
#include <QUrl>
#include <QUuid>

namespace {
// Small enough that a 60s note stays well inside the server's cap, and larger
// than the circle is ever drawn at. Matches the Android output budget.
constexpr int kVideoBitRate = 1'000'000;
constexpr int kAudioBitRate = 64'000;
constexpr int kFrameRate = 30;
}

RoundVideoService::RoundVideoService(QObject* parent)
    : QObject(parent) {
    refreshDevices();

    // Hot-plugging a webcam mid-session is common on a desktop, and a stale
    // device list would leave the camera picker wrong until restart.
    auto* devices = new QMediaDevices(this);
    connect(devices, &QMediaDevices::videoInputsChanged, this, [this]() {
        refreshDevices();
        emit camerasChanged();
    });
}

RoundVideoService::~RoundVideoService() {
    teardownSession();
}

void RoundVideoService::refreshDevices() {
    m_devices = QMediaDevices::videoInputs();
    if (m_deviceIndex >= m_devices.size()) m_deviceIndex = 0;
}

QString RoundVideoService::cameraName() const {
    if (m_deviceIndex < 0 || m_deviceIndex >= m_devices.size()) return QString();
    return m_devices.at(m_deviceIndex).description();
}

void RoundVideoService::bindVideoOutput(QVideoSink* sink) {
    m_videoSink = sink;
    m_session.setVideoSink(sink);
}

bool RoundVideoService::startPreview() {
    refreshDevices();
    if (m_devices.isEmpty()) {
        emit recordingFailed(QStringLiteral("No camera found"));
        return false;
    }
    if (m_isPreviewing) return true;

    buildSession();
    if (!m_camera) return false;

    m_camera->start();
    m_isPreviewing = true;
    emit previewStateChanged();
    emit camerasChanged();
    return true;
}

void RoundVideoService::stopPreview() {
    if (m_isRecording) cancelRecording();
    teardownSession();
    m_isPreviewing = false;
    m_elapsedMs = 0;
    emit previewStateChanged();
    emit elapsedChanged();
}

void RoundVideoService::buildSession() {
    teardownSession();

    m_camera = new QCamera(m_devices.at(m_deviceIndex), this);
    m_audioInput = new QAudioInput(this);
    m_recorder = new QMediaRecorder(this);

    m_session.setCamera(m_camera);
    m_session.setAudioInput(m_audioInput);
    m_session.setRecorder(m_recorder);
    if (m_videoSink) m_session.setVideoSink(m_videoSink);

    QMediaFormat format;
    format.setFileFormat(QMediaFormat::MPEG4);
    format.setVideoCodec(QMediaFormat::VideoCodec::H264);
    format.setAudioCodec(QMediaFormat::AudioCodec::AAC);
    m_recorder->setMediaFormat(format);
    m_recorder->setVideoBitRate(kVideoBitRate);
    m_recorder->setAudioBitRate(kAudioBitRate);
    m_recorder->setVideoFrameRate(kFrameRate);
    m_recorder->setQuality(QMediaRecorder::NormalQuality);

    connect(m_recorder, &QMediaRecorder::durationChanged, this, [this](qint64 duration) {
        m_elapsedMs = duration;
        emit elapsedChanged();
        if (duration >= kMaxDurationMs) stopRecording();
    });

    // StoppedState is the only point at which the container has actually been
    // written. Reading the file before this yields a truncated MP4 with no
    // moov atom, which no player will open - the same trap the Android client
    // hit by treating stop() as synchronous.
    connect(m_recorder, &QMediaRecorder::recorderStateChanged, this,
            [this](QMediaRecorder::RecorderState state) {
        if (state != QMediaRecorder::StoppedState) return;

        const qint64 finalDuration = m_elapsedMs;
        const bool wasCancelling = m_cancelling;

        m_isRecording = false;
        m_isPaused = false;
        m_elapsedMs = 0;
        emit recordingStateChanged();
        emit elapsedChanged();

        if (wasCancelling) {
            m_cancelling = false;
            QFile::remove(m_outputFile);
            m_outputFile.clear();
            return;
        }
        if (finalDuration < kMinDurationMs) {
            QFile::remove(m_outputFile);
            m_outputFile.clear();
            emit recordingTooShort();
            return;
        }
        const QString finished = m_outputFile;
        // Ownership passes to the caller: clearing this first means a later
        // cancel/teardown cannot delete a recording already on its way out.
        m_outputFile.clear();
        emit recordingFinished(finished, finalDuration);
    });

    connect(m_recorder, &QMediaRecorder::errorOccurred, this,
            [this](QMediaRecorder::Error, const QString& errorString) {
        qWarning() << "[RoundVideoService] Recorder error:" << errorString;
        m_isRecording = false;
        m_isPaused = false;
        m_elapsedMs = 0;
        emit recordingStateChanged();
        emit elapsedChanged();
        emit recordingFailed(errorString);
    });
}

void RoundVideoService::teardownSession() {
    if (m_camera) m_camera->stop();
    m_session.setRecorder(nullptr);
    m_session.setCamera(nullptr);
    m_session.setAudioInput(nullptr);

    delete m_recorder;  m_recorder = nullptr;
    delete m_camera;    m_camera = nullptr;
    delete m_audioInput; m_audioInput = nullptr;
}

bool RoundVideoService::startRecording() {
    if (!m_isPreviewing || !m_recorder) return false;
    if (m_isRecording) return false;

    const QString dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    m_outputFile = QDir(dir).filePath(
        QStringLiteral("round_%1.mp4").arg(QUuid::createUuid().toString(QUuid::WithoutBraces)));

    m_recorder->setOutputLocation(QUrl::fromLocalFile(m_outputFile));
    m_cancelling = false;
    m_recorder->record();

    m_isRecording = true;
    m_isPaused = false;
    m_elapsedMs = 0;
    emit recordingStateChanged();
    emit elapsedChanged();
    return true;
}

void RoundVideoService::stopRecording() {
    if (!m_isRecording || !m_recorder) return;
    m_recorder->stop();  // recordingFinished follows from recorderStateChanged
}

void RoundVideoService::cancelRecording() {
    if (!m_isRecording || !m_recorder) return;
    m_cancelling = true;
    m_recorder->stop();
}

void RoundVideoService::togglePause() {
    if (!m_isRecording || !m_recorder) return;
    if (m_isPaused) {
        m_recorder->record();
        m_isPaused = false;
    } else {
        m_recorder->pause();
        m_isPaused = true;
    }
    emit recordingStateChanged();
}

void RoundVideoService::switchCamera() {
    if (m_devices.size() < 2) return;
    // Rebuilding the session drops the recorder, so an in-flight take cannot
    // survive the swap - stop it cleanly rather than losing it silently.
    if (m_isRecording) cancelRecording();

    m_deviceIndex = (m_deviceIndex + 1) % m_devices.size();
    const bool wasPreviewing = m_isPreviewing;
    teardownSession();
    if (wasPreviewing) {
        buildSession();
        if (m_camera) m_camera->start();
    }
    emit camerasChanged();
}
