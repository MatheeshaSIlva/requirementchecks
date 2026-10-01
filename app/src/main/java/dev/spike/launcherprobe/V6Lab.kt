package dev.spike.launcherprobe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * v6 requirement labs. Every lab only uses shell commands / overlays, so the AIDL interface is unchanged.
 *
 *  11a/11b  system animation scale (transition + window) to 0 and back
 *  11c      launch-animation lab: our own expand animation from a tile while the app is started underneath
 *  11d      windowing modes: freeform / multi-window launches from the shell
 *  11e/11f  own status bar: hide the stock bar contents, draw ours on top
 *  11g-11i  Shizuku after reboot: grant Shizuku WRITE_SECURE_SETTINGS, boot log, optional wireless-debugging auto-enable
 */
class V6Lab(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val svc: () -> IProbeService?,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private val choreographer = Choreographer.getInstance()
    private val interp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun dp(v: Int) = (v * density).toInt()

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun canOverlay() = android.provider.Settings.canDrawOverlays(ctx)

    // ---------------------------------------------------------------- 11a / 11b animation scale

    fun animScale(zero: Boolean, restoreAfterSec: Int) {
        val s = svc() ?: run { log("[anim scale] service not connected"); return }
        io.execute {
            try {
                val v = if (zero) "0" else "1.0"
                val before = s.runShell(
                    "echo transition=$(settings get global transition_animation_scale) " +
                        "window=$(settings get global window_animation_scale) " +
                        "animator=$(settings get global animator_duration_scale)"
                ).trim()
                val out = s.runShell(
                    "settings put global transition_animation_scale $v; settings put global window_animation_scale $v; " +
                        "echo transition=$(settings get global transition_animation_scale) window=$(settings get global window_animation_scale)"
                ).trim()
                log("[anim scale] before: $before\n  now: $out\n  (animator_duration_scale is left alone on purpose so our own animations keep running)")
                if (zero && restoreAfterSec > 0) {
                    s.runDetached(
                        "sh -c 'sleep $restoreAfterSec; settings put global transition_animation_scale 1.0; " +
                            "settings put global window_animation_scale 1.0'"
                    )
                    log("  detached restore to 1.0 scheduled in ${restoreAfterSec}s (or press 11b)")
                }
            } catch (t: Throwable) {
                log("[anim scale] FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------- 11c launch lab

    private var labRoot: View? = null
    private var labCard: View? = null

    fun stopLaunchLab() {
        labRoot?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        labCard?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        labRoot = null
        labCard = null
    }

    private class CardParts(val root: FrameLayout, val card: View, val rad: FloatArray)
    private var pre: CardParts? = null
    private var optDefer = 0
    private var optQuiet = false

    /** deferMs: wait this long before starting the app. quiet: no polling during the animation + prewarmed card window. cold: force-stop the app first. */
    fun launchLab(deferMs: Int = 0, quiet: Boolean = false, cold: Boolean = false) {
        if (!canOverlay()) { log("[launch lab] overlay permission missing (section 6)"); return }
        val s = svc() ?: run { log("[launch lab] service not connected"); return }
        stopLaunchLab()
        val (w, h) = realSize()
        optDefer = deferMs
        optQuiet = quiet
        if (cold) io.execute { try { s.runShell("am force-stop com.android.settings") } catch (t: Throwable) { /* ignore */ } }

        val root = FrameLayout(ctx)
        root.setBackgroundColor(0xE6101820.toInt())
        val hint = TextView(ctx).apply {
            text = "LAUNCH LAB\nTap the tile: Settings opens with OUR expand animation while the system's own is\n" +
                "(ideally) switched off with 11a. Tap anywhere else to close the lab."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.TOP
            it.topMargin = dp(120)
        })
        val tile = TextView(ctx).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0xFF3B6FE0.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
        }
        root.addView(tile, FrameLayout.LayoutParams(dp(96), dp(96)).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(220)
        })
        root.setOnClickListener { stopLaunchLab(); log("[launch lab] closed") }
        tile.setOnClickListener { runLaunch(s, tile, w, h) }

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        try {
            wm.addView(root, lp)
            labRoot = root
            if (quiet) pre = buildCard(w, h, visible = false)
            log("[launch lab] open (defer ${deferMs} ms, quiet=$quiet, cold=$cold). Tap the tile.")
        } catch (t: Throwable) {
            log("[launch lab] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildCard(w: Int, h: Int, visible: Boolean): CardParts? {
        val cardRoot = FrameLayout(ctx)
        val rad = floatArrayOf(dp(24).toFloat())
        val card = View(ctx)
        card.setBackgroundColor(0xFF3B6FE0.toInt())
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, rad[0])
            }
        }
        card.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        cardRoot.addView(card, FrameLayout.LayoutParams(w, h))
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        return try {
            wm.addView(cardRoot, lp)
            labCard = cardRoot
            CardParts(cardRoot, card, rad)
        } catch (t: Throwable) {
            log("[launch lab] card addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun runLaunch(s: IProbeService, tile: View, w: Int, h: Int) {
        val loc = IntArray(2)
        tile.getLocationOnScreen(loc)
        val tileW = tile.width.toFloat()
        val tileH = tile.height.toFloat()
        val cx = loc[0] + tileW / 2f
        val cy = loc[1] + tileH / 2f

        // Full-screen card that starts as the tile and grows to the whole screen.
        val parts = pre ?: buildCard(w, h, visible = true)
        pre = null
        if (parts == null) return
        val cardRoot = parts.root
        val card = parts.card
        val rad = parts.rad
        card.visibility = View.VISIBLE
        card.pivotX = w / 2f
        card.pivotY = h / 2f
        val sx0 = tileW / w
        val sy0 = tileH / h
        val tx0 = cx - w / 2f
        val ty0 = cy - h / 2f
        card.scaleX = sx0
        card.scaleY = sy0
        card.translationX = tx0
        card.translationY = ty0
        // The corner radius is applied before scaling, so compensate to keep ~24dp on screen at the start.
        rad[0] = dp(24) / max(sx0, 0.01f)
        card.invalidateOutline()
        labCard = cardRoot

        val t0 = SystemClock.uptimeMillis()
        val frames = ArrayList<Double>()
        var lastNs = 0L
        val fc = object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (lastNs != 0L) frames += (ns - lastNs) / 1e6
                lastNs = ns
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(fc)

        val animDoneAt = AtomicLong(-1L)
        val topAt = AtomicLong(-1L)
        val dispatchedAt = AtomicLong(-1L)

        // Start the real app right away, from a worker thread.
        io.execute {
            try {
                if (optDefer > 0) Thread.sleep(optDefer.toLong())
                s.runDetached("am start -n com.android.settings/.Settings")
                dispatchedAt.set(SystemClock.uptimeMillis() - t0)
            } catch (t: Throwable) {
                log("[launch lab] start FAILED: ${t.message}")
            }
        }
        // Watch for the app actually becoming the top resumed activity.
        io.execute {
            // Quiet mode: do not run dumpsys while the animation is drawing; it competes for CPU.
            if (optQuiet) Thread.sleep(330)
            val deadline = SystemClock.uptimeMillis() + 4000
            while (SystemClock.uptimeMillis() < deadline) {
                val r = try {
                    s.runShell("dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity'")
                } catch (t: Throwable) { "" }
                if (r.contains("com.android.settings")) {
                    topAt.set(SystemClock.uptimeMillis() - t0)
                    break
                }
                Thread.sleep(30)
            }
        }

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 320
        a.addUpdateListener { va ->
            val f = interp.getInterpolation(va.animatedValue as Float)
            card.scaleX = sx0 + (1f - sx0) * f
            card.scaleY = sy0 + (1f - sy0) * f
            card.translationX = tx0 * (1f - f)
            card.translationY = ty0 * (1f - f)
            rad[0] = (dp(24) / max(card.scaleX, 0.01f)) * (1f - f)
            card.invalidateOutline()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animDoneAt.set(SystemClock.uptimeMillis() - t0)
                // Lab scrim is no longer needed once the card covers the screen.
                labRoot?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
                labRoot = null
                waitThenReveal(cardRoot, card, t0, frames, fc,
                    { dispatchedAt.get() }, { topAt.get() }, { animDoneAt.get() })
            }
        })
        a.start()
    }

    private fun waitThenReveal(
        cardRoot: View, card: View, t0: Long, frames: ArrayList<Double>, fc: Choreographer.FrameCallback,
        dispatched: () -> Long, top: () -> Long, animDone: () -> Long,
    ) {
        val check = object : Runnable {
            override fun run() {
                val waited = SystemClock.uptimeMillis() - t0
                if (top() >= 0 || waited > 4200) {
                    val fade = ValueAnimator.ofFloat(1f, 0f)
                    fade.duration = 140
                    fade.addUpdateListener { cardRoot.alpha = it.animatedValue as Float }
                    fade.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            choreographer.removeFrameCallback(fc)
                            try { wm.removeView(cardRoot) } catch (t: Throwable) { /* gone */ }
                            labCard = null
                            report(t0, frames, dispatched(), top(), animDone())
                        }
                    })
                    fade.start()
                } else {
                    ui.postDelayed(this, 30)
                }
            }
        }
        ui.post(check)
    }

    private fun report(t0: Long, frames: ArrayList<Double>, dispatched: Long, top: Long, animDone: Long) {
        val d = if (frames.size > 3) frames.drop(1) else frames
        val line = if (d.isEmpty()) "no frames measured" else {
            val sorted = d.sorted()
            val median = sorted[sorted.size / 2]
            val worst = sorted.last()
            val dropped = d.sumOf { max(0, Math.round(it / median).toInt() - 1) }
            "frames ${d.size}: median ${"%.2f".format(median)} ms, worst ${"%.2f".format(worst)} ms, dropped ~$dropped"
        }
        val topTxt = if (top >= 0) "$top ms" else "NOT DETECTED within 4 s"
        val gap = if (top >= 0 && !optQuiet) (top - animDone) else null
        log(
            "[launch lab] result\n" +
                "  start command sent at +$dispatched ms\n" +
                "  our expand animation finished at +$animDone ms\n" +
                "  Settings reported as top activity at +$topTxt\n" +
                (if (gap != null) "  app ready ${if (gap <= 0) "BEFORE" else "AFTER"} our animation ended by ${kotlin.math.abs(gap)} ms " +
                    "(positive = the card had to wait for the app)\n" else "") +
                "  $line\n" +
                (if (optQuiet) "  (quiet mode: the top-activity check starts after the animation, so only frame numbers and your eyes matter here)\n" else "") +
                "  Tell me: did you see a flash, a double animation (system one + ours), or a clean reveal?"
        )
    }

    // ---------------------------------------------------------------- 11d windowing modes

    fun windowingLab() {
        val s = svc() ?: run { log("[windowing] service not connected"); return }
        io.execute {
            val sb = StringBuilder("[windowing] REQ: split-screen / freeform / pop-up from the shell\n")
            try {
                sb.appendLine("features: " + s.runShell("pm list features | grep -i -E 'freeform|multi_window|picture_in_picture' | tr '\\n' ' '").trim())
                sb.appendLine("enable_freeform_support=" + s.runShell("settings get global enable_freeform_support").trim() +
                    "  force_resizable_activities=" + s.runShell("settings get global force_resizable_activities").trim())
                val tries = listOf(
                    "freeform window (mode 5)" to "am start -n com.android.settings/.Settings --windowingMode 5",
                    "multi-window (mode 6)" to "am start -n com.android.settings/.Settings --windowingMode 6",
                )
                for ((label, cmd) in tries) {
                    val t = SystemClock.uptimeMillis()
                    val r = s.runShell("$cmd 2>&1").trim().replace('\n', ' ')
                    sb.appendLine("$label -> ${SystemClock.uptimeMillis() - t} ms: $r")
                    Thread.sleep(2500)
                    val top = s.runShell("dumpsys activity activities | grep -m2 -i -E 'mWindowingMode|windowingMode=' | tr '\\n' ' '").trim()
                    sb.appendLine("   top windowing info: $top")
                }
                sb.appendLine("Watch the screen while this runs and tell me what appeared (a floating window, a split, or nothing).")
            } catch (t: Throwable) {
                sb.appendLine("FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
            log(sb.toString())
        }
    }

    // ---------------------------------------------------------------- 11e / 11f own status bar

    private var barView: View? = null
    private val barStop = Runnable { stopStatusBar() }

    fun statusBarLab() {
        if (!canOverlay()) { log("[own status bar] overlay permission missing (section 6)"); return }
        val s = svc() ?: run { log("[own status bar] service not connected"); return }
        // Only drop our old view. Do NOT send a restore here: it raced with the new disable flags and cleared them.
        ui.removeCallbacks(barStop)
        barView?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        barView = null
        io.execute {
            val r = try { s.statusBarCmd("clock system-icons notification-icons", 90) } catch (t: Throwable) { "ERROR ${t.message}" }
            ui.post {
                val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
                val barH = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else dp(28)
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.setBackgroundColor(0xFF101820.toInt())
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(20), 0, dp(20), 0)
                val clock = TextClock(ctx).apply {
                    format12Hour = "h:mm"
                    format24Hour = "HH:mm"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                val mid = View(ctx)
                val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val pct = batt?.let {
                    val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (l >= 0) l * 100 / sc else -1
                } ?: -1
                val right = TextView(ctx).apply {
                    text = "OWN BAR  " + (if (pct >= 0) "$pct%" else "")
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                row.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(mid, LinearLayout.LayoutParams(0, 1, 1f))
                row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    barH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                lp.gravity = Gravity.TOP
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
                try {
                    wm.addView(row, lp)
                    barView = row
                    ui.postDelayed(barStop, 90_000)
                    log("[own status bar] REQ: hide stock status bar contents and draw our own\n  stock bar flags: ${r.trim().replace("\n", " | ")}\n" +
                        "  our bar: height $barH px, running 90 s (or 11f). Check: is the stock clock/icons gone, is OUR dark bar on top, " +
                        "does it look right with the camera hole, and what happens if you rotate the phone or pull down the shade?")
                } catch (t: Throwable) {
                    log("[own status bar] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    fun stopStatusBar() {
        ui.removeCallbacks(barStop)
        barView?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        barView = null
        val s = svc() ?: return
        io.execute { try { s.statusBarRestore() } catch (t: Throwable) { /* ignore */ } }
    }

    // ---------------------------------------------------------------- 11g - 11i Shizuku after reboot

    fun grantShizukuSecureSettings() {
        val s = svc() ?: run { log("[shizuku boot] service not connected"); return }
        io.execute {
            val out = s.runShell(
                "pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS 2>&1; " +
                    "dumpsys package moe.shizuku.privileged.api | grep -i 'WRITE_SECURE_SETTINGS' | head -3"
            ).trim()
            log("[shizuku boot] REQ: let the Shizuku app restart itself after reboot\n  $out\n" +
                "  If it says granted=true, open the Shizuku app and look for a 'Start on boot' / 'Start via wireless debugging' option, " +
                "enable it, then reboot and press 11h.")
        }
    }

    fun bootLog() {
        val f = File(ctx.filesDir, "boot.log")
        val prefs = ctx.getSharedPreferences("v6", Context.MODE_PRIVATE)
        val up = SystemClock.elapsedRealtime() / 1000
        val alive = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
        val adbWifi = try { android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", -1) } catch (t: Throwable) { -2 }
        log(
            "[boot log] now: phone up ${up}s, Shizuku running: $alive, wireless debugging setting: $adbWifi, " +
                "auto-enable wireless debugging at boot: ${prefs.getBoolean("autoAdbWifi", false)}\n" +
                (if (f.exists()) f.readText().takeLast(3000) else "(no boot recorded yet. Reboot the phone, then open this app and press 11h.)")
        )
    }

    fun armBootAutoEnable(on: Boolean) {
        ctx.getSharedPreferences("v6", Context.MODE_PRIVATE).edit().putBoolean("autoAdbWifi", on).apply()
        val has = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        log("[boot log] auto-enable wireless debugging at boot: $on. This app holds WRITE_SECURE_SETTINGS: $has" +
            (if (!has) " (grant it in section 6 first, otherwise the boot attempt will just log a failure)" else ""))
    }

    fun stopAll() {
        stopLaunchLab()
        stopStatusBar()
    }

    companion object {
        fun appendBootLog(ctx: Context, line: String) {
            try {
                val f = File(ctx.filesDir, "boot.log")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("$stamp  $line\n")
            } catch (t: Throwable) { /* best effort */ }
        }
    }
}
