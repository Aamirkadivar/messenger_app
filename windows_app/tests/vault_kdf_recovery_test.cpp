// Phase 31 - VaultCrypto HMAC key-length correction gate.
//
// Phase 30 found that the file-local HMAC helper called libsodium's one-shot
// crypto_auth_hmacsha256, which always reads exactly crypto_auth_hmacsha256_KEYBYTES (32) key bytes
// regardless of how long the caller's key actually is. HKDF-Extract passes the SALT as the HMAC key,
// and the two salts in this codebase are 29 bytes (history archive) and 16 bytes (recovery KEK), so
// every extract read past the end of its buffer and folded adjacent heap bytes into the PRK.
//
// That is why this file tests through the real production entry points rather than a copy: the point
// is not that HKDF is implementable, it is that VaultCrypto's own code no longer depends on memory it
// does not own. Every expected value below was computed from an INDEPENDENT RFC 5869 / RFC 2104
// implementation, never from this codebase, so a shared bug cannot make both sides agree.
//
// All key material here is synthetic - counting patterns and fixture strings. No production key,
// recovery key, salt, or account material appears in this file or in its output.

#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QTextStream>
#include <sodium.h>
#include <cstring>

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    out << (ok ? "  PASS  " : "  FAIL  ") << what << Qt::endl;
    if (!ok) ++g_failures;
}

// A key of the given length: key[i] = (0x40 + i) & 0xff.
static QByteArray patternKey(int n) {
    QByteArray k(n, 0);
    for (int i = 0; i < n; ++i) k[i] = static_cast<char>((0x40 + i) & 0xff);
    return k;
}

// The pre-fix helper, reproduced ONLY so the memory-safety checks below can show they have teeth.
// It is never linked into the application and exists purely as the negative control.
static QByteArray brokenHmacSha256(const QByteArray& key, const QByteArray& data) {
    QByteArray out(crypto_auth_hmacsha256_BYTES, 0);
    crypto_auth_hmacsha256(
        reinterpret_cast<unsigned char*>(out.data()),
        reinterpret_cast<const unsigned char*>(data.constData()),
        static_cast<unsigned long long>(data.size()),
        reinterpret_cast<const unsigned char*>(key.constData()));
    return out;
}

