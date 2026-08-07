#include "callservice.h"
#include "videocallengine.h"
#include "../utils/config.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QUuid>
#include <QUrl>
#include <QUrlQuery>
#include <QDebug>
#include <QMetaObject>
#include <QDateTime>
#include <QRandomGenerator>
#include <QAudioFormat>
#include <QAudioDevice>
#include <QMediaDevices>
#include <cstring>
#include <algorithm>
#include <array>
#include <vector>

// ==================== Audio I/O helpers ====================
// Qt Multimedia's QAudioSource/QAudioSink move raw PCM through a QIODevice:
// capture PUSHES bytes to us as the mic produces them (writeData), playback
// PULLS bytes from us whenever the audio backend needs more (readData). Both
// happen off the Qt main thread, so callers marshal back via invokeMethod
// rather than touching CallService state directly from these callbacks.

class AudioCaptureSink : public QIODevice {
    Q_OBJECT
public:
    explicit AudioCaptureSink(QObject* parent = nullptr) : QIODevice(parent) {
        open(QIODevice::WriteOnly);
    }
signals:
    void dataReady(const QByteArray& pcm);
protected:
    qint64 readData(char*, qint64) override { return -1; }
    qint64 writeData(const char* data, qint64 len) override {
        emit dataReady(QByteArray(data, static_cast<int>(len)));
        return len;
    }
};

// Multi-stream playback: each PeerEdge (or the 1:1 path under key "_") pushes
// decoded PCM; readData sums overlapping samples and clips. Missing streams
// contribute silence for that pull, matching the old underrun behaviour.
class AudioPlaybackSource : public QIODevice {
    Q_OBJECT
public:
    explicit AudioPlaybackSource(QObject* parent = nullptr) : QIODevice(parent) {
        open(QIODevice::ReadOnly);
    }

    void pushPcm(const QByteArray& pcm) {
        pushPcm(QStringLiteral("_"), pcm);
    }

    void pushPcm(const QString& streamKey, const QByteArray& pcm) {
        QMutexLocker lock(&m_mutex);
        QByteArray& buf = m_streams[streamKey];
        buf.append(pcm);
        constexpr qint64 maxBufferedBytes = 48000 * 2 * 1; // ~1s of 48kHz mono 16-bit
        if (buf.size() > maxBufferedBytes) {
            buf.remove(0, static_cast<int>(buf.size() - maxBufferedBytes));
        }
    }

    void removeStream(const QString& streamKey) {
        QMutexLocker lock(&m_mutex);
        m_streams.remove(streamKey);
    }

    void clearStreams() {
        QMutexLocker lock(&m_mutex);
        m_streams.clear();
    }

protected:
    qint64 readData(char* data, qint64 maxlen) override {
        QMutexLocker lock(&m_mutex);
        const qint64 sampleBytes = 2;
        const qint64 outSamples = maxlen / sampleBytes;
        if (outSamples <= 0) return 0;

        std::vector<int32_t> mix(static_cast<size_t>(outSamples), 0);
        for (auto it = m_streams.begin(); it != m_streams.end(); ++it) {
            QByteArray& buf = it.value();
            const qint64 avail = buf.size() / sampleBytes;
            const qint64 n = qMin(avail, outSamples);
            const auto* src = reinterpret_cast<const int16_t*>(buf.constData());
            for (qint64 i = 0; i < n; ++i) {
                mix[static_cast<size_t>(i)] += src[i];
            }
            if (n > 0) {
                buf.remove(0, static_cast<int>(n * sampleBytes));
            }
        }

        auto* dst = reinterpret_cast<int16_t*>(data);
        for (qint64 i = 0; i < outSamples; ++i) {
            dst[i] = static_cast<int16_t>(std::clamp(mix[static_cast<size_t>(i)], int32_t{-32768}, int32_t{32767}));
        }
        return outSamples * sampleBytes;
    }
    qint64 writeData(const char*, qint64) override { return -1; }
    qint64 bytesAvailable() const override {
        QMutexLocker lock(const_cast<QMutex*>(&m_mutex));
        qint64 maxBytes = 0;
        for (const auto& buf : m_streams) {
            maxBytes = qMax(maxBytes, static_cast<qint64>(buf.size()));
        }
        return maxBytes + QIODevice::bytesAvailable();
    }

private:
    mutable QMutex m_mutex;
    QHash<QString, QByteArray> m_streams;
};

#include "callservice.moc"

// ==================== CallService ====================

CallService::CallService(AuthService* authService, WebSocketService* webSocketService, QObject* parent)
    : QObject(parent), m_authService(authService), m_webSocketService(webSocketService) {
    m_networkManager = new QNetworkAccessManager(this);
    if (m_webSocketService) {
        connect(m_webSocketService, &WebSocketService::callSignalReceived, this, &CallService::onCallSignal);
    }

    m_videoEngine = new VideoCallEngine(this);
    connect(m_videoEngine, &VideoCallEngine::localFrame, this, &CallService::localVideoFrame);
    connect(m_videoEngine, &VideoCallEngine::remoteFrame, this, &CallService::remoteVideoFrame);
    connect(m_videoEngine, &VideoCallEngine::remoteFrameFromPeer, this, &CallService::remoteVideoFrameFromPeer);
    connect(m_videoEngine, &VideoCallEngine::captureFailed, this, [this](const QString& reason) {
        // No camera is not fatal - the call carries on voice-only, exactly
        // like toggling the camera off, and the peer is told the same way.
        qWarning() << "[CallService] video capture failed:" << reason;
        if (m_cameraOn) {
            m_cameraOn = false;
            if (m_isGroupCall) {
                for (auto it = m_edges.begin(); it != m_edges.end(); ++it) {
                    sendEdgeSignal(it.value().get(), QStringLiteral("call:media"),
                                   {{QStringLiteral("camera"), false}});
                }
            } else {
                sendSignal(QStringLiteral("call:media"), {{"camera", false}});
            }
            emit stateChanged();
        }
    });
}

CallService::~CallService() {
    stopAudioPipeline();
    tearAllEdges();
    if (m_peerConnection) {
        m_peerConnection->close();
    }
    if (m_opusEncoder) opus_encoder_destroy(m_opusEncoder);
    if (m_opusDecoder) opus_decoder_destroy(m_opusDecoder);
}

QString CallService::status() const {
    switch (m_status) {
        case Status::Idle: return QStringLiteral("idle");
        case Status::OutgoingRinging: return QStringLiteral("outgoing_ringing");
        case Status::IncomingRinging: return QStringLiteral("incoming_ringing");
        case Status::Connecting: return QStringLiteral("connecting");
        case Status::Connected: return QStringLiteral("connected");
        case Status::Ended: return QStringLiteral("ended");
    }
    return QStringLiteral("idle");
}

void CallService::setStatus(Status status) {
    m_status = status;
    emit stateChanged();
}

QVariantList CallService::participants() const {
    QVariantList list;
    for (const auto& p : m_participants) {
        QVariantMap m;
        m[QStringLiteral("userId")] = p.userId;
        m[QStringLiteral("name")] = p.name;
        const PeerEdge* edge = m_edges.value(p.userId).get();
        m[QStringLiteral("connected")] = edge && edge->connected;
        m[QStringLiteral("cameraOn")] = m_isVideoCall ? p.cameraOn : false;
        list.append(m);
    }
    return list;
}

void CallService::startOutgoingCall(const QString& chatId, const QString& calleeId, const QString& calleeName,
                                    bool video) {
    if (m_status != Status::Idle) return;

    m_isGroupCall = false;
    m_groupCallId.clear();
    m_participants.clear();
    m_callId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_chatId = chatId;
    m_peerUserId = calleeId;
    m_peerName = calleeName;
    m_endReason.clear();
    m_isCaller = true;
    m_inviteSent = false;
    m_isVideoCall = video;
    m_cameraOn = video;
    m_remoteCameraOn = true;
    setStatus(Status::OutgoingRinging);

    fetchIceServersThen([this]() {
        if (m_status != Status::OutgoingRinging) return; // rejected/ended while fetching
        createPeerConnection();
        addAudioTrack();
        if (m_isVideoCall) addVideoTrack();
        startAudioPipeline(true);
        if (m_isVideoCall && m_cameraOn) {
            m_videoEngine->startCapture();
        }
        m_peerConnection->setLocalDescription(rtc::Description::Type::Offer);
    });
}

