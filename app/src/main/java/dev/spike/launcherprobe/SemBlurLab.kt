package dev.spike.launcherprobe

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Tries Samsung's own View blur methods (found by 13b) on an overlay window. */
class SemBlurLab(private val ctx: Context, private val log: (String) -> Unit, private val svc: () -> IProbeService?) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val choreographer = Choreographer.getInstance()
    private var view: View? = null

    @Suppress("DEPRECATION")
    private fun size(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun findMethod(name: String, vararg params: Class<*>): Method? = try {
        View::class.java.getDeclaredMethod(name, *params).also { it.isAccessible = true }
    } catch (t: Throwable) { null }

    /** 14a: describe SemBlurInfo (its class is found through the parameter type of semSetBlurInfo). */
    fun describe() {
        val sb = StringBuilder("[sem blur] describe\n")
        val m = View::class.java.declaredMethods.firstOrNull { it.name == "semSetBlurInfo" }
        if (m == null) { log(sb.append("semSetBlurInfo not found").toString()); return }
        val p = m.parameterTypes[0]
        sb.appendLine("semSetBlurInfo takes: ${p.name}")
        try {
            p.declaredConstructors.forEach { sb.appendLine("  ctor $it") }
            p.declaredMethods.sortedBy { it.name }.take(60).forEach { sb.appendLine("  method ${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})") }
            p.declaredFields.take(40).forEach { sb.appendLine("  field ${it.type.simpleName} ${it.name}") }
            p.declaredClasses.forEach { c ->
                sb.appendLine("  inner class ${c.name}")
                c.declaredConstructors.forEach { sb.appendLine("    ctor $it") }
                c.declaredMethods.sortedBy { it.name }.take(40).forEach { sb.appendLine("    method ${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})") }
            }
        } catch (t: Throwable) { sb.appendLine("describe failed: ${t.javaClass.simpleName}: ${t.message}") }
        sb.appendLine("\nWindowManager.LayoutParams members containing 'sem':")
        try {
            WindowManager.LayoutParams::class.java.declaredMethods.filter { it.name.startsWith("sem") }.take(30)
                .forEach { sb.appendLine("  ${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})") }
            WindowManager.LayoutParams::class.java.declaredFields.filter { it.name.startsWith("SEM") || it.name.startsWith("sem") }.take(30)
                .forEach { sb.appendLine("  field ${it.type.simpleName} ${it.name}") }
        } catch (t: Throwable) { sb.appendLine("failed: ${t.message}") }
        log(sb.toString())
    }

    /** 14b: overlay window whose view uses the Samsung blur calls; radius animates 0 -> 90. */
    fun test() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) { log("[sem blur] overlay permission missing"); return }
        stop()
        val (w, h) = size()
        val sb = StringBuilder("[sem blur] test\n")
        val v = FrameLayout(ctx)
        v.setBackgroundColor(0x22FFFFFF)
        val enable = findMethod("semSetBlurEnabled", Boolean::class.javaPrimitiveType!!)
        val radius = findMethod("semSetBlurRadius", Int::class.javaPrimitiveType!!)
        val color = findMethod("semSetBackgroundBlurColor", Int::class.javaPrimitiveType!!)
        val corner = findMethod("semSetBackgroundBlurCornerRadius", Float::class.javaPrimitiveType!!)
        fun call(label: String, m: Method?, vararg a: Any) {
            if (m == null) { sb.appendLine("  $label: method not found / blocked"); return }
            try { m.invoke(v, *a); sb.appendLine("  $label: OK") }
            catch (t: Throwable) { sb.appendLine("  $label: FAILED ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}") }
        }
        call("semSetBlurEnabled(true)", enable, true)
        call("semSetBlurRadius(0)", radius, 0)
        call("semSetBackgroundBlurColor(0x33FFFFFF)", color, 0x33FFFFFF)
        call("semSetBackgroundBlurCornerRadius(0)", corner, 0f)
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
        try { wm.addView(v, lp) } catch (t: Throwable) { log(sb.append("addView FAILED: ${t.message}").toString()); return }
        view = v
        val deltas = ArrayList<Double>()
        var startNs = 0L
        var lastNs = 0L
        var radiusFailed = false
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (startNs == 0L) startNs = ns
                if (lastNs != 0L) deltas += (ns - lastNs) / 1e6
                lastNs = ns
                val raw = ((ns - startNs) / 1e6 / 900.0).coerceIn(0.0, 1.0)
                try { radius?.invoke(v, (90 * raw).toInt()) } catch (t: Throwable) { radiusFailed = true }
                if (raw < 1.0) choreographer.postFrameCallback(this) else {
                    ui.postDelayed({
                        stop()
                        val d = if (deltas.size > 3) deltas.drop(2) else deltas
                        val sorted = d.sorted()
                        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
                        val dropped = if (median > 0) d.sumOf { max(0, Math.round(it / median).toInt() - 1) } else 0
                        sb.appendLine("  radius updates failed during animation: $radiusFailed")
                        sb.appendLine("  frames ${d.size}: median ${"%.2f".format(median)} ms, worst ${"%.2f".format(sorted.lastOrNull() ?: 0.0)} ms, dropped ~$dropped")
                        sb.appendLine("  Tell me: was the background behind the window blurred (growing blur), only tinted, or nothing?")
                        log(sb.toString())
                    }, 700)
                }
            }
        }
        choreographer.postFrameCallback(cb)
    }

    /**
     * 14e: strength lab. Steps through stages (2.5 s each) with a label on screen:
     * plain View radius at larger values, then SemBlurInfo.Builder with radius and with Samsung's presets.
     */
    fun strength() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) { log("[sem blur] overlay permission missing"); return }
        stop()
        val (w, h) = size()
        val sb = StringBuilder("[sem blur] strength lab\n")
        val root = FrameLayout(ctx)
        val label = android.widget.TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xCC000000.toInt()); textSize = 16f; setPadding(24, 24, 24, 24)
        }
        root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = 160 })
        val infoCls = try { Class.forName("android.view.SemBlurInfo") } catch (t: Throwable) { null }
        val bCls = try { Class.forName("android.view.SemBlurInfo\$Builder") } catch (t: Throwable) { null }
        fun const(n: String): Int = try { infoCls!!.getField(n).getInt(null) } catch (t: Throwable) { -1 }
        val setInfo = View::class.java.declaredMethods.firstOrNull { it.name == "semSetBlurInfo" }?.also { it.isAccessible = true }
        val enable = findMethod("semSetBlurEnabled", Boolean::class.javaPrimitiveType!!)
        val radiusM = findMethod("semSetBlurRadius", Int::class.javaPrimitiveType!!)
        val colorM = findMethod("semSetBackgroundBlurColor", Int::class.javaPrimitiveType!!)

        fun viewRadius(r: Int) { try { enable?.invoke(root, true); radiusM?.invoke(root, r); colorM?.invoke(root, 0x22FFFFFF) } catch (t: Throwable) { sb.appendLine("  view call failed: ${t.cause?.message ?: t.message}") } }
        fun info(mode: Int, setup: (Any) -> Any = { it }) {
            try {
                enable?.invoke(root, true)
                var b: Any = bCls!!.getConstructor(Int::class.javaPrimitiveType).newInstance(mode)
                b = setup(b)
                val built = bCls.getMethod("build").invoke(b)
                setInfo!!.invoke(root, built)
            } catch (t: Throwable) { sb.appendLine("  SemBlurInfo failed: ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}") }
        }
        fun Any.rad(r: Int): Any = bCls!!.getMethod("setRadius", Int::class.javaPrimitiveType).invoke(this, r)!!
        fun Any.bg(c: Int): Any = bCls!!.getMethod("setBackgroundColor", Int::class.javaPrimitiveType).invoke(this, c)!!
        fun Any.curve(p: Int): Any = bCls!!.getMethod("setColorCurvePreset", Int::class.javaPrimitiveType).invoke(this, p)!!

        val win = const("BLUR_MODE_WINDOW")
        val stages = ArrayList<Pair<String, () -> Unit>>()
        for (r in listOf(150, 300, 600)) stages += "A: View.semSetBlurRadius($r)" to { viewRadius(r) }
        for (r in listOf(50, 150, 300)) stages += "B: Builder(WINDOW).setRadius($r)" to { info(win) { it.rad(r) } }
        stages += "C: WINDOW + THICK_LIGHT preset" to { info(win) { it.curve(const("BLUR_UI_HIGH_THICK_LIGHT")) } }
        stages += "D: WINDOW + ULTRA_THICK_DARK preset" to { info(win) { it.curve(const("BLUR_UI_HIGH_ULTRA_THICK_DARK")) } }
        stages += "E: WINDOW radius 200 + bg 0x44000000" to { info(win) { it.rad(200).let { x -> x.bg(0x44000000) } } }
        stages += "F: WINDOW_CAPTURED radius 200" to { info(const("BLUR_MODE_WINDOW_CAPTURED")) { it.rad(200) } }

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
        try { wm.addView(root, lp) } catch (t: Throwable) { log(sb.append("addView FAILED: ${t.message}").toString()); return }
        view = root
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i >= stages.size) {
                    stop()
                    sb.appendLine("Tell me which labels (A..F) looked fully blurred (text/shapes unreadable) and which only half blurred or tinted.")
                    log(sb.toString()); return
                }
                val (name, apply) = stages[i++]
                label.text = name
                sb.appendLine("  stage: $name")
                apply()
                ui.postDelayed(this, 2500)
            }
        }
        ui.post(step)
    }

    /** 14c: if the calls above are blocked as hidden APIs, allow them via the global policy (needs an app restart). */
    fun allowHiddenApis() {
        val s = svc() ?: run { log("[sem blur] service not connected"); return }
        io.execute {
            val r = s.runShell("settings put global hidden_api_policy 1; settings get global hidden_api_policy").trim().replace("\n", " ")
            log("[sem blur] hidden_api_policy set: $r\n  Now FORCE-STOP this app and reopen it (the policy is read when the app process starts), then run 14b again.\n" +
                "  Press 14d afterwards to put the policy back to normal.")
        }
    }

    fun restoreHiddenApis() {
        val s = svc() ?: return
        io.execute { log("[sem blur] hidden_api_policy back to default: " + s.runShell("settings delete global hidden_api_policy; settings get global hidden_api_policy").trim().replace("\n", " ")) }
    }

    fun stop() {
        view?.let { try { wm.removeView(it) } catch (t: Throwable) { /* gone */ } }
        view = null
    }

    @Suppress("unused") private fun keep(a: Int, b: Int) = min(a, b)
}
