package com.grmemby.winplayer

import java.io.File
import java.util.Properties

/** Persisted Windows desktop settings. Passwords/tokens/signed URLs are never saved. */
data class WinPlayerConfig(
    val serverUrl: String = "",
    val username: String = "",
    val itemId: String = "",
    val mpvPath: String = "mpv.exe",
    val roomServerUrl: String = "http://127.0.0.1:8080",
    val displayName: String = System.getProperty("user.name", "Windows用户"),
    val maxStreamingBitrate: Int = 120_000_000,
    val deviceId: String = DeviceIdentity.defaultDeviceId()
)

object DeviceIdentity {
    fun defaultDeviceId(): String = "grmemby-win64-${stableMachineSuffix()}"

    private fun stableMachineSuffix(): String {
        val user = System.getProperty("user.name").orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").orEmpty()
        return Integer.toUnsignedString("$user|$home|$os".hashCode(), 16)
    }
}

class WinPlayerConfigStore(
    private val file: File = defaultConfigFile()
) {
    fun load(): WinPlayerConfig {
        if (!file.isFile) return WinPlayerConfig()
        val props = Properties()
        file.inputStream().use(props::load)
        return WinPlayerConfig(
            serverUrl = props.getProperty("serverUrl", ""),
            username = props.getProperty("username", ""),
            itemId = props.getProperty("itemId", ""),
            mpvPath = props.getProperty("mpvPath", "mpv.exe"),
            roomServerUrl = props.getProperty("roomServerUrl", "http://127.0.0.1:8080"),
            displayName = props.getProperty("displayName", System.getProperty("user.name", "Windows用户")),
            maxStreamingBitrate = props.getProperty("maxStreamingBitrate")?.toIntOrNull() ?: 120_000_000,
            deviceId = props.getProperty("deviceId", DeviceIdentity.defaultDeviceId())
        )
    }

    fun save(config: WinPlayerConfig) {
        file.parentFile?.mkdirs()
        val props = Properties().apply {
            setProperty("serverUrl", config.serverUrl)
            setProperty("username", config.username)
            setProperty("itemId", config.itemId)
            setProperty("mpvPath", config.mpvPath)
            setProperty("roomServerUrl", config.roomServerUrl)
            setProperty("displayName", config.displayName)
            setProperty("maxStreamingBitrate", config.maxStreamingBitrate.toString())
            setProperty("deviceId", config.deviceId)
        }
        file.outputStream().use { props.store(it, "Grmemby Windows settings - no password/token stored") }
    }

    companion object {
        fun defaultConfigFile(): File {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            val root = appData?.let(::File)
                ?: File(System.getProperty("user.home", "."), ".grmemby-win")
            return File(root, "Grmemby/player.properties")
        }
    }
}
