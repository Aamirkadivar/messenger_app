#include "doubleratchet.h"
#include "encryption.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QMap>
#include <cstring>
#include <sodium.h>

static const char kRootInfo[] = "messenger-dr-root-v1";
static const int kMaxSkip = 200;

static QByteArray hmacSha256(const QByteArray& key, const QByteArray& data) {
    unsigned char out[crypto_auth_hmacsha256_BYTES];
    crypto_auth_hmacsha256_state st;
    crypto_auth_hmacsha256_init(&st, reinterpret_cast<const unsigned char*>(key.constData()),
                                static_cast<size_t>(key.size()));
    crypto_auth_hmacsha256_update(&st, reinterpret_cast<const unsigned char*>(data.constData()),
                                  static_cast<unsigned long long>(data.size()));
    crypto_auth_hmacsha256_final(&st, out);
    return QByteArray(reinterpret_cast<const char*>(out), crypto_auth_hmacsha256_BYTES);
}

static QByteArray hkdf(const QByteArray& ikm, QByteArray salt, const QByteArray& info, int n) {
    if (salt.isEmpty()) salt = QByteArray(32, '\0');
    const QByteArray prk = hmacSha256(salt, ikm);
    QByteArray t;
    QByteArray out;
    int i = 1;
    while (out.size() < n) {
        QByteArray block = t + info + QByteArray(1, static_cast<char>(i));
        t = hmacSha256(prk, block);
        out += t;
        i++;
    }
    return out.left(n);
}

static bool kdfRk(const QByteArray& rk, const QByteArray& dh, QByteArray& newRk, QByteArray& ck) {
    QByteArray salt = rk.size() == 32 ? rk : QByteArray(32, '\0');
    const QByteArray out = hkdf(dh, salt, QByteArray(kRootInfo), 64);
    if (out.size() != 64) return false;
    newRk = out.left(32);
    ck = out.mid(32, 32);
    return true;
}

static void kdfCk(const QByteArray& ck, QByteArray& next, QByteArray& mk) {
    mk = hmacSha256(ck, QByteArray(1, '\x01'));
    next = hmacSha256(ck, QByteArray(1, '\x02'));
}

static QByteArray x25519(const QByteArray& sk, const QByteArray& pk) {
    if (sk.size() != 32 || pk.size() != 32) return {};
    QByteArray q(32, '\0');
    if (crypto_scalarmult(reinterpret_cast<unsigned char*>(q.data()),
                          reinterpret_cast<const unsigned char*>(sk.constData()),
                          reinterpret_cast<const unsigned char*>(pk.constData())) != 0) {
        return {};
    }
    return q;
}

static bool newKp(QByteArray& pk, QByteArray& sk) {
    pk = QByteArray(crypto_box_PUBLICKEYBYTES, '\0');
    sk = QByteArray(crypto_box_SECRETKEYBYTES, '\0');
    return crypto_box_keypair(reinterpret_cast<unsigned char*>(pk.data()),
                              reinterpret_cast<unsigned char*>(sk.data())) == 0;
}

static QString skipId(const QByteArray& dh, quint32 n) {
    return QString::fromLatin1(dh.toHex()) + QLatin1Char(':') + QString::number(n);
}

static void putBe32(QByteArray& b, int off, quint32 v) {
    b[off] = char((v >> 24) & 0xff);
    b[off + 1] = char((v >> 16) & 0xff);
    b[off + 2] = char((v >> 8) & 0xff);
    b[off + 3] = char(v & 0xff);
}

static quint32 getBe32(const QByteArray& b, int off) {
    return (quint32(uchar(b[off])) << 24) | (quint32(uchar(b[off + 1])) << 16) |
           (quint32(uchar(b[off + 2])) << 8) | quint32(uchar(b[off + 3]));
}

bool DoubleRatchet::initAlice(const QByteArray& theirIk, State& out) {
    if (theirIk.size() != 32) return false;
    QByteArray pk, sk;
    if (!newKp(pk, sk)) return false;
    const QByteArray dh = x25519(sk, theirIk);
    if (dh.isEmpty()) return false;
    QByteArray rk, cks;
    if (!kdfRk(QByteArray(32, '\0'), dh, rk, cks)) return false;
    out = State{};
    out.dhsSk = sk;
    out.dhsPk = pk;
    out.dhr = theirIk;
    out.rk = rk;
    out.cks = cks;
    return true;
}

