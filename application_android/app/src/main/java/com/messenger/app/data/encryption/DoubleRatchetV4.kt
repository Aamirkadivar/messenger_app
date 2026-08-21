package com.messenger.app.data.encryption

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Direct-chat X3DH-lite two-root Double Ratchet (encryption_version=4).
 * Byte-for-byte port of back-end/e2ee/ratchet_x3dh.go; see
 * docs/e2ee-protocol-v4.md and test-vectors/e2ee/x3dh-*.json.
 *
 * Why it replaces v3: v3 chose initiator/responder by "who sends first", so when
 * both peers sent before receiving (glare) neither could decrypt the other. v4
 * derives a symmetric root from both identity keys (no role) and keeps the
 * sending/receiving roots separate so concurrent chains never corrupt each
 * other.
 *
 * Wire: type(1) || dh_pk(32) || n(4 BE) || pn(4 BE) || nonce(24) || secretbox(ct)
 */
object DoubleRatchetV4 {
    private const val ROOT_INFO = "messenger-dr-root-v1"
    private const val X3DH_INFO = "messenger-x3dh-lite-v1"
    private const val MAX_SKIP = 200
    // New rk0-rooted sending chain (fresh ephemeral) every this many messages,
    // so the stateless decrypt walk stays short.
    private const val CHAIN_ROTATE = 1000
    private const val HEADER_LEN = 41 // type(1) || dh(32) || n(4) || pn(4)
    private const val TYPE_INITIAL: Byte = 0x01
    private const val TYPE_NORMAL: Byte = 0x02

    /** Deterministic symmetric root from the two identity keys (order-free). */
    fun symmetricRoot(myIkSk: ByteArray, myIkPk: ByteArray, peerIkPk: ByteArray): ByteArray? {
        if (myIkSk.size != 32 || myIkPk.size != 32 || peerIkPk.size != 32) return null
        val dh = E2ECrypto.x25519(myIkSk, peerIkPk) ?: return null
        val lo: ByteArray
        val hi: ByteArray
        if (bytesLess(myIkPk, peerIkPk)) {
            lo = myIkPk; hi = peerIkPk
        } else {
            lo = peerIkPk; hi = myIkPk
        }
        return hkdf(dh, sha256(lo + hi), X3DH_INFO.toByteArray(), 32)
    }

    fun initSession(myIkPk: ByteArray, myIkSk: ByteArray, peerIkPk: ByteArray): Session? {
        val rk0 = symmetricRoot(myIkSk, myIkPk, peerIkPk) ?: return null
        val s = Session()
        s.rk0 = rk0
        s.rks = rk0.copyOf()
        s.rkr = rk0.copyOf()
        s.peerIdent = peerIkPk.copyOf()
        s.identSk = myIkSk.copyOf()
        return s
    }

    class Session {
        var rk0: ByteArray = ByteArray(0)
        var rks: ByteArray = ByteArray(0)
        var cks: ByteArray = ByteArray(0)
        var rkr: ByteArray = ByteArray(0)
        var ckr: ByteArray = ByteArray(0)
        var dhsSk: ByteArray = ByteArray(0)
        var dhsPk: ByteArray = ByteArray(0)
        var dhr: ByteArray = ByteArray(0)
        var peerIdent: ByteArray = ByteArray(0)
        var identSk: ByteArray = ByteArray(0)
        var ns: Int = 0
        var nr: Int = 0
        var pn: Int = 0
        var sentFirst: Boolean = false
        var recvFirst: Boolean = false
        var turnPending: Boolean = false
        // True once the sending chain is rooted at rk0 (INITIAL-only mode).
        // Defaults false so sessions serialized by older builds re-root on
        // their first send after upgrade.
        var initChain: Boolean = false
        /** Bumped on every local persist; vault merge keeps the higher seq. */
        var seq: Long = 0
        val skipped = HashMap<String, ByteArray>()

