// Phase 33 - the history-keyring transport driven through the application's real authenticated
// HTTP path.
//
// Phase 32 proved the protocol against an in-process boundary. What was still unproven was the part
// that only a real socket can show: that the application's own binding attaches the session's
// Authorization and X-Device-Id headers, that Qt actually issues PUT and DELETE, and that the
// server's optimistic-concurrency rules behave as the client assumes - in particular that a stale
// write is refused WITHOUT disturbing what is stored.
//
// This exercises the shipped code: makeKeyringExchange() and HistoryKeyringTransport, unchanged.
// The only test-only affordance is the base URL, which points at an isolated backend instead of the
// production one.
//
// Never touches the production database or the running production server. The token is read from a
// file and never printed; only its presence, scheme and length are reported.
//
// Usage: history_keyring_e2e_test <baseUrl> <tokenFile> <deviceId>

#include "../src/crypto/historykeyringhttp.h"
#include "../src/crypto/historykeyringtransport.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QCoreApplication>
#include <QFile>
#include <QNetworkAccessManager>
#include <QHash>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTextStream>
#include <sodium.h>

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_failures;
}

static void section(const char* t) { QTextStream(stdout) << "-- " << t << " --" << Qt::endl; }

// ------------------------------------------------------------------ local stub
//
// A throwaway HTTP endpoint used for two things the real server cannot conveniently provide:
// observing the headers the application binding actually sends, and returning chosen status codes
// (403, 500) on demand. It speaks only enough HTTP for this test.

