package com.messenger.app.data.storage

import android.content.Context
import android.util.Log
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.settings.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/** Storage attributable to a single conversation. */
data class ConversationStorage(
    val chatId: String,
    val chatName: String,
    val bytes: Long,
    val byKind: Map<MediaKind, Long>
)

data class StorageReport(
    val byKind: Map<MediaKind, Long>,
    val cacheBytes: Long,
    val perConversation: List<ConversationStorage>
) {
    val mediaBytes: Long get() = byKind.values.sum()

    /** Cache counts toward the total the user sees - it is space they can reclaim. */
    val totalBytes: Long get() = mediaBytes + cacheBytes
}

sealed interface StorageScanState {
    data object Idle : StorageScanState
    data object Scanning : StorageScanState
    data class Ready(val report: StorageReport) : StorageScanState
    data class Failed(val message: String) : StorageScanState
}

/**
 * Measures on-disk usage and reclaims it.
 *
 * Scanning walks the whole media tree and is slow enough to matter, so it runs
 * on [Dispatchers.IO] inside an **application-scoped** coroutine rather than a
 * ViewModel scope. That is deliberate: the user can navigate away mid-scan and
 * the work continues, with the result waiting in [state] when they return.
 * A second scan request while one is in flight is ignored rather than queued.
 */
class StorageAnalyzer(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val TAG = "StorageAnalyzer"

        /** Media root. Subdirectories are created by the media pipeline as it saves files. */
        private const val MEDIA_DIR = "media"
        private const val CHATS_DIR = "chats"
    }

    private val _state = MutableStateFlow<StorageScanState>(StorageScanState.Idle)
    val state: StateFlow<StorageScanState> = _state.asStateFlow()

    private var scanJob: Job? = null

    /** Starts a scan unless one is already running. Returns immediately. */
    fun scan(force: Boolean = false) {
        if (scanJob?.isActive == true) return
        if (!force && _state.value is StorageScanState.Ready) return

        _state.value = StorageScanState.Scanning
        scanJob = appScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { buildReport() }
                _state.value = StorageScanState.Ready(report)
            } catch (e: Exception) {
                Log.e(TAG, "Storage scan failed", e)
                _state.value = StorageScanState.Failed(
                    e.message ?: "Could not calculate storage use"
                )
            }
        }
    }

    private suspend fun buildReport(): StorageReport {
        val mediaRoot = File(context.filesDir, MEDIA_DIR)

        val byKind = MediaKind.entries.associateWith { kind ->
            File(mediaRoot, kind.id).sizeRecursive()
        }

        val cacheBytes = context.cacheDir.sizeRecursive()

        // Per-conversation totals, named from the cached chat list where possible.
        val names = runCatching {
            chatRepository.loadCachedChats().associate { it.id to it.displayName() }
        }.getOrDefault(emptyMap())

        val chatsRoot = File(mediaRoot, CHATS_DIR)
        val perConversation = (chatsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .map { dir ->
                val perKind = MediaKind.entries.associateWith { kind ->
                    File(dir, kind.id).sizeRecursive()
                }
                ConversationStorage(
                    chatId = dir.name,
                    chatName = names[dir.name] ?: "Unknown conversation",
                    bytes = perKind.values.sum(),
                    byKind = perKind
                )
            }
            .filter { it.bytes > 0 }
            .sortedByDescending { it.bytes }

        return StorageReport(
            byKind = byKind,
            cacheBytes = cacheBytes,
            perConversation = perConversation
        )
    }

    /**
     * Deletes cached files only.
     *
     * Scoped strictly to [Context.cacheDir]. The Room database lives under the
     * app's `databases/` directory and is never reachable from here, so clearing
     * the cache cannot destroy messages.
     */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = context.cacheDir.sizeRecursive()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val freed = before - context.cacheDir.sizeRecursive()
        scan(force = true)
        freed
    }

    /** Deletes one conversation's stored media. Its messages are untouched. */
    suspend fun clearConversationMedia(chatId: String): Long = withContext(Dispatchers.IO) {
        val dir = File(File(context.filesDir, MEDIA_DIR), "$CHATS_DIR/$chatId")
        val freed = dir.sizeRecursive()
        dir.deleteRecursively()
        scan(force = true)
        freed
    }

    /**
     * Recursive size in bytes. Cooperatively cancellable - a deep tree would
     * otherwise keep running after the caller has gone away.
     */
    private suspend fun File.sizeRecursive(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        listFiles()?.forEach { child ->
            yield()
            total += child.sizeRecursive()
        }
        return total
    }
}

private fun com.messenger.app.data.model.ChatListItemDto.displayName(): String =
    otherUser?.displayName?.takeIf { it.isNotBlank() }
        ?: otherUser?.username
        ?: name.takeIf { it.isNotBlank() }
        ?: "Unknown"

/** Human-readable byte count, e.g. "1.4 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return when {
        unit == 0 -> "${value.toInt()} ${units[unit]}"
        value >= 100 -> "${value.toInt()} ${units[unit]}"
        value >= 10 -> String.format("%.1f %s", value, units[unit])
        else -> String.format("%.1f %s", value, units[unit])
    }
}
