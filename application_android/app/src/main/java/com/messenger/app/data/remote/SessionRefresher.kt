package com.messenger.app.data.remote

import android.util.Log
import com.messenger.app.data.model.RefreshTokenRequest
import com.messenger.app.data.model.TokensDto
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The single place a refresh credential is rotated.
 *
 * There were two independent refresh paths before this: the OkHttp
 * [TokenRefreshAuthenticator] on a 401, and [com.messenger.app.data.repository.AuthRepository]
 * called explicitly. Each had its own view of the world, so both could run at
 * once. Under the Gate 17 server that is not merely wasteful - the two would
 * send different request_ids for the same credential, the second would be a new
 * logical refresh, and once the first successor had been spent the server would
 * read the other as a fork and revoke the session. Funnelling both through one
 * object is what makes "at most one logical refresh per session" true rather
 * than hoped for.
 */
@Singleton
class SessionRefresher @Inject constructor(
    private val tokenManager: TokenManager,
    // Provider, not the service: the Retrofit instance that builds AuthApiService
    // is built from the OkHttpClient the authenticator is installed on, so
    // injecting it directly would be a dependency cycle.
    private val authApi: Provider<AuthApiService>
) {

    private companion object {
        const val TAG = "SessionRefresh"
    }

    /**
     * Serialises logical refreshes.
     *
     * Held across the whole operation - read pending state, send, install - so a
     * second caller cannot start its own rotation mid-flight. Waiters do not
     * queue up a second network call: on waking they re-read the stored access
     * token and, finding it already replaced, use it (see [Outcome.AlreadyFresh]).
     *
     * `withLock` releases on exception and on cancellation, so a failure cannot
     * strand waiters.
     */
    private val mutex = Mutex()

    sealed interface Outcome {
        /** This call performed the rotation. */
        data class Rotated(val tokens: TokensDto) : Outcome
        /** Another caller rotated while this one waited; the stored token is current. */
        data class AlreadyFresh(val accessToken: String) : Outcome
        /** The server refused the credential. The session is over. */
        data object SessionOver : Outcome
        /** Transport failure. Pending state is preserved for a same-request_id retry. */
        data object RetryLater : Outcome
    }

    /**
     * @param attemptedAccessToken the token whose use just failed, if known. Lets
     *   a waiter detect that someone else already refreshed instead of rotating
     *   a second time.
     */
    suspend fun refresh(attemptedAccessToken: String? = null): Outcome = mutex.withLock {
        val current = tokenManager.getAccessToken().getOrNull()
        if (!current.isNullOrBlank() && attemptedAccessToken != null && current != attemptedAccessToken) {
            return@withLock Outcome.AlreadyFresh(current)
        }

        val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()

        // Resume an interrupted refresh rather than beginning a new one.
        //
        // If a previous attempt was sent but its reply never arrived, the server
        // may already have consumed that token and cached the successor for 60
        // seconds under the ORIGINAL request_id. Reusing both recovers it. A new
        // id would be a second logical refresh, and once the first successor had
        // been spent the server would treat it as a fork and revoke the session.
        //
        // Scoped to the account: a record left by a previous user must never be
        // replayed on behalf of the current one.
        val pending = tokenManager.getPendingRefresh().getOrNull()
            ?.takeIf { it.userId.isNotEmpty() && it.userId == userId }

        val refreshToken = pending?.refreshToken ?: tokenManager.getRefreshToken().getOrNull()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token stored - session is over")
            return@withLock Outcome.SessionOver
        }
        val requestId = pending?.requestId ?: UUID.randomUUID().toString()

        // Persist BEFORE sending. The window this closes is exactly the one where
        // the server commits and the reply is lost - which the app may not live
        // to see.
        if (pending == null) {
            val saved = tokenManager.savePendingRefresh(userId, refreshToken, requestId)
            if (saved.isFailure) {
                Log.w(TAG, "Could not record pending refresh - not sending")
                return@withLock Outcome.RetryLater
            }
        }

        val response = runCatching {
            authApi.get().refreshToken(RefreshTokenRequest(refreshToken, requestId))
        }.getOrNull()

        val tokens = response?.body()?.tokens
        if (response?.isSuccessful != true || tokens == null) {
            val code = response?.code()
            if (code == null) {
                // Never reached the server, or the reply was lost. KEEP the
                // pending record: the next attempt must retry this same logical
                // refresh with this same request_id.
                Log.w(TAG, "Refresh transport failure - pending state kept for retry")
                return@withLock Outcome.RetryLater
            }
            if (code >= 500) {
                // The server failed; it did not judge the credential. Its
                // transaction rolled back, so the token is still current and may
                // even have a successor waiting in the replay cache. Treat this
                // exactly like a lost reply and KEEP the pending record - the
                // retry must carry the same request_id, or it becomes a second
                // logical refresh and the server will eventually read the pair
                // as a fork.
                Log.w(TAG, "Refresh failed server-side ($code) - pending state kept for retry")
                return@withLock Outcome.RetryLater
            }
            // The server saw the credential and refused it. Retrying cannot help -
            // either the 60s replay window closed or the session is gone - so
            // clear the record rather than hammering the endpoint with an id the
            // server has forgotten.
            Log.w(TAG, "Refresh rejected ($code) - sign-in required")
            tokenManager.clearPendingRefresh()
            return@withLock Outcome.SessionOver
        }

        // Successor installed and pending record cleared in ONE commit.
        val installed = tokenManager.installRefreshedTokens(
            tokens.accessToken,
            tokens.refreshToken,
            System.currentTimeMillis() + tokens.expiresIn * 1000
        )
        if (installed.isFailure) {
            // The pending record survives, so a retry can recover the very same
            // successor from the server's cache.
            Log.w(TAG, "Could not install refreshed tokens - will retry")
            return@withLock Outcome.RetryLater
        }
        Log.d(TAG, "Access token renewed")
        Outcome.Rotated(tokens)
    }

    /**
     * Drops any in-flight refresh belonging to the outgoing session.
     *
     * Called on logout and account switch. Without it a pending record could
     * outlive its account and be replayed on behalf of the next one - the
     * account check in [refresh] already refuses that, but leaving credential
     * material behind after sign-out is its own problem.
     */
    suspend fun invalidatePending() {
        tokenManager.clearPendingRefresh()
    }
}