class StubServer : public QTcpServer {
public:
    int status = 200;
    QByteArray body = "{}";
    QByteArray lastRequestLine;
    QByteArray lastAuthorization;
    QByteArray lastDeviceId;
    QByteArray lastBody;

protected:
    // Event-driven on purpose. Qt opens several connections per host and may open one
    // speculatively before it has anything to send; blocking here on waitForReadyRead stalls the
    // whole event loop, so the connection actually carrying the request never gets serviced and
    // the request times out. Handling readyRead as a signal keeps the loop live.
    void incomingConnection(qintptr handle) override {
        auto* sock = new QTcpSocket(this);
        sock->setSocketDescriptor(handle);
        m_buffers.insert(sock, QByteArray());

        connect(sock, &QTcpSocket::readyRead, this, [this, sock]() {
            QByteArray& buf = m_buffers[sock];
            buf += sock->readAll();

            const int headEnd = buf.indexOf("\r\n\r\n");
            if (headEnd < 0) return; // headers still incomplete

            int contentLength = 0;
            QByteArray requestLine, authorization, deviceId;
            const QList<QByteArray> lines = buf.left(headEnd).split('\n');
            for (int i = 0; i < lines.size(); ++i) {
                const QByteArray line = lines.at(i).trimmed();
                if (i == 0) { requestLine = line; continue; }
                const int colon = line.indexOf(':');
                if (colon < 0) continue;
                const QByteArray key = line.left(colon).trimmed().toLower();
                const QByteArray val = line.mid(colon + 1).trimmed();
                if (key == "authorization") authorization = val;
                else if (key == "x-device-id") deviceId = val;
                else if (key == "content-length") contentLength = val.toInt();
            }
            if (buf.size() - (headEnd + 4) < contentLength) return; // body still incomplete

            lastRequestLine = requestLine;
            lastAuthorization = authorization;
            lastDeviceId = deviceId;
            lastBody = buf.mid(headEnd + 4, contentLength);

            const QByteArray resp = "HTTP/1.1 " + QByteArray::number(status) + " X\r\n"
                                    "Content-Type: application/json\r\n"
                                    "Content-Length: " + QByteArray::number(body.size()) + "\r\n"
                                    "Connection: close\r\n\r\n" + body;
            sock->write(resp);
            sock->flush();
            sock->disconnectFromHost();
        });

        connect(sock, &QTcpSocket::disconnected, this, [this, sock]() {
            m_buffers.remove(sock);
            sock->deleteLater();
        });
    }

private:
    QHash<QTcpSocket*, QByteArray> m_buffers;
};

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 2;
    QTextStream out(stdout);

    if (argc < 4) {
        out << "usage: history_keyring_e2e_test <baseUrl> <tokenFile> <deviceId>" << Qt::endl;
        return 2;
    }
    const QString baseUrl = QString::fromLocal8Bit(argv[1]);
    const QString deviceId = QString::fromLocal8Bit(argv[3]);

    QFile tf(QString::fromLocal8Bit(argv[2]));
    if (!tf.open(QIODevice::ReadOnly)) { out << "cannot read token file" << Qt::endl; return 2; }
    const QString token = QString::fromLatin1(tf.readAll()).trimmed();
    tf.close();
    if (token.isEmpty()) { out << "empty token" << Qt::endl; return 2; }

    QNetworkAccessManager nam;
    out << "Phase 33 - keyring transport over the real authenticated path" << Qt::endl;
    out << "base: " << baseUrl << "  (isolated backend)" << Qt::endl;

    // The application binding, exactly as AuthService::historyKeyringTransport() builds it.
    auto sessionExchange = makeKeyringExchange(
        &nam, [&]() { return token; }, [&]() { return deviceId; }, baseUrl);
    HistoryKeyringTransport tx(sessionExchange);

    // ---------------------------------------------------------- authentication proof
    section("authentication headers actually sent by the binding");
    {
        StubServer stub;
        if (!stub.listen(QHostAddress::LocalHost, 0)) {
            check(false, QStringLiteral("stub server could not listen"));
        } else {
            const QString stubBase =
                QStringLiteral("http://127.0.0.1:%1/api/v1").arg(stub.serverPort());
            stub.status = 404;
            auto probeExchange = makeKeyringExchange(
                &nam, [&]() { return token; }, [&]() { return deviceId; }, stubBase);
            HistoryKeyringTransport probe(probeExchange);
            probe.get();

            check(stub.lastRequestLine.startsWith("GET /api/v1/e2ee/history-keyring"),
                  QStringLiteral("GET reaches the documented path"));
            check(stub.lastAuthorization.startsWith("Bearer "),
                  QStringLiteral("Authorization header present and uses the Bearer scheme"));
            check(stub.lastAuthorization.size() > 7,
                  QStringLiteral("Authorization carries a non-empty credential (value not shown)"));
            check(stub.lastDeviceId == deviceId.toUtf8(),
                  QStringLiteral("X-Device-Id matches the session device id"));

            // Verbs must really be PUT and DELETE, not emulated.
            stub.status = 200;
            stub.body = "{\"version\":1,\"created\":true}";
            probe.put(0, QByteArray("sealed-bytes"));
            check(stub.lastRequestLine.startsWith("PUT "), QStringLiteral("PUT is a real PUT"));
            check(stub.lastAuthorization.startsWith("Bearer ") && !stub.lastDeviceId.isEmpty(),
                  QStringLiteral("PUT carries both auth headers"));

            // The body must carry ciphertext only - never keyring plaintext.
            check(stub.lastBody.contains("ciphertext_b64") &&
                      stub.lastBody.contains("expected_version"),
                  QStringLiteral("PUT body is the documented two-field object"));
            check(!stub.lastBody.contains("root") && !stub.lastBody.contains("chatId"),
                  QStringLiteral("PUT body exposes no keyring plaintext"));

            stub.body = "{}";
            probe.remove();
            check(stub.lastRequestLine.startsWith("DELETE "),
                  QStringLiteral("DELETE is a real DELETE"));

            // Deterministic status codes the isolated server will not readily produce.
            stub.status = 403; stub.body = "{}";
            check(probe.get().outcome == HistoryKeyringTransport::Outcome::Unauthorized,
                  QStringLiteral("403 maps to Unauthorized, not Absent"));
            stub.status = 500;
            check(probe.get().outcome == HistoryKeyringTransport::Outcome::TransportError,
                  QStringLiteral("500 maps to TransportError, not Absent"));
            stub.close();
        }
    }

    // ---------------------------------------------------------- real server matrix
    section("isolated backend: full keyring lifecycle");

    // Start from a known-clean slot; DELETE is idempotent.
    tx.remove();

    {
        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Absent,
              QStringLiteral("1. GET on a fresh account is Absent (404), not an empty keyring"));
    }

    // Synthetic sealed payloads. This test does not hold a vault; these stand in for ciphertext and
    // are never keyring plaintext.
    QByteArray blobA(96, 0);
    for (int i = 0; i < blobA.size(); ++i) blobA[i] = static_cast<char>(0xA0 + (i % 64));
    QByteArray blobB(96, 0);
    for (int i = 0; i < blobB.size(); ++i) blobB[i] = static_cast<char>(0x10 + (i % 64));

    {
        auto p = tx.put(0, blobA);
        check(p.outcome == HistoryKeyringTransport::Outcome::Ok && p.version == 1 && p.created,
              QStringLiteral("2. PUT expected_version=0 creates version 1 (created=true)"));
    }
    {
        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok && f.version == 1,
              QStringLiteral("3. GET after create returns version 1"));
        check(f.sealed == blobA, QStringLiteral("3. GET returns the identical ciphertext"));
    }
    {
        auto p = tx.put(1, blobB);
        check(p.outcome == HistoryKeyringTransport::Outcome::Ok && p.version == 2 && !p.created,
              QStringLiteral("4. PUT expected_version=1 updates to version 2 (created=false)"));
        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok && f.version == 2 &&
                  f.sealed == blobB,
              QStringLiteral("4. GET reflects version 2 and the new ciphertext"));
    }
    {
        // The one that matters: a replayed or stale writer must be refused AND must not clobber.
        QByteArray attacker(96, 'X');
        auto p = tx.put(1, attacker);
        check(p.outcome == HistoryKeyringTransport::Outcome::Conflict,
              QStringLiteral("5. stale expected_version=1 is refused with Conflict (409)"));
        check(p.serverVersion == 2, QStringLiteral("5. conflict reports the server's version"));
        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Ok && f.version == 2,
              QStringLiteral("5. server is STILL at version 2 after the stale write"));
        check(f.sealed == blobB,
              QStringLiteral("5. stale write did NOT overwrite the stored ciphertext"));
    }
    {
        check(tx.remove() == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("6. DELETE succeeds"));
        auto f = tx.get();
        check(f.outcome == HistoryKeyringTransport::Outcome::Absent,
              QStringLiteral("7. GET after DELETE is Absent (404)"));
        check(tx.remove() == HistoryKeyringTransport::Outcome::Ok,
              QStringLiteral("7. DELETE is idempotent"));
    }

    // ---------------------------------------------------------- real server error matrix
    section("isolated backend: error mapping");
    {
        // 401: no credential at all.
        HistoryKeyringTransport noAuth(makeKeyringExchange(
            &nam, [&]() { return QString(); }, [&]() { return deviceId; }, baseUrl));
        check(noAuth.get().outcome == HistoryKeyringTransport::Outcome::Unauthorized,
              QStringLiteral("401 without a token maps to Unauthorized, not Absent"));

        // 400: the server requires X-Device-Id on these routes.
        HistoryKeyringTransport noDevice(makeKeyringExchange(
            &nam, [&]() { return token; }, [&]() { return QString(); }, baseUrl));
        check(noDevice.get().outcome == HistoryKeyringTransport::Outcome::Rejected,
              QStringLiteral("400 without X-Device-Id maps to Rejected, not Absent"));

        // 413: the server bounds one blob at 1 MiB.
        const QByteArray tooBig((1 << 20) + 1024, 'Z');
        check(tx.put(0, tooBig).outcome == HistoryKeyringTransport::Outcome::Rejected,
              QStringLiteral("413 on an oversized blob maps to Rejected"));

        // A locally rejected request must not reach the network at all.
        check(tx.put(-1, blobA).outcome == HistoryKeyringTransport::Outcome::Rejected,
              QStringLiteral("a negative expected_version is refused before sending"));
        check(tx.put(0, QByteArray()).outcome == HistoryKeyringTransport::Outcome::Rejected,
              QStringLiteral("an empty ciphertext is refused before sending"));

        // Unreachable server: still not "absent".
        HistoryKeyringTransport dead(makeKeyringExchange(
            &nam, [&]() { return token; }, [&]() { return deviceId; },
            QStringLiteral("http://127.0.0.1:1/api/v1")));
        check(dead.get().outcome == HistoryKeyringTransport::Outcome::TransportError,
              QStringLiteral("an unreachable server is TransportError, not Absent"));
    }

    // Leave the isolated account clean.
    tx.remove();

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
