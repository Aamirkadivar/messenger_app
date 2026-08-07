#pragma once

#include <QImage>
#include <QQuickPaintedItem>
#include <QVideoFrame>
#include <QVideoSink>

// A video surface that is genuinely circular.
//
// None of the usual QML ways to round a video work in this app:
//  - QtQuick.Effects (OpacityMask) renders nothing in this Qt/MinGW/GPU combo.
//  - Rectangle.clip ignores radius; it clips to the bounding box.
//  - Painting a "mask" over a square VideoOutput only works if the mask colour
//    is opaque. Over a translucent scrim it occludes nothing, which is exactly
//    why the first attempt showed a circle sitting inside a visible square.
//
// QPainter, by contrast, clips to an arbitrary path with antialiasing, so this
// takes frames from a QVideoSink and paints them through an ellipse clip. The
// frame is centre-cropped to fill, so a 4:3 or 16:9 source fills the circle
// rather than letterboxing - which is also what lets either client record
// whatever aspect its camera happens to produce.
//
// Exposing videoSink as a property means the same item serves as the output
// for a C++ QMediaCaptureSession and for a QML MediaPlayer.
class CircularVideoItem : public QQuickPaintedItem {
    Q_OBJECT
    Q_PROPERTY(QVideoSink* videoSink READ videoSink CONSTANT)

public:
    explicit CircularVideoItem(QQuickItem* parent = nullptr);

    QVideoSink* videoSink() const { return m_sink; }
    void paint(QPainter* painter) override;

private:
    QVideoSink* m_sink = nullptr;
    QImage m_image;
    int m_frameCount = 0;
};
