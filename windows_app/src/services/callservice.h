#pragma once

#include <QObject>
#include <QString>
#include <QStringList>
#include <QImage>
#include <QAudioSource>
#include <QAudioSink>
#include <QNetworkAccessManager>
#include <QMutex>
#include <QByteArray>
#include <QHash>
#include <QVariantList>
#include <QVariantMap>
#include <QTimer>
#include <memory>
#include <opus/opus.h>
#include <rtc/rtc.hpp>
#include "authservice.h"
#include "websocketservice.h"

class AudioCaptureSink;
class AudioPlaybackSource;
class VideoCallEngine;

// Owns audio (and optional video) calls end to end: WebRTC signaling via
// WebSocketService's call:* messages, libdatachannel PeerConnection(s)/Track(s),
// and Opus encode/decode + RTP packetization (VP8 for video via VideoCallEngine).
//
// 1:1 calls use a single PeerConnection. Group calls (audio or video, max 4) are
// a full mesh: one PeerConnection per remote participant (PeerEdge), with session
// lifecycle on call:group_invite/join/leave and pairwise SDP/ICE under a shared
// group_call_id. Media is always DTLS-SRTP peer-to-peer - the server only relays
// signaling. Group video encodes VP8 once and fans RTP to each edge.
class CallService : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString status READ status NOTIFY stateChanged)
    Q_PROPERTY(QString peerName READ peerName NOTIFY stateChanged)
    Q_PROPERTY(bool isMuted READ isMuted NOTIFY stateChanged)
    Q_PROPERTY(bool isActive READ isActive NOTIFY stateChanged)
    Q_PROPERTY(qint64 connectedAtMs READ connectedAtMs NOTIFY stateChanged)
    Q_PROPERTY(QString endReason READ endReason NOTIFY stateChanged)
    Q_PROPERTY(bool isVideoCall READ isVideoCall NOTIFY stateChanged)
    Q_PROPERTY(bool cameraOn READ cameraOn NOTIFY stateChanged)
    Q_PROPERTY(bool remoteCameraOn READ remoteCameraOn NOTIFY stateChanged)
    Q_PROPERTY(bool isGroupCall READ isGroupCall NOTIFY stateChanged)
    Q_PROPERTY(QVariantList participants READ participants NOTIFY stateChanged)

public:
    // Mirrors Android's CallStatus exactly - both platforms speak the same
    // wire protocol (back-end/websocket/calls.go), so the state machine
    // shape matches too.
    enum class Status { Idle, OutgoingRinging, IncomingRinging, Connecting, Connected, Ended };
    Q_ENUM(Status)

    explicit CallService(AuthService* authService, WebSocketService* webSocketService, QObject* parent = nullptr);
    ~CallService() override;

    QString status() const;
    QString peerName() const { return m_peerName; }
    bool isMuted() const { return m_isMuted; }
    bool isActive() const { return m_status != Status::Idle; }
    qint64 connectedAtMs() const { return m_connectedAtMs; }
    QString endReason() const { return m_endReason; }
    bool isVideoCall() const { return m_isVideoCall; }
    bool cameraOn() const { return m_cameraOn; }
    bool remoteCameraOn() const { return m_remoteCameraOn; }
    bool isGroupCall() const { return m_isGroupCall; }
    QVariantList participants() const;

    Q_INVOKABLE void startOutgoingCall(const QString& chatId, const QString& calleeId, const QString& calleeName,
                                       bool video = false);
    // Full-mesh group call (audio, or video when video=true). memberIds/memberNames
    // are the other members (not including self); total participants must be ≤ 4.
    Q_INVOKABLE void startGroupCall(const QString& chatId, const QString& groupName,
                                    const QStringList& memberIds, const QStringList& memberNames,
                                    bool video = false);
    Q_INVOKABLE void acceptCall();
    Q_INVOKABLE void rejectCall();
    Q_INVOKABLE void endCall();
    Q_INVOKABLE void toggleMute();
    // Video calls only: stops/starts our camera without renegotiating - the
    // track stays up, we just stop feeding it, and the peer is told via
    // call:media so it can show an avatar instead of a frozen frame.
    Q_INVOKABLE void toggleCamera();
    Q_INVOKABLE void dismissEnded();

