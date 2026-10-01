package dev.spike.launcherprobe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * v7 labs:
 *  13a/13b  Samsung blur discovery (hidden API exemption, class/method dumps, strings in the framework)
 *  13c      close animation: Settings -> our shrinking card -> home icon position
 *  13d/13e  watcher for apps opened from outside the launcher (notification, other apps), animations off
 *  13f-13h  watchdog: a shell-side loop that restores animation scale + status bar if our app dies
 */
class V7Lab(private val ctx: Context, private val log: (String) -> Unit, private val svc: () -> IProbeService?) {
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
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

    // ------------------------------------------------------------ 13a / 13b Samsung blur discovery

    fun samsungFramework() {
        val s = svc() ?: run { log("[samsung blur] service not connected"); return }
        io.execute {
            val sb = StringBuilder("[samsung blur A] classes named like *Blur* inside the framework files (strings search)\n")
            val cmd = "timeout 6 grep -a -o -h -E 'com/samsung/android/[A-Za-z0-9/_$]*Blur[A-Za-z0-9_$]*' " +
                "/system/framework/framework.jar /system/framework/oat/arm64/framework.vdex /system/framework/oat/arm64/framework.odex 2>/dev/null | sort -u | head -40"
            sb.appendLine(s.runShell(cmd).trim().ifEmpty { "(nothing found in those files)" })
            sb.appendLine("\nclasses named like SemWindow*/SemSurface* (first 25):")
            sb.appendLine(s.runShell(
                "timeout 6 grep -a -o -h -E 'com/samsung/android/(view|graphics)/Sem[A-Za-z0-9_$]*' /system/framework/framework.jar /system/framework/oat/arm64/framework.vdex 2>/dev/null | sort -u | head -25"
            ).trim().ifEmpty { "(none)" })
            sb.appendLine("\nsystem properties mentioning blur:")
            sb.appendLine(s.runShell("getprop | grep -i blur | head -20").trim().ifEmpty { "(none)" })
            sb.appendLine("\nfloating-feature / config mentioning blur:")
            sb.appendLine(s.runShell("timeout 5 grep -i -o -h 'blur[A-Za-z_]*' /system/etc/floating_feature.xml 2>/dev/null | sort -u | head").trim().ifEmpty { "(none)" })
            log(sb.toString())
        }
    }

