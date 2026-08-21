#include "mlscore.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QLoggingCategory>

namespace {

// Mirrors include/mls_core.h. Kept as local typedefs so this translation unit
// does not depend on the generated header's include path - the ABI contract is
// what matters, and it is asserted at runtime by mls_abi_version().
struct MlsBuf {
    quint8* ptr;
    size_t len;
};

using FnAbiVersion = quint32 (*)();
using FnVersion = const char* (*)();
using FnLastError = const char* (*)();
using FnBufFree = void (*)(MlsBuf);
using FnPing = qint32 (*)(const char*, MlsBuf*);
using MlsLogCb = void (*)(qint32, const char*);
using FnSetLog = void (*)(MlsLogCb);
using FnClearLog = void (*)();

using FnClientNew = qint32 (*)(const char*, const char*, void**);
using FnClientRestore = qint32 (*)(const quint8*, size_t, const char*, const char*, void**);
using FnClientFree = void (*)(void*);
using FnHandleBuf = qint32 (*)(void*, MlsBuf*);
using FnGroupCreate = qint32 (*)(void*, const quint8*, size_t);
using FnGroupAdd = qint32 (*)(void*, const quint8*, size_t, const quint8*, size_t, MlsBuf*);
using FnMergePending = qint32 (*)(void*, const quint8*, size_t, quint64*);
using FnClearPending = qint32 (*)(void*, const quint8*, size_t);
using FnJoinWelcome = qint32 (*)(void*, const quint8*, size_t, MlsBuf*);
using FnEncrypt = qint32 (*)(void*, const quint8*, size_t, const quint8*, size_t, MlsBuf*);
using FnProcess = qint32 (*)(void*, const quint8*, size_t, const quint8*, size_t, MlsBuf*);
using FnEpoch = qint32 (*)(void*, const quint8*, size_t, quint64*);
using FnDropGroup = qint32 (*)(void*, const quint8*, size_t);
using FnRoster = qint32 (*)(void*, const quint8*, size_t, MlsBuf*);

FnAbiVersion g_abiVersion = nullptr;
FnVersion g_version = nullptr;
FnLastError g_lastError = nullptr;
FnBufFree g_bufFree = nullptr;
FnPing g_ping = nullptr;
FnSetLog g_setLog = nullptr;
FnClearLog g_clearLog = nullptr;

FnClientNew g_clientNew = nullptr;
FnClientRestore g_clientRestore = nullptr;
FnClientFree g_clientFree = nullptr;
FnHandleBuf g_snapshot = nullptr;
FnHandleBuf g_keyPackage = nullptr;
FnGroupCreate g_groupCreate = nullptr;
FnGroupAdd g_groupAdd = nullptr;
FnMergePending g_mergePending = nullptr;
FnClearPending g_clearPending = nullptr;
FnJoinWelcome g_joinWelcome = nullptr;
FnEncrypt g_encrypt = nullptr;
FnProcess g_process = nullptr;
FnEpoch g_epoch = nullptr;
FnEpoch g_loadGroup = nullptr;
FnRoster g_roster = nullptr;
FnDropGroup g_dropGroup = nullptr;

// Set by the callback below so selfTest() can prove the log channel works.
// Qt silently dropped qInfo for an entire debugging session on the previous
// design, so "the sink is installed" is not the same as "the sink delivers".
bool g_logFired = false;

void coreLog(qint32 level, const char* msg) {
    g_logFired = true;
    const QString line = QStringLiteral("[mls-core] %1").arg(QString::fromUtf8(msg));
    if (level >= 3) qWarning().noquote() << line;
    else qInfo().noquote() << line;
}

// Takes ownership: reads the buffer and always frees it, so no caller can leak
// by forgetting. Rust allocated it; only mls_buf_free may release it.
QByteArray takeBuf(MlsBuf buf) {
    if (!buf.ptr || buf.len == 0) {
        if (g_bufFree) g_bufFree(buf);
        return {};
    }
    QByteArray out(reinterpret_cast<const char*>(buf.ptr), int(buf.len));
    if (g_bufFree) g_bufFree(buf);
    return out;
}

QString coreError() {
    if (!g_lastError) return QStringLiteral("unknown error");
    const char* e = g_lastError();
    return e ? QString::fromUtf8(e) : QStringLiteral("unknown error");
}

} // namespace

MlsCore& MlsCore::instance() {
    static MlsCore inst;
    return inst;
}

