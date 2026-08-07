package com.messenger.app.data.remote

import android.util.Log
import com.messenger.app.data.model.RefreshTokenRequest
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Renews an expired access token on a 401 and replays the request.
 *
 * The app has always had a /auth/refresh endpoint and a stored refresh token,
 * but nothing ever called it: the moment the access token expired, every
 * authenticated request began failing with "Invalid or expired token" and the
 * only cure was signing in again by hand. Worse, the failures were silent -
 * a delete or a send simply did nothing.
 *
 * OkHttp calls an Authenticator only after a 401, which is exactly the right
 * hook: no clock-watching, no refreshing tokens that are still perfectly good.
 */
@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val tokenManager: TokenManager,
    // Provider, not the service itself: the Retrofit instance that builds
    // AuthApiService is built from the OkHttpClient this authenticator is
    // installed on, so injecting it directly would be a dependency cycle.
    private val authApi: Provider<AuthApiService>
) : Authenticator {

    private companion object {
        const val TAG = "TokenRefresh"
        /** Requests already carrying a fresh token; a second 401 means give up. */
        const val MAX_RETRIES = 1
    }

    // One refresh at a time. Without this, a screen that fires several
    // requests at once would refresh several times over and the later
    // responses would invalidate the token the earlier ones just stored.
    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never try to refresh a refresh - that way lies an endless loop.
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
        if (responseCount(response) > MAX_RETRIES) return null

        return runBlocking {
            refreshMutex.withLock {
                val current = tokenManager.getAccessToken().getOrNull()
                val attempted = response.request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.trim()

                // Another request may have refreshed while this one queued on
                // the mutex - if the stored token already differs from the one
                // that failed, just replay with it instead of refreshing again.
                if (!current.isNullOrBlank() && current != attempted) {
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer $current")
                        .build()
                }

                val refresh = tokenManager.getRefreshToken().getOrNull()
                if (refresh.isNullOrBlank()) {
                    Log.w(TAG, "No refresh token stored - session is over")
                    return@withLock null
                }

                val renewed = runCatching {
                    authApi.get().refreshToken(RefreshTokenRequest(refresh))
                }.getOrNull()

                val tokens = renewed?.body()?.tokens
                if (renewed?.isSuccessful != true || tokens == null) {
                    // A refresh token the server rejects is genuinely dead;
                    // returning null lets the 401 surface so the UI can send
                    // the user back to sign-in.
                    Log.w(TAG, "Refresh rejected (${renewed?.code()}) - sign-in required")
                    return@withLock null
                }

                tokenManager.saveAccessToken(tokens.accessToken)
                tokenManager.saveRefreshToken(tokens.refreshToken)
                tokenManager.saveAccessTokenExpiresAt(
                    System.currentTimeMillis() + tokens.expiresIn * 1000
                )
                Log.d(TAG, "Access token renewed; replaying request")

                response.request.newBuilder()
                    .header("Authorization", "Bearer ${tokens.accessToken}")
                    .build()
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
