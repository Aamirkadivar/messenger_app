#pragma once

#include <QObject>
#include <QString>
#include <QList>
#include <QHash>
#include <QVariantList>
#include <QNetworkReply>
#include <QJsonObject>
#include <QJsonArray>
#include <functional>
#include "../utils/config.h"
#include "../utils/messagecache.h"
#include "authservice.h"
#include "groupservice.h"

class ChatService : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isLoading READ isLoading NOTIFY isLoadingChanged)
    Q_PROPERTY(int cryptoRevision READ cryptoRevision NOTIFY cryptoRevisionChanged)

public:
    // groupService may be null (falls back to sending groups unencrypted,
    // same as before group E2EE existed) - always passed in main.cpp.
    explicit ChatService(AuthService* authService, GroupService* groupService = nullptr, QObject* parent = nullptr);
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

    // Removes a chat from THIS user's list only - the other participant keeps
    // theirs, and no message history is destroyed (see the backend's
    // DeleteChat). A later message in a direct chat brings it back.
    Q_INVOKABLE void deleteChat(const QString& chatId);

    // Removes a single message. forEveryone retracts it for both sides and is
    // only permitted on your own messages; otherwise it is stamped into the
    // server's deleted_for list and disappears from this account's view alone,
    // matching how deleteChat is "mine only".
    Q_INVOKABLE void deleteMessage(const QString& chatId, const QString& messageId,
                                    bool forEveryone);

    // Returns (and clears) a security-code-change notice queued for chatId
    // while it wasn't the open chat, so it still surfaces the moment the
    // user opens that chat - empty string if there's nothing pending.
    // Drops a message from the local cache and refreshes the chat-list
    // preview from whatever remains. Used for for_everyone retractions
    // arriving over the socket (ChatService's own deleteMessage path does
    // the same after the REST call succeeds).
    Q_INVOKABLE void noteMessageDeleted(const QString& chatId, const QString& messageId);
    // Push a preview computed by the open ChatView (live messagesModel). The
    // message cache can lag behind the UI while a chat is open - e.g. after
    // deleting the last visible message - so the view owns the truth there.
    Q_INVOKABLE void notifyChatPreview(const QString& chatId, const QString& preview);
    Q_INVOKABLE QString takePendingSecurityNotice(const QString& chatId);

    // Local on-disk cache (SQLite) - exposed for the Settings screen's
    // "storage used" readout and "clear cache" action.
    Q_INVOKABLE qint64 cacheSizeBytes() const { return m_messageCache ? m_messageCache->sizeBytes() : 0; }
    Q_INVOKABLE void clearCache() { if (m_messageCache) m_messageCache->clear(); }

    // Decrypt an (E2EE) message for a chat. If the message isn't encrypted, or
    // we lack the key, returns the content as-is / a placeholder. Exposed to
    // QML so WebSocket-delivered ciphertext can be decrypted at the display site.
    // senderId/keyVersion are required for a GROUP message (a group has no
    // single "other side" key - see the Sender Key scheme on
    // GroupService::PublishSenderKey) and ignored for a direct chat.
    Q_INVOKABLE QString decryptMessage(const QString& chatId, const QString& content, bool encrypted,
                                       const QString& senderId = QString(), int keyVersion = 0) const;

    // Whether we currently hold a pairwise key for this chat (direct only).
    // Groups use Sender Keys - see hasGroupSenderKey / encryptBytesForChat.
    Q_INVOKABLE bool hasKeyForChat(const QString& chatId) const;

    // Whether MY OWN group Sender Key is established and current (matches
    // the group's latest key_epoch) - false means sendMessage() will need to
    // generate and distribute one before it can actually send encrypted.
    Q_INVOKABLE bool hasGroupSenderKey(const QString& chatId) const;

    // ---- Binary media (voice / attachment / round video) ----
    // Direct: pairwise crypto_box. Group: Sender Key secretbox (same key as
    // group text). outKeyVersion is set for group sends (0 for direct).
    // Returns empty on failure - callers fall back to cleartext.
    Q_INVOKABLE QByteArray encryptBytesForChat(const QString& chatId, const QByteArray& plain) const;
    QByteArray encryptBytesForChat(const QString& chatId, const QByteArray& plain, int* outKeyVersion) const;
    Q_INVOKABLE QByteArray decryptBytesForChat(const QString& chatId, const QByteArray& payload,
                                               const QString& senderId = QString(), int keyVersion = 0) const;

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
    // decrypted file onto disk. senderId/keyVersion required for group media.
    Q_INVOKABLE void preparePlayableVoice(const QString& chatId, const QString& messageId,
                                           const QString& fileUrl, bool encrypted,
                                           const QString& senderId = QString(), int keyVersion = 0);
    // Posts the message pointing at an already-uploaded voice note.
    void sendVoiceMessage(const QString& chatId, const QString& chatType,
                           const QString& fileUrl, qint64 durationMs, bool encrypted,
                           int keyVersion = 0);

    // Bumped every time a decryption key is learned. QML message rows read
    // this inside their text binding, so a row that rendered as the
    // "Encrypted message" placeholder - because the cached history painted
    // before the chat list had been parsed - re-decrypts itself the moment
    // the key lands, instead of staying wrong until the next fetch.
    int cryptoRevision() const { return m_cryptoRevision; }

    // ---- Round video messages ----
    // Same read -> encrypt -> upload -> send orchestration as sendVoiceNote,
    // against /messages/video-note. The wire format is deliberately identical
    // to the Android client's - content_type "video_note", length in
    // duration_ms, poster frame in thumbnail_url - so a note recorded on
    // either platform renders on the other.
    //
    // The recording is NOT transcoded. QMediaRecorder is asked for a small,
    // low-bitrate stream up front instead, and both clients centre-crop to the
    // circle at playback, which keeps the format tolerant of whatever aspect
    // the camera produced.
    Q_INVOKABLE void sendVideoNote(const QString& chatId, const QString& chatType,
                                    const QString& localFilePath, qint64 durationMs);

    // Fetches (or reuses a cached) decrypted local copy of a round video,
    // ready to hand to a QML MediaPlayer. Same division of labour as
    // preparePlayableVoice: this only gets a playable file onto disk.
    Q_INVOKABLE void preparePlayableVideoNote(const QString& chatId, const QString& messageId,
                                               const QString& fileUrl, bool encrypted,
                                               const QString& senderId = QString(), int keyVersion = 0);

    // The poster frame, fetched separately so a bubble fills in before the
    // much larger video has arrived.
    Q_INVOKABLE void prepareVideoNoteThumbnail(const QString& chatId, const QString& messageId,
                                                const QString& thumbnailUrl, bool encrypted,
                                                const QString& senderId = QString(), int keyVersion = 0);

    // Steps of the send, split so the poster-frame grab (which is async) can
    // sit between reading the recording and uploading it.
    void uploadVideoNote(const QString& chatId, const QString& chatType,
                          const QByteArray& raw, qint64 durationMs,
                          const QString& thumbnailPath);
    void uploadVideoNoteThumbnail(const QString& chatId, const QString& chatType,
                                   const QString& fileUrl, qint64 durationMs,
                                   bool encrypted, int keyVersion, const QString& thumbnailPath);

    // Posts the message pointing at an already-uploaded round video.
    void sendVideoNoteMessage(const QString& chatId, const QString& chatType,
                               const QString& fileUrl, const QString& thumbnailUrl,
                               qint64 durationMs, bool encrypted, int keyVersion = 0);

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
                                        const QString& fileName,
                                        const QString& senderId = QString(), int keyVersion = 0);

