package com.oneproxyclick.feature.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.oneproxyclick.core.common.LanguageManager
import com.oneproxyclick.core.interceptor.CaptureEvents
import com.oneproxyclick.core.interceptor.InterceptorBus
import com.oneproxyclick.core.interceptor.PendingIntercept
import com.oneproxyclick.core.vpn.TunnelState
import com.oneproxyclick.core.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating control (TYPE_APPLICATION_OVERLAY, plain Views).
 *
 * Collapsed bubble: LED (green = tunnel on, grey = off, orange = request held).
 * - single tap  → toggle the VPN tunnel
 * - double tap / long press → expand to the mini HUD
 * - drag → move (snaps to the nearest screen edge)
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var bubble: FrameLayout
    private lateinit var bubbleLabel: TextView
    private lateinit var led: View
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private lateinit var hud: LinearLayout
    private lateinit var hudParams: WindowManager.LayoutParams
    private lateinit var hudStatus: TextView
    private lateinit var hudPending: TextView
    private lateinit var hudVpnButton: Button
    private lateinit var hudInterceptSwitch: Switch
    private lateinit var hudForward: Button
    private lateinit var hudDrop: Button
    private lateinit var hudForwardAll: Button

    private var expanded = false
    private lateinit var l10n: android.content.Context
    private var lastState = UiState(TunnelState.STOPPED, emptyList(), false, null)

    private data class UiState(
        val tunnel: TunnelState,
        val queue: List<PendingIntercept>,
        val intercept: Boolean,
        val port: Int?,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        l10n = LanguageManager.localized(this)
        startInForeground()
        wm = getSystemService(WindowManager::class.java)
        buildBubble()
        buildHud()
        runCatching { wm.addView(bubble, bubbleParams) }.onFailure {
            Toast.makeText(this, tr(R.string.overlay_permission_missing), Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        _running.value = true
        scope.launch {
            combine(
                VpnController.state,
                InterceptorBus.queue,
                InterceptorBus.interceptEnabled,
                VpnController.activePort,
            ) { tunnel, queue, intercept, port -> UiState(tunnel, queue, intercept, port) }
                .collect { render(it) }
        }
        // Language switch: rebuild the HUD texts and the notification in place.
        scope.launch {
            LanguageManager.language.drop(1).collect { applyLanguage() }
        }
    }

    private fun tr(id: Int, vararg args: Any): String = l10n.getString(id, *args)

    private fun applyLanguage() {
        l10n = LanguageManager.localized(this)
        val wasExpanded = expanded
        val x = hudParams.x
        val y = hudParams.y
        if (wasExpanded) runCatching { wm.removeView(hud) }
        buildHud()
        hudParams.x = x
        hudParams.y = y
        if (wasExpanded) runCatching { wm.addView(hud, hudParams) }
        render(lastState)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        runCatching { if (bubble.isAttachedToWindow) wm.removeView(bubble) }
        runCatching { if (hud.isAttachedToWindow) wm.removeView(hud) }
        super.onDestroy()
    }

    // ---- rendering -----------------------------------------------------------------------

    private fun render(state: UiState) {
        lastState = state
        val held = state.queue.size
        val color = when {
            held > 0 -> COLOR_ORANGE
            state.tunnel == TunnelState.RUNNING -> COLOR_GREEN
            state.tunnel == TunnelState.STARTING -> COLOR_YELLOW
            state.tunnel == TunnelState.ERROR -> COLOR_RED
            else -> COLOR_GREY
        }
        (led.background as GradientDrawable).setColor(color)
        (bubble.background as GradientDrawable).setStroke(dp(2), color)
        bubbleLabel.text = if (held > 0) held.toString() else "OPC"

        hudStatus.text = when (state.tunnel) {
            TunnelState.RUNNING -> tr(R.string.overlay_status_on, state.port?.toString() ?: "?")
            TunnelState.STARTING -> tr(R.string.overlay_status_starting)
            TunnelState.ERROR -> tr(R.string.overlay_status_error, VpnController.lastError.value ?: tr(R.string.overlay_unknown))
            TunnelState.STOPPED -> tr(R.string.overlay_status_off)
        }
        hudStatus.setTextColor(color)
        hudVpnButton.text = tr(if (VpnController.isActive) R.string.overlay_vpn_off else R.string.overlay_vpn_on)

        hudInterceptSwitch.setOnCheckedChangeListener(null)
        hudInterceptSwitch.isChecked = state.intercept
        hudInterceptSwitch.setOnCheckedChangeListener { _, checked -> InterceptorBus.setInterceptEnabled(checked) }

        val first = state.queue.firstOrNull()
        hudPending.text = if (first == null) {
            tr(if (state.intercept) R.string.overlay_waiting else R.string.overlay_passthrough)
        } else {
            "[$held] ${first.summary}"
        }
        hudPending.setTextColor(if (first != null) COLOR_ORANGE else COLOR_TEXT_DIM)
        listOf(hudForward, hudDrop, hudForwardAll).forEach {
            it.isEnabled = first != null
            it.alpha = if (first != null) 1f else 0.4f
        }
    }

    // ---- actions -------------------------------------------------------------------------

    private fun onBubbleTap() {
        if (!VpnController.isPermissionGranted(this)) {
            Toast.makeText(this, tr(R.string.overlay_vpn_permission_needed), Toast.LENGTH_SHORT).show()
            openApp(requestVpn = true)
            return
        }
        VpnController.toggle(this)
    }

    private fun openApp(requestVpn: Boolean = false) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (requestVpn) {
            intent.putExtra(OverlayContract.EXTRA_REQUEST_VPN_PERMISSION, true)
        } else if (lastState.queue.isNotEmpty()) {
            intent.putExtra(OverlayContract.EXTRA_OPEN_TAB, OverlayContract.TAB_INTERCEPT)
        } else {
            intent.putExtra(OverlayContract.EXTRA_OPEN_TAB, OverlayContract.TAB_HISTORY)
            CaptureEvents.lastTransactionId.value?.let { intent.putExtra(OverlayContract.EXTRA_TRANSACTION_ID, it) }
        }
        startActivity(intent)
        if (expanded) collapse()
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        hudParams.x = bubbleParams.x.coerceAtMost(resources.displayMetrics.widthPixels - dp(HUD_WIDTH_DP))
            .coerceAtLeast(0)
        hudParams.y = bubbleParams.y
        runCatching { wm.removeView(bubble) }
        runCatching { wm.addView(hud, hudParams) }
        render(lastState)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        runCatching { wm.removeView(hud) }
        runCatching { wm.addView(bubble, bubbleParams) }
    }

    // ---- views -----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = dp(BUBBLE_DP)
        bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_BG)
                setStroke(dp(2), COLOR_GREY)
            }
            elevation = dp(6).toFloat()
        }
        bubbleLabel = TextView(this).apply {
            text = "OPC"
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        bubble.addView(bubbleLabel, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        led = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_GREY)
                setStroke(dp(1), COLOR_BG)
            }
        }
        bubble.addView(led, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(3)
            marginEnd = dp(3)
        })
        bubbleParams = overlayParams(size, size).apply {
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = resources.displayMetrics.heightPixels / 3
        }
        bubble.setOnTouchListener(DragTouchListener(bubble, { bubbleParams }, snap = true, gestures = true))
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildHud() {
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(COLOR_BG)
                setStroke(dp(1), COLOR_BORDER)
            }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "OneProxyClick"
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("—") { collapse() })
        header.addView(smallButton("✕") { stopSelf() })
        hud.addView(header)

        hudStatus = monoText(12f)
        hud.addView(hudStatus)

        val toggles = row()
        hudVpnButton = smallButton(tr(R.string.overlay_vpn_on)) { onBubbleTap() }
        toggles.addView(hudVpnButton)
        hudInterceptSwitch = Switch(this).apply {
            text = tr(R.string.overlay_intercept)
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            // Same high-contrast palette as the Compose switches (visible when OFF on dark bg).
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#FFE2E8F0")))
            trackTintList = ColorStateList(states, intArrayOf(COLOR_ORANGE, Color.parseColor("#FF8A93A2")))
            trackTintMode = PorterDuff.Mode.SRC_IN
        }
        toggles.addView(hudInterceptSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hud.addView(toggles)

        hudPending = monoText(12f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(6))
        }
        hud.addView(hudPending)

        val actions = row()
        hudForward = smallButton(tr(R.string.overlay_forward), COLOR_GREEN) { InterceptorBus.forwardFirst() }
        hudDrop = smallButton(tr(R.string.overlay_drop), COLOR_RED) { InterceptorBus.dropFirst() }
        hudForwardAll = smallButton(tr(R.string.overlay_forward_all), COLOR_ORANGE) { InterceptorBus.forwardAll() }
        actions.addView(hudForward, weighted())
        actions.addView(hudDrop, weighted())
        actions.addView(hudForwardAll, weighted())
        hud.addView(actions)

        val footer = row()
        footer.addView(smallButton(tr(R.string.overlay_open_app)) { openApp() }, weighted())
        footer.addView(smallButton(tr(R.string.overlay_collapse)) { collapse() }, weighted())
        hud.addView(footer)

        hudParams = overlayParams(dp(HUD_WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT)
        // Drag the HUD by its title.
        header.setOnTouchListener(DragTouchListener(hud, { hudParams }, snap = false, gestures = false))
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, 0)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(4)
    }

    private fun monoText(sp: Float) = TextView(this).apply {
        setTextColor(COLOR_TEXT_DIM)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun smallButton(label: String, accent: Int = COLOR_BORDER, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minHeight = dp(36)
        minimumHeight = dp(36)
        minWidth = dp(44)
        minimumWidth = dp(44)
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(COLOR_SURFACE)
            setStroke(dp(1), accent)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Drag + (optional) tap / double-tap / long-press handling for an overlay window. */
    private inner class DragTouchListener(
        private val view: View,
        private val params: () -> WindowManager.LayoutParams,
        private val snap: Boolean,
        private val gestures: Boolean,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@FloatingOverlayService).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        private val detector = GestureDetector(this@FloatingOverlayService, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onBubbleTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { expand(); return true }
            override fun onLongPress(e: MotionEvent) { if (!dragging) expand() }
        })

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        if (gestures) {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            detector.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = (startY + dy.toInt()).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (snap) snapToEdge(view, p)
                        dragging = false
                        return true
                    }
                }
            }
            if (gestures && !dragging) detector.onTouchEvent(event)
            return true
        }

        private fun snapToEdge(view: View, p: WindowManager.LayoutParams) {
            val screenW = resources.displayMetrics.widthPixels
            val w = view.width.takeIf { it > 0 } ?: dp(BUBBLE_DP)
            val center = p.x + w / 2
            p.x = if (center < screenW / 2) dp(4) else screenW - w - dp(4)
            if (abs(p.x) > screenW) p.x = screenW - w
            runCatching { wm.updateViewLayout(view, p) }
        }
    }

    // ---- foreground --------------------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        run {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val hideIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle("OneProxyClick")
            .setContentText(tr(R.string.overlay_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, tr(R.string.overlay_action_hide), hideIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val ACTION_HIDE = "com.oneproxyclick.overlay.HIDE"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 4202
        private const val BUBBLE_DP = 52
        private const val HUD_WIDTH_DP = 300

        private val COLOR_BG = Color.parseColor("#F2161B22")
        private val COLOR_SURFACE = Color.parseColor("#FF21262D")
        private val COLOR_BORDER = Color.parseColor("#FF30363D")
        private val COLOR_TEXT_DIM = Color.parseColor("#FF8B949E")
        private val COLOR_GREEN = Color.parseColor("#FF3FB950")
        private val COLOR_GREY = Color.parseColor("#FF6E7681")
        private val COLOR_ORANGE = Color.parseColor("#FFFF8C1A")
        private val COLOR_YELLOW = Color.parseColor("#FFD29922")
        private val COLOR_RED = Color.parseColor("#FFF85149")

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
