package com.messenger.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messenger.app.data.call.CallRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Handles the "Decline" action on the incoming-call notification, so a call can
 * be turned down straight from the lock screen without opening the app.
 *
 * Answering deliberately does *not* come through here - it launches
 * MainActivity instead, because accepting may need to prompt for the microphone
 * permission and only an Activity can do that.
 *
 * The dependency is pulled through an entry point rather than @AndroidEntryPoint
 * field injection: for a receiver Hilt only injects inside the generated
 * super.onReceive(), which Kotlin cannot call because BroadcastReceiver
 * declares onReceive abstract. Without that call the field stays uninitialised
 * and every Decline throws instead of hanging up.
 */
class CallActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallActionEntryPoint {
        fun callRepository(): CallRepository
    }

    companion object {
        const val ACTION_DECLINE = "com.messenger.app.action.DECLINE_CALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        EntryPointAccessors
            .fromApplication(context.applicationContext, CallActionEntryPoint::class.java)
            .callRepository()
            .rejectCall()
    }
}
