// Phase 30 cross-platform gate for the Layer B history archive.
//
// An archive sealed on Android is opened on Windows and vice versa, so the two implementations must
// agree byte-for-byte on the deterministic halves of the protocol - the per-message key and the AAD -
// before any integration work is worth doing. Integration built on a silent mismatch would look
// healthy right up until a real message could not be recovered.
//
// The expected key and AAD here were computed from an INDEPENDENT RFC 5869 / RFC 2104 reference, not
// from either implementation. Android asserts the same constants in
// CrossPlatformArchiveFixtureTest, so both platforms are checked against a third party rather than
// merely against each other.
//
// Every value is synthetic: the root is 00..1f, the identifiers are literals, and the plaintext is a
// fixture string. No production key, message, or identifier appears here or in the output.

#include "../src/crypto/historycrypto.h"
#include "../src/crypto/vaultcrypto.h"

#include <QByteArray>
#include <QString>
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

static QByteArray fixtureRoot() {
    QByteArray r(HistoryCrypto::ROOT_BYTES, 0);
    for (int i = 0; i < r.size(); ++i) r[i] = static_cast<char>(i);
    return r;
}

static HistoryCrypto::Context fixtureCtx() {
    HistoryCrypto::Context c;
    c.userId = QStringLiteral("fixture-user");
    c.chatId = QStringLiteral("fixture-chat");
    c.messageId = QStringLiteral("00000000-0000-0000-0000-000000000001");
    c.rootVersion = 1;
    c.protocolVersion = 1;
    return c;
}

static const char* kPlaintext = "Phase30-cross-platform-archive-fixture";

// Computed independently; Android's unit test asserts the identical strings.
static const char* kExpectedKeyHex =
    "c9a0f84cdf1d4e4ca878343145486faa8544f5d2a4f154bc98ecf811cc342a29";
static const char* kExpectedAadHex =
    "000000186d657373656e6765722f686973746f72792d6161642f7631"
    "00000001"
    "0000000c666978747572652d75736572"
    "0000000c666978747572652d63686174"
    "00000024"
    "30303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "00000001";

// A record sealed for this exact fixture by an INDEPENDENT XChaCha20-Poly1305-IETF implementation
// (HChaCha20 + RFC 8439, validated against the RFC 8439 and HChaCha20 known-answer vectors), not by
// this codebase and not by Android. Opening it here proves Windows agrees with the standard Android's
// libsodium also implements, rather than merely agreeing with itself.
//
// The nonce is fixed so the whole record is reproducible; production seals always draw a fresh one.
static const char* kReferenceSealedB64 =
    "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3SExh5MBBjh1voA/T6NxG0Ogz4cjRm89/s2Kq31FVy3wpD03Ai+9wShILQoCnltxQk1YXYYvN";
static const char* kFixedNonceHex = "202122232425262728292a2b2c2d2e2f3031323334353637";
static const char* kReferenceCtTagHex =
    "484c61e4c0418e1d6fa00fd3e8dc46d0e833e1c8d19bcf7fb362aadf5155cb7c290f4dc08bef704a120b4280a796dc50935617618bcd";

