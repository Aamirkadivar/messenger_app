// Minimal in-process HTTP/1.1 server for the Phase 71 outbox / sync tests.
//
// Loopback only, one request per connection ("Connection: close"). A handler decides every answer,
// including DROPPING the connection after reading the request - which is how a lost ACK and an
// unreachable server look from the client. No production service is ever contacted.
#pragma once

#include <QTcpServer>
#include <QTcpSocket>
#include <QByteArray>
#include <QHash>
#include <QString>
#include <functional>

struct FakeHttpRequest {
    QByteArray method;
    QByteArray path;   // includes the query string
    QHash<QByteArray, QByteArray> headers; // lower-case names
    QByteArray body;
};

struct FakeHttpResponse {
    int status = 200;
    QByteArray body;
    bool drop = false; // close without answering
};

class FakeHttpServer : public QTcpServer {
public:
    using Handler = std::function<FakeHttpResponse(const FakeHttpRequest&)>;

    explicit FakeHttpServer(Handler h) : m_handler(std::move(h)) {
        listen(QHostAddress::LocalHost, 0);
        QObject::connect(this, &QTcpServer::newConnection, this, [this]() {
            while (QTcpSocket* s = nextPendingConnection()) {
                auto* buf = new QByteArray;
                QObject::connect(s, &QTcpSocket::readyRead, s, [this, s, buf]() {
                    buf->append(s->readAll());
                    const int headerEnd = buf->indexOf("\r\n\r\n");
                    if (headerEnd < 0) return;
                    FakeHttpRequest req;
                    const QList<QByteArray> lines = buf->left(headerEnd).split('\n');
                    const QList<QByteArray> first = lines.value(0).trimmed().split(' ');
                    req.method = first.value(0);
                    req.path = first.value(1);
                    for (int i = 1; i < lines.size(); ++i) {
                        const int c = lines[i].indexOf(':');
                        if (c > 0) req.headers.insert(lines[i].left(c).trimmed().toLower(), lines[i].mid(c + 1).trimmed());
                    }
                    const int len = req.headers.value("content-length", "0").toInt();
                    if (buf->size() < headerEnd + 4 + len) return;
                    req.body = buf->mid(headerEnd + 4, len);
                    buf->clear();
                    const FakeHttpResponse r = m_handler(req);
                    if (r.drop) {
                        s->abort();
                        return;
                    }
                    QByteArray out = "HTTP/1.1 " + QByteArray::number(r.status) + " X\r\n"
                                     "Content-Type: application/json\r\n"
                                     "Content-Length: " + QByteArray::number(r.body.size()) + "\r\n"
                                     "Connection: close\r\n\r\n" + r.body;
                    s->write(out);
                    s->flush();
                    s->disconnectFromHost();
                });
                QObject::connect(s, &QTcpSocket::disconnected, s, [s, buf]() { delete buf; s->deleteLater(); });
            }
        });
    }

    QString baseUrl() const {
        return QStringLiteral("http://127.0.0.1:%1/api/v1").arg(serverPort());
    }

private:
    Handler m_handler;
};
