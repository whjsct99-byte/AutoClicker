package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class ClickerService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var btn: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        btn?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun setLabel(s: String) = ui.post { btn?.text = s }

    private fun showFloatingButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val b = TextView(this).apply {
            text = "宏"; gravity = Gravity.CENTER; textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC3F51B5.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }

        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX; dy = ev.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = ev.rawX - dx; val my = ev.rawY - dy
                    if (Math.abs(mx) > 10 || Math.abs(my) > 10) moved = true
                    if (moved) { lp.x = sx + mx.toInt(); lp.y = sy + my.toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleMacro()
            }
            true
        }
        wm.addView(b, lp); btn = b
    }

    private fun toggleMacro() {
        if (job?.isActive == true) { job?.cancel(); setLabel("宏"); toast("已停止"); return }
        val steps = getSharedPreferences("cfg", MODE_PRIVATE)
            .getString("steps", MainActivity.DEFAULT_STEPS)!!
            .split(Regex("[,，\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        job = scope.launch {
            setLabel("停")
            for (s in steps) {
                when {
                    s.startsWith("WAIT:", true) -> delay(s.substring(5).toLongOrNull() ?: 500)
                    s.startsWith("TAP:", true) -> {
                        val p = s.substring(4).split(":")
                        tap(p[0].toFloat(), p[1].toFloat()); delay(500)
                    }
                    else -> {
                        var ok = false
                        for (i in 0 until 15) { if (clickByText(s)) { ok = true; break }; delay(400) }
                        if (!ok) { toast("未找到：$s"); break }
                        delay(800)
                    }
                }
            }
            setLabel("宏"); toast("宏已结束")
        }
    }

    private fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null
        )
    }
}