signals:
    void isLoadingChanged();
    // Each entry is a QVariantMap matching the backend's chat list JSON shape
    // (id, type, name, avatar_url, other_user, last_message, last_message_at,
    // unread_count, is_online, updated_at) - see ChatService::parseChatItem.
    void chatsFetched(const QVariantList& chats);
    void chatError(const QString& error);
    // Fired when a contact's E2EE public key is observed to be different
    // from the last one we saw for them - WhatsApp's "security code
    // changed" notice, same trigger (a re-registered/reinstalled device, a
    // reset keypair, or - the scenario this exists to catch - a
    // man-in-the-middle substituting their own key).
    void securityCodeChanged(const QString& chatId, const QString& contactName);
    void usersFound(const QVariantList& users);
    void searchError(const QString& error);
    void directChatReady(const QString& chatId, const QString& chatName);
    void messagesFetched(const QString& chatId, const QVariantList& messages);
    void messageSent(const QString& chatId, const QVariantMap& message);
    void messageError(const QString& error);
    void chatRead(const QString& chatId);
    void chatDeleted(const QString& chatId);
    void chatDeleteError(const QString& error);

    void voiceUploadError(const QString& error);
    void voiceMessageSent(const QString& chatId, const QVariantMap& message);
    void voiceReadyForPlayback(const QString& messageId, const QString& localFilePath);
    void voicePlaybackError(const QString& messageId, const QString& error);

    void attachmentUploadError(const QString& error);
    void attachmentMessageSent(const QString& chatId, const QVariantMap& message);
    void attachmentReady(const QString& messageId, const QString& localFilePath);
    void attachmentError(const QString& messageId, const QString& error);

    void cryptoRevisionChanged();

    void messageDeleted(const QString& chatId, const QString& messageId);
    void messageDeleteError(const QString& error);
    // New chat-list preview after a message was removed (empty string if the
    // chat now has no remaining messages). Symmetric to the live update
    // ChatList does on messageReceived.
    void chatLastMessageChanged(const QString& chatId, const QString& preview);

    void videoNoteUploadError(const QString& error);
    void videoNoteUploadProgress(qint64 sent, qint64 total);
    void videoNoteMessageSent(const QString& chatId, const QVariantMap& message);
    void videoNoteReadyForPlayback(const QString& messageId, const QString& localFilePath);
    void videoNoteThumbnailReady(const QString& messageId, const QString& localFilePath);
    void videoNotePlaybackError(const QString& messageId, const QString& error);