        fun encrypt(plain: ByteArray): ByteArray? {
            if (rk0.size != 32) return null
            // Every send is an INITIAL-type message on a chain rooted at the
            // immutable rk0 (fresh ephemeral, chain index restarts at 0).
            // DH-turn (NORMAL-type) chains gave per-message forward secrecy,
            // but their message keys are consumed on first read — and this
            // product re-downloads ciphertext from the server and re-decrypts
            // on every chat reopen, so every NORMAL row became "encrypted
            // message" forever once its key was used. rk0-rooted chains are
            // re-derivable any time (see decryptInitialStateless). The
            // ephemeral rotates every CHAIN_ROTATE messages to bound the
            // stateless walk. initChain is false for sessions serialized by
            // older builds (possibly mid NORMAL-era chain): re-root once.
            if (!sentFirst || !initChain || ns >= CHAIN_ROTATE) {
                val kp = E2ECrypto.generateKeyPair() ?: return null
                val sk = E2ECrypto.fromHex(kp.privateHex) ?: return null
                val pk = E2ECrypto.fromHex(kp.publicHex) ?: return null
                val dh = E2ECrypto.x25519(sk, peerIdent) ?: return null
                pn = ns
                ns = 0
                val rc = kdfRk(rk0, dh) ?: return null
                rks = rc.first
                cks = rc.second
                dhsSk = sk
                dhsPk = pk
                sentFirst = true
                turnPending = false
                initChain = true
            }
            val msgType = TYPE_INITIAL
            if (cks.size != 32 || dhsPk.size != 32) return null
            val (nextCk, mk) = kdfCk(cks)
            cks = nextCk
            val n = ns
            ns++
            val nonce = ByteArray(24)
            SecureRandom().nextBytes(nonce)
            val ct = E2ECrypto.secretBoxSeal(plain, nonce, mk) ?: return null
            val out = ByteArray(HEADER_LEN + 24 + ct.size)
            out[0] = msgType
            System.arraycopy(dhsPk, 0, out, 1, 32)
            putBe32(out, 33, n)
            putBe32(out, 37, pn)
            System.arraycopy(nonce, 0, out, 41, 24)
            System.arraycopy(ct, 0, out, 65, ct.size)
            return out
        }

        /**
         * Transactional decrypt: advance a clone and commit only if the AEAD tag
         * verifies, so a forged/truncated message can never desync the session.
         *
         * If the stateful path fails, INITIAL-type messages get a stateless
         * retry from the immutable rk0 + identity key, with nothing committed.
         * The live ratchet consumes each message key exactly once, but history
         * rows are re-decrypted on every refetch/repaint — and one's own
         * fan-out blob can only ever be read back this way (a sending session
         * cannot decrypt its own output).
         */
        fun decrypt(payload: ByteArray): ByteArray? {
            // INITIAL messages derive entirely from rk0 + the identity key:
            // open them statelessly and commit nothing, so repeated reads
            // (history refetch, repaints, own fan-out blob) always succeed and
            // the session can never be poisoned by reading old rows.
            decryptInitialStateless(payload)?.let { return it }
            // Legacy NORMAL-type rows (or INITIAL under a stale-identity
            // session): classic transactional ratchet path.
            val trial = clone()
            trial.decryptInto(payload)?.let { pt ->
                copyFrom(trial)
                return pt
            }
            return null
        }

        private fun decryptInitialStateless(payload: ByteArray): ByteArray? {
            if (payload.size < HEADER_LEN + 24 + 16) return null
            if (payload[0] != TYPE_INITIAL) return null
            if (rk0.size != 32 || identSk.size != 32) return null
            val dh = payload.copyOfRange(1, 33)
            val n = getBe32(payload, 33)
            // Chain walk is just HMACs; cap so a corrupt header can't spin us.
            if (n < 0 || n > 4096) return null
            val shared = E2ECrypto.x25519(identSk, dh) ?: return null
            val rc = kdfRk(rk0, shared) ?: return null
            var ck = rc.second
            var mk: ByteArray
            var i = 0
            while (true) {
                val step = kdfCk(ck)
                ck = step.first
                mk = step.second
                if (i == n) break
                i++
            }
            val nonce = payload.copyOfRange(41, 65)
            val ct = payload.copyOfRange(65, payload.size)
            return E2ECrypto.secretBoxOpen(ct, nonce, mk)
        }