void CallService::startGroupCall(const QString& chatId, const QString& groupName,
                                 const QStringList& memberIds, const QStringList& memberNames,
                                 bool video) {
    if (m_status != Status::Idle) return;

    const int total = memberIds.size() + 1; // include self
    if (total < 2 || total > kMaxGroupParticipants) {
        qWarning() << "[CallService] startGroupCall rejected: need 2.." << kMaxGroupParticipants
                   << "participants, got" << total;
        return;
    }

    m_isGroupCall = true;
    m_isVideoCall = video;
    m_cameraOn = video;
    m_remoteCameraOn = false;
    m_groupCallId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_callId = m_groupCallId; // session id for UI/teardown bookkeeping
    m_chatId = chatId;
    m_peerUserId.clear();
    m_peerName = groupName;
    m_endReason.clear();
    m_isCaller = true;
    m_inviteSent = false;
    m_participants.clear();
    m_edges.clear();
    m_orphanIceByCallId.clear();

    for (int i = 0; i < memberIds.size(); ++i) {
        const QString id = memberIds.at(i);
        if (id.isEmpty()) continue;
        const QString name = (i < memberNames.size()) ? memberNames.at(i) : id;
        upsertParticipant(id, name);
    }

    setStatus(Status::OutgoingRinging);

    QVariantMap invite;
    invite[QStringLiteral("from_name")] = m_authService ? m_authService->currentUsername() : QString();
    invite[QStringLiteral("video")] = m_isVideoCall;
    invite[QStringLiteral("participant_ids")] = participantIdList();
    sendGroupSignal(QStringLiteral("call:group_invite"), invite);

    fetchIceServersThen([this]() {
        if (m_status != Status::OutgoingRinging && m_status != Status::Connecting &&
            m_status != Status::Connected) {
            return;
        }
        startAudioPipeline(false);
        if (m_isVideoCall && m_cameraOn) {
            m_cameraOn = m_videoEngine->startCapture();
            emit stateChanged();
        }
    });
}

void CallService::acceptCall() {
    if (m_status != Status::IncomingRinging) return;
    setStatus(Status::Connecting);

    if (m_isGroupCall) {
        QVariantMap join;
        join[QStringLiteral("from_name")] = m_authService ? m_authService->currentUsername() : QString();
        join[QStringLiteral("participant_ids")] = participantIdList();
        sendGroupSignal(QStringLiteral("call:group_join"), join);

        fetchIceServersThen([this]() {
            if (m_status != Status::Connecting && m_status != Status::Connected) return;
            startAudioPipeline(false);
            if (m_isVideoCall) {
                m_cameraOn = m_videoEngine->startCapture();
                emit stateChanged();
            }
            const QList<ParticipantInfo> snapshot = m_participants;
            for (const auto& p : snapshot) {
                ensureMeshEdge(p.userId, p.name);
            }
        });
        return;
    }

    fetchIceServersThen([this]() {
        if (m_status != Status::Connecting) return;
        createPeerConnection();
        try {
            // No addAudioTrack() here, deliberately: applying the offer makes
            // libdatachannel create the Track for us (delivered via onTrack)
            // with the offer's own mid, which is what a valid answer needs.
            // Adding our own would both invent a mismatched mid and, on a
            // still-stable connection, provoke a stray offer.
            m_peerConnection->setRemoteDescription(rtc::Description(m_pendingOfferSdp.toStdString(), "offer"));
            m_remoteDescriptionSet = true;
            flushPendingRemoteCandidates();
            startAudioPipeline(true);
            if (m_isVideoCall) {
                // The video Track itself arrived via onTrack while the offer
                // was applied above; only the camera needs starting here.
                m_cameraOn = m_videoEngine->startCapture();
                emit stateChanged();
            }

            // libdatachannel negotiates on its own: applying a remote offer
            // already builds the answer and calls setLocalDescription
            // internally, leaving signaling state Stable. Driving it a second
            // time throws "Unexpected local description type answer in
            // signaling state stable" - which our catch below turned into
            // teardown("failed"), killing a call that had in fact just been
            // answered successfully (the server had already logged it as
            // answered). Only step in if it somehow didn't self-answer.
            if (m_peerConnection->signalingState() ==
                rtc::PeerConnection::SignalingState::HaveRemoteOffer) {
                m_peerConnection->setLocalDescription(rtc::Description::Type::Answer);
            }
        } catch (const std::exception& e) {
            qWarning() << "[CallService] acceptCall failed:" << e.what();
            teardown(QStringLiteral("failed"));
        }
    });
}

void CallService::rejectCall() {
    if (m_status != Status::IncomingRinging) return;
    if (m_isGroupCall) {
        sendGroupSignal(QStringLiteral("call:group_leave"), {
            {QStringLiteral("reason"), QStringLiteral("declined")},
            {QStringLiteral("participant_ids"), participantIdList()}
        });
        teardown(QStringLiteral("declined"));
        return;
    }
    sendSignal(QStringLiteral("call:reject"), {{"reason", "declined"}});
    teardown(QStringLiteral("declined"));
}

void CallService::endCall() {
    if (m_status == Status::Idle || m_status == Status::Ended) {
        teardown(QStringLiteral("hangup"));
        return;
    }
    if (m_isGroupCall) {
        sendGroupSignal(QStringLiteral("call:group_leave"), {
            {QStringLiteral("reason"), QStringLiteral("hangup")},
            {QStringLiteral("participant_ids"), participantIdList()}
        });
        teardown(QStringLiteral("hangup"));
        return;
    }
    sendSignal(QStringLiteral("call:end"), {{"reason", "hangup"}});
    teardown(QStringLiteral("hangup"));
}

void CallService::toggleMute() {
    m_isMuted = !m_isMuted;
    emit stateChanged();
}

void CallService::toggleCamera() {
    if (!m_isVideoCall || m_status == Status::Idle || m_status == Status::Ended) return;
    if (m_cameraOn) {
        m_videoEngine->stopCapture();
        m_cameraOn = false;
    } else {
        m_cameraOn = m_videoEngine->startCapture();
    }
    if (m_isGroupCall) {
        for (auto it = m_edges.begin(); it != m_edges.end(); ++it) {
            sendEdgeSignal(it.value().get(), QStringLiteral("call:media"),
                           {{QStringLiteral("camera"), m_cameraOn}});
        }
    } else {
        sendSignal(QStringLiteral("call:media"), {{"camera", m_cameraOn}});
    }
    emit stateChanged();
}

void CallService::dismissEnded() {
    if (m_status != Status::Ended) return;
    m_status = Status::Idle;
    m_callId.clear();
    m_chatId.clear();
    m_peerUserId.clear();
    m_peerName.clear();
    m_endReason.clear();
    m_connectedAtMs = 0;
    m_isMuted = false;
    m_isVideoCall = false;
    m_cameraOn = false;
    m_remoteCameraOn = true;
    m_isGroupCall = false;
    m_groupCallId.clear();
    m_participants.clear();
    emit stateChanged();
}

void CallService::onCallSignal(const QString& type, const QVariantMap& data) {
    if (type == QStringLiteral("call:group_invite")) {
        handleGroupInvite(data);
        return;
    }
    if (type == QStringLiteral("call:group_join")) {
        handleGroupJoin(data);
        return;
    }
    if (type == QStringLiteral("call:group_leave")) {
        handleGroupLeave(data);
        return;
    }

    const QString callId = data.value(QStringLiteral("call_id")).toString();
    const QString groupCallId = data.value(QStringLiteral("group_call_id")).toString();

    // Pairwise invite that belongs to our group mesh session.
    if (type == QStringLiteral("call:invite") && !groupCallId.isEmpty() &&
        m_isGroupCall && groupCallId == m_groupCallId &&
        (m_status == Status::Connecting || m_status == Status::Connected ||
         m_status == Status::OutgoingRinging)) {
        answerGroupEdge(data);
        return;
    }

    if (type == QStringLiteral("call:invite")) {
        if (m_status != Status::Idle) {
            // Busy - tell the caller rather than silently dropping the invite.
            QVariantMap busy;
            busy["to_user_id"] = data.value(QStringLiteral("from_user_id"));
            busy["from_user_id"] = m_authService ? m_authService->currentUserId() : QString();
            busy["call_id"] = callId;
            busy["reason"] = "busy";
            if (m_webSocketService) m_webSocketService->sendCallSignal(QStringLiteral("call:end"), busy);
            return;
        }
        m_isGroupCall = false;
        m_groupCallId.clear();
        m_participants.clear();
        m_callId = callId;
        m_chatId = data.value(QStringLiteral("chat_id")).toString();
        m_peerUserId = data.value(QStringLiteral("from_user_id")).toString();
        m_peerName = data.value(QStringLiteral("from_name")).toString();
        m_pendingOfferSdp = data.value(QStringLiteral("sdp")).toString();
        m_endReason.clear();
        m_isCaller = false;
        m_inviteSent = false;
        m_isVideoCall = data.value(QStringLiteral("video")).toBool();
        m_cameraOn = false;
        m_remoteCameraOn = true;
        setStatus(Status::IncomingRinging);
        return;
    }

    // Group mesh: route pairwise answer/ICE/end/media by edge call_id.
    if (m_isGroupCall && m_status != Status::Idle && m_status != Status::Ended) {
        PeerEdge* edge = edgeByCallId(callId);
        if (type == QStringLiteral("call:ice_candidate") && !edge) {
            m_orphanIceByCallId[callId].append(data);
            return;
        }
        if (type == QStringLiteral("call:media")) {
            // Camera state is keyed by peer user id, not the edge object -
            // still apply even if the edge has already been torn down.
            const QString fromId = data.value(QStringLiteral("from_user_id")).toString();
            const bool camOn = data.value(QStringLiteral("camera")).toBool();
            for (auto& p : m_participants) {
                if (p.userId == fromId) {
                    p.cameraOn = camOn;
                    break;
                }
            }
            emit stateChanged();
            return;
        }
        if (!edge) return;

        if (type == QStringLiteral("call:answer")) {
            try {
                edge->peerConnection->setRemoteDescription(
                    rtc::Description(data.value(QStringLiteral("sdp")).toString().toStdString(), "answer"));
                edge->remoteDescriptionSet = true;
                flushEdgePendingCandidates(edge);
                if (m_status == Status::OutgoingRinging) {
                    setStatus(Status::Connecting);
                }
            } catch (const std::exception& e) {
                qWarning() << "[CallService] group setRemoteDescription(answer) failed:" << e.what();
                tearEdge(edge->peerUserId);
            }
        } else if (type == QStringLiteral("call:ice_candidate")) {
            applyEdgeRemoteCandidate(edge, data);
        } else if (type == QStringLiteral("call:end") || type == QStringLiteral("call:reject")) {
            tearEdge(edge->peerUserId);
            emit stateChanged();
        }
        return;
    }

    // Every other signal type only applies to the 1:1 call currently in progress.
    if (callId != m_callId || m_status == Status::Idle) return;

    if (type == QStringLiteral("call:answer")) {
        try {
            m_peerConnection->setRemoteDescription(
                rtc::Description(data.value(QStringLiteral("sdp")).toString().toStdString(), "answer"));
            m_remoteDescriptionSet = true;
            flushPendingRemoteCandidates();
            setStatus(Status::Connecting);
        } catch (const std::exception& e) {
            qWarning() << "[CallService] setRemoteDescription(answer) failed:" << e.what();
            teardown(QStringLiteral("failed"));
        }
    } else if (type == QStringLiteral("call:ice_candidate")) {
        applyRemoteCandidate(data);
    } else if (type == QStringLiteral("call:media")) {
        m_remoteCameraOn = data.value(QStringLiteral("camera")).toBool();
        emit stateChanged();
    } else if (type == QStringLiteral("call:reject")) {
        teardown(QStringLiteral("declined"));
    } else if (type == QStringLiteral("call:end")) {
        QString reason = data.value(QStringLiteral("reason")).toString();
        teardown(reason.isEmpty() ? QStringLiteral("ended") : reason);
    }
}

