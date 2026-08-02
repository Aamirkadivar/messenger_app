#pragma once

#include <QObject>
#include <QString>
#include <QAudioSource>
#include <QAudioSink>
#include <QNetworkAccessManager>
#include <QMutex>
#include <QByteArray>
#include <QTimer>
#include <memory>
#include <opus/opus.h>
#include <rtc/rtc.hpp>
#include "authservice.h"
#include "websocketservice.h"

class AudioCaptureSink;
class AudioPlaybackSource;

// Owns a 1:1 audio call end to end: WebRTC signaling (SDP/ICE via
// WebSocketService's call:* messages), the libdatachannel PeerConnection/
// Track, and the Opus encode/decode + RTP packetization needed to actually
// move audio - libdatachannel handles ICE/DTLS-SRTP but not audio capture,
// codecs, or packetization itself (unlike Android's Google WebRTC stack,
// which does all of that internally).
//
// The call's audio is DTLS-SRTP directly between the two peers (negotiated
// from the SDP/ICE exchanged here) - this class and the server only ever
// handle signaling, never media, so a call is no more interceptable
// server-side than an E2EE message is.
class CallService : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString status READ status NOTIFY stateChanged)
    Q_PROPERTY(QString peerName READ peerName NOTIFY stateChanged)
    Q_PROPERTY(bool isMuted READ isMuted NOTIFY stateChanged)
    Q_PROPERTY(bool isActive READ isActive NOTIFY stateChanged)
    Q_PROPERTY(qint64 connectedAtMs READ connectedAtMs NOTIFY stateChanged)
    Q_PROPERTY(QString endReason READ endReason NOTIFY stateChanged)

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

    Q_INVOKABLE void startOutgoingCall(const QString& chatId, const QString& calleeId, const QString& calleeName);
    Q_INVOKABLE void acceptCall();
    Q_INVOKABLE void rejectCall();
    Q_INVOKABLE void endCall();
    Q_INVOKABLE void toggleMute();
    Q_INVOKABLE void dismissEnded();

signals:
    void stateChanged();

private slots:
    void onCallSignal(const QString& type, const QVariantMap& data);

private:
    void teardown(const QString& reason);
    void setStatus(Status status);
    void createPeerConnection();
    // Caller side only: declares our outgoing audio m-line. The answerer must
    // NOT do this - see attachAudioTrackHandlers/onTrack in the .cpp.
    void addAudioTrack();
    // Wires Opus depacketize+playback onto whichever Track we ended up with,
    // whether we created it (caller) or libdatachannel handed it to us from
    // the remote offer (answerer).
    void attachAudioTrackHandlers();
    void startAudioPipeline();
    void stopAudioPipeline();
    void sendSignal(const QString& type, QVariantMap data);
    // Remote ICE candidates can (and routinely do) arrive before we have a
    // PeerConnection with a remote description to feed them to - the far end
    // starts trickling the moment it dials, while an incoming call sits
    // ringing until it's answered. These buffer them until we can apply them.
    void applyRemoteCandidate(const QVariantMap& data);
    void flushPendingRemoteCandidates();
    void fetchIceServersThen(std::function<void()> onDone);
    void onCaptureData(const QByteArray& pcm);
    void encodeAndSendFrame(const int16_t* samples, int sampleCount);

    AuthService* m_authService = nullptr;
    WebSocketService* m_webSocketService = nullptr;
    QNetworkAccessManager* m_networkManager = nullptr;

    std::shared_ptr<rtc::PeerConnection> m_peerConnection;
    std::shared_ptr<rtc::Track> m_audioTrack;
    std::vector<rtc::IceServer> m_iceServers;

    Status m_status = Status::Idle;
    QString m_callId;
    QString m_chatId;
    QString m_peerUserId;
    QString m_peerName;
    QString m_pendingOfferSdp;
    QString m_endReason;
    bool m_isMuted = false;
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

    // Audio I/O - raw 48kHz mono 16-bit PCM, 20ms frames (960 samples).
    static constexpr int kSampleRate = 48000;
    static constexpr int kChannels = 1;
    static constexpr int kFrameSamples = kSampleRate / 50; // 20ms

    QAudioSource* m_audioSource = nullptr;
    QAudioSink* m_audioSink = nullptr;
    AudioCaptureSink* m_captureSink = nullptr;
    AudioPlaybackSource* m_playbackSource = nullptr;
    QByteArray m_captureAccum;

    OpusEncoder* m_opusEncoder = nullptr;
    OpusDecoder* m_opusDecoder = nullptr;

    rtc::SSRC m_localSsrc = 0;
    uint16_t m_rtpSeq = 0;
    uint32_t m_rtpTimestamp = 0;
    // What we offer when we're the caller. The value actually used on the
    // wire is whatever the two ends negotiated - see attachAudioTrackHandlers.
    static constexpr uint8_t kOpusPayloadType = 111;
    uint8_t m_negotiatedOpusPayloadType = kOpusPayloadType;
    // Running automatic-gain multiplier for captured audio - see encodeAndSendFrame.
    float m_micGain = 1.0f;
};