        private fun decryptInto(payload: ByteArray): ByteArray? {
            if (payload.size < HEADER_LEN + 24 + 16) return null
            val msgType = payload[0]
            val dh = payload.copyOfRange(1, 33)
            val n = getBe32(payload, 33)
            val pn = getBe32(payload, 37)
            val nonce = payload.copyOfRange(41, 65)
            val ct = payload.copyOfRange(65, payload.size)

            val id = skipId(dh, n)
            skipped.remove(id)?.let { mk ->
                return E2ECrypto.secretBoxOpen(ct, nonce, mk)
            }

            if (dhr.size != 32 || !dhr.contentEquals(dh)) {
                if (!recvRatchet(msgType, dh, pn)) return null
            }
            if (!skipRecv(n)) return null
            val (nextCk, mk) = kdfCk(ckr)
            ckr = nextCk
            nr++
            return E2ECrypto.secretBoxOpen(ct, nonce, mk)
        }

        private fun recvRatchet(msgType: Byte, theirDh: ByteArray, pn: Int): Boolean {
            if (!skipRecv(pn)) return false
            val ourSk: ByteArray
            val root: ByteArray
            if (msgType == TYPE_INITIAL && !recvFirst) {
                ourSk = identSk
                root = rk0
            } else {
                if (dhsSk.size != 32) return false
                ourSk = dhsSk
                root = rkr
            }
            val dh = E2ECrypto.x25519(ourSk, theirDh) ?: return false
            val rc = kdfRk(root, dh) ?: return false
            rkr = rc.first
            ckr = rc.second
            dhr = theirDh.copyOf()
            nr = 0
            recvFirst = true
            turnPending = true
            return true
        }

        private fun skipRecv(until: Int): Boolean {
            if (ckr.size != 32 || dhr.size != 32) return until == 0
            if (until <= nr) return true
            if (until - nr > MAX_SKIP) return false
            while (nr < until) {
                val (nextCk, mk) = kdfCk(ckr)
                ckr = nextCk
                skipped[skipId(dhr, nr)] = mk
                nr++
            }
            return true
        }

        private fun clone(): Session {
            val c = Session()
            c.rk0 = rk0.copyOf(); c.rks = rks.copyOf(); c.cks = cks.copyOf()
            c.rkr = rkr.copyOf(); c.ckr = ckr.copyOf()
            c.dhsSk = dhsSk.copyOf(); c.dhsPk = dhsPk.copyOf(); c.dhr = dhr.copyOf()
            c.peerIdent = peerIdent.copyOf(); c.identSk = identSk.copyOf()
            c.ns = ns; c.nr = nr; c.pn = pn
            c.sentFirst = sentFirst; c.recvFirst = recvFirst; c.turnPending = turnPending
            c.initChain = initChain
            c.seq = seq
            for ((k, v) in skipped) c.skipped[k] = v.copyOf()
            return c
        }

        private fun copyFrom(o: Session) {
            rk0 = o.rk0; rks = o.rks; cks = o.cks; rkr = o.rkr; ckr = o.ckr
            dhsSk = o.dhsSk; dhsPk = o.dhsPk; dhr = o.dhr
            peerIdent = o.peerIdent; identSk = o.identSk
            ns = o.ns; nr = o.nr; pn = o.pn
            sentFirst = o.sentFirst; recvFirst = o.recvFirst; turnPending = o.turnPending
            initChain = o.initChain
            seq = o.seq
            skipped.clear()
            skipped.putAll(o.skipped)
        }

