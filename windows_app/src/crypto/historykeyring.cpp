#include "historykeyring.h"
#include "historycrypto.h"

#include <algorithm>

namespace {

void setError(QString* error, const QString& message) {
    if (error) *error = message;
}

/**
 * Strict big-endian reader. Every overrun is an explicit failure rather than a silent clamp, which
 * is what stops a corrupt length from being read as a shorter valid keyring.
 */
class Reader {
public:
    explicit Reader(const QByteArray& buf) : m_buf(buf) {}

    int remaining() const { return m_buf.size() - m_pos; }
    bool failed() const { return m_failed; }

    int u8() {
        if (!need(1)) return 0;
        return static_cast<int>(static_cast<unsigned char>(m_buf.at(m_pos++)));
    }

    int u32() {
        if (!need(4)) return 0;
        const auto b = [&](int i) {
            return static_cast<unsigned int>(static_cast<unsigned char>(m_buf.at(m_pos + i)));
        };
        const unsigned int v = (b(0) << 24) | (b(1) << 16) | (b(2) << 8) | b(3);
        m_pos += 4;
        // Reinterpreted as signed, exactly as Android's Int-returning reader does, so an oversized
        // field arrives here as a negative number and is rejected by the same checks.
        return static_cast<int>(v);
    }

    QByteArray bytes(int n) {
        if (n < 0) { m_failed = true; return {}; }
        if (!need(n)) return {};
        const QByteArray out = m_buf.mid(m_pos, n);
        m_pos += n;
        return out;
    }

private:
    bool need(int n) {
        if (m_failed) return false;
        if (remaining() < n) { m_failed = true; return false; }
        return true;
    }

    const QByteArray& m_buf;
    int m_pos = 0;
    bool m_failed = false;
};

} // namespace

bool HistoryKeyring::of(const QVector<HistoryRootEntry>& entries,
                        HistoryKeyring& out,
                        QString* error) {
    QVector<QPair<QString, int>> seen;
    seen.reserve(entries.size());
    for (const HistoryRootEntry& e : entries) {
        if (e.chatId.isEmpty()) {
            setError(error, QStringLiteral("chatId must not be empty"));
            return false;
        }
        if (e.rootVersion < 1) {
            setError(error, QStringLiteral("rootVersion must be >= 1, was %1").arg(e.rootVersion));
            return false;
        }
        if (e.root.size() != HistoryCrypto::ROOT_BYTES) {
            setError(error, QStringLiteral("history root must be exactly %1 bytes, was %2")
                                .arg(HistoryCrypto::ROOT_BYTES)
                                .arg(e.root.size()));
            return false;
        }
        const QPair<QString, int> key(e.chatId, e.rootVersion);
        if (seen.contains(key)) {
            setError(error, QStringLiteral("duplicate (chatId, rootVersion) entry for version %1")
                                .arg(e.rootVersion));
            return false;
        }
        seen.append(key);
    }

    QVector<HistoryRootEntry> canonical = entries;
    std::sort(canonical.begin(), canonical.end(),
              [](const HistoryRootEntry& a, const HistoryRootEntry& b) {
                  if (a.chatId != b.chatId) return a.chatId < b.chatId;
                  return a.rootVersion < b.rootVersion;
              });
    out = HistoryKeyring(std::move(canonical));
    return true;
}

QByteArray HistoryKeyring::encode() const {
    QByteArray out;
    out.append(static_cast<char>(FORMAT_VERSION));
    out.append(HistoryCrypto::u32be(static_cast<quint32>(m_entries.size())));
    for (const HistoryRootEntry& e : m_entries) {
        const QByteArray chat = e.chatId.toUtf8();
        out.append(HistoryCrypto::u32be(static_cast<quint32>(chat.size())));
        out.append(chat);
        out.append(HistoryCrypto::u32be(static_cast<quint32>(e.rootVersion)));
        out.append(static_cast<char>(e.root.size()));
        out.append(e.root);
    }
    return out;
}

bool HistoryKeyring::decode(const QByteArray& bytes, HistoryKeyring& out, QString* error) {
    Reader r(bytes);

    const int version = r.u8();
    if (r.failed()) {
        setError(error, QStringLiteral("truncated keyring"));
        return false;
    }
    if (version != FORMAT_VERSION) {
        setError(error, QStringLiteral("unsupported keyring format version %1").arg(version));
        return false;
    }

    const int count = r.u32();
    if (r.failed()) {
        setError(error, QStringLiteral("truncated keyring"));
        return false;
    }
    if (count < 0) {
        setError(error, QStringLiteral("negative entry count"));
        return false;
    }
    // Each entry needs at least 4 + 1 + 4 + 1 + 32 bytes; reject absurd counts before allocating so
    // a corrupt length cannot drive a huge allocation.
    if (static_cast<qint64>(count) * 9LL > static_cast<qint64>(bytes.size())) {
        setError(error, QStringLiteral("entry count %1 exceeds available bytes").arg(count));
        return false;
    }

    QVector<HistoryRootEntry> parsed;
    parsed.reserve(count);
    for (int i = 0; i < count; ++i) {
        HistoryRootEntry e;
        const QByteArray chat = r.bytes(r.u32());
        e.chatId = QString::fromUtf8(chat);
        e.rootVersion = r.u32();
        const int rootLen = r.u8();
        e.root = r.bytes(rootLen);
        if (r.failed()) {
            setError(error, QStringLiteral("truncated keyring"));
            return false;
        }
        parsed.append(e);
    }

    if (r.remaining() != 0) {
        setError(error, QStringLiteral("trailing bytes after keyring: %1").arg(r.remaining()));
        return false;
    }
    return of(parsed, out, error);
}

bool HistoryKeyring::latest(const QString& chatId, HistoryRootEntry& out) const {
    bool found = false;
    for (const HistoryRootEntry& e : m_entries) {
        if (e.chatId != chatId) continue;
        if (!found || e.rootVersion > out.rootVersion) {
            out = e;
            found = true;
        }
    }
    return found;
}

bool HistoryKeyring::find(const QString& chatId, int rootVersion, HistoryRootEntry& out) const {
    for (const HistoryRootEntry& e : m_entries) {
        if (e.chatId == chatId && e.rootVersion == rootVersion) {
            out = e;
            return true;
        }
    }
    return false;
}

int HistoryKeyring::latestVersion(const QString& chatId) const {
    HistoryRootEntry e;
    return latest(chatId, e) ? e.rootVersion : 0;
}

bool HistoryKeyring::withAddedVersion(const QString& chatId,
                                      int rootVersion,
                                      const QByteArray& root,
                                      HistoryKeyring& out,
                                      QString* error) const {
    QVector<HistoryRootEntry> next = m_entries;
    HistoryRootEntry add;
    add.chatId = chatId;
    add.rootVersion = rootVersion;
    add.root = root;
    next.append(add);
    return of(next, out, error);
}
