#pragma once

#include <QByteArray>
#include <QLibrary>
#include <QList>
#include <QString>

// Thin bridge to mls-core (Rust/OpenMLS). See docs/mls-v2-architecture.md.
//
// Deliberately loaded at RUNTIME via QLibrary rather than link-time:
//
//  * The Rust core is built with the MSVC ABI while this app is built with
//    MinGW. The FFI is pure C and Rust owns every allocation (the host only
//    ever calls mls_buf_free), so no CRT objects cross the boundary and the mix
//    is safe - but link-time binding would still need an import library
//    generated per toolchain. Runtime resolution sidesteps that entirely.
//  * A missing or mismatched core degrades to "MLS unavailable" instead of
//    refusing to start the app, which matters while v1 Sender Keys are still
//    the shipping path.
//
// This class holds NO cryptographic logic. If it ever starts parsing an MLS
// structure, the architecture has been violated - that belongs in Rust.
class MlsCore {
public:
    static MlsCore& instance();

    // Resolves the library and verifies the ABI. Safe to call repeatedly.
    // Returns false and sets lastError() when the core is unusable.
    bool load();

    bool isAvailable() const { return m_loaded; }
    bool clientApiReady();
    QString coreVersion() const { return m_version; }
    quint32 abiVersion() const { return m_abi; }
    QString lastError() const { return m_lastError; }

    // Phase 0 round-trip. Returns the core's reply, or an empty string on
    // failure with lastError() set.
    QString ping(const QString& name);

    // Dev check: load, verify ABI, round-trip, and confirm the log callback
    // actually fired. Logs each step. Run via E2EE_SELFTEST=1.
    bool selfTest();

    // The ABI this client is written against. A core reporting anything else is
    // rejected rather than called - a silent ABI mismatch is memory corruption.
    static constexpr quint32 kExpectedAbi = 1;

    // ---- opaque client (same operations Android calls through JNI) ----
    void* clientNew(const QString& userId, const QString& deviceId);
    void* clientRestore(const QByteArray& blob, const QString& userId, const QString& deviceId);
    void clientFree(void* handle);
    QByteArray snapshot(void* handle);
    QByteArray keyPackage(void* handle);
    bool groupCreate(void* handle, const QByteArray& gid);
    // Packed: u32be(commit_len) || commit || welcome. Empty on failure.
    QByteArray groupAdd(void* handle, const QByteArray& gid, const QList<QByteArray>& keyPackages);
    qint64 mergePending(void* handle, const QByteArray& gid);
    bool clearPending(void* handle, const QByteArray& gid);
    QByteArray joinWelcome(void* handle, const QByteArray& welcome);
    QByteArray encrypt(void* handle, const QByteArray& gid, const QByteArray& plaintext);
    // [kind] || payload. kind 1 = application, 2 = commit, 3 = proposal.
    QByteArray process(void* handle, const QByteArray& gid, const QByteArray& msg);
    qint64 epoch(void* handle, const QByteArray& gid);
    qint64 loadGroup(void* handle, const QByteArray& gid);
    QByteArray roster(void* handle, const QByteArray& gid);
    bool dropGroup(void* handle, const QByteArray& gid);

private:
    MlsCore() = default;
    Q_DISABLE_COPY_MOVE(MlsCore)

    bool resolve();

    QLibrary m_lib;
    bool m_loaded = false;
    bool m_clientApi = false;
    QString m_version;
    QString m_lastError;
    quint32 m_abi = 0;
};
