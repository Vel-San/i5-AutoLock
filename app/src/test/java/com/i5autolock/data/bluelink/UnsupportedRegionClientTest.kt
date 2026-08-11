package com.i5autolock.data.bluelink

import com.i5autolock.data.bluelink.model.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedRegionClientTest {

    @Test
    fun allOperationsFailClearly() = runTest {
        val client: BlueLinkClient = UnsupportedRegionClient(Region.US)
        assertFalse(client.isAuthenticated())
        assertFalse(client.ensureFreshSession())
        assertTrue(client.vehicles().isEmpty())
        assertTrue(client.lock("v") is CommandResult.Failure)
        assertTrue(client.unlock("v") is CommandResult.Failure)
        assertTrue(client.loginWithPassword("a", "b") is CommandResult.Failure)
    }
}
