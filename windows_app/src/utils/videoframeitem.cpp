#include "videoframeitem.h"

#include <QPainter>
#include <QPainterPath>

VideoFrameItem::VideoFrameItem(QQuickItem* parent) : QQuickPaintedItem(parent) {}

void VideoFrameItem::setMirror(bool mirror) {
    if (m_mirror == mirror) return;
    m_mirror = mirror;
    emit mirrorChanged();
    update();
}

void VideoFrameItem::setRadius(qreal radius) {
    if (qFuzzyCompare(m_radius, radius)) return;
    m_radius = radius;
    emit radiusChanged();
    update();
}

void VideoFrameItem::present(const QVariant& frame) {
    const QImage img = frame.value<QImage>();
    const bool had = hasFrame();
    m_image = img;
    if (had != hasFrame()) emit frameChanged();
    update();
}

void VideoFrameItem::clear() {
    if (m_image.isNull()) return;
    m_image = QImage();
    emit frameChanged();
    update();
}

void VideoFrameItem::paint(QPainter* painter) {
    if (m_image.isNull() || width() <= 0 || height() <= 0) return;

    painter->setRenderHint(QPainter::Antialiasing, true);
    painter->setRenderHint(QPainter::SmoothPixmapTransform, true);

    if (m_radius > 0) {
        QPainterPath clip;
        clip.addRoundedRect(QRectF(0, 0, width(), height()), m_radius, m_radius);
        painter->setClipPath(clip);
    }

    // Centre-crop: scale by the larger ratio so the frame fills the item and
    // the overflow is cropped, rather than leaving letterbox bars.
    const QSizeF img(m_image.size());
    const qreal scale = qMax(width() / img.width(), height() / img.height());
    const QSizeF target(img.width() * scale, img.height() * scale);
    QRectF dest(width() / 2.0 - target.width() / 2.0,
                height() / 2.0 - target.height() / 2.0,
                target.width(), target.height());

    if (m_mirror) {
        painter->save();
        painter->translate(width(), 0);
        painter->scale(-1, 1);
        dest.moveLeft(width() - dest.right());
        painter->drawImage(dest, m_image);
        painter->restore();
    } else {
        painter->drawImage(dest, m_image);
    }
}
