package com.grmemby.app.auth

import com.grmemby.app.ui.screens.auth.keepAliveErrorUserMessage
import com.grmemby.app.ui.screens.auth.isKeepAliveDnsResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class KeepAliveErrorMessageTest {
    @Test
    fun dnsFailureFromAndroidMessageIsClassifiedAndSimplified() {
        val error = Exception(
            "Unable to resolve host \"imagestatic.9club.help\": No address associated with hostname"
        )

        assertTrue(isKeepAliveDnsResolutionFailure(error))
        assertEquals(
            "DNS 解析失败，请检查当前网络/私人 DNS/线路域名",
            keepAliveErrorUserMessage(error)
        )
    }

    @Test
    fun dnsFailureFromNestedUnknownHostExceptionIsClassified() {
        val error = Exception("Playback stream request failed", UnknownHostException("emos.best"))

        assertTrue(isKeepAliveDnsResolutionFailure(error))
        assertEquals(
            "DNS 解析失败，请检查当前网络/私人 DNS/线路域名",
            keepAliveErrorUserMessage(error)
        )
    }

    @Test
    fun nonDnsPlaybackFailureKeepsPlaybackSpecificMessage() {
        val error = Exception("Playback stream request failed: http 404 token=secret-value")

        assertFalse(isKeepAliveDnsResolutionFailure(error))
        assertEquals(
            "播放流请求失败: http 404 token=[REDACTED]",
            keepAliveErrorUserMessage(error)
        )
    }
}
