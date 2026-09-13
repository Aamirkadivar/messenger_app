#ifndef HISTORYKEYRINGSTORE_H
#define HISTORYKEYRINGSTORE_H

#include "historykeyring.h"

#include <QByteArray>
#include <QString>
#include <functional>

/**
 * Outcome of reading one durable slot.
 *
 * The three states are load-bearing and must not be collapsed:
 *
 *     Absent  - nothing stored; a device that has no keyring yet
 *     Ok      - stored and readable
 *     Failed  - stored but unreadable; NEVER reported as Absent
 *
 * Folding Failed into Absent would let a transient DPAPI fault look like a fresh device, and the
 * caller would mint a new root - orphaning every message already archived under the old one.
 */
enum class SlotStatus { Ok, Absent, Failed };

struct SlotRead {
    SlotStatus status = SlotStatus::Failed;
    QByteArray bytes;

    static SlotRead ok(const QByteArray& b) { return { SlotStatus::Ok, b }; }
    static SlotRead absent() { return { SlotStatus::Absent, {} }; }
    static SlotRead failed() { return { SlotStatus::Failed, {} }; }
};

/**
 * Local persistence for the history keyring, in two copies with different jobs. Mirrors Android's
 * `HistoryKeyringStore`.
 *
 *  - **Authoritative**: the MK-sealed keyring. Source of truth and recovery copy. Reading it
 *    requires an unlocked vault.
 *  - **Cache**: the keyring's plain encoding protected by the OS keystore alone (DPAPI on Windows,
 *    Android Keystore on Android). This exists so an ordinary restart can read archived history
 *    without a password prompt, which the authoritative copy cannot do.
 *
 * SECURITY NOTE on the cache: it holds root material protected only by the OS keystore, not by MK.
 * That is the explicit, deliberate cost of cold-start access, and it matches Android exactly. It is
 * never written unprotected - a keystore failure is a write failure, not a plaintext write.
 *
 * The generation marker is a third slot. The two copies are separate durable writes, so a crash
 * between them can leave the cache silently older than the authoritative keyring - and the cache is
 * what a cold start reads FIRST. The marker is bumped BEFORE the authoritative write and stamped
 * into the cache only AFTER both land, so a cache whose stamp differs is provably not known current.
 */
class HistoryKeyringStore {
public:
    virtual ~HistoryKeyringStore() = default;

    virtual bool saveKeyring(const QString& owner, const QByteArray& sealed) = 0;
    virtual SlotRead loadKeyring(const QString& owner) = 0;
    virtual bool deleteKeyring(const QString& owner) = 0;

    virtual bool saveCache(const QString& owner, const QByteArray& plain) = 0;
    virtual SlotRead loadCache(const QString& owner) = 0;
    virtual bool deleteCache(const QString& owner) = 0;

    virtual bool saveGeneration(const QString& owner, qint64 generation) = 0;
    /** Ok with the value, Absent when never written, Failed when unreadable. */
    virtual SlotRead loadGeneration(const QString& owner) = 0;
    virtual bool deleteGeneration(const QString& owner) = 0;
};

/**
 * Wraps bytes so they are unreadable outside this Windows user account - the role Android Keystore
 * plays there.
 *
 * Injected rather than called directly so the persistence tests can run without constructing
 * `CredentialManager`, whose constructor loads and whose destructor rewrites the real per-user
 * credential file. A test must not be able to damage that.
 */
class KeyProtector {
public:
    virtual ~KeyProtector() = default;
    /** Empty return means failure - never a plaintext passthrough. */
    virtual QByteArray protect(const QByteArray& plain) = 0;
    virtual QByteArray unprotect(const QByteArray& wrapped) const = 0;
};

/** DPAPI (CryptProtectData) bound to the current Windows user. */
class DpapiProtector : public KeyProtector {
public:
    QByteArray protect(const QByteArray& plain) override;
    QByteArray unprotect(const QByteArray& wrapped) const override;
};

