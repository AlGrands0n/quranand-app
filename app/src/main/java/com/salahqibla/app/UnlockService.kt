package com.salahqibla.app

import android.app.Notification
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Keeps a small adhkar bubble that appears over other apps every time the phone is unlocked.
 * Tapping the bubble shows one dhikr card and removes the bubble until the next unlock.
 */
class UnlockService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubble: View? = null
    private var card: View? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) showBubble()
        }
    }

    override fun onCreate() {
        super.onCreate()
        PrayerScheduler.ensureChannels(this)
        val notification: Notification = NotificationCompat.Builder(this, PrayerScheduler.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_miqat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(NOTIF_ID, notification)
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        removeView(bubble); bubble = null
        removeView(card); card = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return

        val icon = ImageView(this).apply {
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

        val params = WindowManager.LayoutParams(
            dp(54), dp(54),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(220)
        }

        // Drag to move; a short tap opens the dhikr card.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        icon.setOnTouchListener { view: View, e: MotionEvent ->
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
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX - dx.toInt()   // gravity END: x grows to the left
                    params.y = startY + dy.toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openDhikr()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(icon, params)
            bubble = icon
        } catch (_: Exception) { }
    }

    private fun openDhikr() {
        removeView(bubble); bubble = null
        if (card != null) return

        val morning = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12
        val list = resources.getStringArray(if (morning) R.array.dhikr_morning else R.array.dhikr_evening)
        val text = list[((System.currentTimeMillis() / 60_000) % list.size).toInt()]

        val title = TextView(this).apply {
            setText(R.string.dhikr_title)
            textSize = 13f
            setTextColor(Color.parseColor("#d4af37"))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 19f
            setLineSpacing(0f, 1.5f)
            setTextColor(Color.parseColor("#f1f7f5"))
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            setText(R.string.dhikr_close)
            textSize = 12f
            setTextColor(Color.parseColor("#93aea8"))
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F20E2723"))
                setStroke(dp(1), Color.parseColor("#22FFFFFF"))
            }
            addView(title)
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            setOnClickListener { removeView(card); card = null }
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            wm.addView(frame, params)
            card = frame
            ui.postDelayed({ removeView(card); card = null }, 25_000)
        } catch (_: Exception) { }
    }

    private fun removeView(v: View?) {
        if (v == null) return
        try { wm.removeView(v) } catch (_: Exception) { }
    }

    companion object {
        private const val NOTIF_ID = 4417

        fun start(ctx: Context) {
            val i = Intent(ctx, UnlockService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UnlockService::class.java))
        }
    }
}
