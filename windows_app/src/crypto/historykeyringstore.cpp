#include "historykeyringstore.h"
#include "historycrypto.h"

#include <QCryptographicHash>
#include <QSettings>
#include <sodium.h>

#ifdef Q_OS_WIN
#include <windows.h>
#include <wincrypt.h>
#endif

namespace {

void setError(QString* error, const QString& message) {
    if (error) *error = message;
}

QByteArray u64be(quint64 v) {
    QByteArray out(8, 0);
    for (int i = 0; i < 8; ++i) out[7 - i] = static_cast<char>((v >> (8 * i)) & 0xFF);
    return out;
}

} // namespace

// ---------------------------------------------------------------- key protector

QByteArray DpapiProtector::protect(const QByteArray& plain) {
#ifdef Q_OS_WIN
    if (plain.isEmpty()) return {};
    DATA_BLOB in, outBlob;
    in.pbData = const_cast<BYTE*>(reinterpret_cast<const BYTE*>(plain.constData()));
    in.cbData = static_cast<DWORD>(plain.size());
    QByteArray result;
    if (CryptProtectData(&in, L"MessengerAppHistoryKeyring", nullptr, nullptr, nullptr,
                         CRYPTPROTECT_UI_FORBIDDEN, &outBlob)) {
        result = QByteArray(reinterpret_cast<char*>(outBlob.pbData),
                            static_cast<int>(outBlob.cbData));
        LocalFree(outBlob.pbData);
    }
    return result; // empty on failure: the caller must treat that as a write failure
#else
    Q_UNUSED(plain);
    return {};
#endif
}

QByteArray DpapiProtector::unprotect(const QByteArray& wrapped) const {
#ifdef Q_OS_WIN
    if (wrapped.isEmpty()) return {};
    DATA_BLOB in, outBlob;
    in.pbData = const_cast<BYTE*>(reinterpret_cast<const BYTE*>(wrapped.constData()));
    in.cbData = static_cast<DWORD>(wrapped.size());
    QByteArray result;
    if (CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr,
                           CRYPTPROTECT_UI_FORBIDDEN, &outBlob)) {
        result = QByteArray(reinterpret_cast<char*>(outBlob.pbData),
                            static_cast<int>(outBlob.cbData));
        LocalFree(outBlob.pbData);
    }
    return result;
#else
    Q_UNUSED(wrapped);
    return {};
#endif
}

// ---------------------------------------------------------------- registry store

QString RegistryHistoryKeyringStore::defaultRegistryPath() {
    return QStringLiteral("HKEY_CURRENT_USER\\Software\\MessengerApp\\HistoryKeyring");
}

RegistryHistoryKeyringStore::RegistryHistoryKeyringStore(KeyProtector* protector,
                                                         const QString& registryPath)
    : m_protector(protector), m_path(registryPath) {}

QString RegistryHistoryKeyringStore::slotName(const QString& owner, const char* slot) const {
    // Hashed so the value names do not enumerate which accounts have used this machine.
    const QByteArray h =
        QCryptographicHash::hash(owner.toUtf8(), QCryptographicHash::Sha256).toHex().left(32);
    return QString::fromLatin1(slot) + QStringLiteral("_") + QString::fromLatin1(h);
}

bool RegistryHistoryKeyringStore::write(const QString& name, const QByteArray& value) {
    if (!m_protector) return false;
    const QByteArray wrapped = m_protector->protect(value);
    // A protector failure is a write failure. Never a plaintext fallback.
    if (wrapped.isEmpty()) return false;
    QSettings s(m_path, QSettings::NativeFormat);
    s.setValue(name, wrapped);
    s.sync();
    return s.status() == QSettings::NoError;
}

