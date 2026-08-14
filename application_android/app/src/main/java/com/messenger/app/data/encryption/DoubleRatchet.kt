package com.messenger.app.data.encryption

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Direct-chat Double Ratchet (encryption_version=3). Matches back-end/e2ee/ratchet.go.
 * Wire: dh_pk[32] || n_be_u32 || pn_be_u32 || nonce[24] || secretbox(ct).
 */
object DoubleRatchet {
    private const val ROOT_INFO = "messenger-dr-root-v1"
    private const val MAX_SKIP = 200

    class State {
        var dhsSk: ByteArray = ByteArray(0)
        var dhsPk: ByteArray = ByteArray(0)
        var dhr: ByteArray = ByteArray(0)
        var rk: ByteArray = ByteArray(32)
        var cks: ByteArray = ByteArray(0)
        var ckr: ByteArray = ByteArray(0)
        var ns: Int = 0
        var nr: Int = 0
        var pn: Int = 0
        /** Bumped on every local persist; vault merge keeps the higher seq. */
        var seq: Long = 0
        val skipped = HashMap<String, ByteArray>()

        fun toJson(): String {
            val o = JSONObject()
            o.put("dhs_sk", E2ECrypto.toHex(dhsSk))
            o.put("dhs_pk", E2ECrypto.toHex(dhsPk))
            o.put("dhr", if (dhr.size == 32) E2ECrypto.toHex(dhr) else "")
            o.put("rk", E2ECrypto.toHex(rk))
            o.put("cks", if (cks.size == 32) E2ECrypto.toHex(cks) else "")
            o.put("ckr", if (ckr.size == 32) E2ECrypto.toHex(ckr) else "")
            o.put("ns", ns)
            o.put("nr", nr)
            o.put("pn", pn)
            o.put("seq", seq)
            val sk = JSONObject()
            skipped.forEach { (k, v) -> sk.put(k, E2ECrypto.toHex(v)) }
            o.put("skipped", sk)
            return o.toString()
        }

