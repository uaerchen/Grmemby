package com.grmemby.player.preferences

import android.content.Context
import android.content.SharedPreferences
import com.grmemby.data.model.AudioTranscodeMode
import com.grmemby.player.core.PlayerConstants.DEFAULT_BRIGHTNESS
import com.grmemby.player.core.PlayerConstants.DEFAULT_VOLUME
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Manages player-specific preferences like brightness, volume levels, and hardware acceleration settings
 * Follows ExoPlayer's approach to remember user settings per player session
 */
class PlayerPreferences(context: Context) {
    data class DanmakuApiEndpoint(
        val name: String,
        val url: String
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "grmemby_player_prefs"
        private const val KEY_PLAYER_BRIGHTNESS = "player_brightness"
        private const val KEY_PLAYER_VOLUME = "player_volume"
        private const val KEY_PLAYER_ENGINE = "player_engine"
        private const val KEY_MPV_HARDWARE_DECODING = "mpv_hardware_decoding"
        private const val KEY_MPV_VIDEO_OUTPUT = "mpv_video_output"
        private const val KEY_MPV_AUDIO_OUTPUT = "mpv_audio_output"
        private const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration_enabled"
        private const val KEY_ASYNC_MEDIACODEC = "async_mediacodec_enabled"
        private const val KEY_DECODER_PRIORITY = "decoder_priority"
        private const val KEY_BATTERY_OPTIMIZATION = "battery_optimization_enabled"
        private const val KEY_PLAYER_GESTURES_ENABLED = "player_gestures_enabled"
        private const val KEY_VOLUME_BRIGHTNESS_GESTURES_ENABLED = "volume_brightness_gestures_enabled"
        private const val KEY_USE_DEVICE_VOLUME_IN_PLAYER = "use_device_volume_in_player"
        private const val KEY_USE_DEVICE_BRIGHTNESS_IN_PLAYER = "use_device_brightness_in_player"
        private const val KEY_PROGRESS_SEEK_GESTURE_ENABLED = "progress_seek_gesture_enabled"
        private const val KEY_ZOOM_GESTURE_ENABLED = "zoom_gesture_enabled"
        private const val KEY_LONG_PRESS_SPEED_BOOST_ENABLED = "long_press_speed_boost_enabled"
        private const val KEY_LONG_PRESS_SPEED_BOOST_RATE = "long_press_speed_boost_rate"
        private const val KEY_START_MAXIMIZED = "start_maximized"
        private const val KEY_CACHE_NEXT_EPISODE = "cache_next_episode"
        private const val KEY_PLAYER_DISK_CACHE_ENABLED = "player_disk_cache_enabled"
        private const val KEY_PLAYER_CACHE_SIZE_MB = "player_cache_size_mb"
        private const val KEY_PLAYER_CACHE_TIME_SECONDS = "player_cache_time_seconds"
        private const val KEY_SEEK_BACKWARD_INTERVAL_SECONDS = "seek_backward_interval_seconds"
        private const val KEY_SEEK_FORWARD_INTERVAL_SECONDS = "seek_forward_interval_seconds"
        private const val KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
        private const val KEY_CHAPTER_MARKERS_ENABLED = "chapter_markers_enabled"
        private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
        private const val KEY_DANMAKU_FILTER_WORDS = "danmaku_filter_words"
        private const val KEY_DANMAKU_MATCH_MODE = "danmaku_match_mode"
        private const val KEY_DANMAKU_API_URL = "danmaku_api_url"
        private const val KEY_DANMAKU_API_URLS = "danmaku_api_urls"
        private val DEFAULT_DANMAKU_API_ENDPOINTS = listOf(
            DanmakuApiEndpoint(name = "公共弹幕源 1", url = "https://fortape-danmu-api.hf.space"),
            DanmakuApiEndpoint(name = "公共弹幕源 2", url = "https://lp080624-danmu-api.hf.space"),
            DanmakuApiEndpoint(name = "公共弹幕源 3", url = "https://handsome923-danmu-api.hf.space")
        )
        private const val KEY_DANMAKU_LINE_COUNT = "danmaku_line_count"
        private const val KEY_DANMAKU_SPEED_PERCENT = "danmaku_speed_percent"
        private const val KEY_DANMAKU_OPACITY_PERCENT = "danmaku_opacity_percent"
        private const val KEY_DANMAKU_FONT_SIZE_SP = "danmaku_font_size_sp"
        private const val KEY_DANMAKU_BOLD = "danmaku_bold"
        private const val KEY_DANMAKU_MERGE_DUPLICATES = "danmaku_merge_duplicates"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_SUBTITLE_TEXT_COLOR = "subtitle_text_color"
        private const val KEY_SUBTITLE_BACKGROUND_COLOR = "subtitle_background_color"
        private const val KEY_SUBTITLE_EDGE_TYPE = "subtitle_edge_type"
        private const val KEY_SUBTITLE_TEXT_OPACITY_PERCENT = "subtitle_text_opacity_percent"
        private const val KEY_SUBTITLE_BOTTOM_EDGE_PERCENT = "subtitle_bottom_edge_percent"
        private const val KEY_SUBTITLE_TOP_EDGE_PERCENT = "subtitle_top_edge_percent"
        private const val KEY_STREAMING_QUALITY = "streaming_quality"
        private const val KEY_AUDIO_TRANSCODE_MODE = "audio_transcode_mode"
        private const val KEY_AUDIO_STREAM_INDEX_PREFIX = "audio_stream_index_"
        private const val KEY_SUBTITLE_STREAM_INDEX_PREFIX = "subtitle_stream_index_"
        private const val KEY_STREAM_INDEX_UPDATED_AT_PREFIX = "stream_index_updated_at_"
        private const val MAX_PREFERRED_STREAM_ITEMS = 500

        const val SUBTITLE_TEXT_SIZE_SMALL = "Small"
        const val SUBTITLE_TEXT_SIZE_NORMAL = "Normal"
        const val SUBTITLE_TEXT_SIZE_LARGE = "Large"
        const val SUBTITLE_TEXT_SIZE_EXTRA_LARGE = "Extra Large"
        val SUBTITLE_TEXT_SIZE_OPTIONS = listOf(
            SUBTITLE_TEXT_SIZE_SMALL,
            SUBTITLE_TEXT_SIZE_NORMAL,
            SUBTITLE_TEXT_SIZE_LARGE,
            SUBTITLE_TEXT_SIZE_EXTRA_LARGE
        )

        const val SUBTITLE_TEXT_COLOR_WHITE = "White"
        const val SUBTITLE_TEXT_COLOR_YELLOW = "Yellow"
        const val SUBTITLE_TEXT_COLOR_GREEN = "Green"
        const val SUBTITLE_TEXT_COLOR_CYAN = "Cyan"
        const val SUBTITLE_TEXT_COLOR_BLACK = "Black"
        val SUBTITLE_TEXT_COLOR_OPTIONS = listOf(
            SUBTITLE_TEXT_COLOR_WHITE,
            SUBTITLE_TEXT_COLOR_YELLOW,
            SUBTITLE_TEXT_COLOR_GREEN,
            SUBTITLE_TEXT_COLOR_CYAN,
            SUBTITLE_TEXT_COLOR_BLACK
        )

        const val SUBTITLE_BACKGROUND_TRANSPARENT = "Transparent"
        const val SUBTITLE_BACKGROUND_BLACK = "Black"
        const val SUBTITLE_BACKGROUND_WHITE = "White"
        val SUBTITLE_BACKGROUND_OPTIONS = listOf(
            SUBTITLE_BACKGROUND_TRANSPARENT,
            SUBTITLE_BACKGROUND_BLACK,
            SUBTITLE_BACKGROUND_WHITE
        )

        const val SUBTITLE_EDGE_TYPE_NONE = "None"
        const val SUBTITLE_EDGE_TYPE_OUTLINE = "Outline"
        const val SUBTITLE_EDGE_TYPE_DROP_SHADOW = "Drop Shadow"
        const val SUBTITLE_EDGE_TYPE_RAISED = "Raised"
        const val SUBTITLE_EDGE_TYPE_DEPRESSED = "Depressed"
        val SUBTITLE_EDGE_TYPE_OPTIONS = listOf(
            SUBTITLE_EDGE_TYPE_NONE,
            SUBTITLE_EDGE_TYPE_OUTLINE,
            SUBTITLE_EDGE_TYPE_DROP_SHADOW,
            SUBTITLE_EDGE_TYPE_RAISED,
            SUBTITLE_EDGE_TYPE_DEPRESSED
        )

        const val DEFAULT_SUBTITLE_TEXT_SIZE = SUBTITLE_TEXT_SIZE_NORMAL
        const val DEFAULT_SUBTITLE_TEXT_COLOR = SUBTITLE_TEXT_COLOR_WHITE
        const val DEFAULT_SUBTITLE_BACKGROUND_COLOR = SUBTITLE_BACKGROUND_TRANSPARENT
        const val DEFAULT_SUBTITLE_EDGE_TYPE = SUBTITLE_EDGE_TYPE_NONE
        const val DEFAULT_SUBTITLE_TEXT_OPACITY_PERCENT = 100
        const val DEFAULT_SUBTITLE_BOTTOM_EDGE_PERCENT = 10
        const val DEFAULT_SUBTITLE_TOP_EDGE_PERCENT = 5
        const val DEFAULT_PLAYER_CACHE_SIZE_MB = 200
        const val MAX_PLAYER_CACHE_SIZE_MB = 5000
        const val MIN_PLAYER_CACHE_SIZE_MB = 50
        const val PLAYER_CACHE_SIZE_STEP_MB = 50
        const val DEFAULT_PLAYER_CACHE_TIME_SECONDS = 120
        const val MAX_PLAYER_CACHE_TIME_SECONDS = 900
        const val MIN_PLAYER_CACHE_TIME_SECONDS = 30
        const val PLAYER_CACHE_TIME_STEP_SECONDS = 30
        const val DEFAULT_SEEK_INTERVAL_SECONDS = 30
        const val MAX_SEEK_INTERVAL_SECONDS = 30
        const val MIN_SEEK_INTERVAL_SECONDS = 5
        const val SEEK_INTERVAL_STEP_SECONDS = 5
        const val DEFAULT_USE_DEVICE_VOLUME_IN_PLAYER = false
        const val DEFAULT_USE_DEVICE_BRIGHTNESS_IN_PLAYER = true
        const val DEFAULT_LONG_PRESS_SPEED_BOOST_ENABLED = true
        const val DEFAULT_LONG_PRESS_SPEED_BOOST_RATE = 2
        val LONG_PRESS_SPEED_BOOST_RATE_OPTIONS = listOf(2, 3, 4)
        const val DEFAULT_CACHE_NEXT_EPISODE = false
        const val DEFAULT_PLAYER_DISK_CACHE_ENABLED = true
        const val DEFAULT_SKIP_INTRO_ENABLED = true
        const val DEFAULT_CHAPTER_MARKERS_ENABLED = true
        const val DEFAULT_DANMAKU_ENABLED = true
        const val DANMAKU_MATCH_MODE_FILENAME = "仅文件名"
        const val DANMAKU_MATCH_MODE_FILENAME_HASH = "文件名+哈希值"
        val DANMAKU_MATCH_MODE_OPTIONS = listOf(DANMAKU_MATCH_MODE_FILENAME, DANMAKU_MATCH_MODE_FILENAME_HASH)
        const val DEFAULT_DANMAKU_MATCH_MODE = DANMAKU_MATCH_MODE_FILENAME
        const val DEFAULT_DANMAKU_API_URL = ""
        const val DEFAULT_DANMAKU_FILTER_WORDS = ""
        const val DEFAULT_DANMAKU_LINE_COUNT = 4
        const val MIN_DANMAKU_LINE_COUNT = 1
        const val MAX_DANMAKU_LINE_COUNT = 10
        const val DEFAULT_DANMAKU_SPEED_PERCENT = 100
        const val MIN_DANMAKU_SPEED_PERCENT = 50
        const val MAX_DANMAKU_SPEED_PERCENT = 200
        const val DEFAULT_DANMAKU_OPACITY_PERCENT = 85
        const val MIN_DANMAKU_OPACITY_PERCENT = 20
        const val MAX_DANMAKU_OPACITY_PERCENT = 100
        const val DEFAULT_DANMAKU_FONT_SIZE_SP = 16
        const val MIN_DANMAKU_FONT_SIZE_SP = 12
        const val MAX_DANMAKU_FONT_SIZE_SP = 30
        const val DEFAULT_DANMAKU_BOLD = false
        const val DEFAULT_DANMAKU_MERGE_DUPLICATES = true
        const val PLAYER_ENGINE_EXO = "ExoPlayer"
        const val PLAYER_ENGINE_MPV = "MPV"
        const val DEFAULT_PLAYER_ENGINE = PLAYER_ENGINE_EXO
        val PLAYER_ENGINE_OPTIONS = listOf(PLAYER_ENGINE_EXO, PLAYER_ENGINE_MPV)
        const val MPV_HARDWARE_DECODING_NONE = "no"
        const val MPV_HARDWARE_DECODING_MEDIACODEC = "mediacodec"
        const val MPV_HARDWARE_DECODING_MEDIACODEC_COPY = "mediacodec-copy"
        const val DEFAULT_MPV_HARDWARE_DECODING = MPV_HARDWARE_DECODING_MEDIACODEC
        val MPV_HARDWARE_DECODING_OPTIONS = listOf(
            MPV_HARDWARE_DECODING_NONE,
            MPV_HARDWARE_DECODING_MEDIACODEC,
            MPV_HARDWARE_DECODING_MEDIACODEC_COPY
        )
        const val MPV_VIDEO_OUTPUT_GPU_NEXT = "gpu-next"
        const val MPV_VIDEO_OUTPUT_GPU = "gpu"
        const val DEFAULT_MPV_VIDEO_OUTPUT = MPV_VIDEO_OUTPUT_GPU_NEXT
        val MPV_VIDEO_OUTPUT_OPTIONS = listOf(MPV_VIDEO_OUTPUT_GPU_NEXT, MPV_VIDEO_OUTPUT_GPU)
        const val MPV_AUDIO_OUTPUT_AAUDIO = "aaudio"
        const val MPV_AUDIO_OUTPUT_AUDIOTRACK = "audiotrack"
        const val MPV_AUDIO_OUTPUT_OPENSLES = "opensles"
        const val DEFAULT_MPV_AUDIO_OUTPUT = MPV_AUDIO_OUTPUT_AUDIOTRACK
        val MPV_AUDIO_OUTPUT_OPTIONS = listOf(
            MPV_AUDIO_OUTPUT_AAUDIO,
            MPV_AUDIO_OUTPUT_AUDIOTRACK,
            MPV_AUDIO_OUTPUT_OPENSLES
        )
        const val DECODER_PRIORITY_HARDWARE = "Hardware Decoder"
        const val DECODER_PRIORITY_SOFTWARE = "Software Decoder"
        const val DECODER_PRIORITY_AUTO = "Auto"

        const val STREAMING_QUALITY_ORIGINAL = TranscodeProfiles.ORIGINAL
        val STREAMING_QUALITY_OPTIONS: List<String> = TranscodeProfiles.OPTIONS
        const val DEFAULT_STREAMING_QUALITY = STREAMING_QUALITY_ORIGINAL
        val AUDIO_TRANSCODE_MODE_OPTIONS: List<String> =
            AudioTranscodeMode.entries.map { it.displayName }

        fun getStreamingQualityMaxHeightForOption(quality: String): Int? {
            return TranscodeProfiles.maxHeightForOption(quality)
        }

        fun getStreamingQualityOptions(sourceVideoHeight: Int?): List<String> {
            if (sourceVideoHeight == null || sourceVideoHeight <= 0) {
                return STREAMING_QUALITY_OPTIONS
            }

            return STREAMING_QUALITY_OPTIONS.filter { quality ->
                val maxHeight = getStreamingQualityMaxHeightForOption(quality)
                maxHeight == null || maxHeight <= sourceVideoHeight
            }
        }

        private const val MAX_SUBTITLE_EDGE_PERCENT = 50
        private const val MAX_SUBTITLE_OPACITY_PERCENT = 100
    }

