#pragma once

#include <QObject>
#include <QByteArray>
#include <QImage>
#include <QMap>
#include <QHash>
#include <QElapsedTimer>
#include <QMediaCaptureSession>
#include <QCamera>
#include <QCameraDevice>
#include <QVideoSink>
#include <memory>
#include <vector>
#include <vpx/vpx_encoder.h>
#include <vpx/vpx_decoder.h>
#include <vpx/vp8cx.h>
#include <vpx/vp8dx.h>
#include <rtc/rtc.hpp>

// The video half of a call: camera capture -> VP8 encode -> RTP out, and
// RTP in -> VP8 decode -> QImage frames for the UI.
//
// This exists because, unlike Android's Google WebRTC (which does capture,
// codecs and packetization internally), libdatachannel only moves packets -
// exactly the situation the audio path is already in with Opus, so this
// mirrors that hand-rolled design: Qt Multimedia for the device, libvpx for
// the codec, rtc::RtpHeader + RFC 7741 payload descriptors for the wire.
//
// VP8 specifically because it is the one codec Android's prebuilt WebRTC is
// guaranteed to have (libvpx software fallback), where H.264 support is
// device-dependent.
//
// 1:1 uses setTrack/handleRtp. Group mesh encodes once and fans RTP out to
// every PeerEdge send track (addSendTrack), and decodes per peer
// (handleRtpFromPeer).
//
// Everything here runs on the GUI thread (like the audio path); CallService
// marshals track callbacks onto it before calling in.
class VideoCallEngine : public QObject {
    Q_OBJECT

public:
    explicit VideoCallEngine(QObject* parent = nullptr);
    ~VideoCallEngine() override;

    // 90kHz RTP clock, per RFC 7741. Sending is paced to kSendFps regardless
    // of the camera's own rate; 15fps keeps software VP8 encode + the
    // QPainter-based rendering comfortably cheap.
    static constexpr int kRtpClockRate = 90000;
    static constexpr int kSendFps = 15;
    static constexpr int kTargetBitrateKbps = 700;
    // Small enough to clear any sane MTU once DTLS-SRTP overhead is added.
    static constexpr size_t kMaxRtpPayload = 1100;

    // ---- 1:1 single-track path ----
    void setTrack(std::shared_ptr<rtc::Track> track);
    void setPayloadType(uint8_t payloadType);
    void setSsrc(rtc::SSRC ssrc) { m_ssrc = ssrc; }

    // ---- Group multi-send / multi-decode ----
    // Encode once, packetize per destination with its own SSRC/seq/PT.
    void addSendTrack(const QString& peerId, std::shared_ptr<rtc::Track> track,
                      rtc::SSRC ssrc, uint8_t payloadType);
    void removeSendTrack(const QString& peerId);
    void clearSendTracks();

    void registerDecoder(const QString& peerId, uint8_t payloadType);
    void unregisterDecoder(const QString& peerId);
    void clearDecoders();

    bool startCapture();
    void stopCapture();
    bool captureActive() const { return m_camera != nullptr; }

    // Master switch for the whole engine - tears down capture, codecs and
    // reassembly state. Tracks and per-peer decoders are dropped too.
    void stop();

    // Full RTP packet (header included) from the 1:1 video track.
    void handleRtp(const QByteArray& packet);
    // Same for a group mesh peer - reassembly/decode are isolated per peerId.
    void handleRtpFromPeer(const QString& peerId, const QByteArray& packet);

    // Next encoded frame will be a keyframe (RTCP PLI/FIR from the peer).
    void requestKeyframe();

signals:
    // Both are emitted on the GUI thread, sized for display (the local one is
    // the same image that gets encoded, so the self-view shows exactly what
    // the peer receives).
    void localFrame(const QImage& frame);
    // 1:1 remote feed.
    void remoteFrame(const QImage& frame);
    // Group mesh remote feed (one stream per peer).
    void remoteFrameFromPeer(const QString& peerId, const QImage& frame);
    void captureFailed(const QString& reason);

private:
    struct RxPart {
        QByteArray payload;
        bool start = false;
        bool marker = false;
    };

    struct SendDest {
        std::shared_ptr<rtc::Track> track;
        rtc::SSRC ssrc = 0;
        uint8_t payloadType = 96;
        uint16_t seq = 0;
    };

    struct PeerRx {
        vpx_codec_ctx_t decoder{};
        bool decoderReady = false;
        bool waitingForKeyframe = true;
        uint8_t payloadType = 96;
        uint32_t rxTimestamp = 0;
        bool rxActive = false;
        QMap<uint16_t, RxPart> rxParts;
        qint64 lastPliMs = -1;
    };

    void onCaptureFrame(const QImage& image);
    bool ensureEncoder(int width, int height);
    void destroyEncoder();
    bool ensureDecoder();
    void destroyDecoder();
    void encodeAndSend(const QImage& image);
    void packetizeAndSend(const uint8_t* data, size_t size, uint32_t timestamp, bool keyframe);
    void sendPacketizedTo(SendDest& dest, const uint8_t* data, size_t size,
                          uint32_t timestamp, bool keyframe);
    void tryAssembleFrame();
    void decodeFrame(const QByteArray& frame);
    void handleRtpInto(PeerRx& rx, const QByteArray& packet, const QString& peerId);
    void tryAssembleFrameInto(PeerRx& rx, const QString& peerId);
    void decodeFrameInto(PeerRx& rx, const QByteArray& frame, const QString& peerId);
    void destroyPeerRx(PeerRx& rx);
    void maybeRequestRemoteKeyframe(std::shared_ptr<rtc::Track> track, qint64& lastPliMs);

    // ---- capture ----
    QMediaCaptureSession m_session;
    QCamera* m_camera = nullptr;
    QVideoSink* m_sink = nullptr;
    QElapsedTimer m_sendClock;
    qint64 m_lastEncodeMs = -1;

    // ---- encode ----
    vpx_codec_ctx_t m_encoder{};
    bool m_encoderReady = false;
    int m_encodeWidth = 0;
    int m_encodeHeight = 0;
    std::vector<uint8_t> m_i420Buffer;

    // ---- decode (1:1) ----
    vpx_codec_ctx_t m_decoder{};
    bool m_decoderReady = false;
    // Until a keyframe arrives (first frame, or the first after loss), delta
    // frames reference state the decoder doesn't have and only produce smear.
    bool m_waitingForKeyframe = true;
    bool m_forceKeyframe = false;
    qint64 m_lastPliMs = -1;

    // ---- RTP (1:1) ----
    std::shared_ptr<rtc::Track> m_track;
    uint8_t m_payloadType = 96;
    rtc::SSRC m_ssrc = 0;
    uint16_t m_seq = 0;

    // ---- group fan-out / per-peer decode ----
    QHash<QString, SendDest> m_sendDests;
    QHash<QString, PeerRx*> m_peerRx;

    // ---- receive reassembly (1:1) ----
    // One frame in flight, keyed by RTP timestamp: VP8 frames span several
    // packets (same timestamp, seq-contiguous, marker on the last). Anything
    // from an older timestamp that shows up late is dropped - with no jitter
    // buffer, waiting on stragglers only adds latency to every later frame.
    uint32_t m_rxTimestamp = 0;
    bool m_rxActive = false;
    QMap<uint16_t, RxPart> m_rxParts;
};
