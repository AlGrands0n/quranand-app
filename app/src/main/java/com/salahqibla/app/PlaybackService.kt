package com.salahqibla.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Runs only while a recitation plays and background playback is enabled.
 * Keeps the app process alive with the screen off. Its notification has a stop action.
 */
class PlaybackService : Service() {

    override fun onCreate() {
        super.onCreate()
        PrayerScheduler.ensureChannels(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PrayerScheduler.CH_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.play_text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.play_stop), stop)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PlaybackControl.requestPause?.invoke()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 4418
        const val ACTION_STOP = "com.miqat.app.PLAY_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}

/** Shared playback state between the bridge, the service and the activity. */
object PlaybackControl {
    @Volatile var playing = false
    /** Set by MainActivity: pauses the recitation in the web page. */
    @Volatile var requestPause: (() -> Unit)? = null
}
