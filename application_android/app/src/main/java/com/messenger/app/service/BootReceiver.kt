package com.messenger.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messenger.app.security.TokenManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Restarts the real-time connection after a reboot so a signed-in user can be
 * called without having to open the app first.
 *
 * BOOT_COMPLETED is one of the cases still allowed to start a foreground
 * service from the background on Android 12+.
 *
 * Uses an entry point rather than @AndroidEntryPoint for the same reason as
 * [CallActionReceiver].
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun tokenManager(): TokenManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val tokenManager = EntryPointAccessors
            .fromApplication(context.applicationContext, BootEntryPoint::class.java)
            .tokenManager()
        if (!tokenManager.isAuthenticated()) return
        RealtimeService.start(context)
    }
}
