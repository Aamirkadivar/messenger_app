package com.messenger.app.data.local

import android.content.Context
import com.messenger.app.security.TokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Raised when a cache write cannot be attributed to the account that is
 * authenticated right now. Carries no account identifiers: the message is for a
 * log line, and the identifiers are the thing being protected.
 */
class CacheOwnershipViolation(message: String) : IllegalStateException(message)

/**
 * Owns the one cache database handle that may be open, and binds it to the
 * account that is authenticated right now.
 *
 * This is the security boundary. Before Gate 19 a single process-wide Room
 * database served every account, so any DAO query - enumeration, lookup by
 * conversation id, lookup by message id - could return rows written by a
 * previous account. Filtering those queries would have made isolation a matter
 * of remembering a predicate in thirty-odd places. Here the account decides
 * which FILE is opened, so a query cannot reach another account's rows: they
 * are not in the database it is querying.
 *
 * READS resolve the current account ([current]) - a stale reader simply sees
 * whoever is signed in now, which is correct.
 *
 * WRITES additionally require the account that owned the operation when it
 * STARTED ([requireOwnedBy]). Resolving the account at commit time was not
 * enough: a network reply or WebSocket delivery that began under account A and
 * completed after a switch to B wrote A's rows into B's database. Ownership is
 * therefore captured before the asynchronous work begins and verified here,
 * immediately before the handle is handed out.
 */
@Singleton
class AccountCacheHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    // Provider, not the instance: TokenManagerImpl is itself built from the
    // graph this holder participates in.
    private val tokenManager: Provider<TokenManager>
) {

    private val mutex = Mutex()
    private var openName: String? = null
    private var openDb: AuthDatabase? = null

    /** The account authenticated right now, trimmed, or null. */
    private suspend fun activeAccount(): String? =
        tokenManager.get().getCurrentUserId().getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The current account's database, or null when nobody is authenticated.
     *
     * Null is the honest answer for "no account": there is no cache to read,
     * and inventing one would mean choosing whose.
     */
    suspend fun current(): AuthDatabase? {
        val accountId = activeAccount()
        if (accountId == null) {
            closeIfOpen()
            return null
        }
        return openFor(accountId)
    }

    /**
     * The current account's database, but only if [owner] is still that account.
     *
     * [owner] is the account captured when the operation began, not whoever is
     * signed in at commit time. Three outcomes, all fail-closed:
     *
     *  - no account authenticated  -> refuse
     *  - owner != active account   -> refuse
     *  - owner == active account   -> the ACTIVE account's database
     *
     * The last line matters: this never opens a database chosen by [owner].
     * Doing so would let a late callback from account A reopen A's namespace
     * behind B's back, which is a different leak rather than a fix. The only
     * database this can ever return is the active account's, and only when the
     * operation demonstrably belongs to it.
     */
    suspend fun requireOwnedBy(owner: String): AuthDatabase {
        val captured = owner.trim()
        if (captured.isEmpty()) {
            throw CacheOwnershipViolation("refusing a cache write with no owning account")
        }
        val active = activeAccount()
            ?: throw CacheOwnershipViolation(
                "refusing a cache write while no account is authenticated"
            )
        // Case is NOT folded, matching AccountCacheNamespace: two distinct ids
        // must never compare equal.
        if (captured != active) {
            throw CacheOwnershipViolation(
                "refusing a cache write whose owning session is no longer active"
            )
        }
        return openFor(active)
    }

    private suspend fun openFor(accountId: String): AuthDatabase {
        val wanted = AccountCacheNamespace.databaseNameFor(accountId)
        mutex.withLock {
            val existing = openDb
            if (existing != null && openName == wanted) return existing
            // The account changed (or this is the first access). Release the
            // previous account's file before touching the new one, so two
            // namespaces are never open at once.
            runCatching { existing?.close() }
            val db = AuthDatabase.getDatabase(context, wanted)
            openDb = db
            openName = wanted
            return db
        }
    }

    /**
     * Releases the current handle. Called on logout.
     *
     * This does NOT delete anything. The account's cache is its offline
     * history; logging out ends access to it, not its existence.
     */
    suspend fun deactivate() = mutex.withLock { closeLocked() }

    private suspend fun closeIfOpen() {
        if (openDb != null) mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { openDb?.close() }
        openDb = null
        openName = null
    }

    /** Test/diagnostic view of which namespace is currently open, if any. */
    fun openNamespaceOrNull(): String? = openName
}
