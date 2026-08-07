#pragma once

#include <QObject>
#include <QString>
#include <QMediaPlayer>
#include <QVideoSink>
#include <QTimer>

// Grabs the first frame of a video file and writes it out as a square JPEG.
//
// Qt has no one-call frame extractor (nothing equivalent to Android's
// MediaMetadataRetriever), so this drives a muted QMediaPlayer at the file and
// takes the first valid frame its QVideoSink produces. Deliberately
// self-contained and single-shot: it deletes itself once it has answered, so
// callers can fire and forget.
//
// This is what gives a round video message a poster frame. Without one the
// recipient stares at an empty circle until the whole video has downloaded.
class VideoThumbnailer : public QObject {
    Q_OBJECT

public:
    explicit VideoThumbnailer(QObject* parent = nullptr);

    // Emits ready() with a JPEG path, or failed() - exactly one of the two,
    // exactly once. Deletes itself afterwards.
    void grab(const QString& videoPath);

signals:
    void ready(const QString& jpegPath);
    void failed();

private:
    void finishWithFailure();

    QMediaPlayer* m_player = nullptr;
    QVideoSink* m_sink = nullptr;
    QTimer m_timeout;
    bool m_done = false;
};
