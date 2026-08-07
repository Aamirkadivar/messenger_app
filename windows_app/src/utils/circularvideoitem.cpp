#include "circularvideoitem.h"

#include <QPainter>
#include <QPainterPath>
#include <QDebug>

CircularVideoItem::CircularVideoItem(QQuickItem* parent)
    : QQuickPaintedItem(parent), m_sink(new QVideoSink(this)) {

    // QueuedConnection is essential, not defensive. QVideoSink delivers frames
    // on the media backend's own thread; converting and calling update() from
    // there does not schedule a repaint, so the item stays blank however many
    // frames arrive. Queuing hops onto the GUI thread, where update() means
    // something. QVideoFrame is a registered metatype, so it copies safely.
    connect(m_sink, &QVideoSink::videoFrameChanged, this,
            [this](const QVideoFrame& frame) {
        if (!frame.isValid()) return;
        // toImage() is a CPU conversion, far too expensive for full-screen
        // video - but this draws at ~260px for the recorder and ~190px for a
        // bubble, so the cost is small, and it buys a genuinely antialiased
        // circle that no available QML effect can produce here.
        QImage img = frame.toImage();
        if (img.isNull()) return;
        m_image = img;
        if (m_frameCount++ == 0) {
            qDebug() << "[CircularVideo] first frame" << img.size() << "item" << width() << "x" << height();
        }
        update();
    }, Qt::QueuedConnection);
}

void CircularVideoItem::paint(QPainter* painter) {
    if (m_image.isNull() || width() <= 0 || height() <= 0) return;

    const qreal diameter = qMin(width(), height());
    const QRectF circle((width() - diameter) / 2.0, (height() - diameter) / 2.0,
                        diameter, diameter);

    QPainterPath clip;
    clip.addEllipse(circle);

    painter->setRenderHint(QPainter::Antialiasing, true);
    painter->setRenderHint(QPainter::SmoothPixmapTransform, true);
    painter->setClipPath(clip);

    // Centre-crop: scale by the LARGER ratio so the shorter edge fills the
    // circle and the overflow is cropped, rather than leaving bars.
    const QSizeF img(m_image.size());
    if (img.width() <= 0 || img.height() <= 0) return;
    const qreal scale = qMax(diameter / img.width(), diameter / img.height());
    const QSizeF target(img.width() * scale, img.height() * scale);
    const QRectF dest(circle.center().x() - target.width() / 2.0,
                      circle.center().y() - target.height() / 2.0,
                      target.width(), target.height());

    painter->drawImage(dest, m_image);
}
