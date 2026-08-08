#include "videocallengine.h"

#include <QDebug>
#include <QMediaDevices>
#include <QVideoFrame>
#include <QPainter>
#include <algorithm>
#include <cstring>

// ==================== color conversion ====================
// libvpx speaks I420 planes; Qt speaks QImage. Both directions are plain
// BT.601 integer math - no SIMD, but at 640x480@15fps this is a fraction of
// what the encode itself costs.

namespace {

inline uint8_t clamp255(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

void rgbToI420(const QImage& rgb, uint8_t* dst, int width, int height) {
    uint8_t* yPlane = dst;
    uint8_t* uPlane = yPlane + static_cast<size_t>(width) * height;
    uint8_t* vPlane = uPlane + static_cast<size_t>(width / 2) * (height / 2);

    for (int y = 0; y < height; ++y) {
        const QRgb* row = reinterpret_cast<const QRgb*>(rgb.constScanLine(y));
        uint8_t* yRow = yPlane + static_cast<size_t>(y) * width;
        for (int x = 0; x < width; ++x) {
            const QRgb px = row[x];
            const int r = qRed(px), g = qGreen(px), b = qBlue(px);
            yRow[x] = clamp255(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }
    }
    // Chroma subsampled 2x2 - sampling the block's top-left pixel is visually
    // indistinguishable from averaging at these resolutions and half the work.
    for (int y = 0; y < height / 2; ++y) {
        const QRgb* row = reinterpret_cast<const QRgb*>(rgb.constScanLine(y * 2));
        uint8_t* uRow = uPlane + static_cast<size_t>(y) * (width / 2);
        uint8_t* vRow = vPlane + static_cast<size_t>(y) * (width / 2);
        for (int x = 0; x < width / 2; ++x) {
            const QRgb px = row[x * 2];
            const int r = qRed(px), g = qGreen(px), b = qBlue(px);
            uRow[x] = clamp255(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            vRow[x] = clamp255(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
    }
}

QImage i420ToImage(const vpx_image_t* img) {
    const int width = static_cast<int>(img->d_w);
    const int height = static_cast<int>(img->d_h);
    QImage out(width, height, QImage::Format_RGB32);

    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = img->planes[VPX_PLANE_Y] + static_cast<size_t>(y) * img->stride[VPX_PLANE_Y];
        const uint8_t* uRow = img->planes[VPX_PLANE_U] + static_cast<size_t>(y / 2) * img->stride[VPX_PLANE_U];
        const uint8_t* vRow = img->planes[VPX_PLANE_V] + static_cast<size_t>(y / 2) * img->stride[VPX_PLANE_V];
        QRgb* dstRow = reinterpret_cast<QRgb*>(out.scanLine(y));
        for (int x = 0; x < width; ++x) {
            const int c = 298 * (yRow[x] - 16);
            const int d = uRow[x / 2] - 128;
            const int e = vRow[x / 2] - 128;
            dstRow[x] = qRgb(clamp255((c + 409 * e + 128) >> 8),
                             clamp255((c - 100 * d - 208 * e + 128) >> 8),
                             clamp255((c + 516 * d + 128) >> 8));
        }
    }
    return out;
}

} // namespace

// ==================== engine ====================

VideoCallEngine::VideoCallEngine(QObject* parent) : QObject(parent) {
    m_sendClock.start();
}

VideoCallEngine::~VideoCallEngine() {
    stop();
}

void VideoCallEngine::setTrack(std::shared_ptr<rtc::Track> track) {
    m_track = std::move(track);
}

void VideoCallEngine::setPayloadType(uint8_t payloadType) {
    m_payloadType = payloadType;
}

void VideoCallEngine::addSendTrack(const QString& peerId, std::shared_ptr<rtc::Track> track,
                                  rtc::SSRC ssrc, uint8_t payloadType) {
    if (peerId.isEmpty() || !track) return;
    SendDest dest;
    dest.track = std::move(track);
    dest.ssrc = ssrc;
    dest.payloadType = payloadType;
    dest.seq = 0;
    m_sendDests.insert(peerId, dest);
    qDebug() << "[VideoCallEngine] addSendTrack" << peerId << "ssrc" << ssrc << "pt" << payloadType;
}

void VideoCallEngine::removeSendTrack(const QString& peerId) {
    m_sendDests.remove(peerId);
}

void VideoCallEngine::clearSendTracks() {
    m_sendDests.clear();
}

void VideoCallEngine::registerDecoder(const QString& peerId, uint8_t payloadType) {
    if (peerId.isEmpty()) return;
    PeerRx*& slot = m_peerRx[peerId];
    if (!slot) {
        slot = new PeerRx();
    }
    slot->payloadType = payloadType;
    qDebug() << "[VideoCallEngine] registerDecoder" << peerId << "pt" << payloadType;
}

void VideoCallEngine::unregisterDecoder(const QString& peerId) {
    auto it = m_peerRx.find(peerId);
    if (it == m_peerRx.end()) return;
    destroyPeerRx(*it.value());
    delete it.value();
    m_peerRx.erase(it);
}

void VideoCallEngine::clearDecoders() {
    for (auto it = m_peerRx.begin(); it != m_peerRx.end(); ++it) {
        destroyPeerRx(*it.value());
        delete it.value();
    }
    m_peerRx.clear();
}

void VideoCallEngine::destroyPeerRx(PeerRx& rx) {
    if (rx.decoderReady) {
        vpx_codec_destroy(&rx.decoder);
        rx.decoderReady = false;
    }
    rx.rxParts.clear();
    rx.rxActive = false;
    rx.waitingForKeyframe = true;
}

bool VideoCallEngine::startCapture() {
    if (m_camera) return true;

    const QList<QCameraDevice> devices = QMediaDevices::videoInputs();
    if (devices.isEmpty()) {
        qWarning() << "[VideoCallEngine] no camera available";
        emit captureFailed(QStringLiteral("No camera found"));
        return false;
    }

    m_camera = new QCamera(devices.first(), this);
    m_sink = new QVideoSink(this);
    m_session.setCamera(m_camera);
    m_session.setVideoSink(m_sink);

    // Frames arrive on the media backend's thread; QueuedConnection hops to
    // the GUI thread where the encoder and everything else in this class
    // lives (same pattern, for the same reason, as CircularVideoItem).
    connect(m_sink, &QVideoSink::videoFrameChanged, this, [this](const QVideoFrame& frame) {
        if (!frame.isValid()) return;
        // Pace to kSendFps before doing any conversion work - the camera's
        // native rate can be double what we send, and toImage() on dropped
        // frames would be pure waste.
        const qint64 now = m_sendClock.elapsed();
        if (m_lastEncodeMs >= 0 && now - m_lastEncodeMs < 1000 / kSendFps) return;
        m_lastEncodeMs = now;

        QImage img = frame.toImage();
        if (img.isNull()) return;
        // Fit inside 640x360 keeping aspect, then centre on a black 16:9
        // canvas (Meet-style letterbox/pillarbox). Even dims for 4:2:0.
        QImage scaled = img.scaled(640, 360, Qt::KeepAspectRatio, Qt::FastTransformation)
                           .convertToFormat(QImage::Format_RGB32);
        int w = scaled.width() & ~1;
        int h = scaled.height() & ~1;
        if (w < 16 || h < 16) return;
        if (w != scaled.width() || h != scaled.height()) {
            scaled = scaled.copy(0, 0, w, h);
        }
        QImage canvas(640, 360, QImage::Format_RGB32);
        canvas.fill(Qt::black);
        QPainter pad(&canvas);
        pad.drawImage((640 - w) / 2, (360 - h) / 2, scaled);
        pad.end();
        onCaptureFrame(canvas);
    }, Qt::QueuedConnection);

    m_camera->start();
    qDebug() << "[VideoCallEngine] capture started on" << devices.first().description();
    return true;
}

void VideoCallEngine::stopCapture() {
    if (m_camera) {
        m_camera->stop();
        m_session.setCamera(nullptr);
        m_camera->deleteLater();
        m_camera = nullptr;
    }
    if (m_sink) {
        m_session.setVideoSink(nullptr);
        m_sink->deleteLater();
        m_sink = nullptr;
    }
    m_lastEncodeMs = -1;
}

void VideoCallEngine::stop() {
    stopCapture();
    destroyEncoder();
    destroyDecoder();
    clearSendTracks();
    clearDecoders();
    m_track.reset();
    m_rxParts.clear();
    m_rxActive = false;
    m_waitingForKeyframe = true;
    m_seq = 0;
}

void VideoCallEngine::onCaptureFrame(const QImage& image) {
    emit localFrame(image);
    encodeAndSend(image);
}

// ==================== encode ====================

bool VideoCallEngine::ensureEncoder(int width, int height) {
    if (m_encoderReady && width == m_encodeWidth && height == m_encodeHeight) return true;
    destroyEncoder();

    vpx_codec_enc_cfg_t cfg;
    if (vpx_codec_enc_config_default(vpx_codec_vp8_cx(), &cfg, 0) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] vpx enc config failed";
        return false;
    }
    cfg.g_w = static_cast<unsigned>(width);
    cfg.g_h = static_cast<unsigned>(height);
    cfg.g_timebase = {1, kRtpClockRate};
    cfg.rc_target_bitrate = kTargetBitrateKbps;
    cfg.rc_end_usage = VPX_CBR;
    cfg.g_lag_in_frames = 0;              // realtime: one frame in, one out
    cfg.g_pass = VPX_RC_ONE_PASS;
    cfg.g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;
    // A keyframe every ~2s bounds how long the far end shows a frozen/absent
    // picture after packet loss - there is no RTCP PLI feedback loop here.
    cfg.kf_mode = VPX_KF_AUTO;
    cfg.kf_max_dist = kSendFps * 2;

    if (vpx_codec_enc_init(&m_encoder, vpx_codec_vp8_cx(), &cfg, 0) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] vpx_codec_enc_init failed:" << vpx_codec_error(&m_encoder);
        return false;
    }
    // Fastest usable speed/quality point for software realtime encoding.
    vpx_codec_control(&m_encoder, VP8E_SET_CPUUSED, 8);
    vpx_codec_control(&m_encoder, VP8E_SET_STATIC_THRESHOLD, 1);

    m_encoderReady = true;
    m_encodeWidth = width;
    m_encodeHeight = height;
    m_i420Buffer.resize(static_cast<size_t>(width) * height * 3 / 2);
    qDebug() << "[VideoCallEngine] encoder ready" << width << "x" << height;
    return true;
}

void VideoCallEngine::destroyEncoder() {
    if (m_encoderReady) {
        vpx_codec_destroy(&m_encoder);
        m_encoderReady = false;
    }
    m_encodeWidth = 0;
    m_encodeHeight = 0;
}

void VideoCallEngine::encodeAndSend(const QImage& image) {
    const bool singleReady = m_track && m_track->isOpen();
    bool multiReady = false;
    for (auto it = m_sendDests.constBegin(); it != m_sendDests.constEnd(); ++it) {
        if (it.value().track && it.value().track->isOpen()) {
            multiReady = true;
            break;
        }
    }
    if (!singleReady && !multiReady) return;
    if (!ensureEncoder(image.width(), image.height())) return;

    rgbToI420(image, m_i420Buffer.data(), image.width(), image.height());

    vpx_image_t raw;
    vpx_img_wrap(&raw, VPX_IMG_FMT_I420,
                 static_cast<unsigned>(image.width()), static_cast<unsigned>(image.height()),
                 1, m_i420Buffer.data());

    // The RTP timestamp doubles as the encoder pts - both are the same 90kHz
    // clock, so wall time since the engine started maps directly.
    const uint32_t timestamp = static_cast<uint32_t>(m_sendClock.elapsed() * (kRtpClockRate / 1000));
    const unsigned long duration = kRtpClockRate / kSendFps;

    if (vpx_codec_encode(&m_encoder, &raw, timestamp, duration, 0, VPX_DL_REALTIME) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] vpx_codec_encode failed:" << vpx_codec_error(&m_encoder);
        return;
    }

    vpx_codec_iter_t iter = nullptr;
    const vpx_codec_cx_pkt_t* pkt = nullptr;
    while ((pkt = vpx_codec_get_cx_data(&m_encoder, &iter)) != nullptr) {
        if (pkt->kind != VPX_CODEC_CX_FRAME_PKT) continue;
        packetizeAndSend(static_cast<const uint8_t*>(pkt->data.frame.buf),
                         pkt->data.frame.sz, timestamp,
                         (pkt->data.frame.flags & VPX_FRAME_IS_KEY) != 0);
    }
}

void VideoCallEngine::sendPacketizedTo(SendDest& dest, const uint8_t* data, size_t size,
                                       uint32_t timestamp, bool /*keyframe*/) {
    if (!dest.track || !dest.track->isOpen()) return;

    // RFC 7741 with the minimal one-byte payload descriptor: X=0, N=0,
    // S set on the first packet of the frame, PID=0.
    size_t offset = 0;
    bool first = true;
    while (offset < size) {
        const size_t chunk = std::min(kMaxRtpPayload, size - offset);
        const bool last = (offset + chunk == size);

        std::vector<std::byte> packet(sizeof(rtc::RtpHeader) + 1 + chunk);
        auto* header = reinterpret_cast<rtc::RtpHeader*>(packet.data());
        header->preparePacket();
        header->setPayloadType(dest.payloadType);
        header->setSsrc(dest.ssrc);
        header->setSeqNumber(dest.seq++);
        header->setTimestamp(timestamp);
        header->setMarker(last);

        auto* body = reinterpret_cast<uint8_t*>(header->getBody());
        body[0] = first ? 0x10 : 0x00; // S bit
        memcpy(body + 1, data + offset, chunk);

        if (!dest.track->send(packet.data(), packet.size())) {
            qWarning() << "[VideoCallEngine] video send rejected (multi)";
            return;
        }
        offset += chunk;
        first = false;
    }
}

void VideoCallEngine::packetizeAndSend(const uint8_t* data, size_t size, uint32_t timestamp, bool keyframe) {
    // 1:1 single track
    if (m_track && m_track->isOpen()) {
        size_t offset = 0;
        bool first = true;
        static int s_txFrames = 0;
        while (offset < size) {
            const size_t chunk = std::min(kMaxRtpPayload, size - offset);
            const bool last = (offset + chunk == size);

            std::vector<std::byte> packet(sizeof(rtc::RtpHeader) + 1 + chunk);
            auto* header = reinterpret_cast<rtc::RtpHeader*>(packet.data());
            header->preparePacket();
            header->setPayloadType(m_payloadType);
            header->setSsrc(m_ssrc);
            header->setSeqNumber(m_seq++);
            header->setTimestamp(timestamp);
            header->setMarker(last);

            auto* body = reinterpret_cast<uint8_t*>(header->getBody());
            body[0] = first ? 0x10 : 0x00;
            memcpy(body + 1, data + offset, chunk);

            if (!m_track->send(packet.data(), packet.size())) {
                qWarning() << "[VideoCallEngine] video send rejected";
                break;
            }
            offset += chunk;
            first = false;
        }
        if (++s_txFrames % (kSendFps * 5) == 1) {
            qDebug() << "[VideoCallEngine] TX video frames:" << s_txFrames
                     << "last frame bytes:" << size << "key:" << keyframe;
        }
    }

    // Group mesh: same encoded frame, per-edge SSRC/seq/PT.
    for (auto it = m_sendDests.begin(); it != m_sendDests.end(); ++it) {
        sendPacketizedTo(it.value(), data, size, timestamp, keyframe);
    }
}

// ==================== decode ====================

bool VideoCallEngine::ensureDecoder() {
    if (m_decoderReady) return true;
    vpx_codec_dec_cfg_t cfg{};
    cfg.threads = 2;
    if (vpx_codec_dec_init(&m_decoder, vpx_codec_vp8_dx(), &cfg, 0) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] vpx_codec_dec_init failed:" << vpx_codec_error(&m_decoder);
        return false;
    }
    m_decoderReady = true;
    return true;
}