SlotRead RegistryHistoryKeyringStore::read(const QString& name) const {
    QSettings s(m_path, QSettings::NativeFormat);
    if (!s.contains(name)) return SlotRead::absent();
    const QByteArray wrapped = s.value(name).toByteArray();
    if (wrapped.isEmpty() || !m_protector) return SlotRead::failed();
    const QByteArray plain = m_protector->unprotect(wrapped);
    // Present but unreadable is a FAILURE, never absent - that distinction is what stops a
    // transient protector fault from looking like a fresh device.
    if (plain.isEmpty()) return SlotRead::failed();
    return SlotRead::ok(plain);
}

bool RegistryHistoryKeyringStore::saveKeyring(const QString& o, const QByteArray& sealed) {
    return write(slotName(o, "auth"), sealed);
}
SlotRead RegistryHistoryKeyringStore::loadKeyring(const QString& o) {
    return read(slotName(o, "auth"));
}
bool RegistryHistoryKeyringStore::deleteKeyring(const QString& o) {
    QSettings s(m_path, QSettings::NativeFormat);
    s.remove(slotName(o, "auth"));
    return true;
}

bool RegistryHistoryKeyringStore::saveCache(const QString& o, const QByteArray& plain) {
    return write(slotName(o, "cache"), plain);
}
SlotRead RegistryHistoryKeyringStore::loadCache(const QString& o) {
    return read(slotName(o, "cache"));
}
bool RegistryHistoryKeyringStore::deleteCache(const QString& o) {
    QSettings s(m_path, QSettings::NativeFormat);
    s.remove(slotName(o, "cache"));
    return true;
}

bool RegistryHistoryKeyringStore::saveGeneration(const QString& o, qint64 g) {
    return write(slotName(o, "gen"), QByteArray::number(g));
}
SlotRead RegistryHistoryKeyringStore::loadGeneration(const QString& o) {
    return read(slotName(o, "gen"));
}
bool RegistryHistoryKeyringStore::deleteGeneration(const QString& o) {
    QSettings s(m_path, QSettings::NativeFormat);
    s.remove(slotName(o, "gen"));
    return true;
}

void RegistryHistoryKeyringStore::purge(const QString& owner) {
    QSettings s(m_path, QSettings::NativeFormat);
    s.remove(slotName(owner, "auth"));
    s.remove(slotName(owner, "cache"));
    s.remove(slotName(owner, "gen"));
    s.sync();
}

// ---------------------------------------------------------------- cache format

QByteArray HistoryKeyringCacheFormat::encode(const QString& owner,
                                             qint64 generation,
                                             const QByteArray& keyringBytes) {
    const QByteArray user = owner.toUtf8();
    QByteArray out;
    out.append(static_cast<char>(CACHE_FORMAT_VERSION));
    out.append(HistoryCrypto::u32be(static_cast<quint32>(user.size())));
    out.append(user);
    out.append(u64be(static_cast<quint64>(generation)));
    out.append(keyringBytes);
    return out;
}

HistoryKeyringCacheFormat::Decoded
HistoryKeyringCacheFormat::decode(const QByteArray& blob, const QString& expectedOwner) {
    Decoded d;
    // Anything that is not exactly this version is Unusable: ownership and freshness are both
    // unknown, which is not the same as absent.
    if (blob.size() < 1 + 4 + 8) return d;
    if (static_cast<unsigned char>(blob.at(0)) != CACHE_FORMAT_VERSION) return d;

    const auto b = [&](int i) {
        return static_cast<unsigned int>(static_cast<unsigned char>(blob.at(i)));
    };
    const unsigned int userLen = (b(1) << 24) | (b(2) << 16) | (b(3) << 8) | b(4);
    if (userLen == 0) return d;
    const qint64 need = 1LL + 4LL + static_cast<qint64>(userLen) + 8LL;
    if (static_cast<qint64>(blob.size()) < need) return d;

    const QString owner = QString::fromUtf8(blob.mid(5, static_cast<int>(userLen)));
    if (owner.isEmpty()) return d;
    if (owner != expectedOwner) {
        d.kind = Kind::ForAnotherUser;
        return d;
    }

    quint64 gen = 0;
    const int genAt = 5 + static_cast<int>(userLen);
    for (int i = 0; i < 8; ++i) {
        gen = (gen << 8) | static_cast<unsigned char>(blob.at(genAt + i));
    }

    d.kind = Kind::Owned;
    d.generation = static_cast<qint64>(gen);
    d.keyringBytes = blob.mid(genAt + 8);
    return d;
}

