// Phase 35 - Windows history-archive sealing primitive.
//
// Phase 30/31 proved the deterministic halves of the archive protocol - the per-message key and the
// AAD - and that Windows agrees with an independent AEAD reference. What was still missing was a
// sealing entry point that is deterministic by construction: HistoryCrypto::seal draws its own
// nonce, so it could never reproduce a vector. This proves HistoryCrypto::sealWithNonce against two
// fixed vectors whose expected bytes were computed OUTSIDE this codebase.
//
// The expected ciphertexts come from an independent pure-Python XChaCha20-Poly1305-IETF
// implementation (HChaCha20 + RFC 8439), itself validated against the RFC 8439 and HChaCha20
// known-answer vectors before use. The expected key and AAD for fixture 1 are the strings Android's
// CrossPlatformArchiveFixtureTest already asserts, and fixture 2's are asserted by the Android test
// extended in this phase. Nothing here is checked against this implementation's own output.
//
// Fixture 1 is the existing Phase 30/31 vector, kept so continuity with the established
// Android-compatible answer is visible. Fixture 2 is new and deliberately awkward: multibyte UTF-8,
// embedded NUL bytes, and high-bit bytes, because an ASCII-only fixture would not catch an encoding
// or length bug.
//
// No key material, root, or plaintext is printed. Every value is synthetic; nothing here touches an
// account, a database, the network, or a real message.

#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
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

// -------------------------------------------------------------- fixture 1 (Phase 30/31)

static const char* kF1User = "fixture-user";
static const char* kF1Chat = "fixture-chat";
static const char* kF1Msg  = "00000000-0000-0000-0000-000000000001";
static const char* kF1Plain = "Phase30-cross-platform-archive-fixture";
static const char* kF1NonceHex = "202122232425262728292a2b2c2d2e2f3031323334353637";
static const char* kF1KeyHex =
    "c9a0f84cdf1d4e4ca878343145486faa8544f5d2a4f154bc98ecf811cc342a29";
static const char* kF1CtTagHex =
    "484c61e4c0418e1d6fa00fd3e8dc46d0e833e1c8d19bcf7fb362aadf5155cb7c290f4dc08bef704a120b4280a796dc50935617618bcd";
static const char* kF1WireB64 =
    "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3SExh5MBBjh1voA/T6NxG0Ogz4cjRm89/s2Kq31FVy3wpD03Ai+9wShILQoCnltxQk1YXYYvN";

// -------------------------------------------------------------- fixture 2 (Phase 35, rich)

static const char* kF2User = "9f1c0d4e-0000-4000-8000-0000000000a1";
static const char* kF2Chat = "3b7e5f21-0000-4000-8000-0000000000c2";
static const char* kF2Msg  = "7d2a91b0-0000-4000-8000-0000000000e3";
// "P35 archive fixture: " | UTF-8 (Latin-1 accents, U+2713, Japanese, Greek) | 00 00 01 | ff fe 80 7f | " end"
static const char* kF2PlainHex =
    "503335206172636869766520666978747572653a2068c3a96c6c6f2077c3b6726c6420e29c9320"
    "e697a5e69cace8aa9e20ce95cebbcebbceb7cebdceb9cebaceac000001fffe807f20656e64";
static const char* kF2NonceHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7";
static const char* kF2KeyHex =
    "38ee0d416f4dfdab44533f22636202927b122df19f7537f7b40ef463c8ca3899";
static const char* kF2AadHex =
    "000000186d657373656e6765722f686973746f72792d6161642f7631000000010000002439663163306434"
    "652d303030302d343030302d383030302d3030303030303030303061310000002433623765356632312d30"
    "3030302d343030302d383030302d3030303030303030303063320000002437643261393162302d30303030"
    "2d343030302d383030302d30303030303030303030653300000001";
static const char* kF2WireB64 =
    "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3h641gShq9PbpgibJgl1KbsA5WVozb7pWlzuY6B7HDRWkGWxlzIVsLR"
    "dDXKmPjgWah3zyVGw/I/L7L8aAhB2NlGIM6yAzb1Lzu39sI83Oi8x7oo5GUA+BTTrkTxw=";

static QByteArray root32() {
    QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
    for (int i = 0; i < r.size(); ++i) r[i] = static_cast<char>(i);
    return r;
}
static QByteArray hex(const char* h) { return QByteArray::fromHex(QByteArray(h).simplified()); }

