from pathlib import Path
p = Path(r"D:\messenger_app\windows_app\src\services\chatservice.cpp")
t = p.read_text(encoding="utf-8")

# Helpers after appendForwardFields
helper = '''
void ChatService::setPendingReplyToId(const QString& id) {
    if (m_pendingReplyToId == id) return;
    m_pendingReplyToId = id;
    emit pendingReplyToIdChanged();
}

QString ChatService::takePendingReplyToId() {
    if (m_pendingReplyToId.isEmpty()) return {};
    const QString id = m_pendingReplyToId;
    m_pendingReplyToId.clear();
    emit pendingReplyToIdChanged();
    return id;
}

void ChatService::appendReplyField(QJsonObject& body, const QString& replyToId) const {
    if (replyToId.isEmpty()) return;
    body[QStringLiteral("reply_to_id")] = replyToId;
}

'''
if "appendReplyField" not in t:
    marker = "void ChatService::appendForwardFields(QJsonObject& body, bool isForwarded,"
    idx = t.find(marker)
    if idx < 0:
        raise SystemExit("appendForwardFields not found")
    # find end of function - next blank line after closing brace of function
    end = t.find("\n}\n", idx)
    end = end + 3
    t = t[:end] + helper + t[end:]

# After every appendForwardFields(...); add appendReplyField with take or captured id.
# For sync sendMessage body builds, use takePendingReplyToId at call site.

# Pattern 1: sendMessage (direct) - after appendForwardFields
# We need to take reply id at start of sendMessage and sendGroupTextMessage

# Patch sendMessage definition to capture reply at start
old_send = '''void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType,
                               bool isForwarded, const QString& forwardedFromName,
                               const QString& forwardedFromMessageId) {
    if (chatType == QStringLiteral("group")) {
        sendGroupTextMessage(chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId);
        return;
    }
'''
new_send = '''void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType,
                               bool isForwarded, const QString& forwardedFromName,
                               const QString& forwardedFromMessageId) {
    const QString replyToId = takePendingReplyToId();
    if (chatType == QStringLiteral("group")) {
        // Re-stash so group path can take it (take already cleared).
        if (!replyToId.isEmpty()) setPendingReplyToId(replyToId);
        sendGroupTextMessage(chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId);
        return;
    }
'''
if "const QString replyToId = takePendingReplyToId();" not in t:
    if old_send not in t:
        raise SystemExit("sendMessage signature block not found")
    t = t.replace(old_send, new_send, 1)

# After appendForwardFields in direct sendMessage, add appendReplyField(body, replyToId)
# Find first occurrence after sendMessage's appendForwardFields
needle = "    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);\n\n    QNetworkReply* reply = m_networkManager->post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));\n    connect(reply, &QNetworkReply::finished, this, [this, reply, chatId]() {"
# This is specific to text send - there may be multiple similar. Do replace_all carefully.

# Simpler: replace ALL "appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);"
# with appendForward + appendReply using takePendingReplyToId() - BUT that would clear too early on multi-call.

# Better: only add appendReplyField(body, replyToId) where replyToId is in scope,
# and for other send* methods take at start.

t = t.replace(
    "    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);\n",
    "    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);\n"
    "    appendReplyField(body, replyToId);\n",
)

# Now many places use replyToId without defining it - need to add take at each function start.

# sendGroupTextMessage
old_g = '''void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId) {
    ensureGroupSenderKeyReady(chatId, [this, chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId]() {
'''
new_g = '''void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId) {
    const QString replyToId = takePendingReplyToId();
    ensureGroupSenderKeyReady(chatId, [this, chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId, replyToId]() {
'''
if "sendGroupTextMessage" in t and "replyToId]() {" not in t.split("sendGroupTextMessage")[1][:500]:
    if old_g not in t:
        print("WARN sendGroupTextMessage block mismatch")
    else:
        t = t.replace(old_g, new_g, 1)

# For voice/video/attachment - add take at start of public functions and capture in lambdas.
# This is getting complex. Alternative: appendReplyField(body, takePendingReplyToId()) ONLY once per send body build,
# and ensure take is only called when building the final POST body (not early).

# Revert the blanket replace if it broke things - check how many "appendReplyField(body, replyToId)" without definition

# Instead use appendReplyField(body, takePendingReplyToId()) at body-build sites only,
# and remove the replyToId local from sendMessage group re-stash dance.