// ---------------------------------------------------------------- repository

HistoryKeyringRepository::HistoryKeyringRepository(HistoryKeyringStore* store,
                                                   SealFn seal,
                                                   OpenFn open,
                                                   RandomRootFn randomRoot,
                                                   CanSealFn canSeal)
    : m_store(store), m_seal(std::move(seal)), m_open(std::move(open)),
      m_randomRoot(std::move(randomRoot)), m_canSeal(std::move(canSeal)) {}

void HistoryKeyringRepository::forgetInMemory() {
    m_loaded = false;
    m_cached = HistoryKeyring();
    m_cachedOwner.clear();
}

bool HistoryKeyringRepository::load(const QString& owner, HistoryKeyring& out, QString* error) {
    if (!m_store) {
        setError(error, QStringLiteral("no history keyring store"));
        return false;
    }
    // The durable slots are account-scoped, so there is nothing to address without an owner - and
    // serving "empty" here would be indistinguishable from a genuinely empty keyring.
    if (owner.isEmpty()) {
        setError(error, QStringLiteral("no signed-in account to load a history keyring for"));
        return false;
    }

    // Identity first. An in-memory keyring belongs to the account it was opened for and no other.
    if (m_loaded && m_cachedOwner != owner) forgetInMemory();
    if (m_loaded) {
        out = m_cached;
        return true;
    }

    const SlotRead cacheRead = m_store->loadCache(owner);
    HistoryKeyringCacheFormat::Decoded decodedCache;
    if (cacheRead.status == SlotStatus::Ok) {
        decodedCache = HistoryKeyringCacheFormat::decode(cacheRead.bytes, owner);
    }
    const bool cacheIsForeign =
        cacheRead.status == SlotStatus::Ok &&
        decodedCache.kind == HistoryKeyringCacheFormat::Kind::ForAnotherUser;

    const SlotRead markerRead = m_store->loadGeneration(owner);
    qint64 marker = -1;
    bool markerKnown = false;
    if (markerRead.status == SlotStatus::Ok) {
        marker = markerRead.bytes.toLongLong(&markerKnown);
    }

    // FRESHNESS. The cache is the only copy readable without MK, so a cold start reads it first -
    // which is exactly why it must not be trusted on structure and ownership alone. A stamp equal
    // to the marker is the one piece of evidence that this cache was built from current state.
    if (cacheRead.status == SlotStatus::Ok &&
        decodedCache.kind == HistoryKeyringCacheFormat::Kind::Owned) {
        if (markerKnown && decodedCache.generation == marker) {
            HistoryKeyring k;
            if (HistoryKeyring::decode(decodedCache.keyringBytes, k)) {
                m_cached = k;
                m_cachedOwner = owner;
                m_loaded = true;
                out = k;
                return true; // served without the vault - this is the cold-start path
            }
            // Our own current cache, but the payload is damaged. The authoritative copy may still
            // be good; fall through, and never fall through to "empty".
        }
        // A stale stamp means a write was interrupted, not corruption: fall through to the
        // authoritative copy, which re-stamps the cache below.
    }

    const SlotRead stored = m_store->loadKeyring(owner);
    if (stored.status == SlotStatus::Failed) {
        setError(error, QStringLiteral("history keyring is stored but unreadable"));
        return false;
    }

    if (stored.status == SlotStatus::Absent) {
        // Only a genuinely absent cache, or a provably foreign one, may become an empty keyring.
        // A stale or damaged Owned cache is neither, so it still refuses rather than minting.
        if (cacheRead.status == SlotStatus::Absent || cacheIsForeign) {
            HistoryKeyring empty;
            if (!HistoryKeyring::of({}, empty, error)) return false;
            m_cached = empty;
            m_cachedOwner = owner;
            m_loaded = true;
            out = empty;
            return true;
        }
        setError(error, QStringLiteral("history keyring cache is unreadable and no recovery copy "
                                       "exists; refusing to mint a replacement keyring"));
        return false;
    }

    // Authoritative copy present: opening it needs the vault.
    if (!m_open) {
        setError(error, QStringLiteral("history keyring cannot be opened: vault is locked"));
        return false;
    }
    QByteArray plain;
    if (!m_open(stored.bytes, plain)) {
        // Normally fatal - it may be OUR keyring, damaged. The one exception is when the cache
        // proved the stored state belongs to a DIFFERENT account: an MK-sealed blob cannot say
        // whose it is, and the account-bound cache supplies exactly the evidence it lacks.
        if (cacheIsForeign) {
            HistoryKeyring empty;
            if (!HistoryKeyring::of({}, empty, error)) return false;
            m_cached = empty;
            m_cachedOwner = owner;
            m_loaded = true;
            out = empty;
            return true;
        }
        setError(error, QStringLiteral("history keyring could not be opened"));
        return false;
    }

    HistoryKeyring k;
    if (!HistoryKeyring::decode(plain, k, error)) return false;

    // Re-stamp the cache from the authoritative copy so the next cold start can use it.
    const qint64 gen = markerKnown ? marker : 1;
    if (!markerKnown) m_store->saveGeneration(owner, gen);
    m_store->saveCache(owner, HistoryKeyringCacheFormat::encode(owner, gen, k.encode()));

    m_cached = k;
    m_cachedOwner = owner;
    m_loaded = true;
    out = k;
    return true;
}

