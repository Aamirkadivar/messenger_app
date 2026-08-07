#pragma once

#include <QImage>
#include <QQuickPaintedItem>
#include <QVariant>

// A plain rectangular video surface fed QImage frames from C++ (the call's
// decoded remote video or the local camera preview).
//
// Same rationale as CircularVideoItem: QML VideoOutput and QtQuick.Effects
// are unusable in this Qt/MinGW/GPU combination, and the frames here come
// from our own VP8 decoder as QImages anyway - there is no QMediaPlayer in
// the pipeline to bind a VideoOutput to. QQuickPaintedItem with a
// centre-crop draw is the one approach already proven to work in this app.
//
// Frames arrive via present(), called from QML in response to CallService's
// frame signals (QImage rides through the meta-object system as a QVariant).
class VideoFrameItem : public QQuickPaintedItem {
    Q_OBJECT
    Q_PROPERTY(bool hasFrame READ hasFrame NOTIFY frameChanged)
    // Mirror horizontally - used for the local self-view, which people expect
    // to behave like a mirror.
    Q_PROPERTY(bool mirror READ mirror WRITE setMirror NOTIFY mirrorChanged)
    Q_PROPERTY(qreal radius READ radius WRITE setRadius NOTIFY radiusChanged)

public:
    explicit VideoFrameItem(QQuickItem* parent = nullptr);

    bool hasFrame() const { return !m_image.isNull(); }
    bool mirror() const { return m_mirror; }
    void setMirror(bool mirror);
    qreal radius() const { return m_radius; }
    void setRadius(qreal radius);

    Q_INVOKABLE void present(const QVariant& frame);
    Q_INVOKABLE void clear();

    void paint(QPainter* painter) override;

signals:
    void frameChanged();
    void mirrorChanged();
    void radiusChanged();

private:
    QImage m_image;
    bool m_mirror = false;
    qreal m_radius = 0;
};
