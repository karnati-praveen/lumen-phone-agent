package com.aegis.core.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Persistent lightweight (~45 MB) Foreground Service (specialUse | mediaProjection) for Process 1 survival,
 * fallback Edge Glyph + Bottom HUD display, and instant Emergency Kill Switch floating pill.
 */
class AegisOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var pillView: View? = null
    private var fallbackHudManager: AegisEdgeGlyphWindowManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        serviceScope.launch {
            AegisLiveHudState.state.collectLatest { snap ->
                val a11yRunning = com.aegis.hands.AegisAccessibilityService.instance != null
                if (!a11yRunning && Settings.canDrawOverlays(this@AegisOverlayService)) {
                    if (fallbackHudManager == null) {
                        fallbackHudManager = AegisEdgeGlyphWindowManager(
                            this@AegisOverlayService,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        ).also { it.attach() }
                    }
                    fallbackHudManager?.render(snap)
                } else {
                    fallbackHudManager?.render(HudSnapshot(mode = HudGlowMode.IDLE))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EMERGENCY_KILL -> {
                onEmergencyAbortRequested?.invoke()
                sendBroadcast(Intent(ACTION_EMERGENCY_KILL).setPackage(packageName))
            }
            ACTION_SHOW_OVERLAY -> showFloatingPillIfPermitted()
            ACTION_HIDE_OVERLAY -> removeFloatingPill()
            ACTION_START_PROJECTION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    runCatching { startForeground(NOTIFICATION_ID, buildNotification(), types) }
                }
            }
            else -> Unit // Foreground notification stays active; floating pill is opt-in via ACTION_SHOW_OVERLAY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fallbackHudManager?.detach()
        fallbackHudManager = null
        serviceScope.cancel()
        removeFloatingPill()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFloatingPillIfPermitted() {
        if (!Settings.canDrawOverlays(this) || pillView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 60f
                setColor(Color.parseColor("#E6141418"))
                setStroke(2, Color.parseColor("#FFEF4444"))
            }
        }

        val label = TextView(this).apply {
            text = "Stop Aegis"
            textSize = 12f
            setTextColor(Color.parseColor("#FFF4F4F5"))
        }
        container.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 420
        }

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false

        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - event.rawX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onEmergencyAbortRequested?.invoke()
                        sendBroadcast(Intent(ACTION_EMERGENCY_KILL).setPackage(packageName))
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            pillView = container
        }
    }

    private fun removeFloatingPill() {
        pillView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        pillView = null
    }

    private fun buildNotification(): Notification {
        val abortIntent = Intent(this, AegisOverlayService::class.java).apply {
            action = ACTION_EMERGENCY_KILL
        }
        val abortPending = PendingIntent.getService(
            this,
            101,
            abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aegis Isolated Guard Active")
            .setContentText("0 NET • Rust Broker Armed • Vol-Down Kill Switch Ready")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Emergency Abort",
                    abortPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aegis Security Shell",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status indicator for Aegis on-device security shell"
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_EMERGENCY_KILL = "com.aegis.core.ACTION_EMERGENCY_KILL"
        const val ACTION_SHOW_OVERLAY = "com.aegis.core.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.aegis.core.ACTION_HIDE_OVERLAY"
        const val ACTION_START_PROJECTION = "com.aegis.core.ACTION_START_PROJECTION"
        private const val CHANNEL_ID = "aegis_security_shell"
        private const val NOTIFICATION_ID = 2101

        @Volatile
        var onEmergencyAbortRequested: (() -> Unit)? = null
    }
}
