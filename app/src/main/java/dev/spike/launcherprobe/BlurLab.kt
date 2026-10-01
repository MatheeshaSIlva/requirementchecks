package dev.spike.launcherprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Blur and glass tests. Every window is hardware accelerated (without that flag an overlay window is drawn in
 * software and RenderEffect blur does nothing). Three kinds of blur are compared:
 *   backdrop   - the window blurs what is BEHIND it (what a glass panel over the wallpaper/apps needs)
 *   effect     - RenderEffect blur on our own content (a snapshot of an app)
 *   stress     - three backdrop-blur windows moving while the radius animates
 */
class BlurLab(private val ctx: Context, private val log: (String) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val views = ArrayList<View>()

    @Suppress("DEPRECATION")
    private fun size(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun info(): String {
        val cross = if (Build.VERSION.SDK_INT >= 31) wm.isCrossWindowBlurEnabled else false
        @Suppress("DEPRECATION")
        val hz = wm.defaultDisplay.refreshRate
        return "sdk ${Build.VERSION.SDK_INT}, cross-window blur enabled: $cross, display ${"%.0f".format(hz)} Hz"
    }

    private fun params(w: Int, h: Int, blurBehind: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (blurBehind && Build.VERSION.SDK_INT >= 31) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        return lp
    }

    private fun clear() {
        views.forEach { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        views.clear()
    }

    /** Runs [onFrame] with progress 0..1 over [durMs], then reports pacing under [label]. */
    private fun animate(label: String, durMs: Double, extra: String, onFrame: (Float) -> Unit) {
        val deltas = ArrayList<Double>()
        var startNs = 0L
        var lastNs = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (startNs == 0L) startNs = ns
                if (lastNs != 0L) deltas += (ns - lastNs) / 1e6
                lastNs = ns
                val raw = ((ns - startNs) / 1e6 / durMs).coerceIn(0.0, 1.0)
                onFrame(interp.getInterpolation(raw.toFloat()))
                if (raw < 1.0) choreographer.postFrameCallback(this) else finish(label, deltas, extra)
            }
        }
        choreographer.postFrameCallback(cb)
    }

    private fun finish(label: String, deltas: List<Double>, extra: String) {
        val hw = views.firstOrNull()?.isHardwareAccelerated
        clear()
        val d = if (deltas.size > 3) deltas.drop(2) else deltas
        if (d.isEmpty()) { log("[$label] no frames"); return }
        val sorted = d.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[min(sorted.size - 1, (sorted.size * 0.95).toInt())]
        val worst = sorted.last()
        val dropped = d.sumOf { max(0, Math.round(it / median).toInt() - 1) }
        log(
            "[$label] ${info()}\n  window hardware accelerated: $hw\n  $extra\n" +
                "  frames ${d.size}: median ${"%.2f".format(median)} ms, p95 ${"%.2f".format(p95)} ms, worst ${"%.2f".format(worst)} ms, dropped ~$dropped\n" +
                "  Tell me: was the blur clearly visible, and did it look smooth or stepped?"
        )
    }

    /** 12a: one full-screen window blurring whatever is behind it, radius animated 0 -> 90 px. */
    fun backdrop() {
        if (Build.VERSION.SDK_INT < 31) { log("[backdrop blur] needs Android 12+"); return }
        clear()
        val (w, h) = size()
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0x33FFFFFF)
        val lp = params(w, h, blurBehind = true)
        lp.blurBehindRadius = 0
        try { wm.addView(root, lp) } catch (t: Throwable) { log("[backdrop blur] addView FAILED: ${t.message}"); return }
        views += root
        animate("12a backdrop blur", 700.0, "blurBehindRadius animated 0 -> 90, updated every frame (worst case)") { p ->
            lp.blurBehindRadius = (90 * p).toInt()
            try { wm.updateViewLayout(root, lp) } catch (t: Throwable) { /* gone */ }
        }
    }

    /** 12b: RenderEffect blur on a snapshot of an app (or colored blobs when no snapshot is available), card scaling. */
    fun effect(bmp: Bitmap?) {
        clear()
        val (w, h) = size()
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0x66000000)
        val card: View = if (bmp != null) ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
        } else BlobView(ctx, 0xFF3B6FE0.toInt(), 3)
        if (Build.VERSION.SDK_INT >= 31) card.setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP))
        root.addView(card, FrameLayout.LayoutParams(w, h))
        card.pivotX = w / 2f
        card.pivotY = h / 2f
        try { wm.addView(root, params(w, h, blurBehind = false)) } catch (t: Throwable) { log("[effect blur] addView FAILED: ${t.message}"); return }
        views += root
        animate("12b effect blur (snapshot)", 600.0, "RenderEffect blur radius 30, content: ${if (bmp != null) "real app snapshot" else "colored blobs (no snapshot)"}") { p ->
            val s = 1f + (0.5f - 1f) * p
            card.scaleX = s
            card.scaleY = s
            card.translationY = h * 0.2f * p
        }
    }

    /** 12c: three backdrop-blur windows (panel, dock, header) sliding while a full-screen backdrop radius animates. */
    fun stress() {
        if (Build.VERSION.SDK_INT < 31) { log("[glass stress] needs Android 12+"); return }
        clear()
        val (w, h) = size()
        val specs = listOf(
            Triple(w, h, 0.0f),                       // full-screen backdrop
            Triple((w * 0.9f).toInt(), (h * 0.35f).toInt(), 1f),   // panel
            Triple((w * 0.9f).toInt(), (h * 0.12f).toInt(), -1f),  // dock
        )
        val lps = ArrayList<Pair<View, WindowManager.LayoutParams>>()
        for ((i, spec) in specs.withIndex()) {
            val v = FrameLayout(ctx)
            v.setBackgroundColor(if (i == 0) 0x22FFFFFF else 0x44FFFFFF)
            val lp = params(spec.first, spec.second, blurBehind = true)
            lp.blurBehindRadius = if (i == 0) 0 else 60
            if (i > 0) { lp.x = (w - spec.first) / 2; lp.y = if (spec.third > 0) (h * 0.3f).toInt() else (h * 0.8f).toInt() }
            try { wm.addView(v, lp) } catch (t: Throwable) { log("[glass stress] addView FAILED: ${t.message}"); clear(); return }
            views += v
            lps += v to lp
        }
        animate("12c glass stress", 800.0, "3 blur-behind windows: full-screen radius 0 -> 90 plus a panel (radius 60) and a dock (radius 60) sliding") { p ->
            for ((i, pair) in lps.withIndex()) {
                val (v, lp) = pair
                when (i) {
                    0 -> lp.blurBehindRadius = (90 * p).toInt()
                    1 -> lp.y = (h * 0.3f + h * 0.2f * p).toInt()
                    2 -> lp.y = (h * 0.8f - h * 0.15f * p).toInt()
                }
                try { wm.updateViewLayout(v, lp) } catch (t: Throwable) { /* gone */ }
            }
        }
    }

    fun stopAll() = clear()
}
