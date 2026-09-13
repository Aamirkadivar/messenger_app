// GATE 20 - adversarial account-isolation suite for the Windows message cache.
//
// Drives the REAL production MessageCache. Nothing here reimplements cache
// behaviour: every assertion goes through the same methods ChatService calls.
// Gate 18 showed why that matters - a test helper that duplicated production
// logic made production mutations invisible.
//
// Uses QStandardPaths test mode, so it writes to a throwaway location and never
// touches a real profile's message_cache.db.

#include "../src/utils/messagecache.h"

#include <QCoreApplication>
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QJsonObject>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QTextStream>

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const QString& what) {
    ++g_checks;
    QTextStream out(stdout);
    if (ok) {
        out << "  PASS  " << what << "\n";
    } else {
        ++g_failures;
        out << "  FAIL  " << what << "\n";
    }
}

static const QString A = QStringLiteral("aaaaaaaa-0000-0000-0000-00000000000a");
static const QString B = QStringLiteral("bbbbbbbb-0000-0000-0000-00000000000b");
static const QString CHAT_A = QStringLiteral("conversation-of-A");
static const QString CHAT_B = QStringLiteral("conversation-of-B");
static const QString MSG_A = QStringLiteral("message-of-A");
static const QString MSG_B = QStringLiteral("message-of-B");
static const QString SECRET_A = QStringLiteral("A private: merger closes Tuesday");
static const QString SECRET_B = QStringLiteral("B private: unrelated");

static MessageCache::Entry entry(const QString& id, const QString& body) {
    MessageCache::Entry e;
    e.id = id;
    e.senderId = QStringLiteral("sender");
    e.content = body;
    e.encrypted = false;              // worst case: content is readable
    e.createdAt = QStringLiteral("2026-01-01T00:00:00Z");
    return e;
}

static QJsonObject chatJson(const QString& id) {
    QJsonObject o;
    o.insert(QStringLiteral("id"), id);
    o.insert(QStringLiteral("name"), id);
    return o;
}

static void seed(MessageCache& c, const QString& owner, const QString& chat,
                 const QString& msg, const QString& body) {
    c.saveMessages(chat, { entry(msg, body) }, owner);
    c.saveChats({ chatJson(chat) }, owner);
}

static bool holdsMessage(MessageCache& c, const QString& chat, const QString& msg) {
    for (const MessageCache::Entry& e : c.loadMessages(chat)) {
        if (e.id == msg) return true;
    }
    return false;
}