void VideoCallEngine::destroyDecoder() {
    if (m_decoderReady) {
        vpx_codec_destroy(&m_decoder);
        m_decoderReady = false;
    }
}

void VideoCallEngine::handleRtp(const QByteArray& packet) {
    // Reuse the peer path logic against the 1:1 decoder state by inlining the
    // same parsing here (keeps the 1:1 signal as remoteFrame without a peerId).
    if (packet.size() <= static_cast<int>(sizeof(rtc::RtpHeader))) return;
    const auto* header = reinterpret_cast<const rtc::RtpHeader*>(packet.constData());

    if (header->payloadType() != m_payloadType) return;

    const char* body = header->getBody();
    const auto offset = static_cast<size_t>(body - packet.constData());
    if (offset >= static_cast<size_t>(packet.size())) return;
    const uint8_t* payload = reinterpret_cast<const uint8_t*>(body);
    size_t payloadSize = static_cast<size_t>(packet.size()) - offset;

    if (header->padding()) {
        const uint8_t padLen = static_cast<uint8_t>(packet.constData()[packet.size() - 1]);
        if (padLen == 0 || padLen >= payloadSize) return;
        payloadSize -= padLen;
    }

    if (payloadSize < 1) return;
    const uint8_t b0 = payload[0];
    const bool extended = b0 & 0x80;
    const bool startOfFrame = (b0 & 0x10) && ((b0 & 0x07) == 0);
    size_t descSize = 1;
    if (extended) {
        if (payloadSize < 2) return;
        const uint8_t b1 = payload[1];
        descSize = 2;
        if (b1 & 0x80) {
            if (payloadSize < descSize + 1) return;
            descSize += (payload[descSize] & 0x80) ? 2 : 1;
        }
        if (b1 & 0x40) descSize += 1;
        if (b1 & 0x30) descSize += 1;
    }
    if (payloadSize <= descSize) return;
    payload += descSize;
    payloadSize -= descSize;

    const uint32_t timestamp = header->timestamp();
    const uint16_t seq = header->seqNumber();
    const bool marker = header->marker();

    if (!m_rxActive || timestamp != m_rxTimestamp) {
        m_rxParts.clear();
        m_rxTimestamp = timestamp;
        m_rxActive = true;
    }

    RxPart part;
    part.payload = QByteArray(reinterpret_cast<const char*>(payload), static_cast<int>(payloadSize));
    part.start = startOfFrame;
    part.marker = marker;
    m_rxParts.insert(seq, part);

    if (marker || m_rxParts.size() > 1) tryAssembleFrame();
}