bool HistoryKeyringRepository::persist(const QString& owner,
                                       const HistoryKeyring& next,
                                       QString* error) {
    if (!m_seal) {
        setError(error, QStringLiteral("history keyring cannot be sealed: vault is locked"));
        return false;
    }
    const QByteArray plain = next.encode();

    QByteArray sealed;
    if (!m_seal(plain, sealed) || sealed.isEmpty()) {
        setError(error, QStringLiteral("history keyring cannot be sealed: vault is locked"));
        return false;
    }

    // Bump the marker BEFORE the authoritative write and stamp the cache only AFTER both land, so
    // an interruption leaves the cache provably not-current rather than silently stale.
    const SlotRead markerRead = m_store->loadGeneration(owner);
    bool ok = false;
    const qint64 current = markerRead.status == SlotStatus::Ok
                               ? markerRead.bytes.toLongLong(&ok)
                               : 0;
    const qint64 nextGen = (ok ? current : 0) + 1;

    if (!m_store->saveGeneration(owner, nextGen)) {
        setError(error, QStringLiteral("could not record the keyring generation"));
        return false;
    }
    if (!m_store->saveKeyring(owner, sealed)) {
        setError(error, QStringLiteral("could not store the history keyring"));
        return false;
    }
    if (!m_store->saveCache(owner, HistoryKeyringCacheFormat::encode(owner, nextGen, plain))) {
        // The authoritative copy is written; the marker mismatch makes the cache provably
        // not-current, so the next load recovers through the vault rather than trusting it.
        setError(error, QStringLiteral("could not refresh the history keyring cache"));
        return false;
    }

    m_cached = next;
    m_cachedOwner = owner;
    m_loaded = true;
    ++m_revision;
    return true;
}