    private fun exempt(): String = try {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vm = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclared.invoke(vm, "getRuntime", null as Array<Class<*>>?) as Method
        val setEx = getDeclared.invoke(vm, "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>()::class.java)) as Method
        setEx.invoke(getRuntime.invoke(null), arrayOf("L"))
        "hidden-API exemption: applied via VMRuntime"
    } catch (t: Throwable) {
        "hidden-API exemption FAILED: ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}"
    }

    private fun describeClass(name: String, regex: Regex, sb: StringBuilder) {
        try {
            val c = Class.forName(name)
            val ms = (c.declaredMethods.toList() + c.methods.toList()).distinctBy { it.toString() }
                .filter { regex.containsMatchIn(it.name) }.sortedBy { it.name }
            val fs = c.declaredFields.filter { regex.containsMatchIn(it.name) }
            val cs = c.declaredConstructors.map { it.toString() }
            sb.appendLine("$name: LOADED")
            cs.take(6).forEach { sb.appendLine("   ctor $it") }
            fs.take(20).forEach { sb.appendLine("   field ${it.type.simpleName} ${it.name}") }
            ms.take(40).forEach { m -> sb.appendLine("   ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})") }
            if (ms.isEmpty() && fs.isEmpty()) sb.appendLine("   (no member matches)")
        } catch (t: Throwable) {
            sb.appendLine("$name: ${t.javaClass.simpleName}")
        }
    }

    fun samsungInApp() {
        val sb = StringBuilder("[samsung blur B] inside the app process\n")
        sb.appendLine(exempt())
        val rx = Regex("sem.*blur|blur", RegexOption.IGNORE_CASE)
        listOf(
            "android.view.View", "android.view.Window", "android.view.ViewRootImpl",
            "android.view.WindowManager\$LayoutParams", "android.view.SurfaceControl\$Transaction",
            "com.samsung.android.graphics.SemBlurInfo", "com.samsung.android.graphics.SemBlurInfo\$Builder",
            "com.samsung.android.view.SemBlurInfo", "com.samsung.android.view.SemBlurInfo\$Builder",
            "com.samsung.android.view.SemWindowManager", "com.samsung.android.graphics.SemGfxImageFilter",
            "com.samsung.android.graphics.SemImageFilter",
        ).forEach { describeClass(it, rx, sb) }
        log(sb.toString())
    }

    // ------------------------------------------------------------ 13c close animation

    private var closeView: View? = null

    fun closeLab() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) { log("[close lab] overlay permission missing"); return }
        val s = svc() ?: run { log("[close lab] service not connected"); return }
        io.execute {
            try {
                s.runShell("settings put global transition_animation_scale 0; settings put global window_animation_scale 0")
                s.runDetached("am start -n com.android.settings/.Settings")
                // wait for Settings to be on top, then give it a moment to draw
                val end = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < end) {
                    if (s.runShell("dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity'").contains("com.android.settings")) break
                    Thread.sleep(60)
                }
                Thread.sleep(1200)
                val t = SystemClock.uptimeMillis()
                val bmp: Bitmap? = s.snapshotTask(0)
                val fetch = SystemClock.uptimeMillis() - t
                ui.post { runClose(s, bmp, fetch) }
            } catch (t: Throwable) {
                log("[close lab] FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun runClose(s: IProbeService, bmp: Bitmap?, fetchMs: Long) {
        val (w, h) = realSize()
        val root = FrameLayout(ctx)
        var radius = 0f
        val card: View = if (bmp != null) ImageView(ctx).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
        } else View(ctx).apply { setBackgroundColor(0xFF3B6FE0.toInt()) }
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
        }
        root.addView(card, FrameLayout.LayoutParams(w, h))
        card.pivotX = w / 2f
        card.pivotY = h / 2f
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (android.os.Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0)
        try { wm.addView(root, lp) } catch (t: Throwable) { log("[close lab] addView FAILED: ${t.message}"); return }
        closeView = root