void CallService::handleGroupInvite(const QVariantMap& data) {
    if (m_status != Status::Idle) {
        qDebug() << "[CallService] ignoring group_invite while busy";
        return;
    }

    m_isGroupCall = true;
    m_isVideoCall = data.value(QStringLiteral("video")).toBool();
    m_cameraOn = false;
    m_remoteCameraOn = false;
    m_groupCallId = data.value(QStringLiteral("call_id")).toString();
    m_callId = m_groupCallId;
    m_chatId = data.value(QStringLiteral("chat_id")).toString();
    m_peerUserId = data.value(QStringLiteral("from_user_id")).toString();
    const QString fromName = data.value(QStringLiteral("from_name")).toString();
    m_peerName = fromName.isEmpty() ? QStringLiteral("Group call") : fromName;
    m_endReason.clear();
    m_isCaller = false;
    m_inviteSent = false;
    m_participants.clear();
    m_edges.clear();
    m_orphanIceByCallId.clear();

    upsertParticipant(m_peerUserId, fromName);
    for (const QString& id : parseIdList(data.value(QStringLiteral("participant_ids")))) {
        if (m_authService && id == m_authService->currentUserId()) continue;
        upsertParticipant(id, participantName(id));
    }

    setStatus(Status::IncomingRinging);
}

void CallService::handleGroupJoin(const QVariantMap& data) {
    if (!m_isGroupCall || m_status == Status::Idle || m_status == Status::Ended) return;

    const QString sessionId = data.value(QStringLiteral("call_id")).toString();
    if (!sessionId.isEmpty() && sessionId != m_groupCallId) return;

    const QString fromId = data.value(QStringLiteral("from_user_id")).toString();
    if (m_authService && fromId == m_authService->currentUserId()) return;

    const QString fromName = data.value(QStringLiteral("from_name")).toString();
    upsertParticipant(fromId, fromName.isEmpty() ? participantName(fromId) : fromName);

    for (const QString& id : parseIdList(data.value(QStringLiteral("participant_ids")))) {
        if (m_authService && id == m_authService->currentUserId()) continue;
        upsertParticipant(id, participantName(id));
    }

    emit stateChanged();

    // Only mesh once we're past ringing (accepted or we started the call).
    if (m_status == Status::IncomingRinging) return;

    ensureMeshEdge(fromId, participantName(fromId));
}

void CallService::handleGroupLeave(const QVariantMap& data) {
    if (!m_isGroupCall || m_status == Status::Idle) return;

    const QString sessionId = data.value(QStringLiteral("call_id")).toString();
    if (!sessionId.isEmpty() && sessionId != m_groupCallId) return;

    const QString reason = data.value(QStringLiteral("reason")).toString();
    const QString fromId = data.value(QStringLiteral("from_user_id")).toString();

    if (reason == QStringLiteral("too_large")) {
        teardown(QStringLiteral("failed"));
        return;
    }

    if (m_authService && fromId == m_authService->currentUserId()) return;

    tearEdge(fromId);
    for (int i = 0; i < m_participants.size(); ++i) {
        if (m_participants.at(i).userId == fromId) {
            m_participants.removeAt(i);
            break;
        }
    }
    emit stateChanged();
}

void CallService::ensureMeshEdge(const QString& peerId, const QString& peerName) {
    if (peerId.isEmpty()) return;
    const QString myId = m_authService ? m_authService->currentUserId() : QString();
    if (peerId == myId) return;
    if (m_edges.contains(peerId)) return;

    upsertParticipant(peerId, peerName.isEmpty() ? peerId : peerName);

    // Glare: lexicographically lower userId offers to higher. Higher waits
    // for call:invite with our group_call_id (handled in answerGroupEdge).
    if (myId.isEmpty() || myId >= peerId) {
        qDebug() << "[CallService] ensureMeshEdge wait for offer from" << peerId;
        return;
    }

    auto edge = std::make_shared<PeerEdge>();
    edge->peerUserId = peerId;
    edge->peerName = peerName.isEmpty() ? peerId : peerName;
    edge->callId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    edge->isCaller = true;

    int opusError = 0;
    edge->opusDecoder = opus_decoder_create(kSampleRate, kChannels, &opusError);
    if (opusError != OPUS_OK) {
        qWarning() << "[CallService] edge opus_decoder_create failed:" << opusError;
        edge->opusDecoder = nullptr;
    }

    m_edges.insert(peerId, edge);
    createEdgePeerConnection(edge.get());
    addEdgeAudioTrack(edge.get());
    if (m_isVideoCall) addEdgeVideoTrack(edge.get());
    try {
        edge->peerConnection->setLocalDescription(rtc::Description::Type::Offer);
    } catch (const std::exception& e) {
        qWarning() << "[CallService] edge setLocalDescription(offer) failed:" << e.what();
        tearEdge(peerId);
    }
    emit stateChanged();
}

void CallService::answerGroupEdge(const QVariantMap& data) {
    const QString fromId = data.value(QStringLiteral("from_user_id")).toString();
    if (fromId.isEmpty() || m_edges.contains(fromId)) return;

    const QString myId = m_authService ? m_authService->currentUserId() : QString();
    // Only the higher id should answer; if we somehow both offered, drop.
    if (!myId.isEmpty() && myId < fromId) {
        qDebug() << "[CallService] ignoring edge invite from higher id (we should offer)" << fromId;
        return;
    }

    auto edge = std::make_shared<PeerEdge>();
    edge->peerUserId = fromId;
    edge->peerName = data.value(QStringLiteral("from_name")).toString();
    if (edge->peerName.isEmpty()) edge->peerName = participantName(fromId);
    edge->callId = data.value(QStringLiteral("call_id")).toString();
    edge->isCaller = false;
    edge->pendingOfferSdp = data.value(QStringLiteral("sdp")).toString();

    int opusError = 0;
    edge->opusDecoder = opus_decoder_create(kSampleRate, kChannels, &opusError);
    if (opusError != OPUS_OK) {
        qWarning() << "[CallService] edge opus_decoder_create failed:" << opusError;
        edge->opusDecoder = nullptr;
    }

    upsertParticipant(fromId, edge->peerName);
    m_edges.insert(fromId, edge);

    // Attach any ICE that raced ahead of this invite.
    if (m_orphanIceByCallId.contains(edge->callId)) {
        edge->pendingRemoteCandidates.append(m_orphanIceByCallId.take(edge->callId));
    }

    createEdgePeerConnection(edge.get());
    try {
        // Answerer must NOT addTrack() - libdatachannel delivers the track via
        // onTrack with the offer's mid (same as 1:1).
        edge->peerConnection->setRemoteDescription(
            rtc::Description(edge->pendingOfferSdp.toStdString(), "offer"));
        edge->remoteDescriptionSet = true;
        flushEdgePendingCandidates(edge.get());

        if (edge->peerConnection->signalingState() ==
            rtc::PeerConnection::SignalingState::HaveRemoteOffer) {
            edge->peerConnection->setLocalDescription(rtc::Description::Type::Answer);
        }
        if (m_status == Status::OutgoingRinging) {
            setStatus(Status::Connecting);
        }
    } catch (const std::exception& e) {
        qWarning() << "[CallService] answerGroupEdge failed:" << e.what();
        tearEdge(fromId);
    }
    emit stateChanged();
}

