package com.messenger.app.data.repository

/**
 * The server rejected our credentials (HTTP 401).
 *
 * Modelled as its own type rather than folded into a generic message so callers
 * can distinguish "this request failed" from "this session is no longer valid"
 * and react by sending the user back to login, instead of leaving them on a
 * screen that can never load.
 */
class SessionExpiredException : Exception("Your session has expired. Please sign in again.")
