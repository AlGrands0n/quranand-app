package com.salahqibla.app

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * After every unlock, slides a dhikr message in from the screen edge with a small bubble beside it.
 * Tapping either one dismisses the pair. Both are removed when the screen turns off.
 */
class UnlockService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var dock: View? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> showDock()
                Intent.ACTION_SCREEN_OFF -> removeDock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PrayerScheduler.ensureChannels(this)

        // A foreground service must show a notification. This one is as quiet as the system allows:
        // minimum importance (no status-bar icon), hidden on the lock screen, one stop action.
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, UnlockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, PrayerScheduler.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.service_stop), stop)
            .build()
        startForeground(NOTIF_ID, notification)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PrayerScheduler.setBubbleEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        removeDock()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun currentDhikr(): String {
        val morning = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12
        val list = resources.getStringArray(if (morning) R.array.dhikr_morning else R.array.dhikr_evening)
        return list[((System.currentTimeMillis() / 60_000) % list.size).toInt()]
    }

    private fun showDock() {
        if (dock != null || !Settings.canDrawOverlays(this)) return

        val message = TextView(this).apply {
            text = currentDhikr()
            textSize = 16f
            setLineSpacing(0f, 1.45f)
            setTextColor(Color.parseColor("#f1f7f5"))
            maxWidth = dp(250)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F20E2723"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_miqat)
            setColorFilter(Color.parseColor("#0f463e"))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#d4af37"))
                setStroke(dp(2), Color.parseColor("#66FFFFFF"))
            }
            elevation = dp(6).toFloat()
        }

        // Absolute gravity (LEFT/RIGHT, not START/END) keeps the same layout in Arabic and English:
        // the bubble always sits on the right edge, the message to its left.
        val root = FrameLayout(this).apply {
            addView(
                message,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.LEFT or Gravity.CENTER_VERTICAL
                ).apply { rightMargin = dp(64) }
            )
            addView(
                bubble,
                FrameLayout.LayoutParams(dp(54), dp(54), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(8)
            y = dp(220)
        }

        // Children are not clickable, so every touch reaches the root. A short tap dismisses; a drag moves it.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    params.x = startX - dx.toInt()   // gravity RIGHT: x is measured leftwards from the edge
                    params.y = startY + dy.toInt()
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) dismiss()
                    true
                }
                else -> true
            }
        }

        root.alpha = 0f
        root.translationX = dp(160).toFloat()
        try {
            wm.addView(root, params)
            dock = root
            root.animate().alpha(1f).translationX(0f).setDuration(320).start()
        } catch (_: Exception) { }
    }

    private fun dismiss() {
        val v = dock ?: return
        v.animate()
            .alpha(0f)
            .translationX(dp(160).toFloat())
            .setDuration(220)
            .withEndAction { removeView(v) }
            .start()
        ui.postDelayed({ removeView(v) }, 400)   // safety net if the animation is interrupted
    }

    private fun removeDock() {
        dock?.let { removeView(it) }
    }

    private fun removeView(v: View) {
        if (dock === v) dock = null
        try { wm.removeView(v) } catch (_: Exception) { }
    }

    companion object {
        private const val NOTIF_ID = 4417
        const val ACTION_STOP = "com.inaba.app.BUBBLE_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, UnlockService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UnlockService::class.java))
        }
    }
}
