package dev.spike.launcherprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var restoreSecs: EditText
    private lateinit var shellInput: EditText
    private lateinit var devicePath: EditText

    private var overlayOnHold = false
    private var overlayView: View? = null

    // Called by the privileged service (other process) for each detected gesture event.
    private val gestureCb = object : IGestureCallback.Stub() {
        override fun onGesture(kind: String?, x: Float, y: Float) {
            log("[touch] $kind")
            if (overlayOnHold && kind != null && kind.contains("HOLD") && !kind.contains("after")) showOverlay()
        }
    }

    private fun showOverlay() {
        ui.post {
            if (overlayView != null) return@post
            try {
                val wm = applicationContext.getSystemService(WindowManager::class.java)
                val v = TextView(applicationContext).apply {
                    text = "PROBE OVERLAY\nshown on HOLD\n\n(tap to dismiss, hides itself after 6 s)"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setBackgroundColor(0xE6102040.toInt())
                    setOnClickListener { hideOverlay() }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                wm.addView(v, lp)
                overlayView = v
                log("overlay shown")
                ui.postDelayed({ hideOverlay() }, 6000)
            } catch (t: Throwable) {
                log("overlay FAILED: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun hideOverlay() {
        val v = overlayView ?: return
        overlayView = null
        try {
            applicationContext.getSystemService(WindowManager::class.java).removeView(v)
        } catch (t: Throwable) { /* already gone */ }
    }

    private fun startTouch(label: String) {
        val dm = realMetrics()
        val path = devicePath.text.toString().trim()
        call(label) { it.startTouchReader(path, dm.widthPixels, dm.heightPixels, dm.density, gestureCb, 40) }
    }

    /** Grant (if needed), connect the service, wait for it, then run the full suite. One tap. */
    private fun autoSetupThenRun() {
        if (svc != null) {
            call("run all") { it.runAllTests() }
            return
        }
        if (!shizukuReady()) { log("Shizuku isn't running. Start it (wireless debugging), then tap again."); return }
        if (!hasPermission()) {
            log("requesting Shizuku permission — approve the dialog, then tap 'Auto-setup + Run all tests' again")
            Shizuku.requestPermission(1001)
            return
        }
        log("binding service…")
        Shizuku.bindUserService(userServiceArgs, conn)
        // Poll briefly for the connection, then run.
        val started = System.currentTimeMillis()
        val wait = object : Runnable {
            override fun run() {
                when {
                    svc != null -> call("run all") { it.runAllTests() }
                    System.currentTimeMillis() - started > 8000 -> log("service didn't connect within 8 s — tap again")
                    else -> ui.postDelayed(this, 300)
                }
            }
        }
        ui.postDelayed(wait, 500)
    }

    private fun testBlur() {
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk < 31) { log("[blur] RenderEffect needs API 31+, this device is API $sdk"); return }
        try {
            val v = TextView(this)
            v.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(24f, 24f, android.graphics.Shader.TileMode.CLAMP)
            )
            v.setRenderEffect(null)
            log("[blur] PASS: RenderEffect.createBlurEffect works (API $sdk). This themes YOUR surfaces, not apps behind an overlay.")
        } catch (t: Throwable) {
            log("[blur] FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun notifListenerEnabled(): Boolean = try {
        (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .contains(packageName)
    } catch (t: Throwable) { false }

    private fun testNotifListener() {
        val comp = ComponentName(this, ProbeNotificationListener::class.java).flattenToString()
        if (notifListenerEnabled()) {
            log("[notif] PASS — listener $comp is enabled; it can read the shade contents you'd re-render.")
            return
        }
        val s = svc
        if (s == null) {
            log("[notif] not granted, and the Shizuku service isn't connected. Connect it and tap again " +
                "(it self-grants via `cmd notification allow_listener $comp`).")
            return
        }
        bg.execute {
            val r = try { s.runShell("cmd notification allow_listener $comp 2>&1") } catch (t: Throwable) { "ERROR: ${t.message}" }
            Thread.sleep(600)
            ui.post {
                if (notifListenerEnabled())
                    log("[notif] PASS — self-granted via Shizuku shell; the listener can read shade contents.")
                else
                    log("[notif] FAIL — allow_listener did not enable it. shell said: ${r.trim()}")
            }
        }
    }

    private var svc: IProbeService? = null
    private val lab by lazy { V6Lab(applicationContext, { m -> log(m) }, { svc }) }
    private val lab7 by lazy { V7Lab(applicationContext, { m -> log(m) }, { svc }) }
    private val blurLab by lazy { BlurLab(applicationContext) { m -> log(m) } }
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var polling = false

    // ---- Shizuku wiring -------------------------------------------------------------------

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ProbeService::class.java.name))
            .processNameSuffix("probe")
            .daemon(false)
            .tag("launcherprobe")
            .version(1)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = if (binder != null && binder.pingBinder()) IProbeService.Stub.asInterface(binder) else null
            log("service connected: ${svc != null}")
            ui.post { refreshStatus() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            polling = false
            log("service disconnected (its process ended)")
            ui.post { refreshStatus() }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { ui.post { refreshStatus() } }
    private val binderDead = Shizuku.OnBinderDeadListener { ui.post { svc = null; refreshStatus() } }
    private val permResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("permission result: " + if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
        ui.post { refreshStatus() }
    }

    private fun shizukuReady() = Shizuku.pingBinder() && !Shizuku.isPreV11()
    private fun hasPermission() = shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        status.text = "Shizuku running: ${Shizuku.pingBinder()}   permission: ${hasPermission()}   service: ${svc != null}"
    }

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
        refreshStatus()
    }

    override fun onDestroy() {
        polling = false
        stopStrip()
        lab.stopAll()
        blurLab.stopAll()
        lab7.stopAll()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permResult)
        super.onDestroy()
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun log(s: String) {
        ui.post {
            logView.append(s + "\n\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Runs a service call off the main thread and logs its text result. */
    private fun call(label: String, block: (IProbeService) -> String) {
        val s = svc
        if (s == null) {
            log("[$label] service not connected - tap 'Connect service' first")
            return
        }
        log("[$label] running...")   // so a crashed test is visible: a 'running...' line with no result after it
        bg.execute {
            val r = try {
                block(s)
            } catch (t: Throwable) {
                "EXCEPTION ${t.javaClass.simpleName}: ${t.message}"
            }
            log("[$label]\n$r")
        }
    }

    private fun secs(): Int = restoreSecs.text.toString().toIntOrNull() ?: 15

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun startMonitor(mode: Int, label: String) {
        val dm = realMetrics()
        call(label) { it.startGestureMonitor(dm.widthPixels, dm.heightPixels, dm.density, mode, 25) }
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val s = svc
                if (!polling || s == null || System.currentTimeMillis() - started > 90_000) {
                    polling = false
                    return
                }
                bg.execute {
                    val text = try { s.drainGestureLog() } catch (t: Throwable) { "" }
                    if (text.isNotBlank()) log("[gesture]\n$text")
                }
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    // ---- UI -------------------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val controlsScroll = ScrollView(this).apply { addView(controls) }
        root.addView(controlsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(0x11000000)
            addView(logView)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f).apply {
            topMargin = dp(6)
        })

        fun header(t: String) = controls.addView(TextView(this).apply {
            text = t
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        })

        fun note(t: String) = controls.addView(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })

        fun btn(t: String, action: () -> Unit) = controls.addView(Button(this).apply {
            text = t
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { action() }
        })

        status = TextView(this)
        controls.addView(status)

        // TOP - one-tap requirement suite (kept at the top so it needs no scrolling)
        header("★ Requirement suite")
        note("Setup first (grant + connect), open a few apps, then run this. It covers everything except the swipe/overlay tests, which need a real gesture (section 6).")
        btn("Auto-setup + Run all tests") {
            autoSetupThenRun()
        }
        btn("Run all tests (service already connected)") {
            call("run all") { it.runAllTests() }
        }

        // 0 - setup
        header("0. Setup")
        btn("Request Shizuku permission") {
            if (!shizukuReady()) log("Shizuku is not running (or too old). Start it first.")
            else if (hasPermission()) log("permission already granted")
            else Shizuku.requestPermission(1001)
        }
        btn("Connect service") {
            if (!hasPermission()) log("need a running Shizuku + granted permission first")
            else Shizuku.bindUserService(userServiceArgs, conn)
        }
        btn("Identity (expect uid=2000 for wireless-debugging Shizuku)") {
            call("identity") { it.identity() }
        }

        // 1 - status bar
        header("1a. Block stock shade / QS / recents (binder call, dies with the service)")
        controls.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "Auto-restore after (s): " })
            restoreSecs = EditText(context).apply {
                setText("15")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                minEms = 3
            }
            addView(restoreSecs)
        })
        note("After tapping, go HOME and try: pull shade, pull QS, recents gesture/button - from the home screen AND from inside another app.")
        btn("Block shade + QS") {
            call("block shade+QS") { it.statusBarDisable(DISABLE_EXPAND, DISABLE2_QS or DISABLE2_SHADE, secs()) }
        }
        btn("Block recents") {
            call("block recents") { it.statusBarDisable(DISABLE_RECENT, 0, secs()) }
        }
        btn("Block all three") {
            call("block all") {
                it.statusBarDisable(DISABLE_EXPAND or DISABLE_RECENT, DISABLE2_QS or DISABLE2_SHADE, secs())
            }
        }
        btn("Restore now") { call("restore") { it.statusBarRestore() } }

        header("1b. Same via `cmd statusbar` (may PERSIST after the service dies)")
        btn("cmd: block expansion + recents + QS") {
            call("cmd block") { it.statusBarCmd("statusbar-expansion recents quick-settings", secs()) }
        }
        btn("cmd statusbar help (verify flag names)") {
            call("cmd help") { it.runShell("cmd statusbar help") }
        }
        btn("Kill service now (does a cmd block survive? binder block should not)") {
            val s = svc
            if (s == null) log("service not connected") else bg.execute {
                try { s.destroy() } catch (t: Throwable) { /* expected: process dies */ }
                log("destroy() sent. Now test whether the shade/recents are still blocked. 'Restore now' needs a fresh 'Connect service'.")
            }
        }

        // 2 - recents
        header("2. Recent tasks + thumbnails")
        note("Open 3-4 different apps first, then come back here.")
        btn("List recents + snapshot probe") { call("recents") { it.probeRecents(10) } }

        // 3 - gestures
        header("3. Gesture input monitor (25 s auto-stop)")
        note("Keep the phone in portrait, in gesture navigation. Swipe up from the bottom, down from the top, from the sides, in another app.")
        btn("Monitor: observe only") { startMonitor(0, "monitor observe") }
        btn("Monitor: pilfer on detected top/bottom swipe") { startMonitor(1, "monitor pilfer@detect") }
        btn("Monitor: pilfer on FIRST move from top/bottom edge (aggressive)") { startMonitor(2, "monitor pilfer@first-move") }
        btn("Stop monitor") { call("monitor stop") { it.stopGestureMonitor() } }

        // 4 - nav mode
        header("4. Navigation mode (for the invisible-3-button fallback)")
        btn("Read current mode (0=3-button, 2=gesture)") {
            call("nav mode") { it.runShell("settings get secure navigation_mode") }
        }
        btn("Force 3-button") {
            call("3-button") {
                it.runShell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
            }
        }
        btn("Back to gesture") {
            call("gesture nav") {
                it.runShell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
            }
        }

        // 5 - shell
        header("5. Free shell command")
        shellInput = EditText(this).apply { hint = "e.g. dumpsys activity recents | head -30" }
        controls.addView(shellInput)
        btn("Run") { call("shell") { it.runShell(shellInput.text.toString()) } }
        btn("Run detached (no output; for commands with background timers)") {
            call("detached") { it.runDetached(shellInput.text.toString()) }
        }

        // 6 - v2: raw touch reader
        header("6. Touch reader (v2): raw touches -> bottom-edge hold detection")
        note("Now reads touches via getevent and auto-picks the touchscreen (leave the box as 'auto'). Order: grant overlay -> '1a Block recents' (set the seconds to 45) -> start reader -> switch to ANOTHER app -> swipe up from the bottom edge quickly, then swipe up and HOLD ~1 s. Come back and copy the log. Note whether the overlay appeared, and whether it stayed visible after you released. The 'started via getevent on … axis max …' line confirms the reader found the real touchscreen.")
        devicePath = EditText(this).apply { setText("auto") }
        controls.addView(devicePath)
        btn("List input devices") { call("input devices") { it.listInputDevices() } }
        btn("Grant overlay permission (shell appops)") {
            call("appops") { it.runShell("appops set $packageName SYSTEM_ALERT_WINDOW allow; echo done") }
        }
        btn("Start reader: log only (40 s)") { overlayOnHold = false; startTouch("touch log-only") }
        btn("Start reader: log + overlay on HOLD (40 s)") { overlayOnHold = true; startTouch("touch + overlay") }
        btn("Stop reader") { call("touch stop") { it.stopTouchReader() } }

        // 7 - v2: find snapshot API
        header("7. Find the thumbnail/snapshot API")
        btn("Dump snapshot/thumbnail/capture methods") {
            call("methods") { s ->
                listOf("android.app.IActivityTaskManager", "android.view.IWindowManager", "android.app.IActivityManager")
                    .joinToString("\n\n") { s.dumpMethods(it, "snapshot|thumbnail|screenshot|capture") }
            }
        }

        // 8 - v3: individual requirement tests
        header("8. Requirement tests (individual)")
        btn("Recents thumbnails") { call("thumbnails") { it.testThumbnails(10) } }
        btn("Switch to least-recent task") { call("switch") { it.testSwitchToTask(0) } }
        btn("Remove-task resolves (non-destructive)") { call("remove?") { it.testRemoveTask(0) } }
        btn("Go home (shell intent)") { call("home") { it.testGoHome() } }
        btn("Default launcher / home role") { call("home role") { it.testDefaultHome() } }
        btn("Self-grant WRITE_SECURE_SETTINGS") { call("secure") { it.testSecureSettings() } }
        header("8b. Quick-settings toggles")
        for (a in listOf("wifi-off", "wifi-on", "bt-off", "bt-on", "data-off", "data-on", "dnd-on", "dnd-off", "bright-min", "bright-max")) {
            btn("toggle: $a") { call("toggle") { it.testToggle(a) } }
        }

        // 9 - v3: app-process capabilities (no Shizuku needed)
        header("9. App-process capabilities")
        btn("RenderEffect blur available?") { testBlur() }
        btn("Notification listener bound?") { testNotifListener() }

        // 10 - v5: animation feasibility (the launcher's #1 priority: smooth app open/close + finger tracking)
        header("10. Animation feasibility (v5)")
        note("A) Can the shell user drive system app transitions itself? B) If not, is 'snapshot + our own overlay' fast and smooth enough?")
        btn("10a. Shell permission inventory (transition/task/window perms)") {
            call("shell perms") { s ->
                val held = s.runShell(
                    "dumpsys package com.android.shell | grep -iE 'CONTROL_REMOTE|REMOTE_APP|REMOTE_TRANSITION|START_TASKS|MANAGE_ACTIVITY|REGISTER_WINDOW|MONITOR_INPUT|INJECT_EVENTS|ACCESS_SURFACE|READ_FRAME|CAPTURE_|STATUS_BAR|SET_ANIMATION|OVERRIDE_DISPLAY|SURFACE|SLIPPERY|MANAGE_APP_TOKENS|SET_ACTIVITY_WATCHER|INTERNAL_SYSTEM_WINDOW' | sort -u | head -60"
                )
                val levels = s.runShell(
                    "pm list permissions -f 2>/dev/null | grep -A5 -iE 'permission:android.permission.(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS|START_TASKS_FROM_RECENTS|MANAGE_ACTIVITY_TASKS|REGISTER_WINDOW_MANAGER_LISTENERS|MONITOR_INPUT)$' | grep -iE 'permission:|protectionLevel'"
                )
                "HELD BY SHELL (uid 2000):\n$held\n\nPROTECTION LEVELS:\n$levels"
            }
        }
        btn("10b. Remote-animation / transition API dump") {
            call("anim api") { s ->
                listOf(
                    "android.app.IActivityTaskManager" to "remote|animation|transition",
                    "android.view.IWindowManager" to "remote|animation|transition",
                    "android.app.ActivityOptions" to "remote|transition|customAnimation|scaleUp|clipReveal|thumbnail",
                    "android.view.RemoteAnimationAdapter" to ".*",
                    "android.window.RemoteTransition" to ".*",
                    "android.window.IRemoteTransition" to ".*",
                    "android.view.IRemoteAnimationRunner" to ".*",
                ).joinToString("\n\n") { (c, r) -> s.dumpMethods(c, r) }
            }
        }
        btn("10b2. Recents-animation API dump (the finger-tracking route)") {
            call("recents api") { s ->
                listOf(
                    "android.app.IActivityTaskManager" to "recents|startRecents|cancelRecents|RecentsAnimation",
                    "android.view.IRecentsAnimationRunner" to ".*",
                    "android.view.IRecentsAnimationController" to ".*",
                    "android.view.RemoteAnimationTarget" to "get|is|leash|taskId|mode",
                    "android.app.ActivityManager" to "recents|taskSnapshot|getTaskThumbnail",
                ).joinToString("\n\n") { (c, r) -> s.dumpMethods(c, r) }
            }
        }
        btn("10c. Snapshot latency benchmark (needs 3+ recent apps)") {
            call("snapshot bench") { it.benchSnapshots(5) }
        }
        btn("10h. Snapshot matrix: which APIs work for BACKGROUND apps (open 3+ apps first)") {
            call("snapshot matrix") { it.snapshotMatrix(8) }
        }
        btn("10i. Block home + recents + back + shade via cmd (60 s, auto-restores)") {
            call("block home etc") { it.statusBarCmd("home recents back statusbar-expansion quick-settings", 60) }
        }
        btn("10j. Bottom-strip gesture prototype (90 s; run 10i first)") { startStrip() }
        btn("10k. Stop strip + restore stock navigation") {
            stopStrip()
            call("restore") { it.statusBarRestore() }
        }
        btn("10d. Overlay animation jank test: plain") { runAnimTest(heavy = false, useSnapshot = false) }
        btn("10e. Overlay animation jank test: HEAVY (6 blurred layers)") { runAnimTest(heavy = true, useSnapshot = false) }
        btn("10f. Snapshot -> overlay shrink (real app image, includes fetch time)") { runAnimTest(heavy = false, useSnapshot = true) }
        btn("10g. Snapshot -> overlay shrink with blur (worst case)") { runAnimTest(heavy = true, useSnapshot = true) }

        header("11. v6 labs: animations, windowing, status bar, boot")
        note("Needs overlay permission (section 6). Open 3+ apps first. Every lab restores itself.")
        btn("11a. System transition animations OFF (120 s, auto-restores)") { lab.animScale(true, 120) }
        btn("11b. System animations back to normal") { lab.animScale(false, 0) }
        btn("11c. Launch lab: our own icon-to-app animation (run 11a first, then compare with it off)") { lab.launchLab() }
        header("12. Blur and glass (hardware-accelerated windows)")
        note("Earlier blur tests (10e/10g) drew no blur: the overlay windows were not hardware accelerated. These replace them.")
        btn("12a. Backdrop blur: window blurs what is behind it (open an app first)") { blurLab.backdrop() }
        btn("12b. Blur on an app snapshot (RenderEffect)") {
            val s = svc
            bg.execute {
                val bmp = try {
                    s?.listTaskIds(6)?.filter { it != taskId }?.firstNotNullOfOrNull { id -> fetchAnySnapshot(s, id)?.first }
                } catch (t: Throwable) { null }
                ui.post { blurLab.effect(bmp) }
            }
        }
        btn("12c. Glass stress: 3 blur windows moving while the radius animates") { blurLab.stress() }
        btn("12d. Why is cross-window blur off? Read the switches, try to turn it on, re-check") {
            val s = svc
            if (s == null) log("[blur switches] service not connected") else bg.execute {
                fun cw() = if (android.os.Build.VERSION.SDK_INT >= 31) applicationContext.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled else false
                val sb = StringBuilder("[blur switches]\n")
                sb.appendLine("cross-window blur enabled now: ${cw()}")
                sb.appendLine("ro.surface_flinger.supports_background_blur = " + s.runShell("getprop ro.surface_flinger.supports_background_blur").trim() + "  (1 = the device maker allows it; empty/0 = not allowed)")
                sb.appendLine("persist.sys.sf.disable_blurs = " + s.runShell("getprop persist.sys.sf.disable_blurs").trim())
                sb.appendLine("settings global disable_window_blurs = " + s.runShell("settings get global disable_window_blurs").trim())
                sb.appendLine("battery saver (low_power) = " + s.runShell("settings get global low_power").trim())
                sb.appendLine("power mode / adaptive: " + s.runShell("settings get global low_power_sticky; settings get system psm_switch").trim().replace("\n", " | "))
                sb.appendLine("-- trying: disable_window_blurs=0, low_power=0")
                sb.appendLine(s.runShell("settings put global disable_window_blurs 0 2>&1; settings put global low_power 0 2>&1").trim())
                Thread.sleep(1500)
                sb.appendLine("cross-window blur enabled after: ${cw()}")
                sb.appendLine("If still false the device property is the blocker: it is set by Samsung in the system image and cannot be changed without root.")
                log(sb.toString())
            }
        }
        header("13. v7 labs: Samsung blur, close animation, external opens, watchdog")
        btn("13a. Samsung blur: search the framework for Sem*Blur classes + blur properties") { lab7.samsungFramework() }
        btn("13b. Samsung blur: try hidden-API exemption and dump blur classes in the app") { lab7.samsungInApp() }
        btn("13c. Close lab: Settings opens, then shrinks to an icon spot while home appears") { lab7.closeLab() }
        btn("13d. External-open watcher: 60 s, animations OFF (open apps from notifications/links)") { lab7.externalWatch(true) }
        btn("13e. Stop external-open watcher") { lab7.externalWatch(false) }
        btn("13f. Watchdog: ARM (animations off + stock bar hidden), then force-stop this app") { lab7.armWatchdog() }
        btn("13g. Watchdog: read its log after reopening the app") { lab7.watchdogLog() }
        btn("13h. Watchdog: disarm + restore everything") { lab7.disarmWatchdog() }
        btn("11c2. Launch lab, QUIET (prewarmed card, app starts 80 ms in, no polling while animating)") { lab.launchLab(deferMs = 80, quiet = true) }
        btn("11c3. Launch lab, QUIET + COLD start (Settings force-stopped first; worst case)") { lab.launchLab(deferMs = 80, quiet = true, cold = true) }
        btn("11d. Windowing modes: freeform + multi-window launch test") { lab.windowingLab() }
        btn("11k. Shell-process windows: which window types draw ABOVE the stock status bar (5 x 4 s, watch the top)") {
            val s = svc
            if (s == null) log("[shell windows] service not connected") else bg.execute {
                val sb = StringBuilder("[shell windows] REQ: a shell-owned window above the stock status bar\n")
                for (type in intArrayOf(2017, 2014, 2024, 2006, 2015)) {
                    try { sb.appendLine(s.shellWindowTest(type, 4)) } catch (t: Throwable) { sb.appendLine("type $type: CALL FAILED ${t.message}") }
                    Thread.sleep(4300)
                }
                sb.appendLine("Tell me for each red bar that appeared (in this order: 2017, 2014, 2024, 2006, 2015) whether it covered the stock status bar, sat under it, or did not appear.")
                log(sb.toString())
            }
        }
        btn("11e. Own status bar: hide stock contents, draw ours (90 s)") { lab.statusBarLab() }
        btn("11f. Stop own status bar + restore") { lab.stopStatusBar() }
        btn("11g. Let Shizuku restart itself: grant it WRITE_SECURE_SETTINGS") { lab.grantShizukuSecureSettings() }
        btn("11h. Boot log (reboot the phone first, then open the app and press this)") { lab.bootLog() }
        btn("11i. Arm: turn on wireless debugging at boot (needs section 6 secure-settings grant)") { lab.armBootAutoEnable(true) }
        btn("11j. Disarm boot auto-enable") { lab.armBootAutoEnable(false) }

        // log tools
        header("Log")
        btn("Copy log to clipboard") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("probe-log", logView.text))
            log("copied ${logView.text.length} chars")
        }
        btn("Clear log") { logView.text = "" }

        setContentView(root)
    }

    // ---- v5: overlay animation jank test --------------------------------------------------

    /**
     * Shrinks a full-screen "app window" (plain colour or a real task snapshot) toward a launcher-icon
     * position inside a TYPE_APPLICATION_OVERLAY window, driven frame by frame from Choreographer, and
     * reports frame-time statistics. This is the fallback route for finger-tracking transitions
     * (our own process draws the animation, so the system's remote-animation permission is not needed).
     */
    private fun runAnimTest(heavy: Boolean, useSnapshot: Boolean) {
        val label = "anim ${if (useSnapshot) "snapshot" else "plain"}${if (heavy) "+blur" else ""}"
        if (!android.provider.Settings.canDrawOverlays(this)) {
            log("[$label] overlay permission missing - tap 'Grant overlay permission (shell appops)' in section 6 first")
            return
        }
        if (heavy && android.os.Build.VERSION.SDK_INT < 31) {
            log("[$label] blur needs API 31+")
            return
        }
        if (overlayView != null) { log("[$label] an overlay is already showing; wait for it to close"); return }
        log("[$label] starting... (the screen will show a shrinking card for about 1 s; do not touch)")
        if (!useSnapshot) { startAnim(label, heavy, null, 0.0); return }
        val s = svc
        if (s == null) { log("[$label] service not connected"); return }
        bg.execute {
            val t0 = System.nanoTime()
            var got: Pair<android.graphics.Bitmap, String>? = null
            var usedId = 0
            try {
                for (id in s.listTaskIds(8).filter { it != taskId }) {
                    val r = fetchAnySnapshot(s, id)
                    if (r != null) { got = r; usedId = id; break }
                }
            } catch (t: Throwable) { /* reported below */ }
            val fetchMs = (System.nanoTime() - t0) / 1e6
            val result = got
            if (result == null) {
                log("[$label] snapshot FAILED: no snapshot path worked for any other recent task (run 10h to see which paths return what)")
            } else {
                log("[$label] using snapshot of task $usedId via ${result.second}")
                ui.post { startAnim(label, heavy, result.first, fetchMs) }
            }
        }
    }

    // ---- v5.2: snapshot helpers + strip prototype -----------------------------------------

    /** Tries every snapshot path for one task: live (A), cached (B), take (C), low-res cached (L). */
    private fun fetchAnySnapshot(s: IProbeService, id: Int): Pair<android.graphics.Bitmap, String>? {
        s.snapshotTask(id)?.let { return it to "A live" }
        s.snapshotBuffer(id, 1)?.let { return it to "B cached" }
        s.snapshotBuffer(id, 3)?.let { return it to "C take" }
        s.snapshotBuffer(id, 2)?.let { return it to "L low-res cached" }
        return null
    }

    private var strip: GestureStrip? = null
    private var stripOn = false
    @Volatile private var stripBmp: android.graphics.Bitmap? = null

    private fun startStrip() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            log("[strip] overlay permission missing - tap 'Grant overlay permission (shell appops)' in section 6 first")
            return
        }
        val s = svc
        if (s == null) { log("[strip] service not connected"); return }
        stopStrip()
        stripOn = true
        val gs = GestureStrip(
            applicationContext,
            { msg -> log(msg) },
            {
                bg.execute {
                    try { s.runDetached("am start -a android.intent.action.MAIN -c android.intent.category.HOME") }
                    catch (t: Throwable) { log("[strip] goHome failed: ${t.message}") }
                }
            },
            { stripBmp },
            {
                bg.execute {
                    try {
                        val ids = s.listTaskIds(6)
                        val others = ids.filter { it != taskId }
                        // ids[0] is the foreground task; the previous app is the next one down.
                        val prev = if (ids.isNotEmpty() && ids[0] == taskId) others.firstOrNull() else others.getOrNull(1)
                        if (prev == null) log("[strip] quick switch: no previous app found")
                        else {
                            val t = android.os.SystemClock.uptimeMillis()
                            val r = s.testSwitchToTask(prev)
                            log("[strip] quick switch to task $prev took ${android.os.SystemClock.uptimeMillis() - t} ms: " + r.lines().lastOrNull().orEmpty().trim())
                        }
                    } catch (t: Throwable) { log("[strip] quick switch failed: ${t.message}") }
                }
            }
        )
        strip = gs
        gs.start()
        // Keep a fresh snapshot of the app under the finger ready (the newest task that is not this app).
        val tick = object : Runnable {
            override fun run() {
                if (!stripOn) return
                bg.execute {
                    try {
                        for (id in s.listTaskIds(4).filter { it != taskId }) {
                            val r = fetchAnySnapshot(s, id)
                            if (r != null) { stripBmp = r.first; break }
                        }
                    } catch (t: Throwable) { /* keep the previous one */ }
                }
                ui.postDelayed(this, 1200)
            }
        }
        ui.post(tick)
    }

    private fun stopStrip() {
        stripOn = false
        strip?.stop()
        strip = null
        stripBmp = null
    }

    @Suppress("DEPRECATION")
    private fun startAnim(label: String, heavy: Boolean, bmp: android.graphics.Bitmap?, fetchMs: Double) {
        val dm = realMetrics()
        val w = dm.widthPixels
        val h = dm.heightPixels
        val nominalHz = windowManager.defaultDisplay.refreshRate
        val wm = applicationContext.getSystemService(WindowManager::class.java)
        val root = android.widget.FrameLayout(applicationContext)
        root.setBackgroundColor(0x66000000)

        if (heavy) {
            for (i in 0 until 6) {
                val layer = BlobView(applicationContext, if (i % 2 == 0) 0xAA2040FF.toInt() else 0xAAFF4080.toInt(), i)
                layer.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(30f, 30f, android.graphics.Shader.TileMode.CLAMP)
                )
                root.addView(layer, android.widget.FrameLayout.LayoutParams(w, h))
            }
        }

        val card: View = if (bmp != null) {
            android.widget.ImageView(applicationContext).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }
        } else View(applicationContext).apply { setBackgroundColor(0xFF3B6FE0.toInt()) }
        if (heavy) {
            card.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(8f, 8f, android.graphics.Shader.TileMode.CLAMP)
            )
        }
        var radius = 0f
        card.clipToOutline = true
        card.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        root.addView(card, android.widget.FrameLayout.LayoutParams(w, h))
        card.pivotX = w / 2f
        card.pivotY = h / 2f

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        try {
            wm.addView(root, lp)
        } catch (t: Throwable) {
            log("[$label] addView FAILED: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        overlayView = root

        val durMs = 600.0
        val cornerPx = dp(40).toFloat()
        val interp = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)   // emphasized decelerate
        val choreographer = android.view.Choreographer.getInstance()
        val deltas = ArrayList<Double>()
        var startNs = 0L
        var lastNs = 0L
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (startNs == 0L) startNs = frameTimeNanos
                if (lastNs != 0L) deltas += (frameTimeNanos - lastNs) / 1e6
                lastNs = frameTimeNanos
                val raw = ((frameTimeNanos - startNs) / 1e6 / durMs).coerceIn(0.0, 1.0)
                val p = interp.getInterpolation(raw.toFloat())
                val scale = 1f + (0.16f - 1f) * p
                card.scaleX = scale
                card.scaleY = scale
                card.translationY = h * 0.36f * p
                card.alpha = if (raw < 0.75) 1f else (1f - ((raw - 0.75) / 0.25).toFloat())
                radius = cornerPx * p
                card.invalidateOutline()
                if (raw < 1.0) {
                    choreographer.postFrameCallback(this)
                } else {
                    finishAnim(label, deltas, nominalHz, fetchMs, bmp != null, w, h)
                }
            }
        }
        choreographer.postFrameCallback(cb)
    }

    private fun finishAnim(
        label: String, deltas: List<Double>, nominalHz: Float, fetchMs: Double, snapshot: Boolean, w: Int, h: Int
    ) {
        hideOverlay()
        val d = if (deltas.size > 3) deltas.drop(2) else deltas   // skip window-attach warm-up frames
        if (d.isEmpty()) { log("[$label] no frames recorded"); return }
        val sorted = d.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[minOf(sorted.size - 1, (sorted.size * 0.95).toInt())]
        val p99 = sorted[minOf(sorted.size - 1, (sorted.size * 0.99).toInt())]
        val max = sorted.last()
        val vsync = 1000.0 / nominalHz
        val janky = d.count { it > median * 1.5 }
        val dropped = d.sumOf { maxOf(0, Math.round(it / median).toInt() - 1) }
        log(
            "[$label]\n" +
                "  screen ${w}x$h, panel reports ${"%.0f".format(nominalHz)} Hz (vsync ${"%.1f".format(vsync)} ms)\n" +
                (if (snapshot) "  snapshot fetch: ${"%.1f".format(fetchMs)} ms (before the animation could start)\n" else "") +
                "  frames: ${d.size}, median ${"%.2f".format(median)} ms (~${"%.0f".format(1000.0 / median)} fps effective)\n" +
                "  p95 ${"%.2f".format(p95)} ms, p99 ${"%.2f".format(p99)} ms, worst ${"%.2f".format(max)} ms\n" +
                "  janky frames (> 1.5x median): $janky of ${d.size}, estimated dropped frames: $dropped\n" +
                "  verdict: " + when {
                dropped == 0 -> "SMOOTH (no dropped frames)"
                dropped <= 2 -> "MOSTLY SMOOTH ($dropped dropped)"
                else -> "STUTTER ($dropped dropped frames)"
            }
        )
    }

    companion object {
        // StatusBarManager constants (hidden, hard-coded)
        private const val DISABLE_EXPAND = 0x00010000
        private const val DISABLE_RECENT = 0x01000000
        private const val DISABLE2_QS = 1 shl 0
        private const val DISABLE2_SHADE = 1 shl 2
    }
}
