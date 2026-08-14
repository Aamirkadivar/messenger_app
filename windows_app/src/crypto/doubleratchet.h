#ifndef DOUBLERATCHET_H
#define DOUBLERATCHET_H

#include <QByteArray>
#include <QHash>
#include <QMap>
#include <QString>
#include <cstdint>

class DoubleRatchet {
public:
    struct State {
        QByteArray dhsSk;
        QByteArray dhsPk;
        QByteArray dhr;
        QByteArray rk;
        QByteArray cks;
        QByteArray ckr;
        quint32 ns = 0;
        quint32 nr = 0;
        quint32 pn = 0;
        quint64 seq = 0;
        QHash<QString, QByteArray> skipped;
        QString toJson() const;
        static bool fromJson(const QString& json, State& out);
        static QString preferJson(const QString& a, const QString& b);
        static QMap<QString, QString> mergeMaps(const QMap<QString, QString>& local,
                                                const QMap<QString, QString>& remote);
    };

    static bool initAlice(const QByteArray& theirIk, State& out);
    static bool initBob(const QByteArray& myIkPk, const QByteArray& myIkSk, State& out);
    static QByteArray encrypt(State& st, const QByteArray& plain);
    static QByteArray decrypt(State& st, const QByteArray& payload);
};

#endif