void CallService::createEdgePeerConnection(PeerEdge* edge) {
    if (!edge) return;

    edge->localSsrc = 1000 + static_cast<rtc::SSRC>(QRandomGenerator::global()->bounded(1, 100000));
    edge->videoSsrc = edge->localSsrc + 1;

    rtc::Configuration config;
    config.iceServers = m_iceServers;
    edge->peerConnection = std::make_shared<rtc::PeerConnection>(config);

    const QString peerId = edge->peerUserId;

    edge->peerConnection->onLocalDescription([this, peerId](rtc::Description desc) {
        QString sdp = QString::fromStdString(std::string(desc));
        QMetaObject::invokeMethod(this, [this, peerId, sdp]() {
            PeerEdge* e = edgeByPeerId(peerId);
            if (!e) return;
            if (e->isCaller) {
                if (e->inviteSent) return;
                e->inviteSent = true;
                sendEdgeSignal(e, QStringLiteral("call:invite"), {
                    {QStringLiteral("sdp"), sdp},
                    {QStringLiteral("from_name"),
                     m_authService ? m_authService->currentUsername() : QString()},
                    {QStringLiteral("video"), m_isVideoCall}
                });
            } else {
                sendEdgeSignal(e, QStringLiteral("call:answer"), {{QStringLiteral("sdp"), sdp}});
            }
        }, Qt::QueuedConnection);
    });

    edge->peerConnection->onLocalCandidate([this, peerId](rtc::Candidate candidate) {
        QString cand = QString::fromStdString(candidate.candidate());
        QString mid = QString::fromStdString(candidate.mid());
        QMetaObject::invokeMethod(this, [this, peerId, cand, mid]() {
            PeerEdge* e = edgeByPeerId(peerId);
            if (!e) return;
            sendEdgeSignal(e, QStringLiteral("call:ice_candidate"), {
                {QStringLiteral("candidate"), cand},
                {QStringLiteral("sdp_mid"), mid},
                {QStringLiteral("sdp_mline_index"), 0}
            });
        }, Qt::QueuedConnection);
    });

    // Capture SSRCs now - onTrack fires on the libdatachannel thread and must
    // not touch m_edges (Qt-thread state) while composing the answer SDP.
    const rtc::SSRC edgeSsrc = edge->localSsrc;
    const rtc::SSRC edgeVideoSsrc = edge->videoSsrc;
    edge->peerConnection->onTrack([this, peerId, edgeSsrc, edgeVideoSsrc](std::shared_ptr<rtc::Track> track) {
        bool isVideo = false;
        try {
            isVideo = track->description().type() == "video";
        } catch (const std::exception& ex) {
            qWarning() << "[CallService] edge could not read track type:" << ex.what();
        }

        try {
            auto desc = track->description();
            if (desc.getSSRCs().empty()) {
                desc.addSSRC(isVideo ? edgeVideoSsrc : edgeSsrc,
                             isVideo ? "windows-group-video" : "windows-group-audio");
                track->setDescription(desc);
            }
        } catch (const std::exception& ex) {
            qWarning() << "[CallService] edge could not declare local SSRC:" << ex.what();
        }

        QMetaObject::invokeMethod(this, [this, peerId, track, isVideo]() {
            PeerEdge* e = edgeByPeerId(peerId);
            if (!e || e->isCaller) return;
            if (isVideo) {
                e->videoTrack = track;
                attachEdgeVideoHandlers(e);
            } else {
                e->audioTrack = track;
                attachEdgeAudioHandlers(e);
            }
        }, Qt::QueuedConnection);
    });

    edge->peerConnection->onStateChange([this, peerId](rtc::PeerConnection::State state) {
        qDebug() << "[CallService] edge" << peerId << "PC state:" << static_cast<int>(state);
        QMetaObject::invokeMethod(this, [this, peerId, state]() {
            PeerEdge* e = edgeByPeerId(peerId);
            if (!e) return;
            if (state == rtc::PeerConnection::State::Connected) {
                e->connected = true;
                if (m_status != Status::Connected) {
                    m_connectedAtMs = QDateTime::currentMSecsSinceEpoch();
                    setStatus(Status::Connected);
                } else {
                    emit stateChanged();
                }
            } else if (state == rtc::PeerConnection::State::Failed) {
                tearEdge(peerId);
                emit stateChanged();
            }
        }, Qt::QueuedConnection);
    });
}

void CallService::addEdgeAudioTrack(PeerEdge* edge) {
    if (!edge || !edge->peerConnection) return;
    rtc::Description::Audio audioMedia("audio", rtc::Description::Direction::SendRecv);
    audioMedia.addOpusCodec(kOpusPayloadType);
    audioMedia.addSSRC(edge->localSsrc, "windows-group-audio");
    edge->audioTrack = edge->peerConnection->addTrack(audioMedia);
    attachEdgeAudioHandlers(edge);
}

void CallService::addEdgeVideoTrack(PeerEdge* edge) {
    if (!edge || !edge->peerConnection) return;
    rtc::Description::Video videoMedia("video", rtc::Description::Direction::SendRecv);
    videoMedia.addVP8Codec(kVp8PayloadType);
    videoMedia.addSSRC(edge->videoSsrc, "windows-group-video");
    edge->videoTrack = edge->peerConnection->addTrack(videoMedia);
    attachEdgeVideoHandlers(edge);
}

void CallService::attachEdgeAudioHandlers(PeerEdge* edge) {
    if (!edge || !edge->audioTrack) return;

    edge->audioTrack->setMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());

    edge->negotiatedOpusPayloadType = kOpusPayloadType;
    try {
        const auto desc = edge->audioTrack->description();
        for (int pt : desc.payloadTypes()) {
            if (const auto* map = desc.rtpMap(pt)) {
                if (QString::fromStdString(map->format).compare(QStringLiteral("opus"), Qt::CaseInsensitive) == 0) {
                    edge->negotiatedOpusPayloadType = static_cast<uint8_t>(pt);
                    break;
                }
            }
        }
    } catch (const std::exception& e) {
        qWarning() << "[CallService] edge could not read negotiated PT:" << e.what();
    }

    const QString peerId = edge->peerUserId;
    edge->audioTrack->onMessage([this, peerId](rtc::message_variant data) {
        if (!std::holds_alternative<rtc::binary>(data)) return;
        const auto& bytes = std::get<rtc::binary>(data);
        if (bytes.size() <= sizeof(rtc::RtpHeader)) return;
        if (rtc::IsRtcp(bytes)) return;

        const auto* header = reinterpret_cast<const rtc::RtpHeader*>(bytes.data());
        const auto* bodyStart = reinterpret_cast<const std::byte*>(header->getBody());
        const auto* packetStart = bytes.data();
        const auto offset = static_cast<size_t>(bodyStart - packetStart);
        if (offset >= bytes.size()) return;

        QByteArray payload(reinterpret_cast<const char*>(bodyStart),
                           static_cast<int>(bytes.size() - offset));
        QMetaObject::invokeMethod(this, [this, peerId, payload]() {
            PeerEdge* e = edgeByPeerId(peerId);
            if (!e || !e->opusDecoder || !m_playbackSource) return;
            int16_t pcm[kFrameSamples * 2];
            int decoded = opus_decode(e->opusDecoder,
                                       reinterpret_cast<const unsigned char*>(payload.constData()),
                                       payload.size(), pcm, kFrameSamples * 2, 0);
            if (decoded > 0) {
                m_playbackSource->pushPcm(peerId, QByteArray(reinterpret_cast<const char*>(pcm),
                                                               decoded * static_cast<int>(sizeof(int16_t))));
            }
        }, Qt::QueuedConnection);
    }, nullptr);
}

