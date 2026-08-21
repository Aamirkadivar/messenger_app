package com.messenger.app.data.encryption

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlsPolicyTest {

    @Test
    fun lowestKeyWinsCreatorElection() {
        val a = "user-a|dev-1"
        val b = "user-b|dev-1"
        assertTrue(MlsPolicy.isLowest(a, listOf(a, b)))
        assertFalse(MlsPolicy.isLowest(b, listOf(a, b)))
    }

    @Test
    fun emptyCandidateListFailsOpen() {
        assertTrue(MlsPolicy.isLowest("me|dev", emptyList()))
    }

    @Test
    fun onlyLowestLeafMayAdd() {
        val roster = listOf("u1|d1", "u2|d2", "u1|d9")
        assertTrue(MlsPolicy.isElectedAdder("u1|d1", roster))
        assertFalse(MlsPolicy.isElectedAdder("u2|d2", roster))
        assertFalse(MlsPolicy.isElectedAdder("u1|d9", roster))
        assertFalse("non-member must never add", MlsPolicy.isElectedAdder("u9|d9", roster))
        assertFalse("empty roster: do not invite", MlsPolicy.isElectedAdder("u1|d1", emptyList()))
    }

    @Test
    fun sendWaitsForEveryOtherDeviceToAckWelcome() {
        val live = listOf("me-dev", "phone", "pc")
        assertFalse(
            "adder's own tree is not proof the others can decrypt",
            MlsPolicy.readyToSend(true, "me-dev", live, setOf("phone"), 2, 2)
        )
        assertTrue(
            MlsPolicy.readyToSend(true, "me-dev", live, setOf("phone", "pc"), 2, 2)
        )
        assertFalse(
            "epoch mismatch means we are behind or forked",
            MlsPolicy.readyToSend(true, "me-dev", live, setOf("phone", "pc"), 1, 2)
        )
        assertFalse(MlsPolicy.readyToSend(false, "me-dev", live, setOf("phone", "pc"), 2, 2))
    }

    @Test
    fun missingDevicesSkipThisInstall() {
        assertEquals(
            listOf("pc"),
            MlsPolicy.missingLiveDevices("me-dev", listOf("me-dev", "pc"), emptySet())
        )
        assertTrue(
            MlsPolicy.missingLiveDevices("me-dev", listOf("me-dev"), emptySet()).isEmpty()
        )
    }
}
