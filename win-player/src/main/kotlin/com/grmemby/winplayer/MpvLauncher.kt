package com.grmemby.winplayer

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID

private const val MPV_PIPE_PREFIX = "grmemby-mpv-"

data class MpvLaunchSpec(
    val command: List<String>,
    val skippedHeaders: List<String> = emptyList(),
    val ipcServer: String? = null
)

object MpvCommandBuilder {
    fun build(
        mpvPath: String,
        playback: ResolvedPlayback,
        startPaused: Boolean = false,
        ipcServer: String? = null,
        startPositionMs: Long? = null
    ): MpvLaunchSpec {
        val command = mutableListOf(
            mpvPath.ifBlank { "mpv.exe" },
            "--force-window=yes",
            "--title=Grmemby - ${playback.displayTitle}",
            "--really-quiet"
        )
        if (startPaused) command += "--pause=yes"
        if (ipcServer != null) command += "--input-ipc-server=$ipcServer"
        startPositionMs?.takeIf { it > 0L }?.let { command += "--start=${it / 1000.0}" }

        val headerFields = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        playback.requiredHeaders.forEach { (name, value) ->
            when {
                name.equals("User-Agent", ignoreCase = true) -> command += "--user-agent=$value"
                name.equals("Referer", ignoreCase = true) || name.equals("Referrer", ignoreCase = true) -> command += "--referrer=$value"
                ',' in value -> skipped += name
                else -> headerFields += "$name: $value"
            }
        }
        if (headerFields.isNotEmpty()) {
            command += "--http-header-fields=${headerFields.joinToString(",")}"
        }

        command += playback.url
        return MpvLaunchSpec(command = command, skippedHeaders = skipped, ipcServer = ipcServer)
    }

    fun newIpcServerName(): String = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "\\\\.\\pipe\\$MPV_PIPE_PREFIX${UUID.randomUUID()}"
    } else {
        File(System.getProperty("java.io.tmpdir"), "$MPV_PIPE_PREFIX${UUID.randomUUID()}.sock").absolutePath
    }
}

class MpvLauncher {
    fun launch(spec: MpvLaunchSpec): Process {
        val executable = spec.command.firstOrNull().orEmpty()
        if (executable.contains(File.separator) && !File(executable).exists()) {
            error("mpv executable not found: $executable")
        }
        return ProcessBuilder(spec.command)
            .redirectErrorStream(true)
            .start()
    }
}

class MpvSession(
    private val launcher: MpvLauncher = MpvLauncher()
) {
    private var process: Process? = null
    private var ipcServer: String? = null
    private var startedAtMs: Long = 0L
    private var basePositionMs: Long = 0L
    private var playing = false

    fun start(
        mpvPath: String,
        playback: ResolvedPlayback,
        startPaused: Boolean = false,
        startPositionMs: Long = 0L
    ): MpvLaunchSpec {
        stop()
        val ipc = MpvCommandBuilder.newIpcServerName()
        val spec = MpvCommandBuilder.build(
            mpvPath = mpvPath,
            playback = playback,
            startPaused = startPaused,
            ipcServer = ipc,
            startPositionMs = startPositionMs
        )
        process = launcher.launch(spec)
        ipcServer = ipc
        basePositionMs = startPositionMs
        startedAtMs = System.currentTimeMillis()
        playing = !startPaused
        return spec
    }

    fun play() {
        command("{\"command\":[\"set_property\",\"pause\",false]}\n")
        startedAtMs = System.currentTimeMillis()
        playing = true
    }

    fun pause() {
        basePositionMs = currentPositionMs()
        command("{\"command\":[\"set_property\",\"pause\",true]}\n")
        playing = false
    }

    fun seek(positionMs: Long) {
        basePositionMs = positionMs.coerceAtLeast(0L)
        startedAtMs = System.currentTimeMillis()
        command("{\"command\":[\"set_property\",\"time-pos\",${basePositionMs / 1000.0}]}\n")
    }

    fun stop() {
        runCatching { command("{\"command\":[\"quit\"]}\n") }
        runCatching { process?.destroy() }
        process = null
        ipcServer = null
        playing = false
    }

    fun currentPositionMs(): Long {
        val elapsed = if (playing) System.currentTimeMillis() - startedAtMs else 0L
        return (basePositionMs + elapsed).coerceAtLeast(0L)
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun command(jsonLine: String) {
        val target = ipcServer ?: return
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            OutputStreamWriter(FileOutputStream(target), Charsets.UTF_8).use { it.write(jsonLine) }
        } else {
            val address = UnixDomainSocketAddress.of(target)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(address)
                Channels.newWriter(channel, Charsets.UTF_8).use { it.write(jsonLine) }
            }
        }
    }
}