void CallService::attachEdgeVideoHandlers(PeerEdge* edge) {
    if (!edge || !edge->videoTrack || !m_videoEngine) return;

    edge->videoTrack->setMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());

    edge->negotiatedVp8PayloadType = kVp8PayloadType;
    try {
        const auto desc = edge->videoTrack->description();
        for (int pt : desc.payloadTypes()) {
            if (const auto* map = desc.rtpMap(pt)) {
                if (QString::fromStdString(map->format).compare(QStringLiteral("VP8"), Qt::CaseInsensitive) == 0) {
                    edge->negotiatedVp8PayloadType = static_cast<uint8_t>(pt);
                    break;
                }
            }
        }
        qDebug() << "[CallService] edge video track ready for" << edge->peerUserId
                 << "pt =" << edge->negotiatedVp8PayloadType
                 << "mid =" << QString::fromStdString(edge->videoTrack->mid());
    } catch (const std::exception& e) {
        qWarning() << "[CallService] edge could not read video PT:" << e.what();
    }

    const QString peerId = edge->peerUserId;
    m_videoEngine->addSendTrack(peerId, edge->videoTrack, edge->videoSsrc, edge->negotiatedVp8PayloadType);
    m_videoEngine->registerDecoder(peerId, edge->negotiatedVp8PayloadType);

    edge->videoTrack->onMessage([this, peerId](rtc::message_variant data) {
        if (!std::holds_alternative<rtc::binary>(data)) return;
        const auto& bytes = std::get<rtc::binary>(data);
        if (bytes.size() <= sizeof(rtc::RtpHeader)) return;
        if (rtc::IsRtcp(bytes)) return;

        QByteArray packet(reinterpret_cast<const char*>(bytes.data()), static_cast<int>(bytes.size()));
        QMetaObject::invokeMethod(this, [this, peerId, packet]() {
            if (m_videoEngine) m_videoEngine->handleRtpFromPeer(peerId, packet);
        }, Qt::QueuedConnection);
    }, nullptr);
}

void CallService::applyEdgeRemoteCandidate(PeerEdge* edge, const QVariantMap& data) {
    if (!edge) return;
    if (!edge->peerConnection || !edge->remoteDescriptionSet) {
        edge->pendingRemoteCandidates.append(data);
        return;
    }
    try {
        edge->peerConnection->addRemoteCandidate(rtc::Candidate(
            data.value(QStringLiteral("candidate")).toString().toStdString(),
            data.value(QStringLiteral("sdp_mid")).toString().toStdString()));
    } catch (const std::exception& e) {
        qWarning() << "[CallService] edge addRemoteCandidate failed:" << e.what();
    }
}

void CallService::flushEdgePendingCandidates(PeerEdge* edge) {
    if (!edge || edge->pendingRemoteCandidates.isEmpty()) return;
    const auto buffered = edge->pendingRemoteCandidates;
    edge->pendingRemoteCandidates.clear();
    for (const auto& data : buffered) {
        applyEdgeRemoteCandidate(edge, data);
    }
}

void CallService::tearEdge(const QString& peerId) {
    auto edge = m_edges.take(peerId);
    if (!edge) return;

    if (m_videoEngine) {
        m_videoEngine->removeSendTrack(peerId);
        m_videoEngine->unregisterDecoder(peerId);
    }
    if (m_playbackSource) {
        m_playbackSource->removeStream(peerId);
    }
    try {
        if (edge->peerConnection) {
            edge->peerConnection->close();
            edge->peerConnection.reset();
        }
    } catch (const std::exception& e) {
        qWarning() << "[CallService] tearEdge close threw:" << e.what();
        edge->peerConnection.reset();
    }
    edge->audioTrack.reset();
    edge->videoTrack.reset();
    if (edge->opusDecoder) {
        opus_decoder_destroy(edge->opusDecoder);
        edge->opusDecoder = nullptr;
    }
    if (!edge->callId.isEmpty()) {
        m_orphanIceByCallId.remove(edge->callId);
    }
}

void CallService::tearAllEdges() {
    const QStringList ids = m_edges.keys();
    for (const QString& id : ids) {
        tearEdge(id);
    }
    m_orphanIceByCallId.clear();
}

CallService::PeerEdge* CallService::edgeByPeerId(const QString& peerId) {
    auto it = m_edges.find(peerId);
    return it == m_edges.end() ? nullptr : it.value().get();
}

CallService::PeerEdge* CallService::edgeByCallId(const QString& callId) {
    if (callId.isEmpty()) return nullptr;
    for (auto it = m_edges.begin(); it != m_edges.end(); ++it) {
        if (it.value() && it.value()->callId == callId) {
            return it.value().get();
        }
    }
    return nullptr;
}

void CallService::upsertParticipant(const QString& userId, const QString& name) {
    if (userId.isEmpty()) return;
    if (m_authService && userId == m_authService->currentUserId()) return;
    for (auto& p : m_participants) {
        if (p.userId == userId) {
            if (!name.isEmpty()) p.name = name;
            return;
        }
    }
    m_participants.append({userId, name.isEmpty() ? userId : name});
}

QVariantList CallService::participantIdList() const {
    QVariantList ids;
    if (m_authService) {
        const QString me = m_authService->currentUserId();
        if (!me.isEmpty()) ids.append(me);
    }
    for (const auto& p : m_participants) {
        ids.append(p.userId);
    }
    return ids;
}

QString CallService::participantName(const QString& userId) const {
    for (const auto& p : m_participants) {
        if (p.userId == userId) return p.name;
    }
    return userId;
}

QStringList CallService::parseIdList(const QVariant& raw) {
    QStringList out;
    const QVariantList list = raw.toList();
    for (const QVariant& v : list) {
        const QString id = v.toString();
        if (!id.isEmpty()) out.append(id);
    }
    return out;
}

void CallService::applyRemoteCandidate(const QVariantMap& data) {
    // Until there's a PeerConnection *with a remote description*, a candidate
    // has nothing to attach to. Dropping it - which is what this used to do -
    // loses the far end's entire first burst of candidates, because it
    // trickles them from the moment it dials while an incoming call is still
    // just ringing here. If its gathering finished before the call was
    // answered, that meant zero usable candidates ever arrived and ICE could
    // never pair up: the call sat on "Connecting" until somebody gave up.
    if (!m_peerConnection || !m_remoteDescriptionSet) {
        m_pendingRemoteCandidates.append(data);
        return;
    }
    try {
        qDebug() << "[CallService] REMOTE candidate:" << data.value(QStringLiteral("candidate")).toString();
        m_peerConnection->addRemoteCandidate(rtc::Candidate(
            data.value(QStringLiteral("candidate")).toString().toStdString(),
            data.value(QStringLiteral("sdp_mid")).toString().toStdString()));
    } catch (const std::exception& e) {
        qWarning() << "[CallService] addRemoteCandidate failed:" << e.what();
    }
}

void CallService::flushPendingRemoteCandidates() {
    if (m_pendingRemoteCandidates.isEmpty()) return;
    qDebug() << "[CallService] flushing" << m_pendingRemoteCandidates.size() << "buffered ICE candidates";
    const auto buffered = m_pendingRemoteCandidates;
    m_pendingRemoteCandidates.clear();
    for (const auto& data : buffered) {
        applyRemoteCandidate(data);
    }
}

void CallService::teardown(const QString& reason) {
    // The state transition must not depend on media cleanup succeeding.
    // close() on a PeerConnection that is still gathering - exactly the state
    // an unanswered outgoing call is in when the far end declines - can throw,
    // and an exception here used to escape before setStatus(Ended) ran, so the
    // call stayed on screen ringing forever with nothing left behind it.
    try {
        stopAudioPipeline();
    } catch (const std::exception& e) {
        qWarning() << "[CallService] stopAudioPipeline threw:" << e.what();
    }
    try {
        if (m_videoEngine) m_videoEngine->stop();
    } catch (const std::exception& e) {
        qWarning() << "[CallService] video engine stop threw:" << e.what();
    }
    try {
        tearAllEdges();
    } catch (const std::exception& e) {
        qWarning() << "[CallService] tearAllEdges threw:" << e.what();
    }
    try {
        if (m_peerConnection) {
            m_peerConnection->close();
            m_peerConnection.reset();
        }
    } catch (const std::exception& e) {
        qWarning() << "[CallService] peerConnection close threw:" << e.what();
        m_peerConnection.reset();
    }
    m_audioTrack.reset();
    m_videoTrack.reset();

    bool wasActive = m_status != Status::Idle;
    m_pendingOfferSdp.clear();
    m_isCaller = false;
    m_inviteSent = false;
    m_cameraOn = false;
    m_pendingRemoteCandidates.clear();
    m_remoteDescriptionSet = false;
    m_isGroupCall = false;
    m_groupCallId.clear();
    m_participants.clear();
    m_orphanIceByCallId.clear();
    if (wasActive) {
        m_endReason = reason;
        setStatus(Status::Ended);
    } else {
        m_status = Status::Idle;
    }
}

void CallService::sendSignal(const QString& type, QVariantMap data) {
    if (!m_webSocketService) return;
    data["to_user_id"] = m_peerUserId;
    data["from_user_id"] = m_authService ? m_authService->currentUserId() : QString();
    data["call_id"] = m_callId;
    if (!m_chatId.isEmpty() && !data.contains(QStringLiteral("chat_id"))) {
        data["chat_id"] = m_chatId;
    }
    m_webSocketService->sendCallSignal(type, data);
}

