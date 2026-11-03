package com.salahqibla.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Plays the full adhan when a prayer time arrives, even with the screen off.
 * It stops when the user presses volume down, or taps the stop action in the notification.
 * Only the current adhan stops; the next prayer still plays.
 */
class AdhanService : Service() {

    private var player: MediaPlayer? = null
    private var finished = false
    private var notifId = NOTIF_ID
    private var title = ""
    private var body = ""

    // Any volume decrease stops the adhan. The volume key may change the media, alarm or ring
    // stream depending on what the phone considers active, so every stream counts.
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != VOLUME_CHANGED) return
            val now = i.getIntExtra(EXTRA_NOW, -1)
            val prev = i.getIntExtra(EXTRA_PREV, -1)
            if (now >= 0 && prev >= 0 && now < prev) finish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        PrayerScheduler.ensureChannels(this)
        ContextCompat.registerReceiver(
            this,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (player != null || finished) return START_NOT_STICKY

        notifId = intent?.getIntExtra("id", NOTIF_ID) ?: NOTIF_ID
        title = intent?.getStringExtra("title") ?: getString(R.string.app_name)
        body = intent?.getStringExtra("body") ?: ""

        // Must be called right away when the service starts in the foreground.
        startForeground(RUN_NOTIF_ID, runningNotification())

        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val mp = MediaPlayer()
            resources.openRawResourceFd(R.raw.adhan).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setAudioAttributes(attrs)
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setOnCompletionListener { finish() }
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            finish()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Stops the adhan and replaces the running notification with the normal prayer notification. */
    private fun finish() {
        if (finished) return
        finished = true
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (title.isNotEmpty()) PrayerScheduler.postPrayerNotification(this, notifId, title, body)
        stopSelf()
    }

    private fun runningNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 6,
            Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, PrayerScheduler.CH_ADHAN_RUN)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.adhan_stop), stop)
            .build()
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) { }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4420
        private const val RUN_NOTIF_ID = 4419
        const val ACTION_STOP = "com.inaba.app.ADHAN_STOP"

        // The volume broadcast and its extras are hidden in the public SDK, so the literal names are used.
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_NOW = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