bool MlsCore::resolve() {
    g_abiVersion = reinterpret_cast<FnAbiVersion>(m_lib.resolve("mls_abi_version"));
    g_version    = reinterpret_cast<FnVersion>(m_lib.resolve("mls_version"));
    g_lastError  = reinterpret_cast<FnLastError>(m_lib.resolve("mls_last_error"));
    g_bufFree    = reinterpret_cast<FnBufFree>(m_lib.resolve("mls_buf_free"));
    g_ping       = reinterpret_cast<FnPing>(m_lib.resolve("mls_ping"));
    g_setLog     = reinterpret_cast<FnSetLog>(m_lib.resolve("mls_set_log_callback"));
    g_clearLog   = reinterpret_cast<FnClearLog>(m_lib.resolve("mls_clear_log_callback"));

    g_clientNew      = reinterpret_cast<FnClientNew>(m_lib.resolve("mls_client_new"));
    g_clientRestore  = reinterpret_cast<FnClientRestore>(m_lib.resolve("mls_client_restore"));
    g_clientFree     = reinterpret_cast<FnClientFree>(m_lib.resolve("mls_client_free"));
    g_snapshot       = reinterpret_cast<FnHandleBuf>(m_lib.resolve("mls_client_snapshot"));
    g_keyPackage     = reinterpret_cast<FnHandleBuf>(m_lib.resolve("mls_client_key_package"));
    g_groupCreate    = reinterpret_cast<FnGroupCreate>(m_lib.resolve("mls_group_create"));
    g_groupAdd       = reinterpret_cast<FnGroupAdd>(m_lib.resolve("mls_group_add"));
    g_mergePending   = reinterpret_cast<FnMergePending>(m_lib.resolve("mls_merge_pending"));
    g_clearPending   = reinterpret_cast<FnClearPending>(m_lib.resolve("mls_clear_pending"));
    g_joinWelcome    = reinterpret_cast<FnJoinWelcome>(m_lib.resolve("mls_join_welcome"));
    g_encrypt        = reinterpret_cast<FnEncrypt>(m_lib.resolve("mls_encrypt"));
    g_process        = reinterpret_cast<FnProcess>(m_lib.resolve("mls_process"));
    g_epoch          = reinterpret_cast<FnEpoch>(m_lib.resolve("mls_epoch"));
    g_loadGroup      = reinterpret_cast<FnEpoch>(m_lib.resolve("mls_load_group"));
    g_roster         = reinterpret_cast<FnRoster>(m_lib.resolve("mls_roster"));
    g_dropGroup      = reinterpret_cast<FnDropGroup>(m_lib.resolve("mls_drop_group"));
    m_clientApi = g_clientNew && g_clientRestore && g_clientFree && g_snapshot
                  && g_keyPackage && g_groupCreate && g_groupAdd && g_mergePending
                  && g_clearPending && g_joinWelcome && g_encrypt && g_process
                  && g_epoch && g_loadGroup && g_roster && g_dropGroup;

    if (!g_abiVersion || !g_version || !g_bufFree || !g_ping) {
        m_lastError = QStringLiteral("missing symbols in mls_core: %1").arg(m_lib.errorString());
        return false;
    }
    return true;
}

bool MlsCore::load() {
    if (m_loaded) return true;

    // Next to the executable first (how it ships), then the cargo output dir so
    // a developer build works without copying the DLL each time.
    const QString appDir = QCoreApplication::applicationDirPath();
    const QStringList candidates = {
        appDir + QStringLiteral("/mls_core.dll"),
        appDir + QStringLiteral("/../../mls-core/target/release/mls_core.dll"),
        QStringLiteral("D:/messenger_app/mls-core/target/release/mls_core.dll"),
        QStringLiteral("mls_core"), // let the OS search path decide
    };

    for (const QString& path : candidates) {
        m_lib.setFileName(path);
        if (m_lib.load()) break;
    }
    if (!m_lib.isLoaded()) {
        m_lastError = QStringLiteral("could not load mls_core.dll: %1").arg(m_lib.errorString());
        qWarning().noquote() << "[mls-core]" << m_lastError;
        return false;
    }

    if (!resolve()) {
        qWarning().noquote() << "[mls-core]" << m_lastError;
        m_lib.unload();
        return false;
    }

    m_abi = g_abiVersion();
    if (m_abi != kExpectedAbi) {
        // Refuse rather than call: a mismatched ABI is memory corruption, and
        // it would present as an unrelated crash somewhere else entirely.
        m_lastError = QStringLiteral("ABI mismatch: core reports %1, client expects %2")
                          .arg(m_abi).arg(kExpectedAbi);
        qWarning().noquote() << "[mls-core]" << m_lastError;
        m_lib.unload();
        return false;
    }

    // Install the log sink BEFORE anything else runs, so a failure inside the
    // core is visible instead of silent.
    if (g_setLog) g_setLog(&coreLog);

    m_version = QString::fromUtf8(g_version());
    m_loaded = true;
    qInfo().noquote() << QStringLiteral("[mls-core] loaded %1 (abi %2) from %3")
                             .arg(m_version).arg(m_abi)
                             .arg(QFileInfo(m_lib.fileName()).absoluteFilePath());
    return true;
}