void CallService::sendGroupSignal(const QString& type, QVariantMap data) {
    if (!m_webSocketService) return;
    data[QStringLiteral("from_user_id")] = m_authService ? m_authService->currentUserId() : QString();
    data[QStringLiteral("call_id")] = m_groupCallId;
    if (!m_chatId.isEmpty() && !data.contains(QStringLiteral("chat_id"))) {
        data[QStringLiteral("chat_id")] = m_chatId;
    }
    m_webSocketService->sendCallSignal(type, data);
}

void CallService::sendEdgeSignal(PeerEdge* edge, const QString& type, QVariantMap data) {
    if (!m_webSocketService || !edge) return;
    data[QStringLiteral("to_user_id")] = edge->peerUserId;
    data[QStringLiteral("from_user_id")] = m_authService ? m_authService->currentUserId() : QString();
    data[QStringLiteral("call_id")] = edge->callId;
    data[QStringLiteral("group_call_id")] = m_groupCallId;
    if (!m_chatId.isEmpty() && !data.contains(QStringLiteral("chat_id"))) {
        data[QStringLiteral("chat_id")] = m_chatId;
    }
    m_webSocketService->sendCallSignal(type, data);
}

void CallService::fetchIceServersThen(std::function<void()> onDone) {
    QString authToken = m_authService ? ("Bearer " + m_authService->authToken()) : QString();
    QUrl url(Config::apiBaseUrl() + "/calls/ice-servers");
    QNetworkRequest request(url);
    request.setRawHeader("Authorization", authToken.toUtf8());

    QNetworkReply* reply = m_networkManager->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, onDone]() {
        m_iceServers.clear();
        if (reply->error() == QNetworkReply::NoError) {
            QJsonDocument doc = QJsonDocument::fromJson(reply->readAll());
            QJsonArray servers = doc.object().value(QStringLiteral("ice_servers")).toArray();
            for (const auto& v : servers) {
                QJsonObject obj = v.toObject();
                QJsonArray urls = obj.value(QStringLiteral("urls")).toArray();
                QString username = obj.value(QStringLiteral("username")).toString();
                QString credential = obj.value(QStringLiteral("credential")).toString();
                for (const auto& urlVal : urls) {
                    QString serverUrl = urlVal.toString();
                    try {
                        if (!username.isEmpty()) {
                            // TURN: "turn:host:port?transport=udp" - parse host/port out
                            // so we can use the username/password constructor overload.
                            QUrl parsed(serverUrl.split('?').first());
                            std::string host = parsed.host().toStdString();
                            uint16_t port = static_cast<uint16_t>(parsed.port(3478));
                            m_iceServers.emplace_back(host, port, username.toStdString(), credential.toStdString());
                        } else {
                            m_iceServers.emplace_back(serverUrl.toStdString());
                        }
                    } catch (const std::exception& e) {
                        qWarning() << "[CallService] bad ICE server" << serverUrl << e.what();
                    }
                }
            }
        } else {
            qWarning() << "[CallService] fetchIceServers failed:" << reply->errorString();
        }
        reply->deleteLater();
        onDone();
    });
}

void CallService::createPeerConnection() {
    // Stamped on every RTP packet we send. Assigned here, not in
    // addAudioTrack(), because the answerer never calls that - leaving it 0
    // would mean sending all our audio under SSRC 0.
    m_localSsrc = 1000 + static_cast<rtc::SSRC>(QRandomGenerator::global()->bounded(1, 100000));
    // Distinct stream, distinct SSRC - the far end demuxes audio and video by
    // it (and by payload type; both differ).
    m_videoSsrc = m_localSsrc + 1;

    rtc::Configuration config;
    config.iceServers = m_iceServers;

    m_peerConnection = std::make_shared<rtc::PeerConnection>(config);

    m_peerConnection->onLocalDescription([this](rtc::Description desc) {
        QString sdp = QString::fromStdString(std::string(desc));
        // The audio m-line's direction here is what tells the far end whether
        // to expect media from us at all.
        for (const QString& line : sdp.split('\n')) {
            const QString t = line.trimmed();
            if (t.startsWith(QStringLiteral("m=")) || t.startsWith(QStringLiteral("a=send")) ||
                t.startsWith(QStringLiteral("a=recv")) || t.startsWith(QStringLiteral("a=inactive")) ||
                t.startsWith(QStringLiteral("a=ssrc:")) || t.startsWith(QStringLiteral("a=rtpmap:"))) {
                qDebug() << "[CallService] localSDP:" << t;
            }
        }
        QMetaObject::invokeMethod(this, [this, sdp]() {
            // Keyed off our role, not the SDP type - see m_isCaller.
            if (m_isCaller) {
                if (m_inviteSent) return; // renegotiation offer, not a new call
                m_inviteSent = true;
                sendSignal(QStringLiteral("call:invite"), {
                    {"chat_id", m_chatId},
                    {"sdp", sdp},
                    // Who is calling. The callee can't always work this out
                    // locally - it may have no cached chat entry for us yet -
                    // so an incoming call otherwise just says "Unknown".
                    {"from_name", m_authService ? m_authService->currentUsername() : QString()},
                    // Video-ness is fixed at invite time; the callee needs it
                    // before answering, both for its UI and to start its own
                    // camera against the offer's video m-line.
                    {"video", m_isVideoCall}
                });
            } else {
                sendSignal(QStringLiteral("call:answer"), {{"sdp", sdp}});
            }
        }, Qt::QueuedConnection);
    });

    m_peerConnection->onGatheringStateChange([](rtc::PeerConnection::GatheringState state) {
        qDebug() << "[CallService] ICE gathering state:" << static_cast<int>(state);
    });

    m_peerConnection->onLocalCandidate([this](rtc::Candidate candidate) {
        QString cand = QString::fromStdString(candidate.candidate());
        QString mid = QString::fromStdString(candidate.mid());
        // With this many adapters (VPN/VMware/link-local) it matters exactly
        // which addresses we advertise - a pile of unreachable candidates is
        // indistinguishable from none at all once checks start timing out.
        qDebug() << "[CallService] LOCAL candidate:" << cand;
        QMetaObject::invokeMethod(this, [this, cand, mid]() {
            sendSignal(QStringLiteral("call:ice_candidate"), {
                {"candidate", cand},
                {"sdp_mid", mid},
                {"sdp_mline_index", 0}
            });
        }, Qt::QueuedConnection);
    });

    // Answerer path. setRemoteDescription() on an offer makes libdatachannel
    // build a Track per remote media section, carrying that section's mid.
    // We must reuse it rather than addTrack() our own: an answer has to
    // mirror the offer's m-line exactly, and Google WebRTC (Android) numbers
    // its mid "0" while addTrack() here would name it "audio" - a mismatch
    // the far end rejects, which is why answering an Android call negotiated
    // to Failed the instant it was picked up.
    m_peerConnection->onTrack([this](std::shared_ptr<rtc::Track> track) {
        // A video call's offer has two media sections, so this fires once per
        // track; the section's own type says which one we were handed.
        bool isVideo = false;
        try {
            isVideo = track->description().type() == "video";
        } catch (const std::exception& e) {
            qWarning() << "[CallService] could not read track type:" << e.what();
        }

        // Done inline, NOT queued to the Qt thread: libdatachannel composes
        // the answer during setRemoteDescription, so anything deferred lands
        // after the SDP has already gone out. As the answerer we never author
        // the media section, and the reciprocated one carries no a=ssrc at
        // all - leaving the far end to receive RTP from an SSRC it was never
        // told about, which WebRTC is entitled to ignore.
        try {
            auto desc = track->description();
            if (desc.getSSRCs().empty()) {
                desc.addSSRC(isVideo ? m_videoSsrc : m_localSsrc,
                             isVideo ? "windows-call-video" : "windows-call-audio");
                track->setDescription(desc);
            }
        } catch (const std::exception& e) {
            qWarning() << "[CallService] could not declare local SSRC:" << e.what();
        }

        QMetaObject::invokeMethod(this, [this, track, isVideo]() {
            if (m_isCaller) return; // ours already, from addAudioTrack()/addVideoTrack()
            if (isVideo) {
                m_videoTrack = track;
                attachVideoTrackHandlers();
            } else {
                m_audioTrack = track;
                attachAudioTrackHandlers();
            }
        }, Qt::QueuedConnection);
    });

    m_peerConnection->onIceStateChange([this](rtc::PeerConnection::IceState state) {
        qDebug() << "[CallService] ICE state:" << static_cast<int>(state);
    });

    m_peerConnection->onStateChange([this](rtc::PeerConnection::State state) {
        qDebug() << "[CallService] PeerConnection state:" << static_cast<int>(state);
        QMetaObject::invokeMethod(this, [this, state]() {
            if (state == rtc::PeerConnection::State::Connected) {
                if (m_status != Status::Connected) {
                    m_connectedAtMs = QDateTime::currentMSecsSinceEpoch();
                    setStatus(Status::Connected);
                }
            } else if (state == rtc::PeerConnection::State::Failed) {
                teardown(QStringLiteral("failed"));
            }
        }, Qt::QueuedConnection);
    });
}

