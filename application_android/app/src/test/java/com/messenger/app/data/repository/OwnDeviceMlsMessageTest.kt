package com.messenger.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for "Cannot decrypt own messages (code -1)".
 *
 * A history refresh returns this device's own rows too. Before the guard, every
 * refresh fed them to OpenMLS, which rejected each one - the sender's key is
 * dropped at encrypt time for forward secrecy. Harmless but wasteful, and it
 * buried real decrypt failures in noise.
 *
 * This pins the predicate the v6 branch actually calls. It deliberately does NOT
 * assert that `mlsV2.process` goes uncalled: `ChatRepository` builds its
 * `MlsV2Repository` internally via a private `by lazy`, so no seam exists to
 * observe that without changing production wiring, which was out of scope.
 */
class OwnDeviceMlsMessageTest {

    private val thisDevice = "5a454f70-0000-4000-8000-000000000001"
    private val otherDevice = "ead6306e-0000-4000-8000-000000000002"

    @Test
    fun ownDeviceMessageIsSkipped() {
        assertTrue(isOwnDeviceMlsMessage(thisDevice, thisDevice))
    }

    @Test
    fun remoteDeviceMessageStillGoesToMls() {
        assertFalse(
            "a peer's message must still reach the MLS decrypt path",
            isOwnDeviceMlsMessage(otherDevice, thisDevice)
        )
    }

    @Test
    fun anotherDeviceOfTheSameAccountStillGoesToMls() {
        // The reason this is per-device rather than per-user: a sibling device is
        // a separate MLS leaf, so its messages ARE decryptable here. Treating it
        // as "own" would strand them on the cache fallback.
        val siblingDevice = "5a454f70-0000-4000-8000-00000000000b"
        assertFalse(isOwnDeviceMlsMessage(siblingDevice, thisDevice))
    }

    @Test
    fun blankSenderDeviceIsNotOwnership() {
        // Historical rows carry no sender_device_id. Absence is not evidence, so
        // they keep the previous behaviour rather than silently skipping MLS.
        assertFalse(isOwnDeviceMlsMessage("", thisDevice))
    }

    @Test
    fun blankLocalDeviceIsNotOwnership() {
        // Device id unavailable (early startup): never claim a row is ours.
        assertFalse(isOwnDeviceMlsMessage(thisDevice, ""))
        assertFalse(isOwnDeviceMlsMessage("", ""))
    }
}
