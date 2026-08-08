from pathlib import Path

# ---- messagecache.h ----
h = Path(r"D:\messenger_app\windows_app\src\utils\messagecache.h")
ht = h.read_text(encoding="utf-8")
if "replyToId" not in ht:
    ht = ht.replace(
        "        bool isForwarded = false;\n        QString forwardedFromName;\n    };",
        "        bool isForwarded = false;\n        QString forwardedFromName;\n"
        "        QString replyToId;\n    };",
        1,
    )
    h.write_text(ht, encoding="utf-8", newline="\n")
    print("messagecache.h ok")

# ---- messagecache.cpp ----
c = Path(r"D:\messenger_app\windows_app\src\utils\messagecache.cpp")
ct = c.read_text(encoding="utf-8")
if "reply_to_id" not in ct:
    ct = ct.replace(
        'query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN forwarded_from_name TEXT"));\n',
        'query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN forwarded_from_name TEXT"));\n'
        '    query.exec(QStringLiteral("ALTER TABLE messages ADD COLUMN reply_to_id TEXT"));\n',
        1,
    )
    ct = ct.replace(
        " is_forwarded, forwarded_from_name) \"\n"
        "        \"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\"",
        " is_forwarded, forwarded_from_name, reply_to_id) \"\n"
        "        \"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\"",
        1,
    )
    ct = ct.replace(
        "        query.addBindValue(e.forwardedFromName);\n        if (!query.exec()) {",
        "        query.addBindValue(e.forwardedFromName);\n"
        "        query.addBindValue(e.replyToId);\n        if (!query.exec()) {",
        1,
    )
    ct = ct.replace(
        "       is_forwarded, forwarded_from_name \"\n"
        "        \"FROM messages WHERE chat_id = ? ORDER BY created_at DESC LIMIT ?\"",
        "       is_forwarded, forwarded_from_name, reply_to_id \"\n"
        "        \"FROM messages WHERE chat_id = ? ORDER BY created_at DESC LIMIT ?\"",
        1,
    )
    ct = ct.replace(
        "        e.forwardedFromName = query.value(14).toString();\n        result.prepend(e);",
        "        e.forwardedFromName = query.value(14).toString();\n"
        "        e.replyToId = query.value(15).toString();\n        result.prepend(e);",
        1,
    )
    c.write_text(ct, encoding="utf-8", newline="\n")
    print("messagecache.cpp ok")

# ---- chatservice.h ----
ch = Path(r"D:\messenger_app\windows_app\src\services\chatservice.h")
cht = ch.read_text(encoding="utf-8")
if "pendingReplyToId" not in cht:
    cht = cht.replace(
        "    Q_PROPERTY(int cryptoRevision READ cryptoRevision NOTIFY cryptoRevisionChanged)\n",
        "    Q_PROPERTY(int cryptoRevision READ cryptoRevision NOTIFY cryptoRevisionChanged)\n"
        "    Q_PROPERTY(QString pendingReplyToId READ pendingReplyToId WRITE setPendingReplyToId NOTIFY pendingReplyToIdChanged)\n",
        1,
    )
    cht = cht.replace(
        "    bool isLoading() const { return m_isLoading; }\n",
        "    bool isLoading() const { return m_isLoading; }\n"
        "    QString pendingReplyToId() const { return m_pendingReplyToId; }\n"
        "    void setPendingReplyToId(const QString& id);\n",
        1,
    )
    # find signals section - add notify
    if "pendingReplyToIdChanged" not in cht:
        # insert after cryptoRevisionChanged signal declaration - search
        import re
        m = re.search(r"void cryptoRevisionChanged\(\);", cht)
        if not m:
            raise SystemExit("cryptoRevisionChanged signal not found")
        cht = cht[:m.end()] + "\n    void pendingReplyToIdChanged();" + cht[m.end():]
    # appendReplyField + member
    cht = cht.replace(
        "    void appendForwardFields(QJsonObject& body, bool isForwarded,\n"
        "                              const QString& forwardedFromName,\n"
        "                              const QString& forwardedFromMessageId) const;\n",
        "    void appendForwardFields(QJsonObject& body, bool isForwarded,\n"
        "                              const QString& forwardedFromName,\n"
        "                              const QString& forwardedFromMessageId) const;\n"
        "    void appendReplyField(QJsonObject& body, const QString& replyToId) const;\n"
        "    QString takePendingReplyToId();\n",
        1,
    )
    # private member near other fields - find m_isLoading
    if "m_pendingReplyToId" not in cht:
        cht = cht.replace(
            "    bool m_isLoading",
            "    QString m_pendingReplyToId;\n    bool m_isLoading",
            1,
        )
    ch.write_text(cht, encoding="utf-8", newline="\n")
    print("chatservice.h ok")

print("headers/cache done")