void VideoCallEngine::handleRtpFromPeer(const QString& peerId, const QByteArray& packet) {
    auto it = m_peerRx.find(peerId);
    if (it == m_peerRx.end() || !it.value()) {
        // Lazy-register with default PT if CallService forgot; better than drop.
        registerDecoder(peerId, m_payloadType);
        it = m_peerRx.find(peerId);
        if (it == m_peerRx.end()) return;
    }
    handleRtpInto(*it.value(), packet, peerId);
}

void VideoCallEngine::handleRtpInto(PeerRx& rx, const QByteArray& packet, const QString& peerId) {
    if (packet.size() <= static_cast<int>(sizeof(rtc::RtpHeader))) return;
    const auto* header = reinterpret_cast<const rtc::RtpHeader*>(packet.constData());

    if (header->payloadType() != rx.payloadType) return;

    const char* body = header->getBody();
    const auto offset = static_cast<size_t>(body - packet.constData());
    if (offset >= static_cast<size_t>(packet.size())) return;
    const uint8_t* payload = reinterpret_cast<const uint8_t*>(body);
    size_t payloadSize = static_cast<size_t>(packet.size()) - offset;

    if (header->padding()) {
        const uint8_t padLen = static_cast<uint8_t>(packet.constData()[packet.size() - 1]);
        if (padLen == 0 || padLen >= payloadSize) return;
        payloadSize -= padLen;
    }

    if (payloadSize < 1) return;
    const uint8_t b0 = payload[0];
    const bool extended = b0 & 0x80;
    const bool startOfFrame = (b0 & 0x10) && ((b0 & 0x07) == 0);
    size_t descSize = 1;
    if (extended) {
        if (payloadSize < 2) return;
        const uint8_t b1 = payload[1];
        descSize = 2;
        if (b1 & 0x80) {
            if (payloadSize < descSize + 1) return;
            descSize += (payload[descSize] & 0x80) ? 2 : 1;
        }
        if (b1 & 0x40) descSize += 1;
        if (b1 & 0x30) descSize += 1;
    }
    if (payloadSize <= descSize) return;
    payload += descSize;
    payloadSize -= descSize;

    const uint32_t timestamp = header->timestamp();
    const uint16_t seq = header->seqNumber();
    const bool marker = header->marker();

    if (!rx.rxActive || timestamp != rx.rxTimestamp) {
        rx.rxParts.clear();
        rx.rxTimestamp = timestamp;
        rx.rxActive = true;
    }

    RxPart part;
    part.payload = QByteArray(reinterpret_cast<const char*>(payload), static_cast<int>(payloadSize));
    part.start = startOfFrame;
    part.marker = marker;
    rx.rxParts.insert(seq, part);

    if (marker || rx.rxParts.size() > 1) tryAssembleFrameInto(rx, peerId);
}