bool MlsCore::clientApiReady() {
    if (!load()) return false;
    return m_clientApi;
}

QString MlsCore::ping(const QString& name) {
    if (!load()) return {};

    const QByteArray utf8 = name.toUtf8();
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_ping(utf8.constData(), &out);
    // Free unconditionally: the core guarantees `out` is a valid (possibly
    // empty) buffer even on the error paths.
    const QByteArray reply = takeBuf(out);
    if (rc != 0) {
        m_lastError = QStringLiteral("mls_ping failed (%1): %2").arg(rc).arg(coreError());
        qWarning().noquote() << "[mls-core]" << m_lastError;
        return {};
    }
    return QString::fromUtf8(reply);
}

bool MlsCore::selfTest() {
    qInfo().noquote() << "[mls-core] selftest: loading";
    if (!load()) {
        qWarning().noquote() << "[mls-core] selftest FAILED:" << m_lastError;
        return false;
    }

    g_logFired = false;
    const QString reply = ping(QStringLiteral("qt-bridge"));
    if (!reply.endsWith(QStringLiteral(": qt-bridge"))) {
        qWarning().noquote() << "[mls-core] selftest FAILED: unexpected reply" << reply;
        return false;
    }
    qInfo().noquote() << "[mls-core] selftest: round-trip ok ->" << reply;

    if (!g_logFired) {
        // Not fatal for crypto, but fatal for debuggability - and losing the
        // diagnostic channel is exactly what made the previous design opaque.
        qWarning().noquote() << "[mls-core] selftest WARNING: log callback never fired";
    } else {
        qInfo().noquote() << "[mls-core] selftest: log callback ok";
    }

    // Error path must return a code, not crash the host.
    if (g_lastError) {
        MlsBuf out{ nullptr, 0 };
        const qint32 rc = g_ping(nullptr, &out);
        takeBuf(out);
        if (rc == 0) {
            qWarning().noquote() << "[mls-core] selftest FAILED: null arg was accepted";
            return false;
        }
        qInfo().noquote() << "[mls-core] selftest: null-arg rejected with" << rc;
    }

    qInfo().noquote() << "[mls-core] selftest PASSED";
    return true;
}

static QByteArray callHandleBuf(FnHandleBuf fn, void* handle) {
    if (!fn || !handle) return {};
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = fn(handle, &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) {
        MlsCore::instance(); // keep lastError path consistent
        return {};
    }
    return data;
}

void* MlsCore::clientNew(const QString& userId, const QString& deviceId) {
    if (!load() || !g_clientNew) return nullptr;
    const QByteArray u = userId.toUtf8();
    const QByteArray d = deviceId.toUtf8();
    void* h = nullptr;
    const qint32 rc = g_clientNew(u.constData(), d.constData(), &h);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] clientNew:" << m_lastError;
        return nullptr;
    }
    return h;
}

void* MlsCore::clientRestore(const QByteArray& blob, const QString& userId, const QString& deviceId) {
    if (!load() || !g_clientRestore) return nullptr;
    const QByteArray u = userId.toUtf8();
    const QByteArray d = deviceId.toUtf8();
    void* h = nullptr;
    const qint32 rc = g_clientRestore(
        reinterpret_cast<const quint8*>(blob.constData()), size_t(blob.size()),
        u.constData(), d.constData(), &h);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] clientRestore:" << m_lastError;
        return nullptr;
    }
    return h;
}

void MlsCore::clientFree(void* handle) {
    if (g_clientFree && handle) g_clientFree(handle);
}