void CallService::addAudioTrack() {
    rtc::Description::Audio audioMedia("audio", rtc::Description::Direction::SendRecv);
    audioMedia.addOpusCodec(kOpusPayloadType);
    audioMedia.addSSRC(m_localSsrc, "windows-call-audio");

    m_audioTrack = m_peerConnection->addTrack(audioMedia);
    attachAudioTrackHandlers();
}

void CallService::addVideoTrack() {
    rtc::Description::Video videoMedia("video", rtc::Description::Direction::SendRecv);
    videoMedia.addVP8Codec(kVp8PayloadType);
    videoMedia.addSSRC(m_videoSsrc, "windows-call-video");

    m_videoTrack = m_peerConnection->addTrack(videoMedia);
    attachVideoTrackHandlers();
}

void CallService::attachVideoTrackHandlers() {
    if (!m_videoTrack) return;

    // Same as audio: without a media handler libdatachannel never processes
    // inbound RTP at all and onMessage stays silent.
    m_videoTrack->setMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());

    // Use whatever payload type VP8 actually negotiated to. As the answerer
    // the description came from the far end's offer (Android numbers its
    // codecs itself), so our own constant may simply be wrong.
    uint8_t payloadType = kVp8PayloadType;
    try {
        const auto desc = m_videoTrack->description();
        for (int pt : desc.payloadTypes()) {
            if (const auto* map = desc.rtpMap(pt)) {
                if (QString::fromStdString(map->format).compare(QStringLiteral("VP8"), Qt::CaseInsensitive) == 0) {
                    payloadType = static_cast<uint8_t>(pt);
                    break;
                }
            }
        }
        qDebug() << "[CallService] video track ready. pt =" << payloadType
                 << "mid =" << QString::fromStdString(m_videoTrack->mid())
                 << "direction =" << static_cast<int>(m_videoTrack->direction());
    } catch (const std::exception& e) {
        qWarning() << "[CallService] could not read video payload type:" << e.what();
    }

    m_videoEngine->setTrack(m_videoTrack);
    m_videoEngine->setPayloadType(payloadType);
    m_videoEngine->setSsrc(m_videoSsrc);

    m_videoTrack->onMessage([this](rtc::message_variant data) {
        if (!std::holds_alternative<rtc::binary>(data)) return;
        const auto& bytes = std::get<rtc::binary>(data);
        if (bytes.size() <= sizeof(rtc::RtpHeader)) return;
        if (rtc::IsRtcp(bytes)) return;

        // The engine needs the whole packet - reassembly runs off the RTP
        // header's seq/timestamp/marker, not just the payload.
        QByteArray packet(reinterpret_cast<const char*>(bytes.data()), static_cast<int>(bytes.size()));
        QMetaObject::invokeMethod(this, [this, packet]() {
            m_videoEngine->handleRtp(packet);
        }, Qt::QueuedConnection);
    }, nullptr);
}

void CallService::attachAudioTrackHandlers() {
    if (!m_audioTrack) return;

    // libdatachannel hands a bare Track no media pipeline at all. Without a
    // handler it never processes inbound RTP/RTCP, so onMessage below simply
    // never fires and the call is silent despite being fully connected.
    // RtcpReceivingSession is the minimal one: it consumes RTCP and answers
    // receiver reports, passing RTP through to us.
    m_audioTrack->setMediaHandler(std::make_shared<rtc::RtcpReceivingSession>());

    // Use the payload type actually negotiated for Opus rather than assuming
    // our own constant. As the answerer we never wrote the media description
    // - libdatachannel derived it from the caller's offer - so the far end's
    // numbering wins, and sending under the wrong PT gets our audio dropped.
    m_negotiatedOpusPayloadType = kOpusPayloadType;
    try {
        const auto desc = m_audioTrack->description();
        for (int pt : desc.payloadTypes()) {
            if (const auto* map = desc.rtpMap(pt)) {
                if (QString::fromStdString(map->format).compare(QStringLiteral("opus"), Qt::CaseInsensitive) == 0) {
                    m_negotiatedOpusPayloadType = static_cast<uint8_t>(pt);
                    break;
                }
            }
        }
    } catch (const std::exception& e) {
        qWarning() << "[CallService] could not read negotiated payload type:" << e.what();
    }
    // Direction and SSRC decide whether the far end will even look at what we
    // send: a recvonly answer tells it not to expect our audio at all.
    {
        const auto desc = m_audioTrack->description();
        QStringList ssrcs;
        for (uint32_t s : desc.getSSRCs()) ssrcs << QString::number(s);
        qDebug() << "[CallService] audio track ready. pt =" << m_negotiatedOpusPayloadType
                 << "mid =" << QString::fromStdString(m_audioTrack->mid())
                 << "direction =" << static_cast<int>(m_audioTrack->direction())
                 << "declared SSRCs =" << (ssrcs.isEmpty() ? QStringLiteral("<none>") : ssrcs.join(','))
                 << "sendingSSRC =" << m_localSsrc;
    }

    m_audioTrack->onMessage([this](rtc::message_variant data) {
        if (!std::holds_alternative<rtc::binary>(data)) return;
        const auto& bytes = std::get<rtc::binary>(data);
        if (bytes.size() <= sizeof(rtc::RtpHeader)) return;
        // RTCP shares the transport; feeding a receiver report to an audio
        // decoder is meaningless.
        if (rtc::IsRtcp(bytes)) return;

        const auto* header = reinterpret_cast<const rtc::RtpHeader*>(bytes.data());

        // getBody(), not data()+getSize(): getSize() covers the fixed header
        // and CSRC list but NOT the extension header, and Google WebRTC always
        // sends extensions (abs-send-time, transport-cc, mid). Slicing at
        // getSize() therefore left the extension bytes glued to the front of
        // the payload, and every opus_decode() returned OPUS_INVALID_PACKET
        // (-4) - audio arriving correctly and being discarded as garbage.
        const auto* bodyStart = reinterpret_cast<const std::byte*>(header->getBody());
        const auto* packetStart = bytes.data();
        const auto offset = static_cast<size_t>(bodyStart - packetStart);
        if (offset >= bytes.size()) return;

        QByteArray payload(reinterpret_cast<const char*>(bodyStart),
                           static_cast<int>(bytes.size() - offset));
        QMetaObject::invokeMethod(this, [this, payload]() {
            if (!m_opusDecoder || !m_playbackSource) return;
            int16_t pcm[kFrameSamples * 2]; // headroom for Opus's occasional larger frame counts
            int decoded = opus_decode(m_opusDecoder,
                                       reinterpret_cast<const unsigned char*>(payload.constData()),
                                       payload.size(), pcm, kFrameSamples * 2, 0);
            // One line per second of audio, not per 20ms frame - enough to
            // tell "media is arriving" from "nothing is arriving" without
            // drowning the log.
            static int s_rxFrames = 0;
            if (++s_rxFrames % 50 == 1) {
                qDebug() << "[CallService] RX audio frames:" << s_rxFrames << "last decoded samples:" << decoded;
            }
            if (decoded > 0) {
                m_playbackSource->pushPcm(QByteArray(reinterpret_cast<const char*>(pcm),
                                                       decoded * static_cast<int>(sizeof(int16_t))));
            }
        }, Qt::QueuedConnection);
    }, nullptr);
}

void CallService::startAudioPipeline(bool createSharedDecoder) {
    if (m_audioSource) return; // already running (e.g. group outgoing then join)

    QAudioFormat format;
    format.setSampleRate(kSampleRate);
    format.setChannelCount(kChannels);
    format.setSampleFormat(QAudioFormat::Int16);

    int opusError = 0;
    if (!m_opusEncoder) {
        m_opusEncoder = opus_encoder_create(kSampleRate, kChannels, OPUS_APPLICATION_VOIP, &opusError);
        if (opusError != OPUS_OK) {
            qWarning() << "[CallService] opus_encoder_create failed:" << opusError;
            m_opusEncoder = nullptr;
        }
    }
    if (createSharedDecoder && !m_opusDecoder) {
        m_opusDecoder = opus_decoder_create(kSampleRate, kChannels, &opusError);
        if (opusError != OPUS_OK) {
            qWarning() << "[CallService] opus_decoder_create failed:" << opusError;
            m_opusDecoder = nullptr;
        }
    }

    // Which devices we actually ended up on, and whether they can do
    // 48kHz/mono/int16 at all. Nothing here picked a device explicitly - Qt's
    // default input may well not be the mic the user expects, and a device
    // that doesn't support this exact format can start "successfully" and
    // then produce nothing usable.
    const QAudioDevice inputDevice = QMediaDevices::defaultAudioInput();
    const QAudioDevice outputDevice = QMediaDevices::defaultAudioOutput();

    qDebug() << "[CallService] INPUT device:" << inputDevice.description()
             << "| null:" << inputDevice.isNull()
             << "| supports 48k/mono/int16:" << inputDevice.isFormatSupported(format);
    qDebug() << "[CallService] OUTPUT device:" << outputDevice.description()
             << "| null:" << outputDevice.isNull()
             << "| supports 48k/mono/int16:" << outputDevice.isFormatSupported(format);
    for (const QAudioDevice& d : QMediaDevices::audioInputs()) {
        qDebug() << "[CallService]   available input:" << d.description()
                 << "| default:" << (d.id() == inputDevice.id());
    }

    m_captureSink = new AudioCaptureSink(this);
    connect(m_captureSink, &AudioCaptureSink::dataReady, this, &CallService::onCaptureData);
    m_audioSource = new QAudioSource(inputDevice, format, this);
    m_audioSource->start(m_captureSink);
    qDebug() << "[CallService] QAudioSource state:" << m_audioSource->state()
             << "error:" << m_audioSource->error();

    m_playbackSource = new AudioPlaybackSource(this);
    m_audioSink = new QAudioSink(outputDevice, format, this);
    m_audioSink->start(m_playbackSource);
    qDebug() << "[CallService] QAudioSink state:" << m_audioSink->state()
             << "error:" << m_audioSink->error();
}

