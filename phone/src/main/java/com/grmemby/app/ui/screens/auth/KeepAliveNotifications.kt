package com.grmemby.app.ui.screens.auth

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grmemby.app.R

internal class KeepAliveNotificationManager(
    context: Context
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    fun showRunning() {
        show(
            content = "保号后台运行中",
            ongoing = true,
            priority = NotificationCompat.PRIORITY_LOW
        )
    }

    fun showCompleted() {
        show(
            content = "保号运行完成",
            ongoing = false,
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    private fun show(
        content: String,
        ongoing: Boolean,
        priority: Int
    ) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("一键保号")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(priority)
            .build()
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "一键保号",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保号后台运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "server_keep_alive"
        const val NOTIFICATION_ID = 4206
    }
}