static bool listsChat(MessageCache& c, const QString& chat) {
    for (const QJsonObject& o : c.loadChats()) {
        if (o.value(QStringLiteral("id")).toString() == chat) return true;
    }
    return false;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QStandardPaths::setTestModeEnabled(true);

    // Start from a clean slate so results are not inherited from a prior run.
    const QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    for (const QString& id : { A, B }) {
        QFile::remove(dir + "/" + MessageCache::databaseNameForAccount(id));
    }
    QFile::remove(dir + "/" + MessageCache::legacyQuarantinedFileName());

    QTextStream out(stdout);
    out << "GATE 20 - Windows account-scoped cache, real MessageCache\n\n";

    // ---------------------------------------------- N. namespace properties
    out << "N. namespace identity\n";
    check(MessageCache::databaseNameForAccount(A) == MessageCache::databaseNameForAccount(A),
          "same account resolves to the same namespace");
    check(MessageCache::databaseNameForAccount(A) != MessageCache::databaseNameForAccount(B),
          "distinct accounts resolve to distinct namespaces");
    check(MessageCache::databaseNameForAccount(A) ==
          MessageCache::databaseNameForAccount(QStringLiteral("  ") + A + QStringLiteral("\n")),
          "surrounding whitespace does not fork the namespace");
    check(MessageCache::databaseNameForAccount(QStringLiteral("abcdef")) !=
          MessageCache::databaseNameForAccount(QStringLiteral("ABCDEF")),
          "case is not folded, so distinct ids can never merge");
    {
        const QStringList hostile = {
            QStringLiteral("../../etc/passwd"),
            QStringLiteral("..\\..\\windows\\system32\\config"),
            QStringLiteral("CON"), QStringLiteral("NUL"), QStringLiteral("COM1"),
            QStringLiteral("a/b/c"), QStringLiteral("name with spaces"),
            QStringLiteral("message_cache"),
            QStringLiteral("'; DROP TABLE messages;--"),
            QString(4096, QChar('x'))
        };
        bool safe = true;
        for (const QString& h : hostile) {
            const QString n = MessageCache::databaseNameForAccount(h);
            if (!QRegularExpression(QStringLiteral("^message_cache_[0-9a-f]{32}\\.db$")).match(n).hasMatch()) {
                safe = false;
            }
            if (n == MessageCache::legacyQuarantinedFileName()) safe = false;
        }
        check(safe, "no account id can influence the path or hit the legacy name");
    }
    check(MessageCache::databaseNameForAccount(QStringLiteral("   ")).isEmpty(),
          "a blank account id yields no namespace at all");

    // ---------------------------------------------- M. unauthenticated state
    out << "\nM. unauthenticated state\n";
    {
        MessageCache c;
        check(!c.isOpen(), "constructing the cache opens nothing");
        check(c.loadChats().isEmpty(), "chat list reads empty with no account");
        check(c.loadMessages(CHAT_A).isEmpty(), "messages read empty with no account");
        c.saveMessages(CHAT_A, { entry(MSG_A, SECRET_A) }, A);
        c.saveChats({ chatJson(CHAT_A) }, A);
        check(c.loadMessages(CHAT_A).isEmpty(),
              "an unauthenticated write is refused, not silently attributed");

        // A blank identity must not resolve to some default namespace.
        c.openForAccount(QString());
        check(!c.isOpen(), "a blank account id leaves the cache closed");
        c.openForAccount(QStringLiteral("   "));
        check(!c.isOpen(), "a whitespace-only account id leaves the cache closed");
    }

    // Logout must make the cache unreadable immediately, without waiting for
    // some later account to log in and rebind the handle.
    out << "\nM2. readability ends at logout\n";
    {
        MessageCache c;
        c.openForAccount(A);
        seed(c, A, CHAT_A, MSG_A, SECRET_A);
        check(holdsMessage(c, CHAT_A, MSG_A), "precondition: A can read its own message");
        c.close();                              // logout, and nobody logs in after
        check(!c.isOpen(), "logout closes the handle");
        check(c.loadMessages(CHAT_A).isEmpty(),
              "after logout the cache is unreadable even with no new account");
        check(c.loadChats().isEmpty(), "after logout the chat list is unreadable");
    }

    // ---------------------------------------------- A/C/D/E/G. isolation
    out << "\nA,C,D,E,G. account switch isolation\n";
    {
        MessageCache c;
        c.openForAccount(A);
        seed(c, A, CHAT_A, MSG_A, SECRET_A);
        check(holdsMessage(c, CHAT_A, MSG_A), "A can read its own message");
        check(listsChat(c, CHAT_A), "A can see its own chat list");

        c.close();                       // logout
        c.openForAccount(B);             // switch, no process death

        check(!holdsMessage(c, CHAT_A, MSG_A), "B cannot read A's message by conversation id");
        check(c.loadMessages(CHAT_A).isEmpty(), "B's lookup of A's conversation yields nothing");
        check(!listsChat(c, CHAT_A), "B's chat list does not contain A's chat");
        check(c.loadChats().isEmpty(), "B's chat-list enumeration is empty");

        // Defence in depth: the previous account's connection must not be left
        // registered in Qt's process-global registry, where any code could fetch
        // it by name with QSqlDatabase::database(). Isolation does not depend on
        // this (m_db is replaced wholesale), but a retained handle is exactly the
        // kind of thing that becomes reachable later.
        const QString aConn = QStringLiteral("message_cache_") +
            MessageCache::databaseNameForAccount(A).mid(14, 32);
        check(!QSqlDatabase::connectionNames().contains(aConn),
              "the previous account's connection is deregistered on switch");
    }

    // A switch with NO explicit logout in between. ChatService wires
    // currentUserIdChanged straight to openForAccount, so an account change can
    // arrive without a close() ever being called - openForAccount must release
    // the previous namespace itself.
    out << "\nG2. direct switch with no intervening logout\n";
    {
        MessageCache c;
        c.openForAccount(A);
        seed(c, A, CHAT_A, MSG_A, SECRET_A);
        c.openForAccount(B);                    // straight to B, no close()

        check(c.ownerAccountId() == B, "the cache rebinds to the new account");
        check(!holdsMessage(c, CHAT_A, MSG_A),
              "a direct switch cannot leave A's rows readable");
        check(!listsChat(c, CHAT_A), "a direct switch cannot leave A's chat list readable");
        const QString aConn = QStringLiteral("message_cache_") +
            MessageCache::databaseNameForAccount(A).mid(14, 32);
        check(!QSqlDatabase::connectionNames().contains(aConn),
              "a direct switch deregisters the previous account's connection");
        c.close();
    }

    // ---------------------------------------------- B/H. persistence + reverse
    out << "\nB,H. persistence and reverse switch\n";
    {
        MessageCache c;
        c.openForAccount(B);
        seed(c, B, CHAT_B, MSG_B, SECRET_B);
        c.close();

        c.openForAccount(A);
        check(holdsMessage(c, CHAT_A, MSG_A), "A's history survived logout and B's session");
        check(!holdsMessage(c, CHAT_B, MSG_B), "A cannot read B's message");
        check(listsChat(c, CHAT_A) && !listsChat(c, CHAT_B), "A's chat list is only A's");

        c.close();
        c.openForAccount(B);
        check(holdsMessage(c, CHAT_B, MSG_B), "B's own history is intact");
        check(!holdsMessage(c, CHAT_A, MSG_A), "B still cannot reach A");
        c.close();
    }

    // ---------------------------------------------- F. process restart
    out << "\nF. process restart\n";
    {
        MessageCache fresh;   // a brand-new object, as after a restart
        fresh.openForAccount(A);
        check(holdsMessage(fresh, CHAT_A, MSG_A), "A sees its cache after restart");
        check(!holdsMessage(fresh, CHAT_B, MSG_B), "A does not see B after restart");
        fresh.close();
    }

    // ---------------------------------------------- I. stale handle
    out << "\nI. stale handle\n";
    {
        MessageCache c;
        c.openForAccount(A);
        MessageCache* stale = &c;                 // captured while A was active
        check(holdsMessage(*stale, CHAT_A, MSG_A), "precondition: reference reads A");
        c.close();
        c.openForAccount(B);
        check(!holdsMessage(*stale, CHAT_A, MSG_A),
              "a stale reference cannot read the previous account");
        stale->saveMessages(CHAT_A, { entry(QStringLiteral("late"), SECRET_A) }, A);
        check(!holdsMessage(c, CHAT_A, QStringLiteral("late")),
              "a stale reference cannot write A's data into B's namespace");
        c.close();
    }

    // ---------------------------------------------- J/K. late callback race
    out << "\nJ,K. callback that outlives its session\n";
    {
        MessageCache c;
        c.openForAccount(A);
        const QString ownerAtIssue = c.ownerAccountId();   // captured at request time
        c.close();                                          // A logs out
        c.openForAccount(B);                                // B logs in
        // The reply from A's session finally lands:
        c.saveMessages(CHAT_A, { entry(QStringLiteral("late-a"), SECRET_A) }, ownerAtIssue);
        c.saveChats({ chatJson(CHAT_A) }, ownerAtIssue);

        check(!holdsMessage(c, CHAT_A, QStringLiteral("late-a")),
              "a late reply from A does not write into B's cache");
        check(!listsChat(c, CHAT_A), "a late chat-list reply from A does not enter B's list");
        c.close();
        c.openForAccount(A);
        check(!holdsMessage(c, CHAT_A, QStringLiteral("late-a")),
              "the refused write was dropped, not redirected into A either");
        c.close();
    }

    // ---------------------------------------------- O. legacy quarantine
    out << "\nO. legacy quarantine\n";
    {
        // Plant a legacy database exactly where the pre-Gate-20 build kept it.
        const QString legacyPath = dir + "/" + MessageCache::legacyQuarantinedFileName();
        {
            QSqlDatabase legacy = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"),
                                                           QStringLiteral("legacy_probe"));
            legacy.setDatabaseName(legacyPath);
            if (legacy.open()) {
                QSqlQuery q(legacy);
                q.exec(QStringLiteral("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY,"
                                      " chat_id TEXT, content TEXT)"));
                q.exec(QStringLiteral("INSERT OR REPLACE INTO messages VALUES"
                                      " ('legacy-msg','legacy-chat','LEGACY PLAINTEXT')"));
                legacy.close();
            }
        }
        QSqlDatabase::removeDatabase(QStringLiteral("legacy_probe"));

        MessageCache c;
        c.openForAccount(A);
        check(!holdsMessage(c, QStringLiteral("legacy-chat"), QStringLiteral("legacy-msg")),
              "A does not inherit the legacy database");
        c.close();
        c.openForAccount(B);
        check(!holdsMessage(c, QStringLiteral("legacy-chat"), QStringLiteral("legacy-msg")),
              "B does not inherit the legacy database");
        c.close();
        check(QFile::exists(legacyPath), "the legacy file is preserved, not deleted");
    }

    out << "\n" << (g_checks - g_failures) << "/" << g_checks << " checks passed\n";
    return g_failures == 0 ? 0 : 1;
}