void VideoCallEngine::tryAssembleFrame() {
    uint16_t startSeq = 0;
    bool haveStart = false;
    for (auto it = m_rxParts.constBegin(); it != m_rxParts.constEnd(); ++it) {
        if (it.value().start) {
            startSeq = it.key();
            haveStart = true;
            break;
        }
    }
    if (!haveStart) return;

    QByteArray frame;
    uint16_t seq = startSeq;
    for (int guard = 0; guard < 512; ++guard, ++seq) {
        const auto it = m_rxParts.constFind(seq);
        if (it == m_rxParts.constEnd()) return;
        frame.append(it.value().payload);
        if (it.value().marker) {
            m_rxParts.clear();
            m_rxActive = false;
            decodeFrame(frame);
            return;
        }
    }
}

void VideoCallEngine::tryAssembleFrameInto(PeerRx& rx, const QString& peerId) {
    uint16_t startSeq = 0;
    bool haveStart = false;
    for (auto it = rx.rxParts.constBegin(); it != rx.rxParts.constEnd(); ++it) {
        if (it.value().start) {
            startSeq = it.key();
            haveStart = true;
            break;
        }
    }
    if (!haveStart) return;

    QByteArray frame;
    uint16_t seq = startSeq;
    for (int guard = 0; guard < 512; ++guard, ++seq) {
        const auto it = rx.rxParts.constFind(seq);
        if (it == rx.rxParts.constEnd()) return;
        frame.append(it.value().payload);
        if (it.value().marker) {
            rx.rxParts.clear();
            rx.rxActive = false;
            decodeFrameInto(rx, frame, peerId);
            return;
        }
    }
}