int main() {
    if (sodium_init() < 0) {
        QTextStream(stdout) << "  FAIL  libsodium failed to initialise" << Qt::endl;
        return 1;
    }
    QTextStream out(stdout);
    out << "Phase 31 - VaultCrypto HMAC key-length correction gate" << Qt::endl;

    // ------------------------------------------------------------------ HMAC key lengths
    // Independent HMAC-SHA256 references over the fixed message below.
    out << "-- HMAC-SHA256 at arbitrary key lengths (independent references) --" << Qt::endl;
    const QByteArray msg("phase31-hmac-fixture");
    struct HmacVec { int n; const char* hex; };
    const HmacVec kHmac[] = {
        {  16, "aff92d9aec0c3dde39de1422c140aa0e798bde64a812e3aa3aba43861a9fb230" },
        {  29, "c9b9b5943dbc17edc4ae2b5d169294b4a801879ef5a0788ac8b617276c9bd9be" },
        {  32, "a32a9acaa91907e11d48ad2e66e08ca20697cb3a3e4ea0c4fd30b0b55673eb56" },
        {  64, "869e7bc8977ff4f0267bdfd0013b6aa77a2586881604c4ed8659387a8d1b80ea" },
        { 100, "cbd10caebe14cee68f25842ea08bc0bd331b88f03417a7bb6fe592045b380b5c" },
    };
    for (const HmacVec& v : kHmac) {
        const QByteArray key = patternKey(v.n);
        QByteArray got(crypto_auth_hmacsha256_BYTES, 0);
        crypto_auth_hmacsha256_state st;
        crypto_auth_hmacsha256_init(&st,
            reinterpret_cast<const unsigned char*>(key.constData()),
            static_cast<size_t>(key.size()));
        crypto_auth_hmacsha256_update(&st,
            reinterpret_cast<const unsigned char*>(msg.constData()),
            static_cast<unsigned long long>(msg.size()));
        crypto_auth_hmacsha256_final(&st, reinterpret_cast<unsigned char*>(got.data()));
        check(got.toHex() == QByteArray(v.hex),
              QStringLiteral("HMAC with a %1-byte key matches the reference").arg(v.n));
    }

    // ------------------------------------------------- HKDF through the production function
    // HKDF-Extract uses the salt as the HMAC key, so varying the SALT length is what actually
    // exercises the corrected key handling inside VaultCrypto rather than in this file.
    out << "-- VaultCrypto::hkdfSha256 by salt length (independent references) --" << Qt::endl;
    QByteArray ikm(32, 0);
    for (int i = 0; i < 32; ++i) ikm[i] = static_cast<char>(i);
    const QByteArray info("phase31");
    struct HkdfVec { int n; const char* hex; };
    const HkdfVec kHkdf[] = {
        {  16, "ac567f1ae91e68782b2ddc4657d1da9736161b33f85f2ac7da2ff56e250368fe" },
        {  29, "855b78b3bb2397bde2ffdc2c6aa06b665cf0f69d1b2a0260d66d67c110afe85e" },
        {  32, "042b521a39f52354f99504593fce7bf4595261c6d6c1039cf6f753c12d15ba31" },
        {  64, "0ce6cb0a0288dbaeb54cae2d0baff3a817320c397d0ab5998239d8a6c7d002f5" },
        { 100, "82b6eb5cb453efbbd1ac4316b7e7c7108a5308437b3ac007ee5878635eaea114" },
    };
    for (const HkdfVec& v : kHkdf) {
        const QByteArray got = VaultCrypto::hkdfSha256(ikm, patternKey(v.n), info, 32);
        check(got.toHex() == QByteArray(v.hex),
              QStringLiteral("HKDF with a %1-byte salt matches the reference").arg(v.n));
    }

    // Repeatability: the same inputs must always give the same answer.
    {
        const QByteArray first = VaultCrypto::hkdfSha256(ikm, patternKey(29), info, 32);
        bool stable = true;
        for (int i = 0; i < 256; ++i)
            if (VaultCrypto::hkdfSha256(ikm, patternKey(29), info, 32) != first) stable = false;
        check(stable, QStringLiteral("HKDF is deterministic across 256 repeats"));
    }

    // --------------------------------------------------------------- memory-safety evidence
    // fromRawData points straight at these buffers without copying, so the bytes that follow the
    // key are ours to choose. Identical keys with deliberately different trailing bytes must give
    // identical results; under the old helper they did not, which is what the control shows.
    out << "-- memory safety: the bytes after the key must not matter --" << Qt::endl;
    {
        unsigned char bufA[96], bufB[96];
        const QByteArray salt = patternKey(16);
        memcpy(bufA, salt.constData(), 16); memset(bufA + 16, 0x00, sizeof(bufA) - 16);
        memcpy(bufB, salt.constData(), 16); memset(bufB + 16, 0xFF, sizeof(bufB) - 16);

        const QByteArray sA = QByteArray::fromRawData(reinterpret_cast<const char*>(bufA), 16);
        const QByteArray sB = QByteArray::fromRawData(reinterpret_cast<const char*>(bufB), 16);

        const QByteArray kA = VaultCrypto::hkdfSha256(ikm, sA, info, 32);
        const QByteArray kB = VaultCrypto::hkdfSha256(ikm, sB, info, 32);
        check(kA == kB,
              QStringLiteral("16-byte salt: trailing bytes 0x00 vs 0xFF give the same key"));
        check(kA.toHex() == QByteArray(kHkdf[0].hex),
              QStringLiteral("16-byte salt: result still matches the reference"));

        // Negative control: the old helper demonstrably did depend on those bytes.
        check(brokenHmacSha256(sA, ikm) != brokenHmacSha256(sB, ikm),
              QStringLiteral("control: the OLD helper did depend on the trailing bytes"));

        // And a 29-byte salt, the history-archive case.
        unsigned char cA[96], cB[96];
        const QByteArray s29 = QByteArray("messenger/history-kdf-salt/v1");
        memcpy(cA, s29.constData(), 29); memset(cA + 29, 0x00, sizeof(cA) - 29);
        memcpy(cB, s29.constData(), 29); memset(cB + 29, 0x5A, sizeof(cB) - 29);
        const QByteArray k29A = VaultCrypto::hkdfSha256(
            ikm, QByteArray::fromRawData(reinterpret_cast<const char*>(cA), 29), info, 32);
        const QByteArray k29B = VaultCrypto::hkdfSha256(
            ikm, QByteArray::fromRawData(reinterpret_cast<const char*>(cB), 29), info, 32);
        check(k29A == k29B,
              QStringLiteral("29-byte salt: trailing bytes 0x00 vs 0x5A give the same key"));
    }

    // -------------------------------------------------------------------- recovery KEK
    out << "-- recovery KEK (16-byte salt, synthetic material) --" << Qt::endl;
    QByteArray recoveryKey(32, 0);
    for (int i = 0; i < 32; ++i) recoveryKey[i] = static_cast<char>((0xA0 + i) & 0xff);
    QByteArray rkSalt(16, 0);
    for (int i = 0; i < 16; ++i) rkSalt[i] = static_cast<char>(0x10 + i);

    const QByteArray kek = VaultCrypto::deriveRecoveryKek(recoveryKey, rkSalt);
    check(kek.toHex() == QByteArray("ae7a5195cd3b02a981ce33d6d0bb82a60d181fb073184cf4e79f18245396aaa7"),
          QStringLiteral("deriveRecoveryKek matches the independent reference"));
    check(kek == VaultCrypto::deriveRecoveryKek(recoveryKey, rkSalt),
          QStringLiteral("deriveRecoveryKek is deterministic"));

    // Allocation independence for the recovery path specifically.
    {
        unsigned char rbuf[64];
        memcpy(rbuf, rkSalt.constData(), 16); memset(rbuf + 16, 0xCC, sizeof(rbuf) - 16);
        const QByteArray viaRaw = VaultCrypto::deriveRecoveryKek(
            recoveryKey, QByteArray::fromRawData(reinterpret_cast<const char*>(rbuf), 16));
        const QByteArray viaB64 = VaultCrypto::deriveRecoveryKek(
            recoveryKey, VaultCrypto::unb64(VaultCrypto::b64(rkSalt)));
        check(viaRaw == kek && viaB64 == kek,
              QStringLiteral("recovery KEK is identical across three allocation contexts"));
    }

    // Wrap / unwrap round trip with synthetic material, mirroring buildVault -> unlockWithRecovery.
    {
        const QByteArray aad = VaultCrypto::masterKeyAad(QStringLiteral("fixture-user"),
                                                         QStringLiteral("recovery"));
        QByteArray syntheticMk(32, 0);
        for (int i = 0; i < 32; ++i) syntheticMk[i] = static_cast<char>(0x70 + i);

        const QByteArray wrapped = VaultCrypto::sealXChaCha(kek, syntheticMk, aad);
        check(!wrapped.isEmpty(), QStringLiteral("synthetic recovery secret wraps"));

        // Unwrap re-derives the KEK from the persisted salt, as the real unlock path does.
        const QByteArray kek2 = VaultCrypto::deriveRecoveryKek(
            recoveryKey, VaultCrypto::unb64(VaultCrypto::b64(rkSalt)));
        check(VaultCrypto::openXChaCha(kek2, wrapped, aad) == syntheticMk,
              QStringLiteral("wrap -> unwrap round-trips across a persisted salt"));

        QByteArray wrongRk = recoveryKey; wrongRk[0] = static_cast<char>(0x00);
        check(VaultCrypto::openXChaCha(
                  VaultCrypto::deriveRecoveryKek(wrongRk, rkSalt), wrapped, aad).isEmpty(),
              QStringLiteral("a wrong recovery key fails to unwrap"));

        QByteArray wrongSalt = rkSalt; wrongSalt[0] = static_cast<char>(0x00);
        check(VaultCrypto::openXChaCha(
                  VaultCrypto::deriveRecoveryKek(recoveryKey, wrongSalt), wrapped, aad).isEmpty(),
              QStringLiteral("a wrong salt fails to unwrap"));
    }

    // Input guards are unchanged by this phase; pinned so the fix cannot loosen them.
    check(VaultCrypto::deriveRecoveryKek(QByteArray(31, 'k'), rkSalt).isEmpty(),
          QStringLiteral("a short recovery key is refused"));
    check(VaultCrypto::deriveRecoveryKek(recoveryKey, QByteArray(15, 's')).isEmpty(),
          QStringLiteral("a short salt is refused"));

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