bool HistoryKeyringRepository::addVersion(const QString& owner,
                                          const HistoryKeyring& current,
                                          const QString& chatId,
                                          int version,
                                          HistoryRootEntry& out,
                                          QString* error) {
    // Refuse before drawing any key material. Without this the locked-vault path would generate a
    // root, fail to seal it, and discard it - harmless, but it brings a key into existence for no
    // reason. Checking first means a refused mint never creates one.
    if (m_canSeal && !m_canSeal()) {
        setError(error, QStringLiteral("history keyring cannot be sealed: vault is locked"));
        return false;
    }
    QByteArray root;
    if (m_randomRoot) {
        root = m_randomRoot();
    } else {
        root.resize(HistoryCrypto::ROOT_BYTES);
        randombytes_buf(root.data(), static_cast<size_t>(root.size()));
    }
    if (root.size() != HistoryCrypto::ROOT_BYTES) {
        setError(error, QStringLiteral("history root generator produced %1 bytes")
                            .arg(root.size()));
        return false;
    }

    HistoryKeyring next;
    if (!current.withAddedVersion(chatId, version, root, next, error)) return false;
    if (!persist(owner, next, error)) return false;

    out.chatId = chatId;
    out.rootVersion = version;
    out.root = root;
    return true;
}

bool HistoryKeyringRepository::ensureRoot(const QString& owner,
                                          const QString& chatId,
                                          HistoryRootEntry& out,
                                          QString* error) {
    if (chatId.isEmpty()) {
        setError(error, QStringLiteral("chatId must not be empty"));
        return false;
    }
    HistoryKeyring keyring;
    if (!load(owner, keyring, error)) return false;

    // An existing root is served straight from the opened keyring - no vault needed. Only minting
    // reaches persist(), which is where the vault becomes mandatory.
    if (keyring.latest(chatId, out)) return true;

    return addVersion(owner, keyring, chatId, 1, out, error);
}

bool HistoryKeyringRepository::rotate(const QString& owner,
                                      const QString& chatId,
                                      HistoryRootEntry& out,
                                      QString* error) {
    if (chatId.isEmpty()) {
        setError(error, QStringLiteral("chatId must not be empty"));
        return false;
    }
    HistoryKeyring keyring;
    if (!load(owner, keyring, error)) return false;

    // Rotation adds a version and never replaces one, because older archives still need their old
    // root to open.
    const int next = keyring.latestVersion(chatId) + 1;
    return addVersion(owner, keyring, chatId, next, out, error);
}

bool HistoryKeyringRepository::importFromRecovery(const QString& owner,
                                                  const HistoryKeyring& incoming,
                                                  bool* imported,
                                                  QString* error) {
    if (imported) *imported = false;
    if (owner.isEmpty()) {
        setError(error, QStringLiteral("owner must not be empty"));
        return false;
    }

    HistoryKeyring local;
    if (!load(owner, local, error)) return false;

    // Union, entry by entry. Local first, so a local root is never displaced by a remote one.
    HistoryKeyring merged = local;
    bool added = false;
    for (const HistoryRootEntry& e : incoming.entries()) {
        HistoryRootEntry existing;
        if (merged.find(e.chatId, e.rootVersion, existing)) {
            // Same slot, different key: exactly one of these opens the archives filed under it and
            // nothing here can tell which. Refuse rather than guess.
            if (existing.root != e.root) {
                setError(error, QStringLiteral("recovered keyring conflicts with the local one at "
                                               "chat %1 version %2")
                                    .arg(e.chatId)
                                    .arg(e.rootVersion));
                return false;
            }
            continue; // Already held, byte-identical: nothing to do.
        }
        HistoryKeyring next;
        if (!merged.withAddedVersion(e.chatId, e.rootVersion, e.root, next, error)) return false;
        merged = next;
        added = true;
    }

    // Nothing new means nothing to write. Persisting anyway would bump the revision and, on the
    // Android side, provoke a pointless re-upload of a keyring the server already has.
    if (!added) {
        if (imported) *imported = false;
        return true;
    }

    if (!persist(owner, merged, error)) return false;
    if (imported) *imported = true;
    return true;
}