QByteArray MlsCore::snapshot(void* handle) {
    const QByteArray out = callHandleBuf(g_snapshot, handle);
    if (out.isEmpty() && handle) m_lastError = coreError();
    return out;
}

QByteArray MlsCore::keyPackage(void* handle) {
    const QByteArray out = callHandleBuf(g_keyPackage, handle);
    if (out.isEmpty() && handle) m_lastError = coreError();
    return out;
}

bool MlsCore::groupCreate(void* handle, const QByteArray& gid) {
    if (!g_groupCreate || !handle) return false;
    const qint32 rc = g_groupCreate(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()));
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] groupCreate:" << m_lastError;
        return false;
    }
    return true;
}

QByteArray MlsCore::groupAdd(void* handle, const QByteArray& gid, const QList<QByteArray>& keyPackages) {
    if (!g_groupAdd || !handle || keyPackages.isEmpty()) return {};
    QByteArray packed;
    for (const QByteArray& kp : keyPackages) {
        const quint32 n = quint32(kp.size());
        packed.append(char((n >> 24) & 0xff));
        packed.append(char((n >> 16) & 0xff));
        packed.append(char((n >> 8) & 0xff));
        packed.append(char(n & 0xff));
        packed.append(kp);
    }
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_groupAdd(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()),
        reinterpret_cast<const quint8*>(packed.constData()), size_t(packed.size()),
        &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] groupAdd:" << m_lastError;
        return {};
    }
    return data;
}

qint64 MlsCore::mergePending(void* handle, const QByteArray& gid) {
    if (!g_mergePending || !handle) return -1;
    quint64 epoch = 0;
    const qint32 rc = g_mergePending(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()), &epoch);
    if (rc != 0) {
        m_lastError = coreError();
        return -1;
    }
    return qint64(epoch);
}

bool MlsCore::clearPending(void* handle, const QByteArray& gid) {
    if (!g_clearPending || !handle) return false;
    const qint32 rc = g_clearPending(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()));
    if (rc != 0) {
        m_lastError = coreError();
        return false;
    }
    return true;
}

QByteArray MlsCore::joinWelcome(void* handle, const QByteArray& welcome) {
    if (!g_joinWelcome || !handle) return {};
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_joinWelcome(handle,
        reinterpret_cast<const quint8*>(welcome.constData()), size_t(welcome.size()), &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] joinWelcome:" << m_lastError;
        return {};
    }
    return data;
}

QByteArray MlsCore::encrypt(void* handle, const QByteArray& gid, const QByteArray& plaintext) {
    if (!g_encrypt || !handle) return {};
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_encrypt(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()),
        reinterpret_cast<const quint8*>(plaintext.constData()), size_t(plaintext.size()),
        &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] encrypt:" << m_lastError;
        return {};
    }
    return data;
}

QByteArray MlsCore::process(void* handle, const QByteArray& gid, const QByteArray& msg) {
    if (!g_process || !handle) return {};
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_process(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()),
        reinterpret_cast<const quint8*>(msg.constData()), size_t(msg.size()),
        &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) {
        m_lastError = coreError();
        qWarning().noquote() << "[mls-core] process:" << m_lastError;
        return {};
    }
    return data;
}

qint64 MlsCore::epoch(void* handle, const QByteArray& gid) {
    if (!g_epoch || !handle) return -1;
    quint64 epoch = 0;
    const qint32 rc = g_epoch(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()), &epoch);
    if (rc != 0) return -1;
    return qint64(epoch);
}

qint64 MlsCore::loadGroup(void* handle, const QByteArray& gid) {
    if (!g_loadGroup || !handle) return -1;
    quint64 epoch = 0;
    const qint32 rc = g_loadGroup(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()), &epoch);
    if (rc != 0) return -1;
    return qint64(epoch);
}

QByteArray MlsCore::roster(void* handle, const QByteArray& gid) {
    if (!g_roster || !handle) return {};
    MlsBuf out{ nullptr, 0 };
    const qint32 rc = g_roster(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()), &out);
    const QByteArray data = takeBuf(out);
    if (rc != 0) return {};
    return data;
}

bool MlsCore::dropGroup(void* handle, const QByteArray& gid) {
    if (!g_dropGroup || !handle) return false;
    const qint32 rc = g_dropGroup(handle,
        reinterpret_cast<const quint8*>(gid.constData()), size_t(gid.size()));
    if (rc != 0) {
        m_lastError = coreError();
        return false;
    }
    return true;
}