        companion object {
            fun fromJson(s: String): State? {
                return try {
                    val o = JSONObject(s)
                    val st = State()
                    st.dhsSk = E2ECrypto.fromHex(o.optString("dhs_sk")) ?: return null
                    st.dhsPk = E2ECrypto.fromHex(o.optString("dhs_pk")) ?: return null
                    val dhr = o.optString("dhr")
                    st.dhr = if (dhr.length == 64) E2ECrypto.fromHex(dhr) ?: ByteArray(0) else ByteArray(0)
                    st.rk = E2ECrypto.fromHex(o.optString("rk")) ?: ByteArray(32)
                    val cks = o.optString("cks")
                    st.cks = if (cks.length == 64) E2ECrypto.fromHex(cks) ?: ByteArray(0) else ByteArray(0)
                    val ckr = o.optString("ckr")
                    st.ckr = if (ckr.length == 64) E2ECrypto.fromHex(ckr) ?: ByteArray(0) else ByteArray(0)
                    st.ns = o.optInt("ns")
                    st.nr = o.optInt("nr")
                    st.pn = o.optInt("pn")
                    st.seq = o.optLong("seq")
                    val sk = o.optJSONObject("skipped")
                    if (sk != null) {
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
        }
    }

    fun initAlice(theirIk: ByteArray): State? {
        val kp = E2ECrypto.generateKeyPair() ?: return null
        val sk = E2ECrypto.fromHex(kp.privateHex) ?: return null
        val pk = E2ECrypto.fromHex(kp.publicHex) ?: return null
        val dh = E2ECrypto.x25519(sk, theirIk) ?: return null
        val (rk, cks) = kdfRk(ByteArray(32), dh) ?: return null
        val st = State()
        st.dhsSk = sk
        st.dhsPk = pk
        st.dhr = theirIk.copyOf()
        st.rk = rk
        st.cks = cks
        return st
    }

    fun initBob(myIkPk: ByteArray, myIkSk: ByteArray): State {
        val st = State()
        st.dhsSk = myIkSk.copyOf()
        st.dhsPk = myIkPk.copyOf()
        st.rk = ByteArray(32)
        return st
    }

    fun encrypt(st: State, plain: ByteArray): ByteArray? {
        if (st.cks.size != 32 || st.dhsPk.size != 32) return null
        val (next, mk) = kdfCk(st.cks)
        st.cks = next
        val n = st.ns
        st.ns++
        val nonce = ByteArray(24)
        java.security.SecureRandom().nextBytes(nonce)
        val ct = E2ECrypto.secretBoxSeal(plain, nonce, mk) ?: return null
        val out = ByteArray(40 + 24 + ct.size)
        System.arraycopy(st.dhsPk, 0, out, 0, 32)
        putBe32(out, 32, n)
        putBe32(out, 36, st.pn)
        System.arraycopy(nonce, 0, out, 40, 24)
        System.arraycopy(ct, 0, out, 64, ct.size)
        return out
    }

    fun decrypt(st: State, payload: ByteArray): ByteArray? {
        if (payload.size < 40 + 24 + 16) return null
        val dh = payload.copyOfRange(0, 32)
        val n = getBe32(payload, 32)
        val pn = getBe32(payload, 36)
        val nonce = payload.copyOfRange(40, 64)
        val ct = payload.copyOfRange(64, payload.size)
        val id = skipId(dh, n)
        st.skipped.remove(id)?.let { mk ->
            return E2ECrypto.secretBoxOpen(ct, nonce, mk)
        }
        if (st.dhr.size != 32 || !st.dhr.contentEquals(dh)) {
            if (!skipMessageKeys(st, pn) && st.ckr.size == 32) return null
            if (!dhRatchet(st, dh)) return null
        }
        if (!skipMessageKeys(st, n)) return null
        val (next, mk) = kdfCk(st.ckr)
        st.ckr = next
        st.nr++
        return E2ECrypto.secretBoxOpen(ct, nonce, mk)
    }

    private fun skipMessageKeys(st: State, until: Int): Boolean {
        if (st.ckr.size != 32 || st.dhr.size != 32) return until == 0
        if (until <= st.nr) return true
        if (until - st.nr > MAX_SKIP) return false
        while (st.nr < until) {
            val (next, mk) = kdfCk(st.ckr)
            st.ckr = next
            st.skipped[skipId(st.dhr, st.nr)] = mk
            st.nr++
        }
        return true
    }

    private fun dhRatchet(st: State, theirDh: ByteArray): Boolean {
        st.pn = st.ns
        st.ns = 0
        st.nr = 0
        st.dhr = theirDh.copyOf()
        val dh = E2ECrypto.x25519(st.dhsSk, st.dhr) ?: return false
        val (rk, ckr) = kdfRk(st.rk, dh) ?: return false
        st.rk = rk
        st.ckr = ckr
        val kp = E2ECrypto.generateKeyPair() ?: return false
        st.dhsSk = E2ECrypto.fromHex(kp.privateHex) ?: return false
        st.dhsPk = E2ECrypto.fromHex(kp.publicHex) ?: return false
        val dh2 = E2ECrypto.x25519(st.dhsSk, st.dhr) ?: return false
        val (rk2, cks) = kdfRk(st.rk, dh2) ?: return false
        st.rk = rk2
        st.cks = cks
        return true
    }

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

    /** Rank for vault merge: seq first, then send+receive counters. Ties keep [a]. */
    fun preferSessionJson(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        return if (sessionRank(b) > sessionRank(a)) b else a
    }

    fun mergeSessionMaps(local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val out = local.toMutableMap()
        for ((k, v) in remote) {
            out[k] = preferSessionJson(out[k].orEmpty(), v)
        }
        return out
    }

    private fun sessionRank(json: String): Long {
        return try {
            val o = JSONObject(json)
            o.optLong("seq") * 1_000_000L + o.optLong("ns") + o.optLong("nr")
        } catch (_: Exception) {
            0L
        }
    }
}