signals:
    void stateChanged();
    // Live QImage frames for the overlay's VideoFrameItems (QML receives the
    // QImage as an opaque var and hands it straight to present()).
    void localVideoFrame(const QImage& frame);
    void remoteVideoFrame(const QImage& frame);
    // Group mesh: one remote stream per peer user id.
    void remoteVideoFrameFromPeer(const QString& peerId, const QImage& frame);

private slots:
    void onCallSignal(const QString& type, const QVariantMap& data);

private:
    // One mesh edge to a remote participant in a group call.
    struct PeerEdge {
        QString peerUserId;
        QString peerName;
        QString callId; // pairwise edge id (distinct from group session id)
        std::shared_ptr<rtc::PeerConnection> peerConnection;
        std::shared_ptr<rtc::Track> audioTrack;
        std::shared_ptr<rtc::Track> videoTrack;
        QString pendingOfferSdp;
        QList<QVariantMap> pendingRemoteCandidates;
        bool remoteDescriptionSet = false;
        bool isCaller = false;
        bool inviteSent = false;
        bool connected = false;
        OpusDecoder* opusDecoder = nullptr;
        rtc::SSRC localSsrc = 0;
        rtc::SSRC videoSsrc = 0;
        uint16_t rtpSeq = 0;
        uint32_t rtpTimestamp = 0;
        uint8_t negotiatedOpusPayloadType = 111;
        uint8_t negotiatedVp8PayloadType = 96;
    };

    struct ParticipantInfo {
        QString userId;
        QString name;
        // Remote camera state from call:media (group video). Defaults on until
        // the peer says otherwise - same assumption as 1:1 remoteCameraOn.
        bool cameraOn = true;
    };

    void teardown(const QString& reason);
    void setStatus(Status status);
    void createPeerConnection();
    // Caller side only: declares our outgoing audio m-line. The answerer must
    // NOT do this - see attachAudioTrackHandlers/onTrack in the .cpp.
    void addAudioTrack();
    // Same, for the video m-line of a video call.
    void addVideoTrack();
    // Wires Opus depacketize+playback onto whichever Track we ended up with,
    // whether we created it (caller) or libdatachannel handed it to us from
    // the remote offer (answerer).
    void attachAudioTrackHandlers();
    // Same for video: points VideoCallEngine at the track and negotiated PT.
    void attachVideoTrackHandlers();
    void startAudioPipeline(bool createSharedDecoder = true);
    void stopAudioPipeline();
    void sendSignal(const QString& type, QVariantMap data);
    void sendGroupSignal(const QString& type, QVariantMap data);
    void sendEdgeSignal(PeerEdge* edge, const QString& type, QVariantMap data);
    // Remote ICE candidates can (and routinely do) arrive before we have a
    // PeerConnection with a remote description to feed them to - the far end
    // starts trickling the moment it dials, while an incoming call sits
    // ringing until it's answered. These buffer them until we can apply them.
    void applyRemoteCandidate(const QVariantMap& data);
    void flushPendingRemoteCandidates();
    void fetchIceServersThen(std::function<void()> onDone);
    void onCaptureData(const QByteArray& pcm);
    void encodeAndSendFrame(const int16_t* samples, int sampleCount);
    void encodeAndSendFrameGroup(const int16_t* samples, int sampleCount);

    // ---- Group mesh ----
    void handleGroupInvite(const QVariantMap& data);
    void handleGroupJoin(const QVariantMap& data);
    void handleGroupLeave(const QVariantMap& data);
    void ensureMeshEdge(const QString& peerId, const QString& peerName);
    void answerGroupEdge(const QVariantMap& data);
    void createEdgePeerConnection(PeerEdge* edge);
    void addEdgeAudioTrack(PeerEdge* edge);
    void addEdgeVideoTrack(PeerEdge* edge);
    void attachEdgeAudioHandlers(PeerEdge* edge);
    void attachEdgeVideoHandlers(PeerEdge* edge);
    void applyEdgeRemoteCandidate(PeerEdge* edge, const QVariantMap& data);
    void flushEdgePendingCandidates(PeerEdge* edge);
    void tearEdge(const QString& peerId);
    void tearAllEdges();
    PeerEdge* edgeByPeerId(const QString& peerId);
    PeerEdge* edgeByCallId(const QString& callId);
    void upsertParticipant(const QString& userId, const QString& name);
    QVariantList participantIdList() const;
    QString participantName(const QString& userId) const;
    static QStringList parseIdList(const QVariant& raw);

    AuthService* m_authService = nullptr;
    WebSocketService* m_webSocketService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;

    std::shared_ptr<rtc::PeerConnection> m_peerConnection;
    std::shared_ptr<rtc::Track> m_audioTrack;
    std::shared_ptr<rtc::Track> m_videoTrack;
    VideoCallEngine* m_videoEngine = nullptr;
    std::vector<rtc::IceServer> m_iceServers;

    Status m_status = Status::Idle;
    QString m_callId;
    QString m_chatId;
    QString m_peerUserId;
    QString m_peerName;
    QString m_pendingOfferSdp;
    QString m_endReason;
    bool m_isMuted = false;
    // Fixed at invite time - there is no mid-call audio<->video upgrade
    // (libdatachannel renegotiation is not exercised anywhere in this app).
    bool m_isVideoCall = false;
    bool m_cameraOn = false;
    bool m_remoteCameraOn = true;
    qint64 m_connectedAtMs = 0;
    // Which side of this call we are. The SDP type alone can't decide what to
    // signal: libdatachannel auto-negotiates, so adding our local audio track
    // makes it emit an *offer* even on the answering side, and treating every
    // offer as "send call:invite" made the callee re-invite its own caller
    // under the same call_id - the far end saw an invite mid-call and replied
    // "busy", ending the call it had just answered.
    bool m_isCaller = false;
    bool m_inviteSent = false;
    // Trickled candidates received too early to apply - see applyRemoteCandidate.
    QList<QVariantMap> m_pendingRemoteCandidates;
    bool m_remoteDescriptionSet = false;

    // Group session (full mesh). When false, the 1:1 fields above apply.
    bool m_isGroupCall = false;
    QString m_groupCallId;
    QList<ParticipantInfo> m_participants;
    QHash<QString, std::shared_ptr<PeerEdge>> m_edges; // keyed by peer user id
    // ICE that arrived for an edge call_id before we had the edge object.
    QHash<QString, QList<QVariantMap>> m_orphanIceByCallId;

    // Audio I/O - raw 48kHz mono 16-bit PCM, 20ms frames (960 samples).
    static constexpr int kSampleRate = 48000;
    static constexpr int kChannels = 1;
    static constexpr int kFrameSamples = kSampleRate / 50; // 20ms
    static constexpr int kMaxGroupParticipants = 4;

    QAudioSource* m_audioSource = nullptr;
    QAudioSink* m_audioSink = nullptr;
    AudioCaptureSink* m_captureSink = nullptr;
    AudioPlaybackSource* m_playbackSource = nullptr;
    QByteArray m_captureAccum;

    OpusEncoder* m_opusEncoder = nullptr;
    OpusDecoder* m_opusDecoder = nullptr;

    rtc::SSRC m_localSsrc = 0;
    // Video sends under its own SSRC (derived from m_localSsrc in
    // createPeerConnection) so the far end can demux the two streams.
    rtc::SSRC m_videoSsrc = 0;
    uint16_t m_rtpSeq = 0;
    uint32_t m_rtpTimestamp = 0;
    // What we offer when we're the caller. The value actually used on the
    // wire is whatever the two ends negotiated - see attachAudioTrackHandlers.
    static constexpr uint8_t kOpusPayloadType = 111;
    static constexpr uint8_t kVp8PayloadType = 96;
    uint8_t m_negotiatedOpusPayloadType = kOpusPayloadType;
    // Running automatic-gain multiplier for captured audio - see encodeAndSendFrame.
    float m_micGain = 1.0f;
};
