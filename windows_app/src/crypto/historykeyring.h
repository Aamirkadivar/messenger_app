#ifndef HISTORYKEYRING_H
#define HISTORYKEYRING_H

#include <QByteArray>
#include <QString>
#include <QVector>

/**
 * One (chatId, rootVersion) -> historyRoot binding.
 *
 * A chat may hold several versions at once: after a rotation, older archived messages still need
 * their older root to open, so versions accumulate rather than replace.
 */
struct HistoryRootEntry {
    QString chatId;
    int rootVersion = 0;
    QByteArray root;

    bool operator==(const HistoryRootEntry& o) const {
        return chatId == o.chatId && rootVersion == o.rootVersion && root == o.root;
    }
    bool operator!=(const HistoryRootEntry& o) const { return !(*this == o); }
};

/**
 * Windows side of the Layer B history keyring.
 *
 * The format is defined by the Android implementation
 * (data/encryption/history/HistoryKeyring.kt) and must match it byte-for-byte, because the same
 * keyring bytes are sealed once and opened on either platform. Nothing here is a new design; this is
 * a transcription of that contract.
 *
 * Wire format (all integers fixed-width big-endian):
 *
 *     u8    formatVersion            (must be 1)
 *     u32be entryCount
 *     repeated entryCount times:
 *       u32be chatIdLen
 *       bytes chatId                 (UTF-8, non-empty)
 *       u32be rootVersion            (>= 1)
 *       u8    rootLen                (must be exactly 32)
 *       bytes root
 *
 * Entries are sorted by (chatId, rootVersion) before encoding, so serialisation is canonical: the
 * same set of entries always produces byte-identical output regardless of insertion order. Android
 * sorts with Kotlin's String ordering, which is UTF-16 code-unit order - QString's default
 * comparison is the same, so the two platforms agree on ordering and not merely on content.
 *
 * Like the Android class this owns no vault, no storage and no network. It is representation only;
 * how the keyring is sealed and where it is stored live elsewhere.
 */
class HistoryKeyring {
public:
    static constexpr int FORMAT_VERSION = 1;

    /** An empty keyring. Valid, and distinct from "unknown" - see HistoryKeyringStore. */
    HistoryKeyring() = default;

    /**
     * Validates and canonicalises. Every rejection here is also a decode-time rejection, matching
     * Android's `of()`. Returns false and sets `error` (when given) on the first problem.
     */
    static bool of(const QVector<HistoryRootEntry>& entries,
                   HistoryKeyring& out,
                   QString* error = nullptr);

    /** Strict decode. Any overrun, trailing byte, or invalid field is a failure, never a clamp. */
    static bool decode(const QByteArray& bytes, HistoryKeyring& out, QString* error = nullptr);

    QByteArray encode() const;

    const QVector<HistoryRootEntry>& entries() const { return m_entries; }
    bool isEmpty() const { return m_entries.isEmpty(); }

    /** Highest known version for a chat. False when the chat has no root. */
    bool latest(const QString& chatId, HistoryRootEntry& out) const;

    /** Exact (chatId, rootVersion) lookup. False when absent. */
    bool find(const QString& chatId, int rootVersion, HistoryRootEntry& out) const;

    /** Highest known version for a chat, or 0 when the chat has no root. */
    int latestVersion(const QString& chatId) const;

    /**
     * A copy of this keyring with one more entry. Rejects a duplicate (chatId, rootVersion) and an
     * invalid root exactly as `of()` does, so a caller cannot assemble an invalid keyring.
     */
    bool withAddedVersion(const QString& chatId,
                          int rootVersion,
                          const QByteArray& root,
                          HistoryKeyring& out,
                          QString* error = nullptr) const;

    bool operator==(const HistoryKeyring& o) const { return m_entries == o.m_entries; }
    bool operator!=(const HistoryKeyring& o) const { return !(*this == o); }

private:
    explicit HistoryKeyring(QVector<HistoryRootEntry> canonical)
        : m_entries(std::move(canonical)) {}

    QVector<HistoryRootEntry> m_entries;
};

#endif // HISTORYKEYRING_H
