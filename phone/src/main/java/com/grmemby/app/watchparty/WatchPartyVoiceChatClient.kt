package com.grmemby.app.watchparty

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

@Serializable
private data class VoiceFrameDto(
    val type: String = "voice",
    val senderId: String,
    val payload: String,
    val seq: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class WatchPartyVoiceChatState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isSpeaking: Boolean = false,
    val codecLabel: String = "",
    val errorMessage: String? = null
)

class WatchPartyVoiceChatClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val sequence = AtomicLong(0L)
    private val _state = MutableStateFlow(WatchPartyVoiceChatState())
    private val mixer = VoiceFrameMixer()
    private val decoders = ConcurrentHashMap<String, VoicePayloadCodec>()

    val state: StateFlow<WatchPartyVoiceChatState> = _state.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var automaticGainControl: AutomaticGainControl? = null
    @Volatile private var recordingJob: Job? = null
    @Volatile private var playbackJob: Job? = null
    @Volatile private var localMemberId: String? = null
    @Volatile private var activeRoomId: String? = null
    @Volatile private var speakWhenConnected: Boolean = false
    @Volatile private var activeEncoder: VoicePayloadCodec? = null
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    fun startListening(context: Context, session: ActiveWatchPartySession?) {
        startListening(context, session, startSpeakingWhenConnected = false)
    }

    fun toggleMicrophone(context: Context, session: ActiveWatchPartySession?) {
        if (_state.value.isSpeaking) {
            stopRecorderOnly()
            _state.value = _state.value.copy(isSpeaking = false, errorMessage = null)
            return
        }

        val activeSession = session?.takeIf { it.roomId.isNotBlank() && it.memberId.isNotBlank() }
        if (activeSession == null) {
            _state.value = _state.value.copy(errorMessage = "请先加入一起看房间")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = _state.value.copy(errorMessage = "需要麦克风权限才能开启语音聊天")
            return
        }

        routeVoiceToSpeaker(context.applicationContext)
        val socket = webSocket
        if (_state.value.isConnected && socket != null && activeSession.memberId == localMemberId) {
            startRecorder(socket, activeSession.memberId)
        } else {
            startListening(context, activeSession, startSpeakingWhenConnected = true)
        }
    }

    fun stop() = stop(closeState = true)

    private fun startListening(
        context: Context,
        session: ActiveWatchPartySession?,
        startSpeakingWhenConnected: Boolean
    ) {
        val activeSession = session?.takeIf { it.roomId.isNotBlank() && it.memberId.isNotBlank() }
        if (activeSession == null) {
            _state.value = WatchPartyVoiceChatState(errorMessage = "请先加入一起看房间")
            return
        }

        routeVoiceToSpeaker(context.applicationContext)
        if (
            (state.value.isConnected || state.value.isConnecting) &&
            activeRoomId == activeSession.roomId &&
            localMemberId == activeSession.memberId
        ) {
            speakWhenConnected = speakWhenConnected || startSpeakingWhenConnected
            return
        }

        stop(closeState = false)
        routeVoiceToSpeaker(context.applicationContext)
        activeRoomId = activeSession.roomId
        localMemberId = activeSession.memberId
        speakWhenConnected = startSpeakingWhenConnected
        _state.value = WatchPartyVoiceChatState(isConnecting = true)

        val url = WatchPartyEndpoints.voiceWebSocketUrl(activeSession.roomId, activeSession.memberId)
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = WatchPartyVoiceChatState(isConnected = true, codecLabel = PreferredCodecLabel)
                startPlaybackSink()
                if (speakWhenConnected) {
                    speakWhenConnected = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecorder(webSocket, activeSession.memberId)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                playLegacyTextFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                playBinaryFrame(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = WatchPartyVoiceChatState(errorMessage = "语音聊天连接失败，已断开")
                stop(closeState = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = WatchPartyVoiceChatState()
                stop(closeState = false)
            }
        })
    }

    private fun stop(closeState: Boolean) {
        speakWhenConnected = false
        stopRecorderOnly()
        playbackJob?.cancel()
        playbackJob = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        mixer.clear()
        decoders.values.forEach { it.release() }
        decoders.clear()
        runCatching { webSocket?.close(1000, "voice chat stopped") }
        webSocket = null
        localMemberId = null
        activeRoomId = null
        restoreVoiceRoute()
        if (closeState) _state.value = WatchPartyVoiceChatState()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun routeVoiceToSpeaker(context: Context) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager !== manager) {
            previousAudioMode = manager.mode
            @Suppress("DEPRECATION")
            previousSpeakerphoneOn = manager.isSpeakerphoneOn
            audioManager = manager
        }
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        @Suppress("DEPRECATION")
        runCatching { manager.isSpeakerphoneOn = true }
    }

    private fun restoreVoiceRoute() {
        val manager = audioManager ?: return
        previousAudioMode?.let { mode -> runCatching { manager.mode = mode } }
        previousSpeakerphoneOn?.let { speakerOn ->
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = speakerOn }
        }
        audioManager = null
        previousAudioMode = null
        previousSpeakerphoneOn = null
    }

    private fun startPlaybackSink() {
        playbackJob?.cancel()
        val minBuffer = AudioTrack.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FrameBytes * PlaybackBufferFrames)
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
                AudioTrack.MODE_STREAM
            )
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            _state.value = _state.value.copy(errorMessage = "语音播放初始化失败")
            runCatching { track.release() }
            return
        }
        audioTrack = track
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { track.setVolume(0.80f) }
        }
        runCatching { track.play() }
        playbackJob = scope.launch {
            while (isActive && audioTrack === track) {
                val mixed = mixer.nextMixedFrame()
                if (mixed == null) {
                    delay(PlaybackIdleDelayMs)
                } else {
                    runCatching { track.write(mixed, 0, mixed.size) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket, memberId: String) {
        stopRecorderOnly()
        recordingJob = scope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(
                SampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FrameBytes * RecordingBufferFrames)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                _state.value = _state.value.copy(isSpeaking = false, errorMessage = "麦克风初始化失败")
                return@launch
            }
            audioRecord = recorder
            enableVoicePreProcessing(recorder.audioSessionId)
            val encoder = createBestEncoder()
            activeEncoder = encoder
            _state.value = _state.value.copy(codecLabel = encoder.label)
            val frame = ByteArray(FrameBytes)
            runCatching { recorder.startRecording() }.onFailure {
                stopRecorderOnly()
                _state.value = _state.value.copy(isSpeaking = false, errorMessage = "麦克风启动失败")
                return@launch
            }
            _state.value = _state.value.copy(isConnected = true, isConnecting = false, isSpeaking = true, errorMessage = null)
            while (isActive && webSocket === socket && _state.value.isConnected && _state.value.isSpeaking) {
                val read = recorder.read(frame, 0, frame.size)
                if (read > 0) {
                    val pcm = if (read == frame.size) frame.copyOf() else frame.copyOf(read)
                    val encoded = runCatching { encoder.encode(pcm) }.getOrNull()
                    if (encoded != null && encoded.isNotEmpty()) {
                        val seq = sequence.incrementAndGet()
                        if (encoder.codec == VoiceCodec.PCM16) {
                            // Stable compatibility path: the pre-Opus voice build sent raw PCM in
                            // the legacy text envelope and users confirmed that path sounds normal.
                            // Keep this as the default until device-to-device Opus MediaCodec framing
                            // is validated on real phones; otherwise some decoders output noise.
                            val frameDto = VoiceFrameDto(
                                senderId = memberId,
                                payload = Base64.encodeToString(encoded, Base64.NO_WRAP),
                                seq = seq
                            )
                            socket.send(json.encodeToString(frameDto))
                        } else {
                            val packet = VoiceBinaryProtocol.encode(
                                codec = encoder.codec,
                                senderId = memberId,
                                seq = seq,
                                payload = encoded
                            )
                            socket.send(packet.toByteString())
                        }
                    }
                }
            }
        }
    }

    private fun stopRecorderOnly() {
        recordingJob?.cancel()
        recordingJob = null
        activeEncoder?.release()
        activeEncoder = null
        releaseVoicePreProcessing()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun enableVoicePreProcessing(audioSessionId: Int) {
        releaseVoicePreProcessing()
        if (audioSessionId == AudioManager.ERROR) return
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = runCatching {
                AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
    }

    private fun releaseVoicePreProcessing() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { automaticGainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
    }

    private fun playBinaryFrame(bytes: ByteArray) {
        val packet = VoiceBinaryProtocol.decode(bytes) ?: return
        val decoderKey = "${packet.codec}:${packet.senderId}"
        val decoder = decoders.getOrPut(decoderKey) { createDecoder(packet.codec) }
        val pcm = runCatching { decoder.decode(packet.payload) }.getOrNull() ?: return
        if (pcm.isNotEmpty()) {
            mixer.enqueue(packet.senderId, pcm)
        }
    }

    private fun playLegacyTextFrame(text: String) {
        val frame = runCatching { json.decodeFromString<VoiceFrameDto>(text) }.getOrNull() ?: return
        val bytes = runCatching { Base64.decode(frame.payload, Base64.NO_WRAP) }.getOrNull() ?: return
        // Older working builds sent 16 kHz PCM in this text envelope. The Opus path now
        // runs the playback sink at 48 kHz (Android's native Opus rate), so upsample
        // legacy 20 ms PCM frames instead of playing them 3x too fast.
        val pcm = if (bytes.size == LegacyPcm16FrameBytes) upsamplePcm16Mono3x(bytes) else bytes
        mixer.enqueue(frame.senderId, pcm)
    }

    private fun upsamplePcm16Mono3x(pcm16k: ByteArray): ByteArray {
        val inputSamples = pcm16k.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val output = ByteArray(inputSamples * 3 * 2)
        var out = 0
        for (i in 0 until inputSamples) {
            val lo = pcm16k[i * 2].toInt() and 0xFF
            val hi = pcm16k[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            repeat(3) {
                output[out++] = (sample and 0xFF).toByte()
                output[out++] = ((sample ushr 8) and 0xFF).toByte()
            }
        }
        return output
    }

    private fun createBestEncoder(): VoicePayloadCodec {
        val opus = AndroidOpusCodec.createEncoder()
        if (opus != null) return opus
        return PcmPassthroughCodec()
    }

    private fun createDecoder(codec: VoiceCodec): VoicePayloadCodec = when (codec) {
        VoiceCodec.OPUS -> AndroidOpusCodec.createDecoder() ?: DroppingCodec(VoiceCodec.OPUS, "Opus unavailable")
        VoiceCodec.ADPCM -> ImaAdpcmCodec()
        VoiceCodec.PCM16 -> PcmPassthroughCodec()
    }

    private companion object {
        private const val SampleRate = 48_000
        private const val FrameDurationMs = 20
        private const val FrameSamples = SampleRate * FrameDurationMs / 1_000
        private const val FrameBytes = FrameSamples * 2
        private const val LegacyPcm16FrameBytes = 16_000 * FrameDurationMs / 1_000 * 2
        private const val RecordingBufferFrames = 6
        private const val PlaybackBufferFrames = 8
        private const val PlaybackIdleDelayMs = 8L
        private const val PreferredCodecLabel = "Opus"
    }
}

internal enum class VoiceCodec(val wireValue: Int) {
    PCM16(1),
    OPUS(2),
    ADPCM(3);

    companion object {
        fun fromWire(value: Int): VoiceCodec? = entries.firstOrNull { it.wireValue == value }
    }
}

internal data class VoicePacket(
    val codec: VoiceCodec,
    val senderId: String,
    val seq: Long,
    val timestamp: Long,
    val payload: ByteArray
)

internal object VoiceBinaryProtocol {
    private const val Magic = 0x4A435631 // JCV1
    private const val FixedHeaderBytes = 4 + 1 + 1 + 8 + 8 + 2 + 4

    fun encode(codec: VoiceCodec, senderId: String, seq: Long, payload: ByteArray): ByteArray {
        val sender = senderId.encodeToByteArray()
        require(sender.size <= UShort.MAX_VALUE.toInt()) { "senderId too long" }
        val buffer = ByteBuffer.allocate(FixedHeaderBytes + sender.size + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(Magic)
        buffer.put(codec.wireValue.toByte())
        buffer.put(0) // flags/reserved
        buffer.putLong(seq)
        buffer.putLong(System.currentTimeMillis())
        buffer.putShort(sender.size.toShort())
        buffer.putInt(payload.size)
        buffer.put(sender)
        buffer.put(payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): VoicePacket? {
        if (bytes.size < FixedHeaderBytes) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != Magic) return null
        val codec = VoiceCodec.fromWire(buffer.get().toInt() and 0xFF) ?: return null
        buffer.get() // flags/reserved
        val seq = buffer.long
        val timestamp = buffer.long
        val senderLength = buffer.short.toInt() and 0xFFFF
        val payloadLength = buffer.int
        if (senderLength < 0 || payloadLength < 0 || buffer.remaining() != senderLength + payloadLength) return null
        val senderBytes = ByteArray(senderLength)
        buffer.get(senderBytes)
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return VoicePacket(
            codec = codec,
            senderId = senderBytes.decodeToString(),
            seq = seq,
            timestamp = timestamp,
            payload = payload
        )
    }
}

internal interface VoicePayloadCodec {
    val codec: VoiceCodec
    val label: String
    fun encode(pcm: ByteArray): ByteArray
    fun decode(payload: ByteArray): ByteArray
    fun release() = Unit
}

internal class PcmPassthroughCodec : VoicePayloadCodec {
    override val codec: VoiceCodec = VoiceCodec.PCM16
    override val label: String = "PCM"
    override fun encode(pcm: ByteArray): ByteArray = pcm
    override fun decode(payload: ByteArray): ByteArray = payload
}

internal class DroppingCodec(
    override val codec: VoiceCodec,
    override val label: String
) : VoicePayloadCodec {
    override fun encode(pcm: ByteArray): ByteArray = ByteArray(0)
    override fun decode(payload: ByteArray): ByteArray = ByteArray(0)
}

internal class AndroidOpusCodec private constructor(
    private val mediaCodec: MediaCodec,
    private val encodeMode: Boolean
) : VoicePayloadCodec {
    override val codec: VoiceCodec = VoiceCodec.OPUS
    override val label: String = "Opus"
    private val bufferInfo = MediaCodec.BufferInfo()
    private val pendingEncodedOutput = ArrayDeque<ByteArray>()
    private var presentationTimeUs = 0L

    override fun encode(pcm: ByteArray): ByteArray {
        if (!encodeMode) return ByteArray(0)
        if (pendingEncodedOutput.isNotEmpty()) return pendingEncodedOutput.removeFirst()
        return transcode(pcm, returnSingleAccessUnit = true)
    }

    override fun decode(payload: ByteArray): ByteArray {
        if (encodeMode) return ByteArray(0)
        return transcode(payload, returnSingleAccessUnit = false)
    }

    private fun transcode(input: ByteArray, returnSingleAccessUnit: Boolean): ByteArray {
        val inputIndex = mediaCodec.dequeueInputBuffer(CodecTimeoutUs)
        if (inputIndex >= 0) {
            mediaCodec.getInputBuffer(inputIndex)?.let { buffer ->
                buffer.clear()
                buffer.put(input)
            }
            mediaCodec.queueInputBuffer(inputIndex, 0, input.size, presentationTimeUs, 0)
            presentationTimeUs += FrameDurationUs
        }

        val chunks = mutableListOf<ByteArray>()
        drain@ while (true) {
            val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, CodecTimeoutUs)
            when {
                outputIndex >= 0 -> {
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (bufferInfo.size > 0 && !isCodecConfig) {
                        val buffer = mediaCodec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val out = ByteArray(bufferInfo.size)
                            buffer.get(out)
                            chunks += out
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drain
                else -> break@drain
            }
        }
        if (chunks.isEmpty()) return ByteArray(0)
        if (returnSingleAccessUnit) {
            chunks.drop(1).forEach { pendingEncodedOutput.addLast(it) }
            return chunks.first()
        }
        if (chunks.size == 1) return chunks.first()
        val size = chunks.sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    override fun release() {
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
    }

    companion object {
        private const val MimeOpus = "audio/opus"
        internal const val SampleRate = 48_000
        internal const val ChannelCount = 1
        internal const val FrameDurationUs = 20_000L
        internal const val FrameBytes = SampleRate * 20 / 1_000 * 2
        private const val Bitrate = 24_000
        private const val CodecTimeoutUs = 10_000L
        private const val OpusPreSkipSamples = 312
        private const val OpusSeekPreRollNs = 80_000_000L

        fun createEncoder(): AndroidOpusCodec? = runCatching {
            val format = MediaFormat.createAudioFormat(MimeOpus, SampleRate, ChannelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, Bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FrameBytes)
            }
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: return null
            val codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            AndroidOpusCodec(codec, encodeMode = true)
        }.getOrNull()

        fun createDecoder(): AndroidOpusCodec? = runCatching {
            val format = MediaFormat.createAudioFormat(MimeOpus, SampleRate, ChannelCount).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512)
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", longBuffer((OpusPreSkipSamples * 1_000_000_000L) / SampleRate))
                setByteBuffer("csd-2", longBuffer(OpusSeekPreRollNs))
            }
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val codec = if (codecName != null) MediaCodec.createByCodecName(codecName) else MediaCodec.createDecoderByType(MimeOpus)
            codec.configure(format, null, null, 0)
            codec.start()
            AndroidOpusCodec(codec, encodeMode = false)
        }.getOrNull()

        private fun longBuffer(value: Long): ByteBuffer {
            return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
        }

        private fun opusHead(): ByteArray {
            val buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("OpusHead".encodeToByteArray())
            buffer.put(1) // version
            buffer.put(ChannelCount.toByte())
            buffer.putShort(OpusPreSkipSamples.toShort())
            buffer.putInt(SampleRate)
            buffer.putShort(0) // output gain
            buffer.put(0) // mono/stereo channel mapping family
            return buffer.array()
        }
    }
}

private class ImaAdpcmCodec : VoicePayloadCodec {
    override val codec: VoiceCodec = VoiceCodec.ADPCM
    override val label: String = "ADPCM"
    private var encoderPredictor = 0
    private var encoderIndex = 0

    override fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return ByteArray(0)
        val out = ByteArray(3 + (sampleCount + 1) / 2)
        out[0] = (encoderPredictor and 0xFF).toByte()
        out[1] = ((encoderPredictor ushr 8) and 0xFF).toByte()
        out[2] = encoderIndex.toByte()
        var outIndex = 3
        var highNibble = false
        var packed = 0
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            val nibble = encodeSample(sample.toShort().toInt()) and 0x0F
            if (!highNibble) {
                packed = nibble
                highNibble = true
            } else {
                out[outIndex++] = (packed or (nibble shl 4)).toByte()
                highNibble = false
            }
        }
        if (highNibble) out[outIndex] = packed.toByte()
        return out
    }

    override fun decode(payload: ByteArray): ByteArray {
        if (payload.size < 3) return ByteArray(0)
        var predictor = ((payload[1].toInt() shl 8) or (payload[0].toInt() and 0xFF)).toShort().toInt()
        var index = payload[2].toInt().coerceIn(0, IndexTable.lastIndex)
        val sampleCount = (payload.size - 3) * 2
        val out = ByteArray(sampleCount * 2)
        var outOffset = 0
        fun decodeNibble(nibble: Int) {
            val step = StepTable[index]
            var diff = step shr 3
            if ((nibble and 1) != 0) diff += step shr 2
            if ((nibble and 2) != 0) diff += step shr 1
            if ((nibble and 4) != 0) diff += step
            predictor = if ((nibble and 8) != 0) predictor - diff else predictor + diff
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            index = (index + IndexTable[nibble and 0x0F]).coerceIn(0, StepTable.lastIndex)
            out[outOffset++] = (predictor and 0xFF).toByte()
            out[outOffset++] = ((predictor ushr 8) and 0xFF).toByte()
        }
        for (i in 3 until payload.size) {
            val packed = payload[i].toInt() and 0xFF
            decodeNibble(packed and 0x0F)
            decodeNibble((packed ushr 4) and 0x0F)
        }
        return out
    }

    private fun encodeSample(sample: Int): Int {
        val step = StepTable[encoderIndex]
        var diff = sample - encoderPredictor
        var nibble = 0
        if (diff < 0) {
            nibble = 8
            diff = -diff
        }
        var delta = step shr 3
        if (diff >= step) {
            nibble = nibble or 4
            diff -= step
            delta += step
        }
        if (diff >= step shr 1) {
            nibble = nibble or 2
            diff -= step shr 1
            delta += step shr 1
        }
        if (diff >= step shr 2) {
            nibble = nibble or 1
            delta += step shr 2
        }
        encoderPredictor = if ((nibble and 8) != 0) encoderPredictor - delta else encoderPredictor + delta
        encoderPredictor = encoderPredictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        encoderIndex = (encoderIndex + IndexTable[nibble]).coerceIn(0, StepTable.lastIndex)
        return nibble
    }

    private companion object {
        private val IndexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
        private val StepTable = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
    }
}

private class VoiceFrameMixer {
    private val queues = ConcurrentHashMap<String, ArrayDeque<ShortArray>>()

    fun enqueue(senderId: String, pcm: ByteArray) {
        val samples = pcm.toShortSamples()
        if (samples.isEmpty()) return
        val queue = queues.getOrPut(senderId) { ArrayDeque() }
        synchronized(queue) {
            while (queue.size >= MaxBufferedFramesPerSpeaker) queue.removeFirst()
            queue.addLast(samples)
        }
    }

    fun nextMixedFrame(): ByteArray? {
        val frames = mutableListOf<ShortArray>()
        queues.forEach { (_, queue) ->
            synchronized(queue) {
                if (queue.size >= TargetJitterFrames || frames.isNotEmpty()) {
                    queue.pollFirst()?.let { frames += it }
                }
            }
        }
        if (frames.isEmpty()) return null
        val maxSamples = min(frames.maxOf { it.size }, FrameSamples)
        val mixed = IntArray(maxSamples)
        frames.forEach { frame ->
            for (i in 0 until min(maxSamples, frame.size)) {
                mixed[i] += frame[i]
            }
        }
        val output = ByteArray(maxSamples * 2)
        for (i in 0 until maxSamples) {
            val sample = mixed[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i * 2] = (sample and 0xFF).toByte()
            output[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return output
    }

    fun clear() {
        queues.values.forEach { queue -> synchronized(queue) { queue.clear() } }
        queues.clear()
    }

    private fun ByteArray.toShortSamples(): ShortArray {
        val count = size / 2
        val result = ShortArray(count)
        for (i in 0 until count) {
            val lo = this[i * 2].toInt() and 0xFF
            val hi = this[i * 2 + 1].toInt()
            result[i] = ((hi shl 8) or lo).toShort()
        }
        return result
    }

    private companion object {
        private const val SampleRate = 48_000
        private const val FrameDurationMs = 20
        private const val FrameSamples = SampleRate * FrameDurationMs / 1_000
        private const val MaxBufferedFramesPerSpeaker = 10
        private const val TargetJitterFrames = 2
    }
}