bool DoubleRatchet::initBob(const QByteArray& myIkPk, const QByteArray& myIkSk, State& out) {
    if (myIkPk.size() != 32 || myIkSk.size() != 32) return false;
    out = State{};
    out.dhsSk = myIkSk;
    out.dhsPk = myIkPk;
    out.rk = QByteArray(32, '\0');
    return true;
}

static bool skipMessageKeys(DoubleRatchet::State& st, quint32 until) {
    if (st.ckr.size() != 32 || st.dhr.size() != 32) return until == 0;
    if (until <= st.nr) return true;
    if (until - st.nr > quint32(kMaxSkip)) return false;
    while (st.nr < until) {
        QByteArray next, mk;
        kdfCk(st.ckr, next, mk);
        st.ckr = next;
        st.skipped.insert(skipId(st.dhr, st.nr), mk);
        st.nr++;
    }
    return true;
}

static bool dhRatchet(DoubleRatchet::State& st, const QByteArray& theirDh) {
    st.pn = st.ns;
    st.ns = 0;
    st.nr = 0;
    st.dhr = theirDh;
    QByteArray dh = x25519(st.dhsSk, st.dhr);
    if (dh.isEmpty()) return false;
    QByteArray rk, ckr;
    if (!kdfRk(st.rk, dh, rk, ckr)) return false;
    st.rk = rk;
    st.ckr = ckr;
    QByteArray pk, sk;
    if (!newKp(pk, sk)) return false;
    st.dhsPk = pk;
    st.dhsSk = sk;
    QByteArray dh2 = x25519(st.dhsSk, st.dhr);
    if (dh2.isEmpty()) return false;
    QByteArray cks;
    if (!kdfRk(st.rk, dh2, rk, cks)) return false;
    st.rk = rk;
    st.cks = cks;
    return true;
}

QByteArray DoubleRatchet::encrypt(State& st, const QByteArray& plain) {
    if (st.cks.size() != 32 || st.dhsPk.size() != 32) return {};
    QByteArray next, mk;
    kdfCk(st.cks, next, mk);
    st.cks = next;
    const quint32 n = st.ns;
    st.ns++;
    QByteArray nonce(24, '\0');
    randombytes_buf(nonce.data(), 24);
    QByteArray ct(plain.size() + crypto_secretbox_MACBYTES, '\0');
    if (crypto_secretbox_easy(reinterpret_cast<unsigned char*>(ct.data()),
                              reinterpret_cast<const unsigned char*>(plain.constData()),
                              static_cast<unsigned long long>(plain.size()),
                              reinterpret_cast<const unsigned char*>(nonce.constData()),
                              reinterpret_cast<const unsigned char*>(mk.constData())) != 0) {
        return {};
    }
    QByteArray out(40 + 24 + ct.size(), '\0');
    memcpy(out.data(), st.dhsPk.constData(), 32);
    putBe32(out, 32, n);
    putBe32(out, 36, st.pn);
    memcpy(out.data() + 40, nonce.constData(), 24);
    memcpy(out.data() + 64, ct.constData(), ct.size());
    return out;
}

QByteArray DoubleRatchet::decrypt(State& st, const QByteArray& payload) {
    if (payload.size() < 40 + 24 + crypto_secretbox_MACBYTES) return {};
    const QByteArray dh = payload.left(32);
    const quint32 n = getBe32(payload, 32);
    const quint32 pn = getBe32(payload, 36);
    const QByteArray nonce = payload.mid(40, 24);
    const QByteArray ct = payload.mid(64);
    const QString id = skipId(dh, n);
    if (st.skipped.contains(id)) {
        const QByteArray mk = st.skipped.take(id);
        QByteArray plain(ct.size() - crypto_secretbox_MACBYTES, '\0');
        if (crypto_secretbox_open_easy(reinterpret_cast<unsigned char*>(plain.data()),
                                       reinterpret_cast<const unsigned char*>(ct.constData()),
                                       static_cast<unsigned long long>(ct.size()),
                                       reinterpret_cast<const unsigned char*>(nonce.constData()),
                                       reinterpret_cast<const unsigned char*>(mk.constData())) != 0) {
            return {};
        }
        return plain;
    }
    if (st.dhr.size() != 32 || st.dhr != dh) {
        if (!skipMessageKeys(st, pn) && st.ckr.size() == 32) return {};
        if (!dhRatchet(st, dh)) return {};
    }
    if (!skipMessageKeys(st, n)) return {};
    QByteArray next, mk;
    kdfCk(st.ckr, next, mk);
    st.ckr = next;
    st.nr++;
    QByteArray plain(ct.size() - crypto_secretbox_MACBYTES, '\0');
    if (crypto_secretbox_open_easy(reinterpret_cast<unsigned char*>(plain.data()),
                                   reinterpret_cast<const unsigned char*>(ct.constData()),
                                   static_cast<unsigned long long>(ct.size()),
                                   reinterpret_cast<const unsigned char*>(nonce.constData()),
                                   reinterpret_cast<const unsigned char*>(mk.constData())) != 0) {
        return {};
    }
    return plain;
}

