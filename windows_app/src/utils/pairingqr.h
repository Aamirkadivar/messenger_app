#pragma once

#include <QImage>
#include <QString>

// Renders a pairing string to a high-contrast QR QImage (Nayuki qrcodegen).
namespace PairingQr {
QImage render(const QString& content, int modulePx = 8, int marginModules = 2);
bool savePng(const QString& content, const QString& path, int modulePx = 8);
}