static HistoryCrypto::Context ctxOf(const char* u, const char* c, const char* m, int rv = 1) {
    HistoryCrypto::Context x;
    x.userId = QString::fromUtf8(u);
    x.chatId = QString::fromUtf8(c);
    x.messageId = QString::fromUtf8(m);
    x.rootVersion = rv;
    x.protocolVersion = HistoryCrypto::PROTOCOL_VERSION;
    return x;
}

int main() {
    if (sodium_init() < 0) { QTextStream(stdout) << "  FAIL  libsodium init" << Qt::endl; return 1; }
    QTextStream out(stdout);
    out << "Phase 35 - deterministic history-archive sealing" << Qt::endl;

    const QByteArray root = root32();

    // ============================================================ fixture 1
    section("fixture 1: the established Phase 30/31 Android-compatible vector");
    {
        const auto ctx = ctxOf(kF1User, kF1Chat, kF1Msg);
        const QByteArray pt(kF1Plain);
        const QByteArray nonce = hex(kF1NonceHex);

        check(HistoryCrypto::deriveMessageKey(root, ctx.messageId, ctx.rootVersion).toHex() ==
                  QByteArray(kF1KeyHex),
              QStringLiteral("derived key matches the Android-asserted value"));

        const QByteArray wire = HistoryCrypto::sealWithNonce(root, ctx, pt, nonce);
        check(!wire.isEmpty(), QStringLiteral("sealWithNonce produces a record"));
        check(wire.mid(HistoryCrypto::NONCE_BYTES).toHex() == QByteArray(kF1CtTagHex),
              QStringLiteral("ciphertext||tag matches the independent reference"));
        check(HistoryCrypto::toWireB64(wire) == QString::fromLatin1(kF1WireB64),
              QStringLiteral("Base64 wire form matches the established vector"));
        check(HistoryCrypto::open(root, ctx, wire) == pt,
              QStringLiteral("Windows seal -> Windows open recovers the plaintext"));
    }

    // ============================================================ fixture 2
    section("fixture 2: multibyte UTF-8, NUL bytes, high-bit bytes");
    const auto ctx2 = ctxOf(kF2User, kF2Chat, kF2Msg);
    const QByteArray pt2 = hex(kF2PlainHex);
    const QByteArray nonce2 = hex(kF2NonceHex);
    QByteArray wire2;
    {
        // Pin that the fixture really is awkward, so it cannot quietly decay into ASCII.
        check(pt2.contains('\0'), QStringLiteral("plaintext contains NUL bytes"));
        bool highBit = false;
        for (char c : pt2) if (static_cast<unsigned char>(c) > 0x7F) highBit = true;
        check(highBit, QStringLiteral("plaintext contains high-bit bytes"));
        check(QString::fromUtf8(pt2.left(60)).size() != pt2.left(60).size(),
              QStringLiteral("plaintext contains multibyte UTF-8"));
        check(pt2.size() == 76, QStringLiteral("plaintext is the expected 76 bytes"));

        check(HistoryCrypto::deriveMessageKey(root, ctx2.messageId, ctx2.rootVersion).toHex() ==
                  QByteArray(kF2KeyHex),
              QStringLiteral("derived key matches the independent reference"));
        check(HistoryCrypto::aad(ctx2).toHex() == hex(kF2AadHex).toHex(),
              QStringLiteral("AAD matches the independent reference"));

        wire2 = HistoryCrypto::sealWithNonce(root, ctx2, pt2, nonce2);
        check(!wire2.isEmpty(), QStringLiteral("sealWithNonce produces a record"));
        check(HistoryCrypto::toWireB64(wire2) == QString::fromLatin1(kF2WireB64),
              QStringLiteral("Base64 wire form matches the independent reference"));
        check(HistoryCrypto::open(root, ctx2, wire2) == pt2,
              QStringLiteral("round trip recovers the exact plaintext, NULs included"));
        check(HistoryCrypto::openFromWireB64(root, ctx2, QString::fromLatin1(kF2WireB64)) == pt2,
              QStringLiteral("the Base64 wire API opens the reference record"));
    }

    section("determinism, wire length, nonce placement");
    {
        bool stable = true;
        for (int i = 0; i < 100; ++i)
            if (HistoryCrypto::sealWithNonce(root, ctx2, pt2, nonce2) != wire2) stable = false;
        check(stable, QStringLiteral("100 repeats are byte-identical"));

        check(wire2.size() == HistoryCrypto::NONCE_BYTES + pt2.size() + HistoryCrypto::TAG_BYTES,
              QStringLiteral("wire length is exactly 24 + N + 16"));
        check(wire2.size() == 116, QStringLiteral("wire length is 116 for this 76-byte plaintext"));
        check(wire2.left(HistoryCrypto::NONCE_BYTES) == nonce2,
              QStringLiteral("wire[0..23] is exactly the supplied nonce, with nothing before it"));

        // An empty plaintext is still a valid record of nonce + tag only.
        const QByteArray empty = HistoryCrypto::sealWithNonce(root, ctx2, QByteArray(), nonce2);
        check(empty.size() == 24 + 16, QStringLiteral("an empty plaintext yields a 40-byte record"));
        check(HistoryCrypto::open(root, ctx2, empty) == QByteArray(),
              QStringLiteral("an empty plaintext round-trips"));

        // The random-nonce production path must agree in framing and remain non-deterministic.
        const QByteArray r1 = HistoryCrypto::seal(root, ctx2, pt2);
        const QByteArray r2 = HistoryCrypto::seal(root, ctx2, pt2);
        check(r1.size() == wire2.size() && r1 != r2,
              QStringLiteral("seal() keeps the same layout but draws a fresh nonce"));
        check(HistoryCrypto::open(root, ctx2, r1) == pt2 &&
                  HistoryCrypto::open(root, ctx2, r2) == pt2,
              QStringLiteral("both random-nonce records open"));
    }

    // ============================================================ negative
    section("context binding: every field is authenticated");
    {
        struct Case { const char* name; HistoryCrypto::Context ctx; QByteArray root; };
        QByteArray wrongRoot = root; wrongRoot[0] = static_cast<char>(0xFF);

        auto mut = [&](const char* u, const char* c, const char* m, int rv, int pv) {
            HistoryCrypto::Context x = ctx2;
            if (u) x.userId = QString::fromUtf8(u);
            if (c) x.chatId = QString::fromUtf8(c);
            if (m) x.messageId = QString::fromUtf8(m);
            if (rv) x.rootVersion = rv;
            if (pv) x.protocolVersion = pv;
            return x;
        };

        check(HistoryCrypto::open(wrongRoot, ctx2, wire2).isEmpty(),
              QStringLiteral("1. wrong root fails"));
        check(HistoryCrypto::open(root, mut(nullptr, nullptr, nullptr, 2, 0), wire2).isEmpty(),
              QStringLiteral("2. wrong rootVersion fails"));
        check(HistoryCrypto::open(root, mut(kF1User, nullptr, nullptr, 0, 0), wire2).isEmpty(),
              QStringLiteral("3. wrong userId fails"));
        check(HistoryCrypto::open(root, mut(nullptr, kF1Chat, nullptr, 0, 0), wire2).isEmpty(),
              QStringLiteral("4. wrong chatId fails"));
        check(HistoryCrypto::open(root, mut(nullptr, nullptr, kF1Msg, 0, 0), wire2).isEmpty(),
              QStringLiteral("5. wrong messageId fails"));
        check(HistoryCrypto::open(root, mut(nullptr, nullptr, nullptr, 0, 2), wire2).isEmpty(),
              QStringLiteral("6. wrong protocolVersion fails"));

        // 7. wrong AAD, exercised at the primitive level: same key, deliberately altered AAD.
        {
            const QByteArray key = HistoryCrypto::deriveMessageKey(root, ctx2.messageId, 1);
            QByteArray badAad = HistoryCrypto::aad(ctx2);
            badAad[10] = static_cast<char>(badAad[10] ^ 0x01);
            check(VaultCrypto::openXChaCha(key, wire2, badAad).isEmpty(),
                  QStringLiteral("7. wrong AAD fails"));
        }

        QByteArray badNonce = wire2; badNonce[3] = static_cast<char>(badNonce[3] ^ 0x01);
        check(HistoryCrypto::open(root, ctx2, badNonce).isEmpty(),
              QStringLiteral("8. wrong nonce fails"));

        QByteArray badBody = wire2;
        badBody[40] = static_cast<char>(badBody[40] ^ 0x01);
        check(HistoryCrypto::open(root, ctx2, badBody).isEmpty(),
              QStringLiteral("9. modified ciphertext fails"));

        QByteArray badTag = wire2;
        badTag[wire2.size() - 1] = static_cast<char>(badTag[wire2.size() - 1] ^ 0x01);
        check(HistoryCrypto::open(root, ctx2, badTag).isEmpty(),
              QStringLiteral("10. modified tag fails"));

        check(HistoryCrypto::open(root, ctx2, wire2.mid(4)).isEmpty(),
              QStringLiteral("11. truncated nonce fails"));
        check(HistoryCrypto::open(root, ctx2, wire2.left(60)).isEmpty(),
              QStringLiteral("12. truncated ciphertext fails"));
        check(HistoryCrypto::open(root, ctx2, wire2.left(wire2.size() - 4)).isEmpty(),
              QStringLiteral("13. truncated tag fails"));
        check(HistoryCrypto::open(root, ctx2, QByteArray()).isEmpty(),
              QStringLiteral("14a. empty wire fails"));
        check(HistoryCrypto::open(root, ctx2, wire2.left(30)).isEmpty(),
              QStringLiteral("14b. a record shorter than nonce+tag fails"));
        check(HistoryCrypto::open(root, ctx2, wire2 + QByteArray(4, 'x')).isEmpty(),
              QStringLiteral("14c. an extended record fails"));

        check(HistoryCrypto::openFromWireB64(root, ctx2, QStringLiteral("!!!not base64!!!")).isEmpty(),
              QStringLiteral("15a. malformed Base64 fails"));
        check(HistoryCrypto::fromWireB64(QStringLiteral("!!!not base64!!!")).isEmpty(),
              QStringLiteral("15b. strict Base64 refuses out-of-alphabet input"));
        check(HistoryCrypto::openFromWireB64(root, ctx2, QString()).isEmpty(),
              QStringLiteral("15c. empty Base64 fails"));
        check(HistoryCrypto::openFromWireB64(root, ctx2, QStringLiteral("YWJj")).isEmpty(),
              QStringLiteral("15d. well-formed but too-short Base64 fails"));

        // Refusals at the sealing side.
        check(HistoryCrypto::sealWithNonce(root, ctx2, pt2, QByteArray(23, 'n')).isEmpty(),
              QStringLiteral("a 23-byte nonce is refused"));
        check(HistoryCrypto::sealWithNonce(root, ctx2, pt2, QByteArray(25, 'n')).isEmpty(),
              QStringLiteral("a 25-byte nonce is refused"));
        check(HistoryCrypto::sealWithNonce(QByteArray(31, 'r'), ctx2, pt2, nonce2).isEmpty(),
              QStringLiteral("a 31-byte root is refused"));
    }

    // ============================================================ isolation
    section("cross-context isolation: one record cannot be opened as another");
    {
        // Two messages sealed under the SAME root and nonce differ, and neither opens as the other.
        auto ctxB = ctx2; ctxB.messageId = QStringLiteral("7d2a91b0-0000-4000-8000-0000000000ff");
        const QByteArray wireB = HistoryCrypto::sealWithNonce(root, ctxB, pt2, nonce2);
        check(wireB != wire2, QStringLiteral("a different messageId yields different ciphertext"));
        check(HistoryCrypto::open(root, ctx2, wireB).isEmpty(),
              QStringLiteral("message B's record cannot be opened as message A"));
        check(HistoryCrypto::open(root, ctxB, wire2).isEmpty(),
              QStringLiteral("message A's record cannot be opened as message B"));

        auto ctxChat = ctx2; ctxChat.chatId = QStringLiteral("3b7e5f21-0000-4000-8000-0000000000ff");
        check(HistoryCrypto::open(root, ctxChat, wire2).isEmpty(),
              QStringLiteral("chat A's record cannot be opened as chat B"));

        auto ctxUser = ctx2; ctxUser.userId = QStringLiteral("9f1c0d4e-0000-4000-8000-0000000000ff");
        check(HistoryCrypto::open(root, ctxUser, wire2).isEmpty(),
              QStringLiteral("user A's record cannot be opened as user B"));

        auto ctxV2 = ctx2; ctxV2.rootVersion = 2;
        check(HistoryCrypto::open(root, ctxV2, wire2).isEmpty(),
              QStringLiteral("rootVersion 1's record cannot be opened at rootVersion 2"));

        // rootVersion is bound into the KEY as well as the AAD, so the keys are unrelated.
        check(HistoryCrypto::deriveMessageKey(root, ctx2.messageId, 1) !=
                  HistoryCrypto::deriveMessageKey(root, ctx2.messageId, 2),
              QStringLiteral("rootVersion changes the derived key, not just the AAD"));
        check(HistoryCrypto::deriveMessageKey(root, ctx2.messageId, 1) !=
                  HistoryCrypto::deriveMessageKey(root, ctxB.messageId, 1),
              QStringLiteral("messageId changes the derived key"));

        // A legitimately re-sealed record for the other context DOES open - the binding is
        // cryptographic context, not an authorization rule.
        check(HistoryCrypto::open(root, ctxB, wireB) == pt2,
              QStringLiteral("a record sealed for that context opens normally"));
    }

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES")
        << "  checks=" << g_checks << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
