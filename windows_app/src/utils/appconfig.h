#pragma once

#include <QObject>
#include <QString>
#include "config.h"

// QML-facing wrapper around Config's static helpers - Config itself has no
// QObject base (it's header-only static methods), so it can't be exposed as a
// context property directly. Kept separate from any of the *Service classes
// since URL resolution isn't really a chat/group/voice concern.
class AppConfig : public QObject {
    Q_OBJECT
public:
    explicit AppConfig(QObject* parent = nullptr) : QObject(parent) {}

    Q_INVOKABLE QString serverOrigin() const { return Config::serverOrigin(); }
    Q_INVOKABLE QString resolveUrl(const QString& path) const { return Config::resolveServerUrl(path); }
};