/**
 * The production Windows store: three registry values per account, each DPAPI-wrapped.
 *
 * The registry holds only wrapped blobs. A root is never written unwrapped - if the protector
 * fails, the write fails, exactly as Android refuses rather than falling back to plaintext.
 *
 * Account scoping uses a hash of the user id rather than the id itself, so the value names do not
 * enumerate which accounts have used this machine.
 */
class RegistryHistoryKeyringStore : public HistoryKeyringStore {
public:
    static QString defaultRegistryPath();

    RegistryHistoryKeyringStore(KeyProtector* protector,
                                const QString& registryPath = defaultRegistryPath());

    bool saveKeyring(const QString& owner, const QByteArray& sealed) override;
    SlotRead loadKeyring(const QString& owner) override;
    bool deleteKeyring(const QString& owner) override;

    bool saveCache(const QString& owner, const QByteArray& plain) override;
    SlotRead loadCache(const QString& owner) override;
    bool deleteCache(const QString& owner) override;

    bool saveGeneration(const QString& owner, qint64 generation) override;
    SlotRead loadGeneration(const QString& owner) override;
    bool deleteGeneration(const QString& owner) override;

    /** Removes every slot for this account. Used by tests to clean up after themselves. */
    void purge(const QString& owner);

private:
    QString slotName(const QString& owner, const char* slot) const;
    bool write(const QString& name, const QByteArray& value);
    SlotRead read(const QString& name) const;

    KeyProtector* m_protector = nullptr;
    QString m_path;
};

/**
 * The account binding on the keystore-only keyring cache. Transcribed from Android's
 * `HistoryKeyringCacheFormat` (cache format version 3).
 *
 *     u8    cacheFormatVersion = 3
 *     u32be userIdLen
 *     bytes userId (UTF-8, non-empty)
 *     u64be generation
 *     bytes keyring encoding   (itself beginning with HistoryKeyring::FORMAT_VERSION = 1)
 *
 * This framing is PLATFORM-LOCAL: the cache never leaves the device and its at-rest protection
 * differs per platform (DPAPI here, Android Keystore there). It is reproduced byte-for-byte anyway
 * so the two clients share one set of semantics rather than two - but it is not protocol, and
 * nothing depends on another platform being able to read these bytes.
 *
 * Legacy detection is exact: an unbound cache is a raw keyring encoding leading with 1, the bound
 * but unstamped format leads with 2, this one leads with 3. Anything else is Unusable - ownership
 * and freshness unknown, which is NOT the same as absent.
 */
class HistoryKeyringCacheFormat {
public:
    static constexpr int CACHE_FORMAT_VERSION = 3;

    enum class Kind {
        Owned,          ///< Bound to the expected account and structurally sound.
        ForAnotherUser, ///< Provably not ours; as good as absent.
        Unusable,       ///< Legacy/corrupt/unreadable binding; ownership UNKNOWN.
    };

    struct Decoded {
        Kind kind = Kind::Unusable;
        QByteArray keyringBytes;
        qint64 generation = 0;
    };

    static QByteArray encode(const QString& owner, qint64 generation, const QByteArray& keyringBytes);
    static Decoded decode(const QByteArray& blob, const QString& expectedOwner);
};

/**
 * Owns the per-chat archive roots on Windows. Mirrors Android's `HistoryKeyringRepository`.
 *
 * The keyring is held sealed at rest and opened through the vault, so roots exist in the clear only
 * inside this process. Every write is seal-then-store: if either half fails nothing is persisted and
 * the in-memory copy is left untouched, so a caller can never proceed believing a root is durable
 * when it is not.
 *
 * The master key never crosses this boundary. The vault is supplied as a seal/open pair, exactly as
 * Android supplies `HistoryKeyringVault`; there is deliberately no accessor returning MK and there
 * must never be one. A locked vault is a failure, never an unsealed result and never an empty one.
 *
 * Nothing here derives from, or feeds into, MLS.
 */