int main(int argc, char** argv) {
    Q_UNUSED(argc);
    Q_UNUSED(argv);
    QTextStream out(stdout);

    if (sodium_init() < 0) {
        out << "  FAIL  libsodium failed to initialise" << Qt::endl;
        return 1;
    }

    const QByteArray root = fixtureRoot();
    const HistoryCrypto::Context ctx = fixtureCtx();
    const QByteArray plaintext = QByteArray(kPlaintext);

    out << "Phase 30 - Layer B cross-platform archive gate" << Qt::endl;
    out << "fixture: fixture-user / fixture-chat / fixture message" << Qt::endl;

    // ---------------------------------------------------------- deterministic halves

    const QByteArray key = HistoryCrypto::deriveMessageKey(root, ctx.messageId, ctx.rootVersion);
    check(key.size() == 32, QStringLiteral("derived key is 32 bytes"));
    check(key.toHex() == QByteArray(kExpectedKeyHex),
          QStringLiteral("derived key matches the cross-platform reference"));

    const QByteArray aad = HistoryCrypto::aad(ctx);
    check(aad.size() == 108, QStringLiteral("AAD is 108 bytes"));
    check(aad.toHex() == QByteArray(kExpectedAadHex),
          QStringLiteral("AAD matches the cross-platform reference"));

    // ------------------------------------------------------------- encoding rules

    check(HistoryCrypto::u32be(1) == QByteArray::fromHex("00000001"),
          QStringLiteral("u32be is big-endian"));
    check(HistoryCrypto::lp(QByteArray()) == QByteArray::fromHex("00000000"),
          QStringLiteral("an empty field contributes only its length"));
    check(HistoryCrypto::lp(QStringLiteral("ab")) == QByteArray::fromHex("0000000261 62").replace(' ', ""),
          QStringLiteral("lp is u32be(len) followed by UTF-8 bytes"));

    // ------------------------------------------------------------- seal / open

    const QByteArray sealed = HistoryCrypto::seal(root, ctx, plaintext);
    check(sealed.size() == 24 + plaintext.size() + 16,
          QStringLiteral("record layout is nonce(24) || ciphertext || tag(16)"));
    check(HistoryCrypto::open(root, ctx, sealed) == plaintext,
          QStringLiteral("Windows seal -> Windows open recovers the plaintext"));

    const QByteArray sealedAgain = HistoryCrypto::seal(root, ctx, plaintext);
    check(sealed != sealedAgain, QStringLiteral("nonce is fresh per seal"));
    check(HistoryCrypto::open(root, ctx, sealedAgain) == plaintext,
          QStringLiteral("both independent seals open"));

    // ------------------------------------------------------------- base64 wire form

    const QString b64 = VaultCrypto::b64(sealed);
    check(!b64.contains('\n') && !b64.contains('\r'),
          QStringLiteral("base64 has no line wrapping"));
    check(!b64.contains('-') && !b64.contains('_'),
          QStringLiteral("base64 is standard alphabet, not URL-safe"));
    check(b64.endsWith(QLatin1Char('=')) || (b64.size() % 4) == 0,
          QStringLiteral("base64 is padded to a multiple of four"));
    check(VaultCrypto::unb64(b64) == sealed,
          QStringLiteral("base64 round-trips the exact record bytes"));

    // ------------------------------------------------------------- negative cases

    QByteArray wrongRoot = root;
    wrongRoot[0] = static_cast<char>(0xFF);
    check(HistoryCrypto::open(wrongRoot, ctx, sealed).isEmpty(),
          QStringLiteral("wrong root fails"));

    HistoryCrypto::Context c;
    c = ctx; c.messageId = QStringLiteral("00000000-0000-0000-0000-000000000002");
    check(HistoryCrypto::open(root, c, sealed).isEmpty(), QStringLiteral("wrong message id fails"));
    c = ctx; c.chatId = QStringLiteral("other-chat");
    check(HistoryCrypto::open(root, c, sealed).isEmpty(), QStringLiteral("wrong chat id fails"));
    c = ctx; c.userId = QStringLiteral("other-user");
    check(HistoryCrypto::open(root, c, sealed).isEmpty(), QStringLiteral("wrong user id fails"));
    c = ctx; c.rootVersion = 2;
    check(HistoryCrypto::open(root, c, sealed).isEmpty(), QStringLiteral("wrong root version fails"));
    c = ctx; c.protocolVersion = 2;
    check(HistoryCrypto::open(root, c, sealed).isEmpty(), QStringLiteral("wrong protocol version fails"));

    QByteArray tamperedBody = sealed;
    tamperedBody[40] = static_cast<char>(tamperedBody[40] ^ 0x01);
    check(HistoryCrypto::open(root, ctx, tamperedBody).isEmpty(),
          QStringLiteral("tampered ciphertext fails"));

    QByteArray tamperedNonce = sealed;
    tamperedNonce[0] = static_cast<char>(tamperedNonce[0] ^ 0x01);
    check(HistoryCrypto::open(root, ctx, tamperedNonce).isEmpty(),
          QStringLiteral("tampered nonce fails"));

    QByteArray tamperedTag = sealed;
    tamperedTag[sealed.size() - 1] = static_cast<char>(tamperedTag[sealed.size() - 1] ^ 0x01);
    check(HistoryCrypto::open(root, ctx, tamperedTag).isEmpty(),
          QStringLiteral("tampered tag fails"));

    check(HistoryCrypto::open(root, ctx, sealed.left(20)).isEmpty(),
          QStringLiteral("truncated record fails safely"));
    check(HistoryCrypto::open(root, ctx, QByteArray()).isEmpty(),
          QStringLiteral("empty record fails safely"));
    check(HistoryCrypto::open(root, ctx, sealed + QByteArray(4, 'x')).isEmpty(),
          QStringLiteral("extended record fails safely"));
    check(HistoryCrypto::deriveMessageKey(QByteArray(16, 0), ctx.messageId, 1).isEmpty(),
          QStringLiteral("a wrong-sized root refuses to derive"));

    // ------------------------------------- cross-implementation gate (independent reference)

    // reference -> Windows: a record this codebase did not produce must open here.
    const QByteArray refSealed = VaultCrypto::unb64(QString::fromLatin1(kReferenceSealedB64));
    check(refSealed.size() == 24 + plaintext.size() + 16,
          QStringLiteral("reference record has the expected layout"));
    check(HistoryCrypto::open(root, ctx, refSealed) == plaintext,
          QStringLiteral("independent reference seal -> Windows open recovers the exact plaintext"));

    // Windows -> reference: with the nonce pinned to the reference's, Windows must produce the
    // reference's exact bytes. This is the deterministic direction of the same interoperability
    // claim, and it fails loudly if the AAD binding or key differ by even one byte.
    {
        const QByteArray nonce = QByteArray::fromHex(QByteArray(kFixedNonceHex));
        QByteArray ctTag(plaintext.size() + 16, 0);
        unsigned long long ctLen = 0;
        crypto_aead_xchacha20poly1305_ietf_encrypt(
            reinterpret_cast<unsigned char*>(ctTag.data()), &ctLen,
            reinterpret_cast<const unsigned char*>(plaintext.constData()),
            static_cast<unsigned long long>(plaintext.size()),
            reinterpret_cast<const unsigned char*>(aad.constData()),
            static_cast<unsigned long long>(aad.size()),
            nullptr,
            reinterpret_cast<const unsigned char*>(nonce.constData()),
            reinterpret_cast<const unsigned char*>(key.constData()));
        ctTag.resize(static_cast<int>(ctLen));
        check(ctTag.toHex() == QByteArray(kReferenceCtTagHex),
              QStringLiteral("Windows seal at a fixed nonce is byte-identical to the reference"));
        check(HistoryCrypto::open(root, ctx, nonce + ctTag) == plaintext,
              QStringLiteral("that reconstructed record opens through the normal Windows path"));
    }

    // Malformed wire input must fail closed, not crash or half-succeed.
    check(HistoryCrypto::open(root, ctx, VaultCrypto::unb64(QStringLiteral("!!!not base64!!!"))).isEmpty(),
          QStringLiteral("malformed base64 fails safely"));
    check(HistoryCrypto::open(root, ctx, VaultCrypto::unb64(QStringLiteral("YWJj"))).isEmpty(),
          QStringLiteral("a too-short decoded record fails safely"));

    // The record Windows produces for Android to open, printed so the Android fixture can embed it.
    out << "WINDOWS_SEALED_B64=" << VaultCrypto::b64(sealed) << Qt::endl;

    out << (g_failures == 0 ? "ALL PASS" : "FAILURES") << "  checks=" << g_checks
        << " failures=" << g_failures << Qt::endl;
    return g_failures == 0 ? 0 : 1;
}
