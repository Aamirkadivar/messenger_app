#pragma once

#include <QObject>
#include <QString>
#include <QList>
#include <QHash>
#include <QVariantList>
#include <QNetworkReply>
#include <QJsonObject>
#include <QJsonArray>
#include "../utils/config.h"
#include "../utils/messagecache.h"
#include "authservice.h"

class ChatService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isLoading READ isLoading NOTIFY isLoadingChanged)

public:
    explicit ChatService(AuthService* authService, QObject* parent = nullptr);
    ~ChatService();

    bool isLoading() const { return m_isLoading; }

    QNetworkAccessManager* networkManager() { return m_networkManager; }

    Q_INVOKABLE void fetchChats();
    Q_INVOKABLE void searchUsers(const QString& query);
    Q_INVOKABLE void startDirectChat(const QString& userId, const QString& userName);
    Q_INVOKABLE void fetchMessages(const QString& chatId);
    // chatType defaults to "direct" for existing call sites; pass the real
    // type for a group chat (the server derives it from the chat row anyway,
    // but the message shouldn't claim "direct" for a group either).
    Q_INVOKABLE void sendMessage(const QString& chatId, const QString& text, const QString& chatType = QStringLiteral("direct"));
    Q_INVOKABLE void markAsRead(const QString& chatId);

    // Local on-disk cache (SQLite) - exposed for the Settings screen's
    // "storage used" readout and "clear cache" action.
    Q_INVOKABLE qint64 cacheSizeBytes() const { return m_messageCache ? m_messageCache->sizeBytes() : 0; }
    Q_INVOKABLE void clearCache() { if (m_messageCache) m_messageCache->clear(); }

    // Decrypt an (E2EE) message for a chat. If the message isn't encrypted, or
    // we lack the key, returns the content as-is / a placeholder. Exposed to
    // QML so WebSocket-delivered ciphertext can be decrypted at the display site.
    Q_INVOKABLE QString decryptMessage(const QString& chatId, const QString& content, bool encrypted) const;

    // Whether we currently hold the key needed to encrypt/decrypt for this
    // chat (false for a group, where the pairwise scheme doesn't apply - see
    // the note on encryptBytesForChat below).
    Q_INVOKABLE bool hasKeyForChat(const QString& chatId) const;

    // ---- Voice notes ----
    // Voice is content, so it gets the same E2EE treatment as text. The
    // pairwise crypto_box scheme needs exactly one recipient key, which is
    // only meaningful for a direct chat - callers must check hasKeyForChat()
    // first and tell the user plainly when a group voice note will go out
    // unencrypted, rather than the failure surfacing silently as static.
    Q_INVOKABLE QByteArray encryptBytesForChat(const QString& chatId, const QByteArray& plain) const;
    Q_INVOKABLE QByteArray decryptBytesForChat(const QString& chatId, const QByteArray& payload) const;

    // Reads localFilePath (a just-recorded temp file), encrypts it for chatId
    // when possible, uploads it, and posts the message - one call from QML,
    // one eventual voiceMessageSent/voiceUploadError. QML has no reasonable
    // way to shuttle a QByteArray of raw audio through JS, so the whole
    // read -> encrypt -> upload -> send sequence lives here instead of being
    // split across invokable steps. The plaintext temp file is deleted the
    // moment it's been read, whether or not the rest of the sequence
    // succeeds - it shouldn't linger on disk either way.
    Q_INVOKABLE void sendVoiceNote(const QString& chatId, const QString& chatType,
                                    const QString& localFilePath, qint64 durationMs);

    // Fetches (or reuses an already-cached) decrypted local copy of a voice
    // note, ready to hand to VoicePlayer. Emits voiceReadyForPlayback on
    // success. QML owns actually starting playback (via voiceService) once
    // the file is ready - this call is only responsible for getting a
    // decrypted file onto disk.
    Q_INVOKABLE void preparePlayableVoice(const QString& chatId, const QString& messageId,
                                           const QString& fileUrl, bool encrypted);
    // Posts the message pointing at an already-uploaded voice note.
    void sendVoiceMessage(const QString& chatId, const QString& chatType,
                           const QString& fileUrl, qint64 durationMs, bool encrypted);

    // ---- File/image attachments ----
    // localFileUrl is whatever FileDialog.selectedFile.toString() hands QML
    // ("file:///C:/..."); converted to a local path the same way
    // GroupService::uploadGroupAvatar already does, and the display filename
    // is derived from that path rather than trusted from a caller-supplied
    // string. contentType is "image" or "file" - the caller (QML) decides
    // based on the picked file's extension. Same read -> encrypt -> upload ->
    // send orchestration as sendVoiceNote, with one difference: the file was
    // picked from the user's own filesystem, not a throwaway temp recording,
    // so it is never deleted afterward.
    Q_INVOKABLE void sendAttachment(const QString& chatId, const QString& chatType,
                                     const QString& localFileUrl, const QString& contentType);

    // Fetches (or reuses an already-cached) decrypted local copy of an
    // attachment. Emits attachmentReady on success with a local file path -
    // QML displays it directly (image) or hands it to the OS via
    // Qt.openUrlExternally (any other file type).
    Q_INVOKABLE void prepareAttachment(const QString& chatId, const QString& messageId,
                                        const QString& fileUrl, bool encrypted,
                                        const QString& fileName);

signals:
    void isLoadingChanged();
    // Each entry is a QVariantMap matching the backend's chat list JSON shape
    // (id, type, name, avatar_url, other_user, last_message, last_message_at,
    // unread_count, is_online, updated_at) - see ChatService::parseChatItem.
    void chatsFetched(const QVariantList& chats);
    void chatError(const QString& error);
    void usersFound(const QVariantList& users);
    void searchError(const QString& error);
    void directChatReady(const QString& chatId, const QString& chatName);
    void messagesFetched(const QString& chatId, const QVariantList& messages);
    void messageSent(const QString& chatId, const QVariantMap& message);
    void messageError(const QString& error);
    void chatRead(const QString& chatId);

    void voiceUploadError(const QString& error);
    void voiceMessageSent(const QString& chatId, const QVariantMap& message);
    void voiceReadyForPlayback(const QString& messageId, const QString& localFilePath);
    void voicePlaybackError(const QString& messageId, const QString& error);

    void attachmentUploadError(const QString& error);
    void attachmentMessageSent(const QString& chatId, const QVariantMap& message);
    void attachmentReady(const QString& messageId, const QString& localFilePath);
    void attachmentError(const QString& messageId, const QString& error);

private slots:
    void onChatsReplyFinished(QNetworkReply* reply);

private:
    void setupNetworkManager();
    QString buildAuthHeader() const;
    // Posts the message pointing at an already-uploaded attachment.
    void sendAttachmentMessage(const QString& chatId, const QString& chatType,
                                const QString& fileUrl, const QString& fileName,
                                const QString& contentType, qint64 fileSize, bool encrypted);

    AuthService* m_authService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;
    QNetworkReply* m_currentReply = nullptr;
    bool m_isLoading = false;
    MessageCache* m_messageCache = nullptr;

    // chatId -> the other participant's public key (hex), learned from the
    // chat list. For a direct chat this key both encrypts our outgoing
    // messages and decrypts everything in the thread (box is symmetric in the
    // shared-secret sense), so one key per chat is all we need.
    QHash<QString, QString> m_chatOtherPub;

    // Helper: parse a single chat from JSON into a QML-friendly QVariantMap
    QVariantMap parseChatItem(const QJsonObject& obj);
};