class HistoryKeyringRepository {
public:
    /** Returns false when the vault is locked or sealing fails. MK never crosses this boundary. */
    using SealFn = std::function<bool(const QByteArray& plaintext, QByteArray& sealedOut)>;
    using OpenFn = std::function<bool(const QByteArray& sealed, QByteArray& plainOut)>;
    /** 32 cryptographically random bytes. Injected so tests can pin a synthetic root. */
    using RandomRootFn = std::function<QByteArray()>;
    /**
     * Whether a durable write could succeed right now - in practice, whether the vault is unlocked.
     *
     * Optional. Without it the repository still fails closed, but only after having drawn 32 random
     * bytes it then throws away. With it, a locked vault is detected BEFORE any root material is
     * generated, so a refused mint never brings a key into existence at all. It answers one boolean
     * and carries no master key.
     */
    using CanSealFn = std::function<bool()>;

    HistoryKeyringRepository(HistoryKeyringStore* store,
                             SealFn seal,
                             OpenFn open,
                             RandomRootFn randomRoot = nullptr,
                             CanSealFn canSeal = nullptr);

    /**
     * The current keyring, opening it from local storage on first use.
     *
     * A device that has never archived anything legitimately has no stored keyring; that is an
     * empty keyring. A keyring that is stored but cannot be opened is a FAILURE - reporting it as
     * empty would silently orphan every existing archive.
     */
    bool load(const QString& owner, HistoryKeyring& out, QString* error = nullptr);

    /**
     * The root for a chat, creating exactly one at version 1 when none exists.
     *
     * Calling this again while a root exists returns the existing one and does NOT create a new
     * version. Serving an existing root needs only the cache; minting a new one needs the vault.
     */
    bool ensureRoot(const QString& owner, const QString& chatId,
                    HistoryRootEntry& out, QString* error = nullptr);

    /** Starts a new root version for a chat, keeping every earlier version. Requires the vault. */
    bool rotate(const QString& owner, const QString& chatId,
                HistoryRootEntry& out, QString* error = nullptr);

    /**
     * Merges a keyring recovered from the server into this device's, as a UNION.
     *
     * The fresh-install path: the account's MK is recoverable from the password, and this is what
     * turns MK back into the roots that open archive ciphertext. It is the only way a root reaches
     * a device that never minted one.
     *
     * UNION, NEVER REPLACEMENT. Every local entry survives. A device that archived while offline
     * holds roots the server has not seen, and adopting the server copy wholesale would orphan
     * exactly those archives - silently, and permanently, because the root is the only thing that
     * can open them.
     *
     * A CONFLICT IS A FAILURE, NOT A CHOICE. If both sides carry the same (chatId, rootVersion)
     * with different root bytes, one of them cannot open its archives and there is no way to tell
     * which. Picking a winner would destroy history; this refuses the whole import instead, leaving
     * the local keyring untouched.
     *
     * Requires an unlocked vault: the merged keyring is resealed under MK before it is stored.
     * `imported` reports whether anything was actually added, so a caller can tell "converged" from
     * "already had everything".
     */
    bool importFromRecovery(const QString& owner, const HistoryKeyring& incoming,
                            bool* imported = nullptr, QString* error = nullptr);

    /** Drops the in-memory copy, as a process restart would. Durable copies are untouched. */
    void forgetInMemory();

    /** Bumped on every durable keyring change; unchanged when nothing was persisted. */
    qint64 revision() const { return m_revision; }

private:
    bool persist(const QString& owner, const HistoryKeyring& next, QString* error);
    bool addVersion(const QString& owner, const HistoryKeyring& current, const QString& chatId,
                    int version, HistoryRootEntry& out, QString* error);

    HistoryKeyringStore* m_store = nullptr;
    SealFn m_seal;
    OpenFn m_open;
    RandomRootFn m_randomRoot;
    CanSealFn m_canSeal;

    bool m_loaded = false;
    HistoryKeyring m_cached;
    QString m_cachedOwner;
    qint64 m_revision = 0;
};

#endif // HISTORYKEYRINGSTORE_H