void CallService::stopAudioPipeline() {
    if (m_audioSource) { m_audioSource->stop(); m_audioSource->deleteLater(); m_audioSource = nullptr; }
    if (m_audioSink) { m_audioSink->stop(); m_audioSink->deleteLater(); m_audioSink = nullptr; }
    if (m_captureSink) { m_captureSink->deleteLater(); m_captureSink = nullptr; }
    if (m_playbackSource) { m_playbackSource->deleteLater(); m_playbackSource = nullptr; }
    m_captureAccum.clear();
    if (m_opusEncoder) { opus_encoder_destroy(m_opusEncoder); m_opusEncoder = nullptr; }
    if (m_opusDecoder) { opus_decoder_destroy(m_opusDecoder); m_opusDecoder = nullptr; }
    m_rtpSeq = 0;
    m_rtpTimestamp = 0;
    m_micGain = 1.0f;
}

void CallService::onCaptureData(const QByteArray& pcm) {
    // Qt hands us capture buffers of whatever size the backend feels like;
    // accumulate into exact 20ms (960-sample) frames before encoding, since
    // Opus and RTP timestamps both assume fixed-size frames.
    m_captureAccum.append(pcm);
    constexpr int frameBytes = kFrameSamples * sizeof(int16_t);
    while (m_captureAccum.size() >= frameBytes) {
        encodeAndSendFrame(reinterpret_cast<const int16_t*>(m_captureAccum.constData()), kFrameSamples);
        m_captureAccum.remove(0, frameBytes);
    }
}

void CallService::encodeAndSendFrame(const int16_t* samples, int sampleCount) {
    if (m_isGroupCall) {
        encodeAndSendFrameGroup(samples, sampleCount);
        return;
    }

    if (m_isMuted || !m_opusEncoder || !m_audioTrack || !m_audioTrack->isOpen()) {
        m_rtpTimestamp += sampleCount; // keep the clock running even while muted
        m_rtpSeq++;
        return;
    }

    // ---- Automatic gain ----
    // The capture device runs very quiet (peaks around 1000 of 32767, about
    // -30 dBFS). That is perfectly usable for a recorder app, which applies
    // its own gain, but sent as-is the far end plays it back near-silent -
    // Android confirmed it was receiving and playing every frame, just far
    // too quietly to hear. Normalise towards a healthy level here.
    int peak = 0;
    for (int i = 0; i < sampleCount; ++i) {
        const int v = samples[i] < 0 ? -samples[i] : samples[i];
        if (v > peak) peak = v;
    }

    constexpr int kTargetPeak = 12000;   // ~37% of full scale, leaves headroom
    constexpr float kMaxGain = 24.0f;
    constexpr int kSilenceFloor = 40;    // don't amplify a silent room's noise
    if (peak > kSilenceFloor) {
        const float desired = std::clamp(static_cast<float>(kTargetPeak) / peak, 1.0f, kMaxGain);
        // Duck fast when it gets loud (avoid clipping), recover slowly (avoid
        // audible pumping between words).
        const float rate = (desired < m_micGain) ? 0.35f : 0.02f;
        m_micGain += (desired - m_micGain) * rate;
    }

    std::array<int16_t, kFrameSamples> boosted{};
    const int n = std::min<int>(sampleCount, static_cast<int>(boosted.size()));
    for (int i = 0; i < n; ++i) {
        const int v = static_cast<int>(samples[i] * m_micGain);
        boosted[static_cast<size_t>(i)] = static_cast<int16_t>(std::clamp(v, -32768, 32767));
    }

    unsigned char opusPayload[4000];
    int encodedBytes = opus_encode(m_opusEncoder, boosted.data(), n, opusPayload, sizeof(opusPayload));
    if (encodedBytes <= 0) return;

    std::vector<std::byte> packet(sizeof(rtc::RtpHeader) + encodedBytes);
    auto* header = reinterpret_cast<rtc::RtpHeader*>(packet.data());
    header->preparePacket();
    header->setPayloadType(m_negotiatedOpusPayloadType);
    header->setSsrc(m_localSsrc);
    header->setSeqNumber(m_rtpSeq++);
    header->setTimestamp(m_rtpTimestamp);
    memcpy(header->getBody(), opusPayload, encodedBytes);

    bool sent = m_audioTrack->send(reinterpret_cast<const std::byte*>(packet.data()), packet.size());
    static int s_txFrames = 0;
    if (++s_txFrames % 50 == 1) {
        qDebug() << "[CallService] TX frames:" << s_txFrames << "bytes:" << encodedBytes
                 << "accepted:" << sent << "| raw peak:" << peak
                 << "gain:" << QString::number(m_micGain, 'f', 1)
                 << "-> sent peak:" << std::min(32767, static_cast<int>(peak * m_micGain));
    }
    m_rtpTimestamp += sampleCount;
}

void CallService::encodeAndSendFrameGroup(const int16_t* samples, int sampleCount) {
    // Keep per-edge RTP clocks moving while muted so receivers don't stall.
    if (m_isMuted || !m_opusEncoder) {
        for (auto it = m_edges.begin(); it != m_edges.end(); ++it) {
            PeerEdge* e = it.value().get();
            if (!e) continue;
            e->rtpTimestamp += sampleCount;
            e->rtpSeq++;
        }
        return;
    }

    int peak = 0;
    for (int i = 0; i < sampleCount; ++i) {
        const int v = samples[i] < 0 ? -samples[i] : samples[i];
        if (v > peak) peak = v;
    }

    constexpr int kTargetPeak = 12000;
    constexpr float kMaxGain = 24.0f;
    constexpr int kSilenceFloor = 40;
    if (peak > kSilenceFloor) {
        const float desired = std::clamp(static_cast<float>(kTargetPeak) / peak, 1.0f, kMaxGain);
        const float rate = (desired < m_micGain) ? 0.35f : 0.02f;
        m_micGain += (desired - m_micGain) * rate;
    }

    std::array<int16_t, kFrameSamples> boosted{};
    const int n = std::min<int>(sampleCount, static_cast<int>(boosted.size()));
    for (int i = 0; i < n; ++i) {
        const int v = static_cast<int>(samples[i] * m_micGain);
        boosted[static_cast<size_t>(i)] = static_cast<int16_t>(std::clamp(v, -32768, 32767));
    }

    // Encode once, fan-out the same Opus payload to every open edge track
    // with that edge's own SSRC / sequence / timestamp.
    unsigned char opusPayload[4000];
    int encodedBytes = opus_encode(m_opusEncoder, boosted.data(), n, opusPayload, sizeof(opusPayload));
    if (encodedBytes <= 0) return;

    for (auto it = m_edges.begin(); it != m_edges.end(); ++it) {
        PeerEdge* e = it.value().get();
        if (!e || !e->audioTrack || !e->audioTrack->isOpen()) {
            if (e) {
                e->rtpTimestamp += sampleCount;
                e->rtpSeq++;
            }
            continue;
        }

        std::vector<std::byte> packet(sizeof(rtc::RtpHeader) + encodedBytes);
        auto* header = reinterpret_cast<rtc::RtpHeader*>(packet.data());
        header->preparePacket();
        header->setPayloadType(e->negotiatedOpusPayloadType);
        header->setSsrc(e->localSsrc);
        header->setSeqNumber(e->rtpSeq++);
        header->setTimestamp(e->rtpTimestamp);
        memcpy(header->getBody(), opusPayload, static_cast<size_t>(encodedBytes));
        e->audioTrack->send(reinterpret_cast<const std::byte*>(packet.data()), packet.size());
        e->rtpTimestamp += sampleCount;
    }
}