void VideoCallEngine::decodeFrame(const QByteArray& frame) {
    if (!ensureDecoder() || frame.isEmpty()) return;

    const bool keyframe = (static_cast<uint8_t>(frame[0]) & 0x01) == 0;
    if (m_waitingForKeyframe && !keyframe) return;

    if (vpx_codec_decode(&m_decoder,
                         reinterpret_cast<const uint8_t*>(frame.constData()),
                         static_cast<unsigned>(frame.size()), nullptr, 0) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] vpx_codec_decode failed:" << vpx_codec_error(&m_decoder)
                   << "- waiting for keyframe";
        m_waitingForKeyframe = true;
        return;
    }
    m_waitingForKeyframe = false;

    vpx_codec_iter_t iter = nullptr;
    vpx_image_t* img = nullptr;
    static int s_rxFrames = 0;
    while ((img = vpx_codec_get_frame(&m_decoder, &iter)) != nullptr) {
        if (img->fmt != VPX_IMG_FMT_I420) continue;
        if (++s_rxFrames % (kSendFps * 5) == 1) {
            qDebug() << "[VideoCallEngine] RX video frames:" << s_rxFrames
                     << "size:" << img->d_w << "x" << img->d_h;
        }
        emit remoteFrame(i420ToImage(img));
    }
}