        fun toJson(): String {
            val o = JSONObject()
            o.put("v", 4)
            o.put("rk0", E2ECrypto.toHex(rk0))
            o.put("rks", E2ECrypto.toHex(rks))
            o.put("cks", if (cks.size == 32) E2ECrypto.toHex(cks) else "")
            o.put("rkr", E2ECrypto.toHex(rkr))
            o.put("ckr", if (ckr.size == 32) E2ECrypto.toHex(ckr) else "")
            o.put("dhs_sk", if (dhsSk.size == 32) E2ECrypto.toHex(dhsSk) else "")
            o.put("dhs_pk", if (dhsPk.size == 32) E2ECrypto.toHex(dhsPk) else "")
            o.put("dhr", if (dhr.size == 32) E2ECrypto.toHex(dhr) else "")
            o.put("peer_ident", E2ECrypto.toHex(peerIdent))
            o.put("ident_sk", E2ECrypto.toHex(identSk))
            o.put("ns", ns); o.put("nr", nr); o.put("pn", pn)
            o.put("sent_first", sentFirst); o.put("recv_first", recvFirst); o.put("turn_pending", turnPending)
            o.put("init_chain", initChain)
            o.put("seq", seq)
            val sk = JSONObject()
            skipped.forEach { (k, v) -> sk.put(k, E2ECrypto.toHex(v)) }
            o.put("skipped", sk)
            return o.toString()
        }

        companion object {
            fun fromJson(s: String): Session? {
                return try {
                    val o = JSONObject(s)
                    if (o.optInt("v") != 4) return null
                    val st = Session()
                    st.rk0 = E2ECrypto.fromHex(o.optString("rk0")) ?: return null
                    st.rks = E2ECrypto.fromHex(o.optString("rks")) ?: return null
                    st.cks = hexOrEmpty(o.optString("cks"))
                    st.rkr = E2ECrypto.fromHex(o.optString("rkr")) ?: return null
                    st.ckr = hexOrEmpty(o.optString("ckr"))
                    st.dhsSk = hexOrEmpty(o.optString("dhs_sk"))
                    st.dhsPk = hexOrEmpty(o.optString("dhs_pk"))
                    st.dhr = hexOrEmpty(o.optString("dhr"))
                    st.peerIdent = E2ECrypto.fromHex(o.optString("peer_ident")) ?: return null
                    st.identSk = E2ECrypto.fromHex(o.optString("ident_sk")) ?: return null
                    st.ns = o.optInt("ns"); st.nr = o.optInt("nr"); st.pn = o.optInt("pn")
                    st.sentFirst = o.optBoolean("sent_first")
                    st.recvFirst = o.optBoolean("recv_first")
                    st.turnPending = o.optBoolean("turn_pending")
                    st.initChain = o.optBoolean("init_chain", false)
                    st.seq = o.optLong("seq")
                    o.optJSONObject("skipped")?.let { sk ->
                        val keys = sk.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = E2ECrypto.fromHex(sk.optString(k)) ?: continue
                            st.skipped[k] = v
                        }
                    }
                    st
                } catch (_: Exception) {
                    null
                }
            }

            private fun hexOrEmpty(s: String): ByteArray =
                if (s.length == 64) E2ECrypto.fromHex(s) ?: ByteArray(0) else ByteArray(0)
        }
    }

    // ---- shared KDF primitives (identical constants to ratchet_x3dh.go) ----

    private fun bytesLess(a: ByteArray, b: ByteArray): Boolean {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xff
            val bi = b[i].toInt() and 0xff
            if (ai != bi) return ai < bi
        }
        return a.size < b.size
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun kdfRk(rk: ByteArray, dh: ByteArray): Pair<ByteArray, ByteArray>? {
        val salt = if (rk.size == 32) rk else ByteArray(32)
        val out = hkdf(dh, salt, ROOT_INFO.toByteArray(), 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = hmac(ck, byteArrayOf(0x01))
        val next = hmac(ck, byteArrayOf(0x02))
        return next to mk
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        val buf = ByteArrayOutputStream()
        var i = 1
        while (buf.size() < n) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            buf.write(t)
            i++
        }
        return buf.toByteArray().copyOf(n)
    }

    private fun skipId(dh: ByteArray, n: Int) = E2ECrypto.toHex(dh) + ":" + n

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }

    private fun getBe32(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)
    }
}