        // Target: where an icon would be (bottom centre, like the launch lab tile).
        val tileW = dp(96).toFloat()
        val sx1 = tileW / w
        val sy1 = tileW / h
        val tx1 = 0f
        val ty1 = (h - dp(220) - dp(48)) - h / 2f

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
        val t0 = SystemClock.uptimeMillis()
        io.execute { try { s.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME") } catch (t: Throwable) { /* ignore */ } }

        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 300
        a.addUpdateListener { va ->
            val f = interp.getInterpolation(va.animatedValue as Float)
            card.scaleX = 1f + (sx1 - 1f) * f
            card.scaleY = 1f + (sy1 - 1f) * f
            card.translationY = ty1 * f
            radius = (dp(24) / max(card.scaleX, 0.01f)) * f
            card.invalidateOutline()
            card.alpha = 1f - max(0f, (f - 0.75f) / 0.25f)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                choreographer.removeFrameCallback(fc)
                ui.postDelayed({
                    try { wm.removeView(root) } catch (t: Throwable) { /* gone */ }
                    closeView = null
                    val d = if (frames.size > 3) frames.drop(1) else frames
                    val sorted = d.sorted()
                    val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
                    val dropped = if (median > 0) d.sumOf { max(0, Math.round(it / median).toInt() - 1) } else 0
                    s.let { sv -> io.execute { try { sv.runShell("settings put global transition_animation_scale 1.0; settings put global window_animation_scale 1.0") } catch (t: Throwable) { /* ignore */ } } }
                    log(
                        "[close lab] result\n  snapshot of the app: ${if (bmp != null) "${bmp.width}x${bmp.height}" else "NONE"}, fetched in $fetchMs ms before the animation\n" +
                            "  frames ${d.size}: median ${"%.2f".format(median)} ms, worst ${"%.2f".format(sorted.lastOrNull() ?: 0.0)} ms, dropped ~$dropped\n" +
                            "  system animations restored to normal.\n  Tell me: did the Settings image shrink smoothly down to the bottom while home appeared behind it, with no flash or double animation?"
                    )
                }, 160)
            }
        })
        a.start()
    }

    // ------------------------------------------------------------ 13d / 13e external-launch watcher

    @Volatile private var watching = false

    fun externalWatch(on: Boolean) {
        val s = svc() ?: run { log("[external watch] service not connected"); return }
        if (!on) { watching = false; return }
        if (watching) { log("[external watch] already running"); return }
        watching = true
        io.execute {
            val sb = StringBuilder("[external watch] 60 s. System animations are OFF. Open apps in ways the launcher did not start: " +
                "tap a notification, open a link from another app, use the share sheet, open an app from a widget.\n")
            s.runShell("settings put global transition_animation_scale 0; settings put global window_animation_scale 0")
            val t0 = SystemClock.uptimeMillis()
            var last = ""
            var polls = 0
            var costTotal = 0L
            while (watching && SystemClock.uptimeMillis() - t0 < 60_000) {
                val c0 = SystemClock.uptimeMillis()
                val r = try { s.runShell("dumpsys activity activities | grep -m1 -E 'topResumedActivity'") } catch (t: Throwable) { "" }
                costTotal += SystemClock.uptimeMillis() - c0
                polls++
                val comp = Regex("([a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+)").find(r)?.value ?: ""
                if (comp.isNotEmpty() && comp != last) {
                    sb.appendLine("  +${SystemClock.uptimeMillis() - t0} ms  top is now $comp")
                    last = comp
                }
                Thread.sleep(60)
            }
            s.runShell("settings put global transition_animation_scale 1.0; settings put global window_animation_scale 1.0")
            watching = false
            sb.appendLine("polls: $polls, average poll cost ${if (polls > 0) costTotal / polls else 0} ms. System animations restored.")
            sb.appendLine("Tell me: for each app you opened from outside, did it appear with NO animation (as expected with animations off)? " +
                "The timestamps above show how quickly a watcher could notice and start our own animation.")
            log(sb.toString())
        }
    }

    // ------------------------------------------------------------ 13f - 13h watchdog

    fun armWatchdog() {
        val s = svc() ?: run { log("[watchdog] service not connected"); return }
        io.execute {
            val script = "/data/local/tmp/wd.sh"
            val body = "#!/system/bin/sh\n" +
                "echo \"\$(date +%T) armed\" > /data/local/tmp/wd.log\n" +
                "gone=0\n" +
                "while true; do\n" +
                "  if ps -A -o NAME 2>/dev/null | grep -qx dev.spike.launcherprobe; then gone=0; else gone=\$((gone+1)); fi\n" +
                "  if [ \$gone -ge 3 ]; then\n" +
                "    settings put global transition_animation_scale 1.0\n" +
                "    settings put global window_animation_scale 1.0\n" +
                "    cmd statusbar send-disable-flag none\n" +
                "    echo \"\$(date +%T) app process gone -> restored animation scale and status bar\" >> /data/local/tmp/wd.log\n" +
                "    exit 0\n" +
                "  fi\n" +
                "  sleep 1\n" +
                "done\n"
            val w = s.runShell("cat > $script <<'EOF'\n$body\nEOF\nchmod 755 $script; echo written")
            s.runShell("pkill -f wd.sh 2>/dev/null; true")
            val started = s.runDetached("setsid nohup sh $script >/dev/null 2>&1 &")
            s.runShell("settings put global transition_animation_scale 0; settings put global window_animation_scale 0")
            s.statusBarCmd("clock system-icons notification-icons", 0)
            log("[watchdog] armed. ($w; $started)\n  Animations are now OFF and the stock status bar contents are hidden.\n" +
                "  NOW FORCE-STOP this app (recents swipe it away, or Settings > Apps > LauncherProbe > Force stop).\n" +
                "  Within about 5 s the system animations and status bar should come back by themselves. Then reopen the app and press 13g.")
        }
    }

    fun watchdogLog() {
        val s = svc() ?: run { log("[watchdog] service not connected: it may have died with the app. Reopen and connect first."); return }
        io.execute {
            log("[watchdog] log written by the shell-side loop:\n" + s.runShell("cat /data/local/tmp/wd.log 2>&1").trim() +
                "\n  scale now: " + s.runShell("settings get global transition_animation_scale").trim() +
                "\n  loop still running: " + s.runShell("pgrep -f wd.sh | head -1").trim().ifEmpty { "no" })
        }
    }

    /** 13i: show what the system thinks is hiding the status bar, and which processes the watchdog can see. */
    fun diagnoseStatusBar() {
        val s = svc() ?: run { log("[statusbar diag] service not connected"); return }
        io.execute {
            val sb = StringBuilder("[statusbar diag]\n")
            sb.appendLine("-- dumpsys statusbar (disable records):")
            sb.appendLine(s.runShell("dumpsys statusbar | grep -iE 'disable|what=|pkg=|token' | head -40").trim())
            sb.appendLine("-- processes named like us (NAME PID):")
            sb.appendLine(s.runShell("ps -A -o NAME,PID | grep -i launcherprobe").trim())
            sb.appendLine("-- pidof dev.spike.launcherprobe: " + s.runShell("pidof dev.spike.launcherprobe").trim())
            sb.appendLine("-- watchdog log:")
            sb.appendLine(s.runShell("cat /data/local/tmp/wd.log 2>&1").trim())
            sb.appendLine("-- animation scales: " + s.runShell("settings get global transition_animation_scale; settings get global window_animation_scale").trim().replace("\n", " / "))
            log(sb.toString())
        }
    }

    /** 13j: emergency restore, tries every way we know to give the status bar and animations back. */
    fun emergencyRestore() {
        val s = svc() ?: run { log("[emergency restore] service not connected"); return }
        io.execute {
            val sb = StringBuilder("[emergency restore]\n")
            sb.appendLine(s.runShell("pkill -f wd.sh 2>/dev/null; pkill -f 'sleep [0-9]*; cmd statusbar' 2>/dev/null; echo killed watchers").trim())
            sb.appendLine(s.runShell("settings put global transition_animation_scale 1.0; settings put global window_animation_scale 1.0; echo scales reset").trim())
            sb.appendLine("send-disable-flag none: " + s.runShell("cmd statusbar send-disable-flag none 2>&1; echo exit=\$?").trim())
            sb.appendLine("collapse: " + s.runShell("cmd statusbar collapse 2>&1; echo exit=\$?").trim())
            sb.appendLine("-- disable records after restore:")
            sb.appendLine(s.runShell("dumpsys statusbar | grep -iE 'disable|what=|pkg=' | head -20").trim())
            sb.appendLine("If the bar is still missing, tell me; a reboot always clears it (these flags live in memory only).")
            log(sb.toString())
        }
    }

    fun disarmWatchdog() {
        val s = svc() ?: return
        io.execute {
            s.runShell("pkill -f wd.sh 2>/dev/null; settings put global transition_animation_scale 1.0; settings put global window_animation_scale 1.0; cmd statusbar send-disable-flag none")
            log("[watchdog] disarmed, everything restored.")
        }
    }

    fun stopAll() {
        watching = false
        closeView?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        closeView = null
    }

    @Suppress("unused") private fun keep(a: Int, b: Int) = min(a, b)
}
