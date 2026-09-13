// Regression: an unsupported direct encryption_version must never be decrypted
// as if it were some other scheme.
//
// decryptDirect()'s version chain used to end in ">= 2", so any version this
// build did not recognise - a scheme a newer client introduces - fell into the
// v2 ephemeral-box branch. It could not actually produce plaintext, but it
// decided an unknown wire format on the sender's behalf instead of admitting it
// did not know the format. That is the "treat an unknown version as a known
// one" hazard, and this test pins the rule so the chain cannot regrow it.
//
// The rule is asserted through ChatService::isSupportedDirectVersion(), which is
// the predicate the production dispatch itself calls - not a copy of it. A test
// that reimplemented the version table would keep passing while production
// drifted, which is exactly the failure mode this suite exists to avoid.

#include "../src/services/chatservice.h"

#include <QTextStream>

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    if (ok) {
        out << "  PASS  " << what << "\n";
    } else {
        out << "  FAIL  " << what << "\n";
        ++g_failures;
    }
}

int main() {
    QTextStream out(stdout);
    out << "encryption_version dispatch\n";

    // The four schemes a direct thread may legitimately carry.
    check(ChatService::isSupportedDirectVersion(1), "v1 (pairwise box) is supported");
    check(ChatService::isSupportedDirectVersion(2), "v2 (ephemeral box) is supported");
    check(ChatService::isSupportedDirectVersion(3), "v3 (ratchet) is supported");
    check(ChatService::isSupportedDirectVersion(4), "v4 (ratchet) is supported");

    // The group schemes are handled before the direct dispatch is reached. If
    // one arrives labelled as a direct message the answer is "not mine", never
    // "close enough to v2".
    check(!ChatService::isSupportedDirectVersion(5),
          "v5 (mlspp group) is NOT a direct scheme");
    check(!ChatService::isSupportedDirectVersion(6),
          "v6 (mls-core group) is NOT a direct scheme");

    // The actual regression: the next version to be invented must be refused
    // rather than swept into the legacy branch by a >= comparison.
    for (int v : {7, 8, 42, 99, 1000}) {
        check(!ChatService::isSupportedDirectVersion(v),
              QStringLiteral("unknown v%1 is refused, not routed to the legacy branch").arg(v));
    }

    // Absent/malformed versions must not be supported either. decryptDirect
    // normalises <= 0 to 1 before dispatching, so this only pins the predicate:
    // it must never answer "yes" for a value that was never a scheme.
    check(!ChatService::isSupportedDirectVersion(0), "v0 is not a scheme");
    check(!ChatService::isSupportedDirectVersion(-1), "negative version is not a scheme");

    out << (g_failures ? "FAILED " : "OK ") << (g_checks - g_failures) << "/" << g_checks << "\n";
    return g_failures ? 1 : 0;
}
