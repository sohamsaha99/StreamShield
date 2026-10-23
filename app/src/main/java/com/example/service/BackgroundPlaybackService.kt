package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class BackgroundPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_BG_PLAY) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "StreamShield Background Audio"
        val channel = intent?.getStringExtra(EXTRA_CHANNEL) ?: "Ad-Free Stream"
        val speedBadge = intent?.getStringExtra(EXTRA_SPEED) ?: "1.0x"

        val notification = buildNotification(title, channel, speedBadge)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // In case foreground service permission is restricted on certain OS states
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamShield::BackgroundAudioWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Up to 4 hours
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_bg_playback_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_bg_playback_desc)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, author: String, speedBadge: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = ACTION_STOP_BG_PLAY
        }
        val pendingStop = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("$author • Speed $speedBadge • Shield Active")
            .setSubText("Background Playback")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Background",
                pendingStop
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "streamshield_bg_playback_channel"
        const val NOTIFICATION_ID = 4088
        const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_BG"
        const val ACTION_STOP_BG_PLAY = "com.example.action.STOP_BG"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_SPEED = "extra_speed"

        fun startOrUpdate(context: Context, title: String, channelName: String, speedBadge: String) {
            val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHANNEL, channelName)
                putExtra(EXTRA_SPEED, speedBadge)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java).apply {
                action = ACTION_STOP_BG_PLAY
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
