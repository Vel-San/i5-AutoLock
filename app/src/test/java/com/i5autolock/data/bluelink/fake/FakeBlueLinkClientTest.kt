package com.i5autolock.data.bluelink.fake

import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.bluelink.model.LockState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBlueLinkClientTest {

    @Test
    fun locksAnUnlockedVehicle() = runTest {
        val client = FakeBlueLinkClient()
        client.login("demo", "demo")

        val before = client.status("demo-ioniq5", forceRefresh = true)
        assertEquals(LockState.UNLOCKED, before.lockState)

        val result = client.lock("demo-ioniq5")
        assertTrue(result is CommandResult.Success)

        val after = client.status("demo-ioniq5", forceRefresh = true)
        assertEquals(LockState.LOCKED, after.lockState)
    }

    @Test
    fun clearSessionDeauthenticates() = runTest {
        val client = FakeBlueLinkClient()
        client.login("demo", "demo")
        assertTrue(client.isAuthenticated())
        client.clearSession()
        assertFalse(client.isAuthenticated())
    }
}
