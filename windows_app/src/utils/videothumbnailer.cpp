#include "videothumbnailer.h"

#include <QDebug>
#include <QFileInfo>
#include <QImage>
#include <QUrl>
#include <QVideoFrame>

namespace {
// Matches the Android poster size; big enough for a 190px circle on a
// high-DPI display, small enough to upload almost instantly.
constexpr int kThumbSize = 384;
}

VideoThumbnailer::VideoThumbnailer(QObject* parent)
    : QObject(parent), m_player(new QMediaPlayer(this)), m_sink(new QVideoSink(this)) {
    m_player->setVideoSink(m_sink);
    // No QAudioOutput is attached at all, so decoding the file to reach its
    // first frame stays silent.

    m_timeout.setSingleShot(true);
    m_timeout.setInterval(4000);
    connect(&m_timeout, &QTimer::timeout, this, &VideoThumbnailer::finishWithFailure);
}

void VideoThumbnailer::grab(const QString& videoPath) {
    connect(m_sink, &QVideoSink::videoFrameChanged, this,
            [this, videoPath](const QVideoFrame& frame) {
        if (m_done || !frame.isValid()) return;

        QImage image = frame.toImage();
        if (image.isNull()) return;

        m_done = true;
        m_timeout.stop();
        m_player->stop();

        // Centre-crop to a square first: the bubble is a circle, so anything
        // outside the middle square is never seen anyway, and cropping here
        // keeps the poster's framing identical to the video's.
        const int side = qMin(image.width(), image.height());
        const QImage square = image.copy((image.width() - side) / 2,
                                          (image.height() - side) / 2,
                                          side, side)
                                   .scaled(kThumbSize, kThumbSize,
                                           Qt::KeepAspectRatio, Qt::SmoothTransformation);

        const QString jpegPath = QFileInfo(videoPath).absolutePath() + "/" +
                                 QFileInfo(videoPath).completeBaseName() + "_thumb.jpg";
        if (!square.save(jpegPath, "JPEG", 82)) {
            qWarning() << "[VideoThumbnailer] Could not write" << jpegPath;
            emit failed();
            deleteLater();
            return;
        }

        emit ready(jpegPath);
        deleteLater();
    }, Qt::QueuedConnection);

    connect(m_player, &QMediaPlayer::errorOccurred, this,
            [this](QMediaPlayer::Error, const QString& msg) {
        qWarning() << "[VideoThumbnailer] player error:" << msg;
        finishWithFailure();
    });

    m_player->setSource(QUrl::fromLocalFile(videoPath));
    // play(), not pause(): several backends do not decode anything until
    // playback actually starts, so pausing at position 0 can yield no frame.
    m_player->play();
    m_timeout.start();
}

void VideoThumbnailer::finishWithFailure() {
    if (m_done) return;
    m_done = true;
    m_timeout.stop();
    if (m_player) m_player->stop();
    emit failed();
    deleteLater();
}
