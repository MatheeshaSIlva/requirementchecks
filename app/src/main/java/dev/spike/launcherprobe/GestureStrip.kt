package dev.spike.launcherprobe

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Prototype of the launcher's own bottom-edge gesture: a thin touchable overlay strip at the bottom of the
 * screen receives the finger directly (no getevent hop), and a second NOT_TOUCHABLE full-screen overlay draws
 * the "app window" card that follows the finger, then flies home or springs back.
 *
 * It only means anything while the system's own home/recents gesture is blocked (button 10i), otherwise the
 * system also handles the same swipe. It measures: frame pacing while dragging, touch-to-frame latency, and
 * whether the system home gesture really is suppressed on this device.
 */
class GestureStrip(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val goHome: () -> Unit,
    private val snapshot: () -> Bitmap?,
    private val quickSwitch: () -> Unit = {},
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)   // emphasized decelerate

    private var strip: View? = null
    private var cardRoot: FrameLayout? = null
    private var card: View? = null
    private var screenH = 0
    private var screenW = 0
    private var radius = 0f

    private var startX = 0f
    private var startY = 0f
    private var lastDy = 0f
    private var lastT = 0L
    private var vy = 0f                 // px per ms, upward positive
    private var dragging = false
    private var sideMode = false
    private var lastDx = 0f
    private var animating = false
    private var anim: ValueAnimator? = null

    private val frameDeltas = ArrayList<Double>()
    private val latencies = ArrayList<Double>()
    private var lastFrameNs = 0L
    private val stopRunnable = Runnable { stop() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) frameDeltas += (frameTimeNanos - lastFrameNs) / 1e6
            lastFrameNs = frameTimeNanos
            if (dragging || animating) choreographer.postFrameCallback(this)
        }
    }

    private fun dp(v: Int) = (v * density).toInt()

    @Suppress("DEPRECATION")
    fun start() {
        if (strip != null) { log("[strip] already running"); return }
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val v = View(ctx)
        v.setBackgroundColor(0x33FFFFFF)
        v.setOnTouchListener { _, e -> onTouch(e) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(44),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        try {
            wm.addView(v, lp)
        } catch (t: Throwable) {
            log("[strip] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        strip = v
        ui.postDelayed(stopRunnable, 90_000)
        log("[strip] running for 90 s. A faint white bar sits at the very bottom. Go to another app, then swipe up on the bar. " +
            "If you did NOT run 10i first, the system also handles the swipe.")
    }

    fun stop() {
        ui.removeCallbacks(stopRunnable)
        anim?.cancel()
        anim = null
        dragging = false
        animating = false
        removeCard()
        strip?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        strip = null
    }

    private fun removeCard() {
        cardRoot?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        cardRoot = null
        card = null
    }

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animating) { grab(e); return true }
                sideMode = false
                lastDx = 0f
                startX = e.rawX
                startY = e.rawY
                lastDy = 0f
                lastT = e.eventTime
                vy = 0f
                dragging = false
                frameDeltas.clear()
                latencies.clear()
                lastFrameNs = 0L
            }
            MotionEvent.ACTION_MOVE -> {
                if (animating) return true
                val dy = startY - e.rawY
                val dx = e.rawX - startX
                lastDx = dx
                if (!dragging) {
                    if (dy > dp(10)) { sideMode = false; beginDrag() }
                    else if (abs(dx) > dp(14) && abs(dx) > abs(dy) * 1.2f) { sideMode = true; beginDrag() }
                }
                if (dragging) {
                    updateCard(dx, dy)
                    val evMs = e.eventTime.toDouble()
                    choreographer.postFrameCallback { ns -> latencies += ns / 1e6 - evMs }
                }
                val dt = max(1L, e.eventTime - lastT)
                vy = 0.8f * vy + 0.2f * ((dy - lastDy) / dt)
                lastDy = dy
                lastT = e.eventTime
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) endDrag(e.actionMasked == MotionEvent.ACTION_UP, startY - e.rawY)
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun beginDrag() {
        // Re-read the display every time so rotation never leaves the card at the old size.
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels != screenW || dm.heightPixels != screenH) {
            log("[strip] display is now ${dm.widthPixels}x${dm.heightPixels} (was ${screenW}x$screenH), card resized")
        }
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0x00000000)
        val bmp = snapshot()
        val c: View = if (bmp != null) {
            ImageView(ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
        } else {
            View(ctx).apply { setBackgroundColor(0xFF3B6FE0.toInt()) }
        }
        c.clipToOutline = true
        c.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        root.addView(c, FrameLayout.LayoutParams(screenW, screenH))
        c.pivotX = screenW / 2f
        c.pivotY = screenH / 2f
        // Exact full-display size from the first frame, drawn into the cutout/status-bar area too, so the
        // card never gets laid out below the status bar and then resized (that showed as a stretch).
        val lp = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        try {
            wm.addView(root, lp)
        } catch (t: Throwable) {
            log("[strip] card addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        cardRoot = root
        card = c
        dragging = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
        if (bmp == null) log("[strip] note: no snapshot prefetched yet, using a plain card")
    }

    private fun updateCard(dx: Float, dy: Float) {
        val c = card ?: return
        val p = min(1f, max(0f, dy / (screenH * 0.40f)))
        val s = 1f - 0.5f * p
        c.scaleX = s
        c.scaleY = s
        c.translationY = -dy * 0.35f
        c.translationX = dx * 0.9f
        radius = dp(36) * p
        c.invalidateOutline()
        val a = min(255, (p * 4f * 255f).toInt())
        cardRoot?.setBackgroundColor((a shl 24) or 0x101820)
    }

    /** Finger lands on a card that is still animating: take over from where the card is, no restart. */
    private fun grab(e: MotionEvent) {
        val c = card
        if (c == null) return
        anim?.removeAllListeners()
        anim?.removeAllUpdateListeners()
        anim?.cancel()
        anim = null
        animating = false
        val p = min(1f, max(0f, (1f - c.scaleX) / 0.5f))
        val dyCur = p * screenH * 0.40f
        val dxCur = c.translationX / 0.9f
        startY = e.rawY + dyCur
        startX = e.rawX - dxCur
        lastDy = dyCur
        lastDx = dxCur
        lastT = e.eventTime
        vy = 0f
        sideMode = false
        c.alpha = 1f
        dragging = true
        frameDeltas.clear()
        latencies.clear()
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
        log("[strip] GRABBED mid-animation: card handed to the finger at scale ${"%.2f".format(c.scaleX)} (no restart, no jump?)")
    }

    private fun endDrag(up: Boolean, dy: Float) {
        val c = card
        val root = cardRoot
        if (c == null || root == null) { dragging = false; removeCard(); return }
        val goesHome = !sideMode && up && (dy > screenH * 0.18f || vy > 0.9f)
        val switched = sideMode && up && abs(lastDx) > screenW * 0.18f
        val dirSign = if (lastDx >= 0f) 1f else -1f
        dragging = false
        animating = true
        choreographer.postFrameCallback(frameCallback)

        val s0 = c.scaleX
        val ty0 = c.translationY
        val tx0 = c.translationX
        val r0 = radius
        val s1 = if (goesHome) 0.14f else if (switched) 0.85f else 1f
        val ty1 = if (goesHome) screenH * 0.32f else 0f
        val tx1 = if (switched) dirSign * screenW else 0f
        val r1 = if (goesHome) dp(36).toFloat() else 0f
        if (goesHome) goHome()   // home loads underneath while the card flies to the icon
        if (switched) quickSwitch()   // previous app loads underneath while the card flies off to the side

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (goesHome) 280L else 220L
        a.addUpdateListener { va ->
            val f = interp.getInterpolation(va.animatedValue as Float)
            val s = s0 + (s1 - s0) * f
            c.scaleX = s
            c.scaleY = s
            c.translationY = ty0 + (ty1 - ty0) * f
            c.translationX = tx0 + (tx1 - tx0) * f
            radius = r0 + (r1 - r0) * f
            c.invalidateOutline()
            if (goesHome || switched) c.alpha = 1f - max(0f, (f - 0.7f) / 0.3f)
            val bg = if (goesHome) 255 else ((1f - f) * 255f).toInt()
            root.setBackgroundColor((bg shl 24) or 0x101820)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                animating = false
                finish(if (goesHome) "WENT HOME" else if (switched) "QUICK-SWITCHED (sideways)" else "cancelled (springed back)", goesHome || switched, dy)
            }
        })
        anim = a
        a.start()
    }

    private fun finish(label: String, wentHome: Boolean, dy: Float) {
        val delay = if (wentHome) 150L else 0L   // let the home screen draw before the card goes away
        ui.postDelayed({ removeCard() }, delay)
        val d = if (frameDeltas.size > 3) frameDeltas.drop(1) else frameDeltas
        if (d.isEmpty()) { log("[strip] gesture too short to measure"); return }
        val sorted = d.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[min(sorted.size - 1, (sorted.size * 0.95).toInt())]
        val worst = sorted.last()
        val dropped = d.sumOf { max(0, Math.round(it / median).toInt() - 1) }
        val lat = latencies.sorted()
        val latLine = if (lat.isEmpty()) "n/a" else
            "median ${"%.1f".format(lat[lat.size / 2])} ms, p95 ${"%.1f".format(lat[min(lat.size - 1, (lat.size * 0.95).toInt())])} ms (n=${lat.size})"
        log(
            "[strip] $label; drag travel ${"%.0f".format(dy / density)} dp\n" +
                "  frames ${d.size}: median ${"%.2f".format(median)} ms, p95 ${"%.2f".format(p95)} ms, worst ${"%.2f".format(worst)} ms, dropped ~$dropped\n" +
                "  touch-event -> next frame latency: $latLine\n" +
                "  If the phone ALSO did a stock home animation under the card, the system home gesture was not blocked."
        )
    }
}