    /**
     * Get the saved player brightness level (0.0f to 1.0f)
     * Returns the last used brightness or default if none saved
     */
    fun getPlayerBrightness(): Float {
        return prefs.getFloat(KEY_PLAYER_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(0.0f, 1.0f)
    }

    /**
     * Save the current player brightness level
     */
    fun setPlayerBrightness(brightness: Float) {
        prefs.edit()
            .putFloat(KEY_PLAYER_BRIGHTNESS, brightness.coerceIn(0.0f, 1.0f))
            .apply()
    }

    /**
     * Get the saved player volume level (0.0f to 1.0f)
     * Returns the last used volume or default if none saved
     */
    fun getPlayerVolume(): Float {
        return prefs.getFloat(KEY_PLAYER_VOLUME, DEFAULT_VOLUME)
            .coerceIn(0.0f, 1.0f)
    }

    /**
     * Save the current player volume level
     */
    fun setPlayerVolume(volume: Float) {
        prefs.edit()
            .putFloat(KEY_PLAYER_VOLUME, volume.coerceIn(0.0f, 1.0f))
            .apply()
    }

    /**
     * Clear all player preferences (useful for reset)
     */
    fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    fun getPlayerEngine(): String {
        val engine = prefs.getString(KEY_PLAYER_ENGINE, DEFAULT_PLAYER_ENGINE) ?: DEFAULT_PLAYER_ENGINE
        return if (engine in PLAYER_ENGINE_OPTIONS) engine else DEFAULT_PLAYER_ENGINE
    }

    fun setPlayerEngine(engine: String) {
        prefs.edit()
            .putString(
                KEY_PLAYER_ENGINE,
                if (engine in PLAYER_ENGINE_OPTIONS) engine else DEFAULT_PLAYER_ENGINE
            )
            .apply()
    }

    fun getMpvHardwareDecoding(): String {
        val value = prefs.getString(KEY_MPV_HARDWARE_DECODING, DEFAULT_MPV_HARDWARE_DECODING)
            ?: DEFAULT_MPV_HARDWARE_DECODING
        return if (value in MPV_HARDWARE_DECODING_OPTIONS) value else DEFAULT_MPV_HARDWARE_DECODING
    }

    fun setMpvHardwareDecoding(hardwareDecoding: String) {
        prefs.edit()
            .putString(
                KEY_MPV_HARDWARE_DECODING,
                if (hardwareDecoding in MPV_HARDWARE_DECODING_OPTIONS) {
                    hardwareDecoding
                } else {
                    DEFAULT_MPV_HARDWARE_DECODING
                }
            )
            .apply()
    }

    fun getMpvVideoOutput(): String {
        val value = prefs.getString(KEY_MPV_VIDEO_OUTPUT, DEFAULT_MPV_VIDEO_OUTPUT)
            ?: DEFAULT_MPV_VIDEO_OUTPUT
        return if (value in MPV_VIDEO_OUTPUT_OPTIONS) value else DEFAULT_MPV_VIDEO_OUTPUT
    }

    fun setMpvVideoOutput(videoOutput: String) {
        prefs.edit()
            .putString(
                KEY_MPV_VIDEO_OUTPUT,
                if (videoOutput in MPV_VIDEO_OUTPUT_OPTIONS) videoOutput else DEFAULT_MPV_VIDEO_OUTPUT
            )
            .apply()
    }

    fun getMpvAudioOutput(): String {
        val value = prefs.getString(KEY_MPV_AUDIO_OUTPUT, DEFAULT_MPV_AUDIO_OUTPUT)
            ?: DEFAULT_MPV_AUDIO_OUTPUT
        return if (value in MPV_AUDIO_OUTPUT_OPTIONS) value else DEFAULT_MPV_AUDIO_OUTPUT
    }

    fun setMpvAudioOutput(audioOutput: String) {
        prefs.edit()
            .putString(
                KEY_MPV_AUDIO_OUTPUT,
                if (audioOutput in MPV_AUDIO_OUTPUT_OPTIONS) audioOutput else DEFAULT_MPV_AUDIO_OUTPUT
            )
            .apply()
    }

    /**
     * Get hardware acceleration preference
     */
    fun isHardwareAccelerationEnabled(): Boolean {
        return prefs.getBoolean(KEY_HARDWARE_ACCELERATION, true)
    }

    /**
     * Set hardware acceleration preference
     */
    fun setHardwareAccelerationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HARDWARE_ACCELERATION, enabled).apply()
    }

    /**
     * Get asynchronous MediaCodec preference
     */
    fun isAsyncMediaCodecEnabled(): Boolean {
        return prefs.getBoolean(KEY_ASYNC_MEDIACODEC, false)
    }

    /**
     * Set asynchronous MediaCodec preference
     */
    fun setAsyncMediaCodecEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ASYNC_MEDIACODEC, enabled).apply()
    }

    /**
     * Get decoder priority preference
     */
    fun getDecoderPriority(): String {
        return prefs.getString(KEY_DECODER_PRIORITY, DECODER_PRIORITY_AUTO) ?: DECODER_PRIORITY_AUTO
    }

    /**
     * Set decoder priority preference
     */
    fun setDecoderPriority(priority: String) {
        prefs.edit().putString(KEY_DECODER_PRIORITY, priority).apply()
    }

    /**
     * Get battery optimization preference
     */
    fun isBatteryOptimizationEnabled(): Boolean {
        return prefs.getBoolean(KEY_BATTERY_OPTIMIZATION, false)
    }

    /**
     * Set battery optimization preference
     */
    fun setBatteryOptimizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_OPTIMIZATION, enabled).apply()
    }

    fun arePlayerGesturesEnabled(): Boolean {
        return prefs.getBoolean(KEY_PLAYER_GESTURES_ENABLED, true)
    }

    fun setPlayerGesturesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PLAYER_GESTURES_ENABLED, enabled)
            .apply()
    }

    fun isVolumeBrightnessGesturesEnabled(): Boolean {
        return prefs.getBoolean(KEY_VOLUME_BRIGHTNESS_GESTURES_ENABLED, true)
    }

    fun setVolumeBrightnessGesturesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_VOLUME_BRIGHTNESS_GESTURES_ENABLED, enabled)
            .apply()
    }

    fun isUseDeviceVolumeInPlayerEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_USE_DEVICE_VOLUME_IN_PLAYER,
            DEFAULT_USE_DEVICE_VOLUME_IN_PLAYER
        )
    }

    fun setUseDeviceVolumeInPlayerEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_USE_DEVICE_VOLUME_IN_PLAYER, enabled)
            .apply()
    }

    fun isUseDeviceBrightnessInPlayerEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_USE_DEVICE_BRIGHTNESS_IN_PLAYER,
            DEFAULT_USE_DEVICE_BRIGHTNESS_IN_PLAYER
        )
    }

    fun setUseDeviceBrightnessInPlayerEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_USE_DEVICE_BRIGHTNESS_IN_PLAYER, enabled)
            .apply()
    }

    fun isProgressSeekGestureEnabled(): Boolean {
        return prefs.getBoolean(KEY_PROGRESS_SEEK_GESTURE_ENABLED, true)
    }

    fun setProgressSeekGestureEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PROGRESS_SEEK_GESTURE_ENABLED, enabled)
            .apply()
    }

    fun isZoomGestureEnabled(): Boolean {
        return prefs.getBoolean(KEY_ZOOM_GESTURE_ENABLED, true)
    }

    fun setZoomGestureEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ZOOM_GESTURE_ENABLED, enabled)
            .apply()
    }

    fun isLongPressSpeedBoostEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_LONG_PRESS_SPEED_BOOST_ENABLED,
            DEFAULT_LONG_PRESS_SPEED_BOOST_ENABLED
        )
    }

    fun setLongPressSpeedBoostEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_LONG_PRESS_SPEED_BOOST_ENABLED, enabled)
            .apply()
    }

    fun getLongPressSpeedBoostRate(): Int {
        val saved = prefs.getInt(KEY_LONG_PRESS_SPEED_BOOST_RATE, DEFAULT_LONG_PRESS_SPEED_BOOST_RATE)
        return if (saved in LONG_PRESS_SPEED_BOOST_RATE_OPTIONS) saved else DEFAULT_LONG_PRESS_SPEED_BOOST_RATE
    }

    fun setLongPressSpeedBoostRate(rate: Int) {
        val value = if (rate in LONG_PRESS_SPEED_BOOST_RATE_OPTIONS) rate else DEFAULT_LONG_PRESS_SPEED_BOOST_RATE
        prefs.edit()
            .putInt(KEY_LONG_PRESS_SPEED_BOOST_RATE, value)
            .apply()
    }

    /**
     * Get start maximized preference
     */
    fun isStartMaximizedEnabled(): Boolean {
        return prefs.getBoolean(KEY_START_MAXIMIZED, false)
    }

    /**
     * Set start maximized preference
     */
    fun setStartMaximizedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_MAXIMIZED, enabled).apply()
    }

    fun isCacheNextEpisodeEnabled(): Boolean {
        return prefs.getBoolean(KEY_CACHE_NEXT_EPISODE, DEFAULT_CACHE_NEXT_EPISODE)
    }

    fun setCacheNextEpisodeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CACHE_NEXT_EPISODE, enabled).apply()
    }

    fun isPlayerDiskCacheEnabled(): Boolean {
        return prefs.getBoolean(KEY_PLAYER_DISK_CACHE_ENABLED, DEFAULT_PLAYER_DISK_CACHE_ENABLED)
    }

    fun setPlayerDiskCacheEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PLAYER_DISK_CACHE_ENABLED, enabled)
            .apply()
    }

    fun getPlayerCacheSizeMb(): Int {
        return prefs.getInt(KEY_PLAYER_CACHE_SIZE_MB, DEFAULT_PLAYER_CACHE_SIZE_MB)
            .coerceIn(MIN_PLAYER_CACHE_SIZE_MB, MAX_PLAYER_CACHE_SIZE_MB)
    }

    fun setPlayerCacheSizeMb(sizeMb: Int) {
        prefs.edit()
            .putInt(
                KEY_PLAYER_CACHE_SIZE_MB,
                sizeMb.coerceIn(MIN_PLAYER_CACHE_SIZE_MB, MAX_PLAYER_CACHE_SIZE_MB)
            )
            .apply()
    }

    fun getPlayerCacheTimeSeconds(): Int {
        return prefs.getInt(KEY_PLAYER_CACHE_TIME_SECONDS, DEFAULT_PLAYER_CACHE_TIME_SECONDS)
            .coerceIn(MIN_PLAYER_CACHE_TIME_SECONDS, MAX_PLAYER_CACHE_TIME_SECONDS)
    }

    fun setPlayerCacheTimeSeconds(seconds: Int) {
        prefs.edit()
            .putInt(
                KEY_PLAYER_CACHE_TIME_SECONDS,
                seconds.coerceIn(MIN_PLAYER_CACHE_TIME_SECONDS, MAX_PLAYER_CACHE_TIME_SECONDS)
            )
            .apply()
    }

    fun getSeekBackwardIntervalSeconds(): Int {
        return prefs.getInt(KEY_SEEK_BACKWARD_INTERVAL_SECONDS, DEFAULT_SEEK_INTERVAL_SECONDS)
            .coerceIn(MIN_SEEK_INTERVAL_SECONDS, MAX_SEEK_INTERVAL_SECONDS)
    }

    fun setSeekBackwardIntervalSeconds(seconds: Int) {
        prefs.edit()
            .putInt(
                KEY_SEEK_BACKWARD_INTERVAL_SECONDS,
                seconds.coerceIn(MIN_SEEK_INTERVAL_SECONDS, MAX_SEEK_INTERVAL_SECONDS)
            )
            .apply()
    }

    fun getSeekForwardIntervalSeconds(): Int {
        return prefs.getInt(KEY_SEEK_FORWARD_INTERVAL_SECONDS, DEFAULT_SEEK_INTERVAL_SECONDS)
            .coerceIn(MIN_SEEK_INTERVAL_SECONDS, MAX_SEEK_INTERVAL_SECONDS)
    }

    fun setSeekForwardIntervalSeconds(seconds: Int) {
        prefs.edit()
            .putInt(
                KEY_SEEK_FORWARD_INTERVAL_SECONDS,
                seconds.coerceIn(MIN_SEEK_INTERVAL_SECONDS, MAX_SEEK_INTERVAL_SECONDS)
            )
            .apply()
    }

    fun isSkipIntroEnabled(): Boolean {
        return prefs.getBoolean(KEY_SKIP_INTRO_ENABLED, DEFAULT_SKIP_INTRO_ENABLED)
    }

    fun setSkipIntroEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SKIP_INTRO_ENABLED, enabled)
            .apply()
    }

    fun areChapterMarkersEnabled(): Boolean {
        return prefs.getBoolean(KEY_CHAPTER_MARKERS_ENABLED, DEFAULT_CHAPTER_MARKERS_ENABLED)
    }

    fun setChapterMarkersEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CHAPTER_MARKERS_ENABLED, enabled)
            .apply()
    }

    fun isDanmakuEnabled(): Boolean {
        return prefs.getBoolean(KEY_DANMAKU_ENABLED, DEFAULT_DANMAKU_ENABLED)
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DANMAKU_ENABLED, enabled).apply()
    }

    fun getDanmakuFilterWords(): String {
        return prefs.getString(KEY_DANMAKU_FILTER_WORDS, DEFAULT_DANMAKU_FILTER_WORDS)
            ?: DEFAULT_DANMAKU_FILTER_WORDS
    }

    fun setDanmakuFilterWords(words: String) {
        prefs.edit().putString(KEY_DANMAKU_FILTER_WORDS, words.trim()).apply()
    }

    fun getDanmakuMatchMode(): String {
        val saved = prefs.getString(KEY_DANMAKU_MATCH_MODE, DEFAULT_DANMAKU_MATCH_MODE)
            ?: DEFAULT_DANMAKU_MATCH_MODE
        return if (saved in DANMAKU_MATCH_MODE_OPTIONS) saved else DEFAULT_DANMAKU_MATCH_MODE
    }

    fun setDanmakuMatchMode(mode: String) {
        prefs.edit()
            .putString(KEY_DANMAKU_MATCH_MODE, if (mode in DANMAKU_MATCH_MODE_OPTIONS) mode else DEFAULT_DANMAKU_MATCH_MODE)
            .apply()
    }

    fun getDanmakuApiUrl(): String {
        return getDanmakuApiUrls().firstOrNull().orEmpty()
    }

    fun setDanmakuApiUrl(url: String) {
        setDanmakuApiUrls(listOf(url))
    }

    fun getDanmakuApiUrls(): List<String> = getDanmakuApiEndpoints().map { it.url }

    fun setDanmakuApiUrls(urls: List<String>) {
        setDanmakuApiEndpoints(urls.mapIndexed { index, url ->
            DanmakuApiEndpoint(name = "API ${index + 1}", url = url)
        })
    }

    fun getDanmakuApiEndpoints(): List<DanmakuApiEndpoint> {
        val stored = prefs.getString(KEY_DANMAKU_API_URLS, null)
        val endpoints = stored
            ?.lineSequence()
            ?.mapIndexedNotNull { index, line -> parseDanmakuApiEndpoint(line, index) }
            ?.distinctBy { it.url.lowercase() }
            ?.toList()
            .orEmpty()
        if (endpoints.isNotEmpty()) return endpoints

        val legacy = prefs.getString(KEY_DANMAKU_API_URL, null)
            ?.let(::normalizeDanmakuApiUrl)
            .orEmpty()
        return legacy
            .takeIf { it.isNotBlank() && !it.equals("http://127.0.0.1:9321", ignoreCase = true) }
            ?.let { listOf(DanmakuApiEndpoint(name = "API 1", url = it)) }
            ?: DEFAULT_DANMAKU_API_ENDPOINTS
    }

    fun setDanmakuApiEndpoints(endpoints: List<DanmakuApiEndpoint>) {
        val normalized = endpoints
            .mapIndexedNotNull { index, endpoint ->
                val url = normalizeDanmakuApiUrl(endpoint.url)
                if (url.isBlank()) {
                    null
                } else {
                    DanmakuApiEndpoint(
                        name = endpoint.name.trim().ifBlank { "API ${index + 1}" },
                        url = url
                    )
                }
            }
            .distinctBy { it.url.lowercase() }
        prefs.edit()
            .putString(KEY_DANMAKU_API_URLS, normalized.joinToString("\n") { formatDanmakuApiEndpoint(it) })
            .putString(KEY_DANMAKU_API_URL, normalized.firstOrNull()?.url.orEmpty())
            .apply()
    }

    private fun parseDanmakuApiEndpoint(line: String, index: Int): DanmakuApiEndpoint? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null
        val tabIndex = trimmed.indexOf('\t')
        val rawName: String
        val rawUrl: String
        if (tabIndex >= 0) {
            rawName = decodeDanmakuApiPart(trimmed.substring(0, tabIndex))
            rawUrl = decodeDanmakuApiPart(trimmed.substring(tabIndex + 1))
        } else {
            rawName = "API ${index + 1}"
            rawUrl = trimmed
        }
        val url = normalizeDanmakuApiUrl(rawUrl)
        if (url.isBlank() || url.equals("http://127.0.0.1:9321", ignoreCase = true)) return null
        return DanmakuApiEndpoint(
            name = rawName.trim().ifBlank { "API ${index + 1}" },
            url = url
        )
    }

    private fun formatDanmakuApiEndpoint(endpoint: DanmakuApiEndpoint): String {
        return "${encodeDanmakuApiPart(endpoint.name)}\t${encodeDanmakuApiPart(endpoint.url)}"
    }

    private fun encodeDanmakuApiPart(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeDanmakuApiPart(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun normalizeDanmakuApiUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    fun getDanmakuLineCount(): Int {
        return prefs.getInt(KEY_DANMAKU_LINE_COUNT, DEFAULT_DANMAKU_LINE_COUNT)
            .coerceIn(MIN_DANMAKU_LINE_COUNT, MAX_DANMAKU_LINE_COUNT)
    }

    fun setDanmakuLineCount(count: Int) {
        prefs.edit()
            .putInt(KEY_DANMAKU_LINE_COUNT, count.coerceIn(MIN_DANMAKU_LINE_COUNT, MAX_DANMAKU_LINE_COUNT))
            .apply()
    }

    fun getDanmakuSpeedPercent(): Int {
        return prefs.getInt(KEY_DANMAKU_SPEED_PERCENT, DEFAULT_DANMAKU_SPEED_PERCENT)
            .coerceIn(MIN_DANMAKU_SPEED_PERCENT, MAX_DANMAKU_SPEED_PERCENT)
    }

    fun setDanmakuSpeedPercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_DANMAKU_SPEED_PERCENT, percent.coerceIn(MIN_DANMAKU_SPEED_PERCENT, MAX_DANMAKU_SPEED_PERCENT))
            .apply()
    }

    fun getDanmakuOpacityPercent(): Int {
        return prefs.getInt(KEY_DANMAKU_OPACITY_PERCENT, DEFAULT_DANMAKU_OPACITY_PERCENT)
            .coerceIn(MIN_DANMAKU_OPACITY_PERCENT, MAX_DANMAKU_OPACITY_PERCENT)
    }

    fun setDanmakuOpacityPercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_DANMAKU_OPACITY_PERCENT, percent.coerceIn(MIN_DANMAKU_OPACITY_PERCENT, MAX_DANMAKU_OPACITY_PERCENT))
            .apply()
    }

    fun getDanmakuFontSizeSp(): Int {
        return prefs.getInt(KEY_DANMAKU_FONT_SIZE_SP, DEFAULT_DANMAKU_FONT_SIZE_SP)
            .coerceIn(MIN_DANMAKU_FONT_SIZE_SP, MAX_DANMAKU_FONT_SIZE_SP)
    }

    fun setDanmakuFontSizeSp(sizeSp: Int) {
        prefs.edit()
            .putInt(KEY_DANMAKU_FONT_SIZE_SP, sizeSp.coerceIn(MIN_DANMAKU_FONT_SIZE_SP, MAX_DANMAKU_FONT_SIZE_SP))
            .apply()
    }

    fun isDanmakuBoldEnabled(): Boolean {
        return prefs.getBoolean(KEY_DANMAKU_BOLD, DEFAULT_DANMAKU_BOLD)
    }

    fun setDanmakuBoldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DANMAKU_BOLD, enabled).apply()
    }

    fun isDanmakuMergeDuplicatesEnabled(): Boolean {
        return prefs.getBoolean(KEY_DANMAKU_MERGE_DUPLICATES, DEFAULT_DANMAKU_MERGE_DUPLICATES)
    }

    fun setDanmakuMergeDuplicatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DANMAKU_MERGE_DUPLICATES, enabled).apply()
    }

    fun getStreamingQuality(): String {
        val saved = prefs.getString(KEY_STREAMING_QUALITY, DEFAULT_STREAMING_QUALITY)
        return if (saved in STREAMING_QUALITY_OPTIONS) {
            saved!!
        } else {
            DEFAULT_STREAMING_QUALITY
        }
    }

    fun setStreamingQuality(quality: String) {
        val value = if (quality in STREAMING_QUALITY_OPTIONS) {
            quality
        } else {
            DEFAULT_STREAMING_QUALITY
        }
        prefs.edit().putString(KEY_STREAMING_QUALITY, value).apply()
    }

    fun getMaxStreamingBitrate(): Int? {
        return TranscodeProfiles.byLabel(getStreamingQuality())?.maxBitrate
    }

    fun getStreamingQualityMaxHeight(): Int? {
        return TranscodeProfiles.byLabel(getStreamingQuality())?.maxHeight
    }

    fun getAudioTranscodeMode(): AudioTranscodeMode {
        val saved = prefs.getString(
            KEY_AUDIO_TRANSCODE_MODE,
            AudioTranscodeMode.AUTO.preferenceValue
        )
        return AudioTranscodeMode.fromPreferenceValue(saved)
    }

    fun setAudioTranscodeMode(mode: AudioTranscodeMode) {
        prefs.edit()
            .putString(KEY_AUDIO_TRANSCODE_MODE, mode.preferenceValue)
            .apply()
    }

    fun getSubtitleTextSize(): String {
        val saved = prefs.getString(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE)
        return if (saved in SUBTITLE_TEXT_SIZE_OPTIONS) saved!! else DEFAULT_SUBTITLE_TEXT_SIZE
    }

    fun setSubtitleTextSize(size: String) {
        val value = if (size in SUBTITLE_TEXT_SIZE_OPTIONS) size else DEFAULT_SUBTITLE_TEXT_SIZE
        prefs.edit().putString(KEY_SUBTITLE_TEXT_SIZE, value).apply()
    }

    fun getSubtitleTextColor(): String {
        val saved = prefs.getString(KEY_SUBTITLE_TEXT_COLOR, DEFAULT_SUBTITLE_TEXT_COLOR)
        return if (saved in SUBTITLE_TEXT_COLOR_OPTIONS) saved!! else DEFAULT_SUBTITLE_TEXT_COLOR
    }

    fun setSubtitleTextColor(color: String) {
        val value = if (color in SUBTITLE_TEXT_COLOR_OPTIONS) color else DEFAULT_SUBTITLE_TEXT_COLOR
        prefs.edit().putString(KEY_SUBTITLE_TEXT_COLOR, value).apply()
    }

    fun getSubtitleBackgroundColor(): String {
        val saved = prefs.getString(KEY_SUBTITLE_BACKGROUND_COLOR, DEFAULT_SUBTITLE_BACKGROUND_COLOR)
        return if (saved in SUBTITLE_BACKGROUND_OPTIONS) saved!! else DEFAULT_SUBTITLE_BACKGROUND_COLOR
    }

    fun setSubtitleBackgroundColor(color: String) {
        val value = if (color in SUBTITLE_BACKGROUND_OPTIONS) color else DEFAULT_SUBTITLE_BACKGROUND_COLOR
        prefs.edit().putString(KEY_SUBTITLE_BACKGROUND_COLOR, value).apply()
    }

    fun getSubtitleEdgeType(): String {
        val saved = prefs.getString(KEY_SUBTITLE_EDGE_TYPE, DEFAULT_SUBTITLE_EDGE_TYPE)
        return if (saved in SUBTITLE_EDGE_TYPE_OPTIONS) saved!! else DEFAULT_SUBTITLE_EDGE_TYPE
    }

    fun setSubtitleEdgeType(edgeType: String) {
        val value = if (edgeType in SUBTITLE_EDGE_TYPE_OPTIONS) edgeType else DEFAULT_SUBTITLE_EDGE_TYPE
        prefs.edit().putString(KEY_SUBTITLE_EDGE_TYPE, value).apply()
    }

    fun getSubtitleTextOpacityPercent(): Int {
        return prefs.getInt(
            KEY_SUBTITLE_TEXT_OPACITY_PERCENT,
            DEFAULT_SUBTITLE_TEXT_OPACITY_PERCENT
        ).coerceIn(0, MAX_SUBTITLE_OPACITY_PERCENT)
    }

    fun setSubtitleTextOpacityPercent(percent: Int) {
        prefs.edit()
            .putInt(
                KEY_SUBTITLE_TEXT_OPACITY_PERCENT,
                percent.coerceIn(0, MAX_SUBTITLE_OPACITY_PERCENT)
            )
            .apply()
    }

    fun getSubtitlePosition(): Int {
        return getSubtitleBottomEdgePositionPercent()
    }

    fun getSubtitleBottomEdgePositionPercent(): Int {
        return prefs.getInt(
            KEY_SUBTITLE_BOTTOM_EDGE_PERCENT,
            DEFAULT_SUBTITLE_BOTTOM_EDGE_PERCENT
        ).coerceIn(0, MAX_SUBTITLE_EDGE_PERCENT)
    }

    fun setSubtitleBottomEdgePositionPercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_SUBTITLE_BOTTOM_EDGE_PERCENT, percent.coerceIn(0, MAX_SUBTITLE_EDGE_PERCENT))
            .apply()
    }

    fun getSubtitleTopEdgePositionPercent(): Int {
        return prefs.getInt(
            KEY_SUBTITLE_TOP_EDGE_PERCENT,
            DEFAULT_SUBTITLE_TOP_EDGE_PERCENT
        ).coerceIn(0, MAX_SUBTITLE_EDGE_PERCENT)
    }

    fun setSubtitleTopEdgePositionPercent(percent: Int) {
        prefs.edit()
            .putInt(KEY_SUBTITLE_TOP_EDGE_PERCENT, percent.coerceIn(0, MAX_SUBTITLE_EDGE_PERCENT))
            .apply()
    }

    fun getPreferredAudioStreamIndex(itemId: String): Int? {
        val key = audioStreamKey(itemId)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun setPreferredAudioStreamIndex(itemId: String, streamIndex: Int?) {
        val key = audioStreamKey(itemId)
        val subtitleExists = prefs.contains(subtitleStreamKey(itemId))
        prefs.edit().apply {
            if (streamIndex == null) {
                remove(key)
            } else {
                putInt(key, streamIndex)
            }
            if (streamIndex == null && !subtitleExists) {
                remove(streamUpdatedAtKey(itemId))
            } else {
                putLong(streamUpdatedAtKey(itemId), System.currentTimeMillis())
            }
        }.apply()
        prunePreferredStreamIndexesIfNeeded()
    }

    fun getPreferredSubtitleStreamIndex(itemId: String): Int? {
        val key = subtitleStreamKey(itemId)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun setPreferredSubtitleStreamIndex(itemId: String, streamIndex: Int?) {
        val key = subtitleStreamKey(itemId)
        val audioExists = prefs.contains(audioStreamKey(itemId))
        prefs.edit().apply {
            if (streamIndex == null) {
                remove(key)
            } else {
                putInt(key, streamIndex)
            }
            if (streamIndex == null && !audioExists) {
                remove(streamUpdatedAtKey(itemId))
            } else {
                putLong(streamUpdatedAtKey(itemId), System.currentTimeMillis())
            }
        }.apply()
        prunePreferredStreamIndexesIfNeeded()
    }

    private fun audioStreamKey(itemId: String): String {
        return "$KEY_AUDIO_STREAM_INDEX_PREFIX$itemId"
    }

    private fun subtitleStreamKey(itemId: String): String {
        return "$KEY_SUBTITLE_STREAM_INDEX_PREFIX$itemId"
    }

    private fun streamUpdatedAtKey(itemId: String): String {
        return "$KEY_STREAM_INDEX_UPDATED_AT_PREFIX$itemId"
    }

    private fun prunePreferredStreamIndexesIfNeeded() {
        val itemIds = mutableSetOf<String>()
        prefs.all.keys.forEach { key ->
            when {
                key.startsWith(KEY_AUDIO_STREAM_INDEX_PREFIX) -> {
                    itemIds.add(key.removePrefix(KEY_AUDIO_STREAM_INDEX_PREFIX))
                }
                key.startsWith(KEY_SUBTITLE_STREAM_INDEX_PREFIX) -> {
                    itemIds.add(key.removePrefix(KEY_SUBTITLE_STREAM_INDEX_PREFIX))
                }
            }
        }

        if (itemIds.size <= MAX_PREFERRED_STREAM_ITEMS) return

        val toRemoveCount = itemIds.size - MAX_PREFERRED_STREAM_ITEMS
        val oldestItems = itemIds
            .map { itemId ->
                itemId to prefs.getLong(streamUpdatedAtKey(itemId), 0L)
            }
            .sortedBy { it.second }
            .take(toRemoveCount)

        prefs.edit().apply {
            oldestItems.forEach { (itemId, _) ->
                remove(audioStreamKey(itemId))
                remove(subtitleStreamKey(itemId))
                remove(streamUpdatedAtKey(itemId))
            }
        }.apply()
    }
}