# Undo blanket and use takePendingReplyToId at each append site:
t = t.replace(
    "    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);\n"
    "    appendReplyField(body, replyToId);\n",
    "    appendForwardFields(body, isForwarded, forwardedFromName, forwardedFromMessageId);\n"
    "    appendReplyField(body, takePendingReplyToId());\n",
)

# Then simplify sendMessage - remove re-stash, just call group path (group will take when building body)
old_send2 = '''void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType,
                               bool isForwarded, const QString& forwardedFromName,
                               const QString& forwardedFromMessageId) {
    const QString replyToId = takePendingReplyToId();
    if (chatType == QStringLiteral("group")) {
        // Re-stash so group path can take it (take already cleared).
        if (!replyToId.isEmpty()) setPendingReplyToId(replyToId);
        sendGroupTextMessage(chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId);
        return;
    }
'''
new_send2 = '''void ChatService::sendMessage(const QString& chatId, const QString& text, const QString& chatType,
                               bool isForwarded, const QString& forwardedFromName,
                               const QString& forwardedFromMessageId) {
    if (chatType == QStringLiteral("group")) {
        sendGroupTextMessage(chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId);
        return;
    }
'''
t = t.replace(old_send2, new_send2, 1)

# Remove sendGroupTextMessage local take if we added it - group body uses takePendingReplyToId() at append
old_g2 = '''void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId) {
    const QString replyToId = takePendingReplyToId();
    ensureGroupSenderKeyReady(chatId, [this, chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId, replyToId]() {
'''
new_g2 = '''void ChatService::sendGroupTextMessage(const QString& chatId, const QString& text,
                                        bool isForwarded, const QString& forwardedFromName,
                                        const QString& forwardedFromMessageId) {
    ensureGroupSenderKeyReady(chatId, [this, chatId, text, isForwarded, forwardedFromName, forwardedFromMessageId]() {
'''
t = t.replace(old_g2, new_g2, 1)

# Parse reply_to_id into maps wherever isForwarded is set from JSON
# Cache load path
if 'item["replyToId"]' not in t:
    t = t.replace(
        'item["isForwarded"] = e.isForwarded;\n                item["forwardedFromName"] = e.forwardedFromName;\n',
        'item["isForwarded"] = e.isForwarded;\n                item["forwardedFromName"] = e.forwardedFromName;\n'
        '                item["replyToId"] = e.replyToId;\n',
    )
    t = t.replace(
        'item["isForwarded"] = m[QStringLiteral("is_forwarded")].toBool(false);\n'
        '                item["forwardedFromName"] = m[QStringLiteral("forwarded_from_name")].toString();\n'
        '                item["forwardedFromMessageId"] = m[QStringLiteral("forwarded_from_message_id")].toString();\n',
        'item["isForwarded"] = m[QStringLiteral("is_forwarded")].toBool(false);\n'
        '                item["forwardedFromName"] = m[QStringLiteral("forwarded_from_name")].toString();\n'
        '                item["forwardedFromMessageId"] = m[QStringLiteral("forwarded_from_message_id")].toString();\n'
        '                item["replyToId"] = m[QStringLiteral("reply_to_id")].toString();\n',
    )
    t = t.replace(
        'cacheEntry.isForwarded = item["isForwarded"].toBool();\n'
        '                cacheEntry.forwardedFromName = item["forwardedFromName"].toString();\n',
        'cacheEntry.isForwarded = item["isForwarded"].toBool();\n'
        '                cacheEntry.forwardedFromName = item["forwardedFromName"].toString();\n'
        '                cacheEntry.replyToId = item["replyToId"].toString();\n',
    )

# WS / send response maps - multiple similar blocks
t = t.replace(
    'item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);\n'
    '        item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();\n',
    'item["isForwarded"] = data[QStringLiteral("is_forwarded")].toBool(false);\n'
    '        item["forwardedFromName"] = data[QStringLiteral("forwarded_from_name")].toString();\n'
    '        item["replyToId"] = data[QStringLiteral("reply_to_id")].toString();\n',
)

# websocketservice may also parse messages - check later

p.write_text(t, encoding="utf-8", newline="\n")
print("chatservice.cpp patched, reply append count", t.count("appendReplyField"))
print("replyToId item count", t.count('item["replyToId"]'))