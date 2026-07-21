#pragma once

#include <QObject>
#include <QString>
#include <QDateTime>
#include <QUuid>

class Message : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString id READ id WRITE setId NOTIFY idChanged)
    Q_PROPERTY(QString chatId READ chatId WRITE setChatId NOTIFY chatIdChanged)
    Q_PROPERTY(QString senderId READ senderId WRITE setSenderId NOTIFY senderIdChanged)
    Q_PROPERTY(QString senderName READ senderName WRITE setSenderName NOTIFY senderNameChanged)
    Q_PROPERTY(QString content READ content WRITE setContent NOTIFY contentChanged)
    Q_PROPERTY(QString encryptedContent READ encryptedContent WRITE setEncryptedContent NOTIFY encryptedContentChanged)
    Q_PROPERTY(QString nonce READ nonce WRITE setNonce NOTIFY nonceChanged)
    Q_PROPERTY(QString recipientId READ recipientId WRITE setRecipientId NOTIFY recipientIdChanged)
    Q_PROPERTY(QDateTime timestamp READ timestamp WRITE setTimestamp NOTIFY timestampChanged)
    Q_PROPERTY(bool delivered READ delivered WRITE setDelivered NOTIFY deliveredChanged)
    Q_PROPERTY(bool read READ read WRITE setRead NOTIFY readChanged)
    Q_PROPERTY(bool encrypted READ encrypted NOTIFY encryptedChanged)
    Q_PROPERTY(int messageType READ messageType WRITE setMessageType NOTIFY messageTypeChanged)

public:
    enum MessageType {
        Text,
        Image,
        File,
        System
    };
    Q_ENUM(MessageType)

    Message() = default;
    explicit Message(QObject* parent) : QObject(parent) {}

    const QString& id() const { return m_id; }
    void setId(const QString& id) { m_id = id; emit idChanged(); }

    const QString& chatId() const { return m_chatId; }
    void setChatId(const QString& chatId) { m_chatId = chatId; emit chatIdChanged(); }

    const QString& senderId() const { return m_senderId; }
    void setSenderId(const QString& senderId) { m_senderId = senderId; emit senderIdChanged(); }

    const QString& senderName() const { return m_senderName; }
    void setSenderName(const QString& senderName) { m_senderName = senderName; emit senderNameChanged(); }

    const QString& content() const { return m_content; }
    void setContent(const QString& content) { m_content = content; emit contentChanged(); }

    const QString& encryptedContent() const { return m_encryptedContent; }
    void setEncryptedContent(const QString& encryptedContent) { m_encryptedContent = encryptedContent; emit encryptedContentChanged(); }

    const QString& nonce() const { return m_nonce; }
    void setNonce(const QString& nonce) { m_nonce = nonce; emit nonceChanged(); }

    const QString& recipientId() const { return m_recipientId; }
    void setRecipientId(const QString& recipientId) { m_recipientId = recipientId; emit recipientIdChanged(); }

    const QDateTime& timestamp() const { return m_timestamp; }
    void setTimestamp(const QDateTime& timestamp) { m_timestamp = timestamp; emit timestampChanged(); }

    bool delivered() const { return m_delivered; }
    void setDelivered(bool delivered) { m_delivered = delivered; emit deliveredChanged(); }

    bool read() const { return m_read; }
    void setRead(bool read) { m_read = read; emit readChanged(); }

    bool encrypted() const { return m_encrypted; }
    void setEncrypted(bool encrypted) { m_encrypted = encrypted; emit encryptedChanged(); }

    int messageType() const { return m_messageType; }
    void setMessageType(int messageType) { m_messageType = messageType; emit messageTypeChanged(); }

    Q_INVOKABLE QString formattedTime() const {
        return m_timestamp.toString(QStringLiteral("HH:mm"));
    }

    Q_INVOKABLE QString formattedDate() const {
        QStringList todayNames = {QStringLiteral("Today"), QStringLiteral("امروز")};
        QStringList yesterdayNames = {QStringLiteral("Yesterday"), QStringLiteral("دیروز")};

        QDate today = QDate::currentDate();
        QDate yesterday = today.addDays(-1);

        if (m_timestamp.date() == today)
            return todayNames.first();
        if (m_timestamp.date() == yesterday)
            return yesterdayNames.first();

        return m_timestamp.toString(QStringLiteral("dd MMM yyyy"));
    }

    Q_INVOKABLE bool isSameDay(const Message& other) const {
        return m_timestamp.date() == other.m_timestamp.date();
    }

signals:
    void idChanged();
    void chatIdChanged();
    void senderIdChanged();
    void senderNameChanged();
    void contentChanged();
    void encryptedContentChanged();
    void nonceChanged();
    void recipientIdChanged();
    void timestampChanged();
    void deliveredChanged();
    void readChanged();
    void encryptedChanged();
    void messageTypeChanged();

private:
    QString m_id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QString m_chatId;
    QString m_senderId;
    QString m_senderName;
    QString m_content;
    QString m_encryptedContent;
    QString m_nonce;
    QString m_recipientId;
    QDateTime m_timestamp = QDateTime::currentDateTime();
    bool m_delivered = false;
    bool m_read = false;
    bool m_encrypted = false;
    int m_messageType = Text;
};