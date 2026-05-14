package com.grmemby.app.watchparty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.grmemby.app.R
import com.grmemby.app.ui.activity.GrmembyActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchPartyKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = WatchPartyRepository()
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            clearPersistedSession()
            stopSelf()
            return START_NOT_STICKY
        }

        val session = sessionFromIntent(intent) ?: readPersistedSession()
        if (session == null || !session.isHost || session.roomId.isBlank() || session.memberId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        persistSession(session)
        startHeartbeat(session)
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startHeartbeat(session: ActiveWatchPartySession) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching { repository.heartbeat(session.roomId, session.memberId) }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification(roomId: String): Notification {
        val launchIntent = Intent(this, GrmembyActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("一起看房间保活中")
            .setContentText("房间 $roomId 将在后台持续保活")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "一起看房间保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持房主的一起看房间在后台在线"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun sessionFromIntent(intent: Intent?): ActiveWatchPartySession? {
        intent ?: return null
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)?.takeIf { it.isNotBlank() } ?: return null
        val memberId = intent.getStringExtra(EXTRA_MEMBER_ID)?.takeIf { it.isNotBlank() } ?: return null
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME)
        val inviteText = intent.getStringExtra(EXTRA_INVITE_TEXT).orEmpty()
        return ActiveWatchPartySession(
            roomId = roomId,
            memberId = memberId,
            isHost = true,
            roomName = roomName,
            inviteText = inviteText
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun persistSession(session: ActiveWatchPartySession) {
        prefs().edit()
            .putString(KEY_ROOM_ID, session.roomId)
            .putString(KEY_MEMBER_ID, session.memberId)
            .putString(KEY_ROOM_NAME, session.roomName)
            .putString(KEY_INVITE_TEXT, session.inviteText)
            .apply()
    }

    private fun readPersistedSession(): ActiveWatchPartySession? {
        val prefs = prefs()
        val roomId = prefs.getString(KEY_ROOM_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val memberId = prefs.getString(KEY_MEMBER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return ActiveWatchPartySession(
            roomId = roomId,
            memberId = memberId,
            isHost = true,
            roomName = prefs.getString(KEY_ROOM_NAME, null),
            inviteText = prefs.getString(KEY_INVITE_TEXT, "").orEmpty()
        )
    }

    private fun clearPersistedSession() {
        prefs().edit().clear().apply()
    }

    companion object {
        private const val CHANNEL_ID = "watch_party_keep_alive"
        private const val NOTIFICATION_ID = 4104
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val ACTION_STOP = "com.grmemby.app.watchparty.STOP_KEEP_ALIVE"
        private const val EXTRA_ROOM_ID = "roomId"
        private const val EXTRA_MEMBER_ID = "memberId"
        private const val EXTRA_ROOM_NAME = "roomName"
        private const val EXTRA_INVITE_TEXT = "inviteText"
        private const val PREFS_NAME = "watch_party_keep_alive"
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_MEMBER_ID = "memberId"
        private const val KEY_ROOM_NAME = "roomName"
        private const val KEY_INVITE_TEXT = "inviteText"

        fun start(context: Context, session: ActiveWatchPartySession) {
            if (!session.isHost || session.roomId.isBlank() || session.memberId.isBlank()) return
            val intent = Intent(context, WatchPartyKeepAliveService::class.java)
                .putExtra(EXTRA_ROOM_ID, session.roomId)
                .putExtra(EXTRA_MEMBER_ID, session.memberId)
                .putExtra(EXTRA_ROOM_NAME, session.roomName)
                .putExtra(EXTRA_INVITE_TEXT, session.inviteText)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
            context.stopService(Intent(context, WatchPartyKeepAliveService::class.java))
        }

        fun restoreSession(context: Context): ActiveWatchPartySession? {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val roomId = prefs.getString(KEY_ROOM_ID, null)?.takeIf { it.isNotBlank() } ?: return null
            val memberId = prefs.getString(KEY_MEMBER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
            return ActiveWatchPartySession(
                roomId = roomId,
                memberId = memberId,
                isHost = true,
                roomName = prefs.getString(KEY_ROOM_NAME, null),
                inviteText = prefs.getString(KEY_INVITE_TEXT, "").orEmpty()
            )
        }
    }
}
