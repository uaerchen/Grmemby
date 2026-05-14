package com.grmemby.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun remoteOlderSemanticVersionDoesNotUpdateEvenWhenRemoteVersionCodeIsLarger() {
        assertFalse(
            isRemoteVersionNewer(
                remoteVersionName = "v1.0",
                remoteVersionCode = 100,
                currentVersionName = "1.3.0",
                currentVersionCode = 19
            )
        )
    }

    @Test
    fun remoteNewerSemanticVersionUpdatesEvenWhenRemoteVersionCodeIsLower() {
        assertTrue(
            isRemoteVersionNewer(
                remoteVersionName = "v1.4.0",
                remoteVersionCode = 20,
                currentVersionName = "1.3.0",
                currentVersionCode = 99
            )
        )
    }

    @Test
    fun equalSemanticVersionFallsBackToVersionCode() {
        assertTrue(
            isRemoteVersionNewer(
                remoteVersionName = "v1.3",
                remoteVersionCode = 20,
                currentVersionName = "1.3.0",
                currentVersionCode = 19
            )
        )
    }
}