private slots:
    void onChatsReplyFinished(QNetworkReply* reply);

private:
    void setupNetworkManager();
    QString buildAuthHeader() const;
    // Recomputes the chat-list preview from the local message cache and emits
    // chatLastMessageChanged. Called after any path that removes a message.
    void refreshChatListPreview(const QString& chatId);
    QString computeLastMessagePreview(const QString& chatId) const;
    // Posts the message pointing at an already-uploaded attachment.
    void sendAttachmentMessage(const QString& chatId, const QString& chatType,
                                const QString& fileUrl, const QString& fileName,
                                const QString& contentType, qint64 fileSize, bool encrypted,
                                int keyVersion = 0);

    // ---- Group Sender Keys (WhatsApp/Signal-style group E2EE) ----
    // Used for group text and binary media (voice / attachment / round video).
    // Direct chats stay on pairwise crypto_box.
    void sendGroupTextMessage(const QString& chatId, const QString& text);
    // Ensures my current Sender Key is generated and distributed to every
    // current member before calling onReady() - a no-op straight to
    // onReady() if it's already current for this group's key_epoch.
    void ensureGroupSenderKeyReady(const QString& chatId, std::function<void()> onReady);
    // Fetches and decrypts every other member's Sender Key I don't already
    // have cached, then calls onDone() regardless of outcome (best-effort -
    // a group message from a sender whose key we couldn't get just shows the
    // "encrypted" placeholder instead of blocking the whole chat).
    void fetchGroupSenderKeys(const QString& chatId, std::function<void()> onDone);
    // Cache-only lookup (no network) - myself or another member, by version.
    QString groupSenderKeyFor(const QString& chatId, const QString& senderId, int keyVersion) const;
    void persistMySenderKey(const QString& chatId, int version, const QString& keyHex);
    void loadMySenderKeyFromDisk(const QString& chatId);

    AuthService* m_authService = nullptr;
    GroupService* m_groupService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;
    QNetworkReply* m_currentReply = nullptr;
    bool m_isLoading = false;
    int m_cryptoRevision = 0;
    MessageCache* m_messageCache = nullptr;

    // chatId -> the other participant's public key (hex), learned from the
    // chat list. For a direct chat this key both encrypts our outgoing
    // messages and decrypts everything in the thread (box is symmetric in the
    // shared-secret sense), so one key per chat is all we need.
    QHash<QString, QString> m_chatOtherPub;

    // chatId -> "direct"/"group", learned from the chat list - lets
    // decryptMessage() and friends branch to the right scheme without every
    // caller having to pass the chat type through explicitly.
    QHash<QString, QString> m_chatType;
    // chatId -> the group's current key_epoch (bumped server-side on every
    // membership change - see models.Chat.KeyEpoch).
    QHash<QString, int> m_groupKeyEpoch;

    struct SenderKeyState {
        int version = 0;
        QString keyHex;
    };
    // chatId -> my own current Sender Key for that group.
    QHash<QString, SenderKeyState> m_mySenderKeys;
    // "chatId|senderId|version" -> that sender's key, decrypted and cached
    // after fetchGroupSenderKeys().
    QHash<QString, QString> m_groupOtherKeys;

    // chatId -> contact name, for a security-code-change notice detected
    // while that chat wasn't the one open - see takePendingSecurityNotice().
    QHash<QString, QString> m_pendingSecurityNotices;

    // Helper: parse a single chat from JSON into a QML-friendly QVariantMap
    QVariantMap parseChatItem(const QJsonObject& obj);
};
