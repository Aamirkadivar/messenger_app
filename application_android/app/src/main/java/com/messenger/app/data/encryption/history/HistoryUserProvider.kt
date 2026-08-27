package com.messenger.app.data.encryption.history

/**
 * The account the history keyring belongs to right now.
 *
 * Bound in production to `TokenManager.getCurrentUserId()`, the same source every
 * other account-scoped decision in the app uses. It exists as a one-method port
 * so the keyring repository can be account-aware without depending on the whole
 * TokenManager surface, and so account switching is directly testable.
 *
 * Returning null means "no signed-in account". That is never treated as a
 * wildcard: without an identity the keyring cache cannot be evaluated, and the
 * repository fails closed rather than guessing.
 */
fun interface HistoryUserProvider {
    suspend fun currentUserId(): String?
}
