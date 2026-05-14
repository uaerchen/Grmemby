package com.grmemby.app.watchparty

import com.grmemby.app.BuildConfig
import java.net.URLEncoder

internal object WatchPartyEndpoints {
    private val baseHttpUrl: String = BuildConfig.GRMEMBY_ROOM_SERVER_URL.trim().trimEnd('/')

    fun httpBaseUrl(): String = baseHttpUrl

    fun voiceWebSocketUrl(roomId: String, memberId: String): String {
        val wsBase = when {
            baseHttpUrl.startsWith("https://", ignoreCase = true) -> "wss://" + baseHttpUrl.removePrefixIgnoreCase("https://")
            baseHttpUrl.startsWith("http://", ignoreCase = true) -> "ws://" + baseHttpUrl.removePrefixIgnoreCase("http://")
            else -> baseHttpUrl
        }.trimEnd('/')
        return "$wsBase/ws/rooms/${roomId.trim()}/voice?memberId=${query(memberId)}"
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