QString DoubleRatchet::State::toJson() const {
    QJsonObject o;
    o.insert(QStringLiteral("dhs_sk"), QString::fromLatin1(dhsSk.toHex()));
    o.insert(QStringLiteral("dhs_pk"), QString::fromLatin1(dhsPk.toHex()));
    o.insert(QStringLiteral("dhr"), QString::fromLatin1(dhr.toHex()));
    o.insert(QStringLiteral("rk"), QString::fromLatin1(rk.toHex()));
    o.insert(QStringLiteral("cks"), QString::fromLatin1(cks.toHex()));
    o.insert(QStringLiteral("ckr"), QString::fromLatin1(ckr.toHex()));
    o.insert(QStringLiteral("ns"), int(ns));
    o.insert(QStringLiteral("nr"), int(nr));
    o.insert(QStringLiteral("pn"), int(pn));
    o.insert(QStringLiteral("seq"), qint64(seq));
    QJsonObject sk;
    for (auto it = skipped.constBegin(); it != skipped.constEnd(); ++it)
        sk.insert(it.key(), QString::fromLatin1(it.value().toHex()));
    o.insert(QStringLiteral("skipped"), sk);
    return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

bool DoubleRatchet::State::fromJson(const QString& json, State& out) {
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
    if (o.isEmpty()) return false;
    out = State{};
    out.dhsSk = QByteArray::fromHex(o.value(QStringLiteral("dhs_sk")).toString().toLatin1());
    out.dhsPk = QByteArray::fromHex(o.value(QStringLiteral("dhs_pk")).toString().toLatin1());
    out.dhr = QByteArray::fromHex(o.value(QStringLiteral("dhr")).toString().toLatin1());
    out.rk = QByteArray::fromHex(o.value(QStringLiteral("rk")).toString().toLatin1());
    out.cks = QByteArray::fromHex(o.value(QStringLiteral("cks")).toString().toLatin1());
    out.ckr = QByteArray::fromHex(o.value(QStringLiteral("ckr")).toString().toLatin1());
    out.ns = quint32(o.value(QStringLiteral("ns")).toInt());
    out.nr = quint32(o.value(QStringLiteral("nr")).toInt());
    out.pn = quint32(o.value(QStringLiteral("pn")).toInt());
    out.seq = quint64(o.value(QStringLiteral("seq")).toVariant().toULongLong());
    const QJsonObject sk = o.value(QStringLiteral("skipped")).toObject();
    for (auto it = sk.begin(); it != sk.end(); ++it)
        out.skipped.insert(it.key(), QByteArray::fromHex(it.value().toString().toLatin1()));
    return out.dhsSk.size() == 32 && out.dhsPk.size() == 32;
}

static quint64 drSessionRank(const QString& json) {
    const QJsonObject o = QJsonDocument::fromJson(json.toUtf8()).object();
    const quint64 seq = quint64(o.value(QStringLiteral("seq")).toVariant().toULongLong());
    const quint64 ns = quint64(o.value(QStringLiteral("ns")).toInt());
    const quint64 nr = quint64(o.value(QStringLiteral("nr")).toInt());
    return seq * 1000000ULL + ns + nr;
}

QString DoubleRatchet::State::preferJson(const QString& a, const QString& b) {
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;
    return drSessionRank(b) > drSessionRank(a) ? b : a;
}

QMap<QString, QString> DoubleRatchet::State::mergeMaps(const QMap<QString, QString>& local,
                                                       const QMap<QString, QString>& remote) {
    QMap<QString, QString> out = local;
    for (auto it = remote.constBegin(); it != remote.constEnd(); ++it)
        out.insert(it.key(), preferJson(out.value(it.key()), it.value()));
    return out;
}