void VideoCallEngine::decodeFrameInto(PeerRx& rx, const QByteArray& frame, const QString& peerId) {
    if (frame.isEmpty()) return;

    if (!rx.decoderReady) {
        vpx_codec_dec_cfg_t cfg{};
        cfg.threads = 1;
        if (vpx_codec_dec_init(&rx.decoder, vpx_codec_vp8_dx(), &cfg, 0) != VPX_CODEC_OK) {
            qWarning() << "[VideoCallEngine] peer decoder init failed for" << peerId
                       << vpx_codec_error(&rx.decoder);
            return;
        }
        rx.decoderReady = true;
    }

    const bool keyframe = (static_cast<uint8_t>(frame[0]) & 0x01) == 0;
    if (rx.waitingForKeyframe && !keyframe) return;

    if (vpx_codec_decode(&rx.decoder,
                         reinterpret_cast<const uint8_t*>(frame.constData()),
                         static_cast<unsigned>(frame.size()), nullptr, 0) != VPX_CODEC_OK) {
        qWarning() << "[VideoCallEngine] peer decode failed for" << peerId
                   << vpx_codec_error(&rx.decoder);
        rx.waitingForKeyframe = true;
        return;
    }
    rx.waitingForKeyframe = false;

    vpx_codec_iter_t iter = nullptr;
    vpx_image_t* img = nullptr;
    while ((img = vpx_codec_get_frame(&rx.decoder, &iter)) != nullptr) {
        if (img->fmt != VPX_IMG_FMT_I420) continue;
        emit remoteFrameFromPeer(peerId, i420ToImage(img));
    }
}
