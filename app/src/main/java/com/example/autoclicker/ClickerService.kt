package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class RedBlob(var x: Float, var y: Float, val size: Int) {
    var vx: Float = 0f
    var vy: Float = 0f
}

class RedSnap(val t: Long, val w: Int, val h: Int, val list: List<RedBlob>)

class ClickerService : AccessibilityService() {

    private val tapIntervalMs = 5L
    private val totalMs = 3000L
    private val batchSize = 20
    private val minBlob = 2
    private val maxPoints = 100
    private val leadMs = 40L
    private val maxPredictMs = 600L
    private val matchDist = 250f
    private val maxSpeed = 3f

    private lateinit var wm: WindowManager
    private var btn: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var snap: RedSnap? = null
    private var px = IntArray(0)
    private var queue = IntArray(0)
    private var seen = BooleanArray(0)

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        val b = btn
        if (b != null) {
            try {
                wm.removeView(b)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    private fun setLabel(s: String) {
        ui.post { btn?.text = s }
    }

    private fun showFloatingButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val b = TextView(this)
        b.text = "红"
        b.gravity = Gravity.CENTER
        b.textSize = 18f
        b.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xCC3F51B5.toInt())
        b.background = bg

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 400

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = ev.rawX - downX
                    val my = ev.rawY - downY
                    if (Math.abs(mx) > 10 || Math.abs(my) > 10) moved = true
                    if (moved) {
                        lp.x = startX + mx.toInt()
                        lp.y = startY + my.toInt()
                        wm.updateViewLayout(b, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                }
            }
            true
        }
        wm.addView(b, lp)
        btn = b
    }

    private fun toggle() {
        val running = job
        if (running != null && running.isActive) {
            running.cancel()
            setLabel("红")
            return
        }
        snap = null
        job = scope.launch {
            setLabel("停")
            val end = SystemClock.elapsedRealtime() + totalMs

            val scanner = launch {
                var prev: List<RedBlob> = emptyList()
                var prevT = 0L
                while (isActive) {
                    val bmp = shot()
                    if (bmp == null) {
                        delay(30)
                        continue
                    }
                    val t = SystemClock.elapsedRealtime()
                    val w = bmp.width
                    val h = bmp.height
                    val list = findRed(bmp)
                    bmp.recycle()
                    track(list, prev, t - prevT)
                    prev = list
                    prevT = t
                    snap = RedSnap(t, w, h, list)
                }
            }

            try {
                var idx = 0
                val xs = FloatArray(batchSize)
                val ys = FloatArray(batchSize)
                while (isActive && SystemClock.elapsedRealtime() < end) {
                    val s = snap
                    if (s == null || s.list.isEmpty()) {
                        delay(8)
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    for (i in 0 until batchSize) {
                        val tg = s.list[idx % s.list.size]
                        idx++
                        var dt = now - s.t + leadMs + i * tapIntervalMs
                        if (dt > maxPredictMs) dt = maxPredictMs
                        val dtf = dt.toFloat()
                        xs[i] = (tg.x + tg.vx * dtf).coerceIn(0f, (s.w - 1).toFloat())
                        ys[i] = (tg.y + tg.vy * dtf).coerceIn(0f, (s.h - 1).toFloat())
                    }
                    val ok = tapBatch(xs, ys)
                    if (!ok) delay(5)
                }
            } finally {
                scanner.cancel()
                setLabel("红")
            }
        }
    }

    private suspend fun shot(): Bitmap? = suspendCancellableCoroutine<Bitmap?> { cont ->
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                var out: Bitmap? = null
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (hw != null) {
       
