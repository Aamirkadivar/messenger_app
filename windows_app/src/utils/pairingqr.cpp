#include "pairingqr.h"

#include <QDir>
#include <QFile>
#include <QtGui/qrgb.h>
#include "../../third_party/qrcodegen/qrcodegen.hpp"

QImage PairingQr::render(const QString& content, int modulePx, int marginModules) {
    if (content.isEmpty() || modulePx < 1) return {};
    try {
        const std::string text = content.toStdString();
        const qrcodegen::QrCode qr = qrcodegen::QrCode::encodeText(
            text.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
        const int size = qr.getSize();
        const int dim = (size + marginModules * 2) * modulePx;
        QImage img(dim, dim, QImage::Format_RGB32);
        img.fill(Qt::white);
        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                if (!qr.getModule(x, y)) continue;
                const int px = (x + marginModules) * modulePx;
                const int py = (y + marginModules) * modulePx;
                for (int dy = 0; dy < modulePx; ++dy) {
                    for (int dx = 0; dx < modulePx; ++dx) {
                        img.setPixel(px + dx, py + dy, qRgb(0, 0, 0));
                    }
                }
            }
        }
        return img;
    } catch (...) {
        return {};
    }
}

bool PairingQr::savePng(const QString& content, const QString& path, int modulePx) {
    const QImage img = render(content, modulePx);
    if (img.isNull()) return false;
    return img.save(path, "PNG");
}