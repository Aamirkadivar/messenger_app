package com.messenger.app.data.encryption.history

/**
 * One (chatId, rootVersion) -> historyRoot binding.
 *
 * A chat may hold several versions simultaneously: after a rotation, older archived messages still
 * need their older root to open, so versions accumulate rather than replace.
 */
class HistoryRootEntry(
    val chatId: String,
    val rootVersion: Int,
    val root: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistoryRootEntry) return false
        return chatId == other.chatId &&
            rootVersion == other.rootVersion &&
            root.contentEquals(other.root)
    }

    override fun hashCode(): Int =
        (chatId.hashCode() * 31 + rootVersion) * 31 + root.contentHashCode()

    /** Never render root material, including accidentally through logging or debuggers. */
    override fun toString(): String = "HistoryRootEntry(chatId=" + chatId +
        ", rootVersion=" + rootVersion + ", root=<redacted 32B>)"
}

/**
 * Minimal versioned keyring for history roots.
 *
 * Deliberately small: Gate 2 defines only the representation. How it is sealed, where it is stored,
 * and when roots rotate are Gate 3 decisions, so nothing here touches the vault, Room, or the
 * network. The representation has no field for a password, an MLS secret, or a device identity
 * private key, and must never gain one - those belong to different trust domains.
 *
 * Wire format (all integers fixed-width big-endian):
 *
 *     u8    formatVersion            (must be 1)
 *     u32be entryCount
 *     repeated entryCount times:
 *       u32be chatIdLen
 *       bytes chatId                 (UTF-8, non-empty)
 *       u32be rootVersion            (>= 1)
 *       u8    rootLen                (must be exactly 32)
 *       bytes root
 *
 * Entries are sorted by (chatId, rootVersion) before encoding, so serialisation is canonical:
 * the same set of entries always produces byte-identical output regardless of insertion order.
 */
class HistoryKeyring private constructor(val entries: List<HistoryRootEntry>) {

    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(FORMAT_VERSION)
        out.write(HistoryCrypto.u32be(entries.size))
        for (e in entries) {
            val chat = e.chatId.toByteArray(Charsets.UTF_8)
            out.write(HistoryCrypto.u32be(chat.size))
            out.write(chat)
            out.write(HistoryCrypto.u32be(e.rootVersion))
            out.write(e.root.size)
            out.write(e.root)
        }
        return out.toByteArray()
    }

    /** Highest known version for a chat, or null if the chat has no root. */
    fun latest(chatId: String): HistoryRootEntry? =
        entries.filter { it.chatId == chatId }.maxByOrNull { it.rootVersion }

    fun find(chatId: String, rootVersion: Int): HistoryRootEntry? =
        entries.firstOrNull { it.chatId == chatId && it.rootVersion == rootVersion }

    override fun equals(other: Any?): Boolean =
        this === other || (other is HistoryKeyring && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        const val FORMAT_VERSION = 1

        /** Validates and canonicalises. Every rejection below is also a decode-time rejection. */
        fun of(entries: List<HistoryRootEntry>): Result<HistoryKeyring> {
            val seen = HashSet<Pair<String, Int>>()
            for (e in entries) {
                if (e.chatId.isEmpty()) {
                    return fail("chatId must not be empty")
                }
                if (e.rootVersion < 1) {
                    return fail("rootVersion must be >= 1, was " + e.rootVersion)
                }
                if (e.root.size != HistoryCrypto.ROOT_BYTES) {
                    return fail(
                        "history root must be exactly " + HistoryCrypto.ROOT_BYTES +
                            " bytes, was " + e.root.size
                    )
                }
                if (!seen.add(e.chatId to e.rootVersion)) {
                    return fail(
                        "duplicate (chatId, rootVersion) entry for version " + e.rootVersion
                    )
                }
            }
            val canonical = entries.sortedWith(
                compareBy({ it.chatId }, { it.rootVersion })
            )
            return Result.success(HistoryKeyring(canonical))
        }

        fun decode(bytes: ByteArray): Result<HistoryKeyring> {
            val r = Reader(bytes)
            try {
                val version = r.u8()
                if (version != FORMAT_VERSION) {
                    return fail("unsupported keyring format version " + version)
                }
                val count = r.u32()
                if (count < 0) return fail("negative entry count")
                // Each entry needs at least 4 + 1 + 4 + 1 + 32 bytes; reject absurd counts before
                // allocating, so a corrupt length cannot drive a huge allocation.
                if (count.toLong() * 9L > bytes.size.toLong()) {
                    return fail("entry count " + count + " exceeds available bytes")
                }
                val parsed = ArrayList<HistoryRootEntry>(count)
                repeat(count) {
                    val chatId = String(r.bytes(r.u32()), Charsets.UTF_8)
                    val rootVersion = r.u32()
                    val rootLen = r.u8()
                    val root = r.bytes(rootLen)
                    parsed.add(HistoryRootEntry(chatId, rootVersion, root))
                }
                if (r.remaining() != 0) {
                    return fail("trailing bytes after keyring: " + r.remaining())
                }
                return of(parsed)
            } catch (e: IllegalArgumentException) {
                return Result.failure(e)
            }
        }

        private fun fail(message: String): Result<HistoryKeyring> =
            Result.failure(IllegalArgumentException(message))
    }

    /** Strict big-endian reader; every overrun is an explicit failure, never a silent clamp. */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0

        fun remaining(): Int = buf.size - pos

        fun u8(): Int {
            need(1)
            return buf[pos++].toInt() and 0xFF
        }

        fun u32(): Int {
            need(4)
            val v = ((buf[pos].toInt() and 0xFF) shl 24) or
                ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                (buf[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0) throw IllegalArgumentException("negative length " + n)
            need(n)
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        private fun need(n: Int) {
            if (remaining() < n) {
                throw IllegalArgumentException(
                    "truncated keyring: need " + n + " bytes, have " + remaining()
                )
            }
        }
    }
}
