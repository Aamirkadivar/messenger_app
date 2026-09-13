// Phase 45 enrolment helper for the existing regression suites.
//
// Those suites take a token file and a device id and expect that pair to be authorized. Since
// Phase 44 that is only true once the device has proved possession of its key, and since Phase 45
// the proof runs through the production DeviceEnrollment client. This tool performs exactly that
// enrolment so the suites themselves need no change: they keep testing the archive and keyring
// client logic, now against the real gate.
//
// It mints a throwaway identity keypair per invocation rather than reading a vault, because these
// fixtures only need a device that legitimately holds the key it registers - not the account's real
// identity. Nothing is printed but the device id and an HTTP status.
//
// Usage: device_enroll_tool <baseUrl> <tokenFile> <deviceId>

#include "../src/crypto/deviceenrollment.h"
#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/vaultcrypto.h"

#include <QCoreApplication>
#include <QFile>
#include <QNetworkAccessManager>
#include <QTextStream>
#include <sodium.h>

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);
    if (argc < 4) {
        out << "usage: device_enroll_tool <baseUrl> <tokenFile> <deviceId>" << Qt::endl;
        return 2;
    }
    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    QFile f(QString::fromLocal8Bit(argv[2]));
    if (!f.open(QIODevice::ReadOnly)) { out << "cannot read token file" << Qt::endl; return 2; }
    const QString token = QString::fromLatin1(f.readAll()).trimmed();
    f.close();
    const QString deviceId = QString::fromLocal8Bit(argv[3]);

    QByteArray priv, pub;
    if (!VaultCrypto::generateBoxKeyPair(priv, pub)) {
        out << "keypair generation failed" << Qt::endl;
        return 2;
    }
    const QString pubHex = QString::fromLatin1(pub.toHex());
    const QString privHex = QString::fromLatin1(priv.toHex());

    QNetworkAccessManager nam;
    const DeviceEnrollment enrol(
        makeKeyringExchange(&nam, [&]() { return token; }, [&]() { return deviceId; }, baseUrl),
        [&]() { return pubHex; },
        [&]() { return privHex; });
    const auto r = enrol.enroll(deviceId, QStringLiteral("regression"), QStringLiteral("windows"));

    out << (r.verified() ? "enrolled " : "FAILED   ") << deviceId
        << (r.verified() ? (r.created ? " (new)" : " (existing)") : "") << Qt::endl;
    return r.verified() ? 0 : 1;
}
