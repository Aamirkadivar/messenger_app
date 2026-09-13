package com.messenger.app.data.remote

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
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
    private val refresher: SessionRefresher
) : Authenticator {

    private companion object {
        const val TAG = "TokenRefresh"
        /** Requests already carrying a fresh token; a second 401 means give up. */
        const val MAX_RETRIES = 1
    }


    override fun authenticate(route: Route?, response: Response): Request? {
        // Never try to refresh a refresh - that way lies an endless loop.
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
        if (responseCount(response) > MAX_RETRIES) return null

        return runBlocking {
            val attempted = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()

            // All rotation goes through SessionRefresher, which owns the
            // single-flight lock and the request_id lifecycle. This class only
            // decides whether to replay the request.
            when (val outcome = refresher.refresh(attempted)) {
                is SessionRefresher.Outcome.Rotated ->
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${outcome.tokens.accessToken}")
                        .build()

                is SessionRefresher.Outcome.AlreadyFresh ->
                    // Someone else refreshed while this request queued; replay
                    // with what they stored rather than rotating again.
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${outcome.accessToken}")
                        .build()

                // Returning null lets the 401 surface so the UI can route to
                // sign-in. RetryLater keeps the pending record, so the next 401
                // resumes the same logical refresh.
                SessionRefresher.Outcome.SessionOver,
                SessionRefresher.Outcome.RetryLater -> null
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
