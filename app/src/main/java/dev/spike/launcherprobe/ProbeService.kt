package dev.spike.launcherprobe

import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-launched process (uid 2000 "shell" when started via wireless debugging).
 * Everything here is reflection against hidden framework APIs, and every failure is reported back
 * as text, because the point of this app is to find out which calls work on which Android version.
 */
class ProbeService : IProbeService.Stub() {

    private val thread = HandlerThread("probe-thread").also { it.start() }
    private val handler = Handler(thread.looper)
    private val token: IBinder = Binder()

    private var monitor: Any? = null
    private var receiver: MonitorReceiver? = null
    private val gestureLines = ArrayDeque<String>()

    private val restoreRunnable = Runnable { restoreInternal() }
    private val autoStopRunnable = Runnable { stopMonitorInternal() }

    // ---------------------------------------------------------------- basics

    override fun destroy() {
        // Deliberately does NOT run `cmd statusbar ... none`: the point of Test 1b is to see
        // whether cmd-set flags survive this process dying. Binder flags vanish with the token.
        stopMonitorInternal()
        stopTouchInternal()
        exitProcess(0)
    }

    override fun identity(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER} ${Build.MODEL}"

    // ---------------------------------------------------------------- status bar (binder)

    override fun statusBarDisable(what1: Int, what2: Int, autoRestoreSec: Int): String {
        handler.removeCallbacks(restoreRunnable)
        val out = StringBuilder()
        try {
            val sb = systemService("statusbar", STATUSBAR_STUB)
            out.appendLine("disable(0x${what1.toString(16)}): " + callDisable(sb, listOf("disable", "disableForUser"), what1))
            out.appendLine("disable2(0x${what2.toString(16)}): " + callDisable(sb, listOf("disable2", "disable2ForUser"), what2))
        } catch (t: Throwable) {
            out.appendLine("ERROR: ${describe(t)}")
        }
        if (autoRestoreSec > 0) {
            handler.postDelayed(restoreRunnable, autoRestoreSec * 1000L)
            out.appendLine("auto-restore in ${autoRestoreSec}s")
        }
        return out.toString().trimEnd()
    }

    override fun statusBarRestore(): String {
        handler.removeCallbacks(restoreRunnable)
        return restoreInternal()
    }

    private fun restoreInternal(): String {
        val out = StringBuilder()
        try {
            val sb = systemService("statusbar", STATUSBAR_STUB)
            out.appendLine("binder disable(0): " + callDisable(sb, listOf("disable", "disableForUser"), 0))
            out.appendLine("binder disable2(0): " + callDisable(sb, listOf("disable2", "disable2ForUser"), 0))
        } catch (t: Throwable) {
            out.appendLine("binder restore ERROR: ${describe(t)}")
        }
        out.appendLine("cmd none: " + shell("cmd statusbar send-disable-flag none", 5000).trim())
        return out.toString().trimEnd()
    }

    // ---------------------------------------------------------------- status bar (cmd)

    override fun statusBarCmd(flagNames: String, autoRestoreSec: Int): String {
        val out = StringBuilder()
        // Earlier runs leave a sleeping 'restore' behind; if it fires later it silently clears these new flags.
        shell("pkill -f 'sleep [0-9]*; cmd statusbar send-disable-flag none' 2>/dev/null; true", 3000)
        out.appendLine("cmd statusbar send-disable-flag $flagNames")
        out.appendLine(shell("cmd statusbar send-disable-flag $flagNames", 5000).trim())
        if (autoRestoreSec > 0) {
            // Dead-man switch that does not depend on this process staying alive.
            try {
                ProcessBuilder(
                    "sh", "-c",
                    "nohup sh -c 'sleep $autoRestoreSec; cmd statusbar send-disable-flag none' >/dev/null 2>&1 &"
                ).start()
                out.appendLine("detached restore scheduled in ${autoRestoreSec}s")
            } catch (t: Throwable) {
                out.appendLine("WARNING: could not schedule detached restore: ${describe(t)}")
            }
        }
        return out.toString().trimEnd()
    }

    // ---------------------------------------------------------------- recents

    override fun probeRecents(max: Int): String {
        val out = StringBuilder()
        try {
            val atm = systemService("activity_task", ATM_STUB)
            val m = atm.javaClass.methods.firstOrNull { it.name == "getRecentTasks" }
                ?: return "getRecentTasks not found on IActivityTaskManager"
            out.appendLine("getRecentTasks signature: ${m.parameterTypes.joinToString { it.simpleName }}")
            if (m.parameterTypes.size != 3) return out.appendLine("unexpected parameter count; adapt probe").toString()

            // (maxNum, flags = RECENT_IGNORE_UNAVAILABLE, userId = 0)
            val slice = m.invoke(atm, max, 2, 0) ?: return out.appendLine("returned null").toString()
            val list = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
            out.appendLine("-> ${list.size} recent tasks")

            val ids = mutableListOf<Int>()
            for ((i, t) in list.withIndex()) {
                if (t == null) continue
                val id = readInt(t, "taskId") ?: readInt(t, "persistentId") ?: -1
                val comp = readField(t, "topActivity") ?: readField(t, "baseActivity") ?: readField(t, "origActivity") ?: readField(t, "realActivity")
                out.appendLine("  #$i id=$id ${comp ?: "(component hidden by system)"}")
                ids += id
            }
            out.appendLine("(thumbnails are tested in the 'thumbnails' section)")
        } catch (t: Throwable) {
            out.appendLine("ERROR: ${describe(t)}")
        }
        return out.toString().trimEnd()
    }

    private fun probeSnapshot(atm: Any, taskId: Int): String {
        val m = atm.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" }
            ?: return "getTaskSnapshot not found"
        return try {
            var firstBoolean = true
            val args = m.parameterTypes.map { p ->
                when (p) {
                    Int::class.javaPrimitiveType -> taskId
                    Boolean::class.javaPrimitiveType -> if (firstBoolean) { firstBoolean = false; false } else true
                    else -> null
                }
            }.toTypedArray()
            val snap = m.invoke(atm, *args)
            if (snap == null) {
                "null (no cached snapshot, or permission missing)"
            } else {
                val hb = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer
                if (hb == null) "snapshot object but no hardware buffer" else "OK ${hb.width}x${hb.height} format=${hb.format}"
            }
        } catch (t: Throwable) {
            "FAILED: ${describe(t)}"
        }
    }

    // ---------------------------------------------------------------- gesture input monitor

    override fun startGestureMonitor(width: Int, height: Int, density: Float, mode: Int, autoStopSec: Int): String {
        stopMonitorInternal()
        return try {
            var mon: Any? = null
            val errors = StringBuilder()
            for (im in inputManagerCandidates()) {
                val m = im.javaClass.methods.firstOrNull { it.name == "monitorGestureInput" } ?: continue
                try {
                    mon = m.invoke(im, "launcherprobe", 0)
                    break
                } catch (t: Throwable) {
                    errors.append("${im.javaClass.simpleName}: ${describe(t)}; ")
                }
            }
            val monitorObj = mon ?: return "FAILED: no working monitorGestureInput. $errors"

            val channel = monitorObj.javaClass.getMethod("getInputChannel").invoke(monitorObj) as InputChannel
            monitor = monitorObj
            receiver = MonitorReceiver(channel, thread.looper, width, height, density, mode)

            if (autoStopSec > 0) handler.postDelayed(autoStopRunnable, autoStopSec * 1000L)
            "monitor started (mode=$mode, ${width}x$height, auto-stop ${autoStopSec}s). Swipe now."
        } catch (t: Throwable) {
            monitor = null
            "FAILED: ${describe(t)}"
        }
    }

    override fun stopGestureMonitor(): String = stopMonitorInternal()

    override fun drainGestureLog(): String = synchronized(gestureLines) {
        val s = gestureLines.joinToString("\n")
        gestureLines.clear()
        s
    }

    private fun stopMonitorInternal(): String {
        handler.removeCallbacks(autoStopRunnable)
        var result = "no monitor running"
        receiver?.let {
            try { it.dispose() } catch (e: Throwable) { /* ignore */ }
            receiver = null
            result = "monitor stopped"
        }
        monitor?.let {
            try { it.javaClass.getMethod("dispose").invoke(it) } catch (e: Throwable) { /* ignore */ }
            monitor = null
        }
        if (result == "monitor stopped") glog("monitor stopped")
        return result
    }

    private fun inputManagerCandidates(): List<Any> {
        val list = mutableListOf<Any>()
        for (cn in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val o = Class.forName(cn).getMethod("getInstance").invoke(null)
                if (o != null) list += o
            } catch (e: Throwable) { /* class or method absent on this Android version */ }
        }
        return list
    }

    private fun glog(s: String) {
        synchronized(gestureLines) {
            if (gestureLines.size > 300) gestureLines.removeFirst()
            gestureLines.addLast(String.format("[%6d] %s", SystemClock.uptimeMillis() % 1_000_000, s))
        }
    }

    private inner class MonitorReceiver(
        channel: InputChannel,
        looper: Looper,
        private val w: Int,
        private val h: Int,
        private val density: Float,
        private val mode: Int,
    ) : InputEventReceiver(channel, looper) {

        private var downX = 0f
        private var downY = 0f
        private var edge = "none"
        private var fired = false
        private var moves = 0
        private var pilfered = false

        override fun onInputEvent(event: InputEvent) {
            try {
                if (event is MotionEvent) handle(event)
            } catch (t: Throwable) {
                glog("receiver error: ${describe(t)}")
            } finally {
                finishInputEvent(event, true)
            }
        }

        private fun handle(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fired = false
                    pilfered = false
                    moves = 0
                    val margin = 40 * density
                    edge = when {
                        downY >= h - margin -> "bottom"
                        downY <= margin -> "top"
                        downX <= margin -> "left"
                        downX >= w - margin -> "right"
                        else -> "none"
                    }
                    glog("DOWN x=${downX.toInt()} y=${downY.toInt()} edge=$edge")
                }
                MotionEvent.ACTION_MOVE -> {
                    moves++
                    val vertical = edge == "top" || edge == "bottom"
                    if (mode == 2 && vertical && !pilfered) {
                        pilfered = true
                        glog("first MOVE from $edge -> " + pilfer())
                    }
                    if (fired) return
                    val dy = downY - e.rawY
                    val dx = e.rawX - downX
                    val t = 100 * density
                    val hit = (edge == "bottom" && dy > t) || (edge == "top" && -dy > t) ||
                        (edge == "left" && dx > t) || (edge == "right" && -dx > t)
                    if (hit) {
                        fired = true
                        glog("EDGE SWIPE from $edge detected (dx=${dx.toInt()} dy=${dy.toInt()})")
                        if (mode == 1 && vertical && !pilfered) {
                            pilfered = true
                            glog("pilfer -> " + pilfer())
                        }
                    }
                }
                MotionEvent.ACTION_UP -> glog("UP   y=${e.rawY.toInt()} moves=$moves")
                MotionEvent.ACTION_CANCEL -> glog("CANCEL moves=$moves (pointers pilfered by someone, or system took over)")
            }
        }

        private fun pilfer(): String = try {
            val mon = monitor ?: return "no monitor"
            mon.javaClass.getMethod("pilferPointers").invoke(mon)
            "pilferPointers() called"
        } catch (t: Throwable) {
            "pilfer FAILED: ${describe(t)}"
        }
    }

    // ---------------------------------------------------------------- v2: raw touch reader

    @Volatile private var touchRunning = false
    private var touchThread: Thread? = null
    private var touchProc: java.lang.Process? = null   // qualified: android.os.Process is imported
    private val touchAutoStop = Runnable { stopTouchInternal() }

    override fun listInputDevices(): String =
        shell("getevent -pl 2>&1 | grep -E 'add device|name:|ABS_MT_POSITION_X'", 6000)

    override fun startTouchReader(
        devicePath: String, width: Int, height: Int, density: Float,
        callback: IGestureCallback?, autoStopSec: Int,
    ): String {
        stopTouchInternal()
        if (callback == null) return "FAILED: no callback"

        // Discover touch devices + axis maxima with getevent -lp (labelled; hex codes as fallback).
        // getevent enumerates the real touchscreen itself, so no /dev/input node has to be guessed
        // per device — which is why the hardcoded event2 read nothing on the S24.
        val probe = shell("getevent -lp 2>&1", 5000)
        val devices = parseTouchDevices(probe)
        if (devices.isEmpty())
            return "FAILED: no device exposes ABS_MT_POSITION_X. Raw getevent -lp:\n$probe"

        val chosen = if (devicePath.isNotBlank() && devicePath != "auto")
            devices.firstOrNull { it.path == devicePath }
                ?: return "FAILED: $devicePath has no touch axes. Candidates:\n" +
                    devices.joinToString("\n") { "  ${it.path} \"${it.name}\" max ${it.maxX}x${it.maxY}" }
        else pickTouchscreen(devices)

        val proc = try {
            ProcessBuilder("getevent", "-l").redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return "FAILED to start getevent -l: ${describe(t)}"
        }
        touchProc = proc
        touchRunning = true
        val tracker = GestureTracker(width, height, density, callback)
        val t = Thread({ readGetevent(proc, chosen, width, height, tracker) }, "touch-reader")
        touchThread = t
        t.start()
        if (autoStopSec > 0) handler.postDelayed(touchAutoStop, autoStopSec * 1000L)
        return "touch reader started via getevent on ${chosen.path} (\"${chosen.name}\", " +
            "axis max ${chosen.maxX}x${chosen.maxY}, screen ${width}x$height, auto-stop ${autoStopSec}s). Swipe now."
    }

    override fun stopTouchReader(): String = stopTouchInternal()

    private fun stopTouchInternal(): String {
        handler.removeCallbacks(touchAutoStop)
        val was = touchRunning
        touchRunning = false
        touchProc?.let { try { it.destroy() } catch (e: Throwable) { /* ignore */ } } // unblocks readLine()
        touchProc = null
        touchThread?.let { try { it.join(800) } catch (e: Throwable) { /* ignore */ } }
        touchThread = null
        return if (was) "touch reader stopped" else "no touch reader running"
    }

    private data class TouchDev(val path: String, val name: String, val maxX: Int, val maxY: Int)

    /**
     * Choose the real touchscreen. The S24 exposes "sec_touchscreen" AND "sec_touchpad" with identical
     * 4095x4095 axes, so axis size alone can't decide (v4.3 picked the touchpad). Prefer a name containing
     * "touchscreen", then anything that isn't a touchpad/proximity/pen device, then the largest axis range.
     */
    private fun pickTouchscreen(devices: List<TouchDev>): TouchDev {
        fun score(d: TouchDev): Int {
            val n = d.name.lowercase()
            var s = 0
            if (n.contains("touchscreen")) s += 100
            if (n.contains("touchpad") || n.contains("proximity") || n.contains("pen") ||
                n.contains("stylus") || n.contains("hover")) s -= 100
            return s
        }
        return devices.sortedWith(
            compareByDescending<TouchDev> { score(it) }.thenByDescending { it.maxY }
        ).first()
    }

    /** Parse `getevent -lp` (labelled) output into the devices that carry MT position axes. */
    private fun parseTouchDevices(text: String): List<TouchDev> {
        val out = mutableListOf<TouchDev>()
        var path = ""; var name = ""; var maxX = -1; var maxY = -1
        fun flush() {
            if (path.isNotEmpty() && maxX > 0 && maxY > 0) out += TouchDev(path, name, maxX, maxY)
            path = ""; name = ""; maxX = -1; maxY = -1
        }
        val maxRe = Regex("""max\s+(\d+)""")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            Regex("""add device \d+:\s*(\S+)""").find(line)?.let { flush(); path = it.groupValues[1]; return@let }
            Regex("""name:\s*"(.*)"""").find(line)?.let { name = it.groupValues[1] }
            if (line.contains("ABS_MT_POSITION_X") || Regex("""\b0035\b""").containsMatchIn(line))
                maxRe.find(line)?.let { maxX = it.groupValues[1].toIntOrNull() ?: maxX }
            if (line.contains("ABS_MT_POSITION_Y") || Regex("""\b0036\b""").containsMatchIn(line))
                maxRe.find(line)?.let { maxY = it.groupValues[1].toIntOrNull() ?: maxY }
        }
        flush()
        return out
    }

    /** Stream `getevent -l` (all devices), keep the chosen device's lines, drive the tracker. */
    private fun readGetevent(proc: java.lang.Process, dev: TouchDev, w: Int, h: Int, tracker: GestureTracker) {
        val prefix = "${dev.path}:"
        val ws = Regex("""\s+""")
        var x = -1; var y = -1
        var down = false; var up = false
        var contact = false                                        // works for BTN_TOUCH- and tracking-id-only drivers
        try {
            val reader = proc.inputStream.bufferedReader()
            while (touchRunning) {
                val line = reader.readLine() ?: break
                if (!line.startsWith(prefix)) continue
                val parts = line.substring(prefix.length).trim().split(ws)
                if (parts.size < 3) continue
                when (parts[1]) {                                   // parts = [EV_TYPE, CODE, VALUE]
                    "ABS_MT_POSITION_X", "ABS_X" -> x = parts[2].toLongOrNull(16)?.toInt() ?: x
                    "ABS_MT_POSITION_Y", "ABS_Y" -> y = parts[2].toLongOrNull(16)?.toInt() ?: y
                    "BTN_TOUCH" -> when (parts[2]) {
                        "DOWN" -> { down = true; contact = true }
                        "UP" -> { up = true; contact = false }
                    }
                    "ABS_MT_TRACKING_ID" ->
                        if (parts[2].equals("ffffffff", true)) { up = true; contact = false }
                        else if (!contact) { down = true; contact = true }
                    "SYN_REPORT" -> {
                        val px = if (x in 0..dev.maxX) x.toFloat() * w / dev.maxX else -1f
                        val py = if (y in 0..dev.maxY) y.toFloat() * h / dev.maxY else -1f
                        val d = down; val u = up; down = false; up = false
                        handler.post { tracker.frame(d, u, px, py) }
                    }
                }
            }
        } catch (t: Throwable) {
            // stream closed by stopTouchInternal(), or read error — nothing to recover.
        } finally {
            try { proc.destroy() } catch (e: Throwable) { /* ignore */ }
        }
    }

    /** Bottom-edge swipe / hold detection. Runs on the handler thread only. */
    private inner class GestureTracker(
        private val w: Int, private val h: Int, private val density: Float, private val cb: IGestureCallback,
    ) {
        private var active = false
        private var pending = false
        private var fired = false
        private var edge = "none"
        private var downX = 0f
        private var downY = 0f
        private var curX = 0f
        private var curY = 0f
        private var downT = 0L
        private var anchorY = 0f
        private var anchorT = 0L
        private val holdCheck = Runnable { checkHold() }

        private fun send(text: String) {
            val stamped = String.format("[%6d] %s", SystemClock.uptimeMillis() % 1_000_000, text)
            try { cb.onGesture(stamped, curX, curY) } catch (t: Throwable) { /* app process gone */ }
        }

        private fun travel() = downY - curY

        fun frame(down: Boolean, up: Boolean, x: Float, y: Float) {
            if (down && x >= 0 && y >= 0) {
                active = true
                fired = false
                pending = false
                downX = x; downY = y; curX = x; curY = y
                downT = SystemClock.uptimeMillis()
                anchorY = y; anchorT = downT
                val band = h * 0.10f
                edge = when {
                    y >= h - band -> "bottom"
                    y <= band -> "top"
                    x <= w * 0.04f -> "left"
                    x >= w * 0.96f -> "right"
                    else -> "none"
                }
                send("DOWN edge=$edge x=${x.toInt()} y=${y.toInt()} (screen ${w}x$h)")
                return
            }
            if (up) {
                if (!active) return
                active = false
                handler.removeCallbacks(holdCheck)
                pending = false
                val dur = SystemClock.uptimeMillis() - downT
                val dp = (travel() / density).toInt()
                val kind = when {
                    fired -> "UP after HOLD"
                    edge == "bottom" && travel() >= h * HOLD_TRAVEL -> "SWIPE_UP (quick)"
                    else -> "UP"
                }
                send("$kind travel=${dp}dp dur=${dur}ms")
                return
            }
            if (!active || x < 0 || y < 0) return
            curX = x; curY = y
            if (abs(y - anchorY) > 6 * density) {
                anchorY = y
                anchorT = SystemClock.uptimeMillis()
            }
            if (!pending && !fired && edge == "bottom" && travel() >= h * HOLD_TRAVEL) {
                pending = true
                handler.postDelayed(holdCheck, HOLD_MS)
            }
        }

        private fun checkHold() {
            pending = false
            if (!active || fired || edge != "bottom" || travel() < h * HOLD_TRAVEL) return
            val dwell = SystemClock.uptimeMillis() - anchorT
            if (dwell >= HOLD_MS) {
                fired = true
                send("HOLD travel=${(travel() / density).toInt()}dp dwell=${dwell}ms")
            } else {
                pending = true
                handler.postDelayed(holdCheck, HOLD_MS - dwell)
            }
        }
    }

    // ---------------------------------------------------------------- v2: reflection dump

    override fun dumpMethods(ifaceName: String, regex: String): String = try {
        val re = Regex(regex, RegexOption.IGNORE_CASE)
        val lines = Class.forName(ifaceName).methods
            .filter { re.containsMatchIn(it.name) }
            .sortedBy { it.name }
            .map { m -> "  ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
        "$ifaceName: ${lines.size} matching methods\n" + lines.joinToString("\n")
    } catch (t: Throwable) {
        "$ifaceName: ${describe(t)}"
    }

    // ---------------------------------------------------------------- shell

    override fun runDetached(command: String): String = try {
        val devNull = File("/dev/null")
        ProcessBuilder("sh", "-c", command)
            .redirectInput(ProcessBuilder.Redirect.from(devNull))
            .redirectOutput(ProcessBuilder.Redirect.to(devNull))
            .redirectError(ProcessBuilder.Redirect.to(devNull))
            .start()
        "started (detached, output discarded): $command"
    } catch (t: Throwable) {
        "ERROR: ${describe(t)}"
    }

    override fun runShell(command: String): String = shell(command, 8000)

    private fun shell(command: String, timeoutMs: Long): String {
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { synchronized(sb) { sb.appendLine(it) } }
                } catch (e: Throwable) { /* stream closed */ }
            }
            reader.start()
            reader.join(timeoutMs)
            if (reader.isAlive) {
                p.destroyForcibly()
                synchronized(sb) { sb.toString() } + "[timeout, killed]"
            } else {
                p.waitFor()
                synchronized(sb) { sb.toString() }.trimEnd() + "\n[exit ${p.exitValue()}]"
            }
        } catch (t: Throwable) {
            "ERROR: ${describe(t)}"
        }
    }

    // ---------------------------------------------------------------- v3: requirement tests

    /** Recent task ids, most-recent first, via IActivityTaskManager.getRecentTasks. */
    private fun recentTaskIds(max: Int): List<Int> {
        val atm = systemService("activity_task", ATM_STUB)
        val m = atm.javaClass.methods.first { it.name == "getRecentTasks" }
        // (maxNum, flags = RECENT_IGNORE_UNAVAILABLE(2), userId = 0)
        val slice = m.invoke(atm, max, 2, 0) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val list = slice.javaClass.getMethod("getList").invoke(slice) as List<Any?>
        return list.mapNotNull { it?.let { t -> readInt(t, "taskId") ?: readInt(t, "persistentId") ?: readInt(t, "id") } }
    }

    override fun testThumbnails(max: Int): String {
        val out = StringBuilder("REQ: recents thumbnails\n")
        val ids = try { recentTaskIds(max) } catch (t: Throwable) {
            return out.append("  getRecentTasks FAILED: ${describe(t)}").toString()
        }
        if (ids.isEmpty()) return out.append("  no recent tasks (open a few apps first)").toString()
        out.appendLine("  tasks: $ids")

        // Path A: IWindowManager.snapshotTaskForRecents(int) -> Bitmap
        try {
            val wm = systemService("window", WM_STUB)
            val m = wm.javaClass.methods.firstOrNull { it.name == "snapshotTaskForRecents" }
            if (m == null) out.appendLine("  A snapshotTaskForRecents: method absent")
            else {
                val bmp = m.invoke(wm, ids.first())
                out.appendLine("  A snapshotTaskForRecents(${ids.first()}): " + bitmapDesc(bmp))
            }
        } catch (t: Throwable) {
            out.appendLine("  A snapshotTaskForRecents FAILED: ${describe(t)}")
        }

        // Path B: IActivityTaskManager.getTaskSnapshot(int, boolean) -> TaskSnapshot -> HardwareBuffer
        try {
            val atm = systemService("activity_task", ATM_STUB)
            val m = atm.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" && it.parameterTypes.size == 2 }
            if (m == null) out.appendLine("  B getTaskSnapshot: method absent")
            else {
                val snap = m.invoke(atm, ids.first(), false)
                if (snap == null) out.appendLine("  B getTaskSnapshot(${ids.first()}): null (no cached snapshot)")
                else {
                    val hb = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer
                    out.appendLine("  B getTaskSnapshot: " + if (hb == null) "snapshot but no buffer"
                        else "OK ${hb.width}x${hb.height} format=${hb.format}")
                }
            }
        } catch (t: Throwable) {
            out.appendLine("  B getTaskSnapshot FAILED: ${describe(t)}")
        }
        return out.toString().trimEnd()
    }

    private fun bitmapDesc(bmp: Any?): String = when {
        bmp == null -> "null (permission missing or no snapshot)"
        else -> try {
            val w = bmp.javaClass.getMethod("getWidth").invoke(bmp)
            val h = bmp.javaClass.getMethod("getHeight").invoke(bmp)
            "OK ${w}x$h bitmap"
        } catch (t: Throwable) { "non-null but unreadable: ${describe(t)}" }
    }

    override fun testSwitchToTask(taskId: Int): String {
        return try {
            val id = if (taskId != 0) taskId else recentTaskIds(10).lastOrNull()
                ?: return "REQ: switch app\n  no task to switch to"
            val atm = systemService("activity_task", ATM_STUB)
            val m = atm.javaClass.methods.firstOrNull { it.name == "startActivityFromRecents" }
                ?: return "REQ: switch app\n  startActivityFromRecents absent"
            m.invoke(atm, id, null)
            "REQ: switch app\n  startActivityFromRecents($id): PASS (call returned; task should be foreground)"
        } catch (t: Throwable) {
            "REQ: switch app\n  startActivityFromRecents FAILED: ${describe(t)}"
        }
    }

    override fun testRemoveTask(taskId: Int): String {
        if (taskId == 0) {
            // Non-destructive: just verify the method resolves.
            return try {
                val atm = systemService("activity_task", ATM_STUB)
                val present = atm.javaClass.methods.any { it.name == "removeTask" && it.parameterTypes.size == 1 }
                "REQ: remove task\n  removeTask(int) present: $present (pass a real id to actually remove)"
            } catch (t: Throwable) { "REQ: remove task\n  FAILED: ${describe(t)}" }
        }
        return try {
            val atm = systemService("activity_task", ATM_STUB)
            val m = atm.javaClass.methods.first { it.name == "removeTask" && it.parameterTypes.size == 1 }
            val r = m.invoke(atm, taskId)
            "REQ: remove task\n  removeTask($taskId) -> $r"
        } catch (t: Throwable) { "REQ: remove task\n  FAILED: ${describe(t)}" }
    }

    override fun testGoHome(): String {
        val r = shell(
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1", 5000
        ).trim()
        val ok = !r.contains("Error", true) && !r.contains("Exception", true)
        return "REQ: go home (shell)\n  ${if (ok) "PASS" else "CHECK"}: $r"
    }

    override fun testToggle(action: String): String {
        val cmd = when (action) {
            "wifi-on" -> "svc wifi enable"
            "wifi-off" -> "svc wifi disable"
            "bt-on" -> "svc bluetooth enable"
            "bt-off" -> "svc bluetooth disable"
            "data-on" -> "svc data enable"
            "data-off" -> "svc data disable"
            "dnd-on" -> "cmd notification set_dnd priority"
            "dnd-off" -> "cmd notification set_dnd off"
            "bright-min" -> "settings put system screen_brightness 10"
            "bright-max" -> "settings put system screen_brightness 255"
            else -> return "REQ: toggle\n  unknown action '$action'"
        }
        val r = shell("$cmd 2>&1", 6000).trim()
        val cmdOk = !r.contains("Error", true) && !r.contains("Exception", true) &&
            !r.contains("Permission", true) && !r.contains("Denial", true)
        // Read the state back so PASS means "it actually changed", not just "exit 0".
        val (readCmd, expect) = when (action) {
            "wifi-on" -> "settings get global wifi_on" to "1"
            "wifi-off" -> "settings get global wifi_on" to "0"
            "bt-on" -> "settings get global bluetooth_on" to "1"
            "bt-off" -> "settings get global bluetooth_on" to "0"
            "dnd-on" -> "settings get global zen_mode" to "1"
            "dnd-off" -> "settings get global zen_mode" to "0"
            "bright-min" -> "settings get system screen_brightness" to "10"
            "bright-max" -> "settings get system screen_brightness" to "255"
            else -> null to null
        }
        var verdict = if (cmdOk) "PASS" else "FAIL"
        var detail = r.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("[exit") } ?: "ok"
        if (cmdOk && readCmd != null && expect != null) {
            var actual = ""
            for (attempt in 0 until 6) {          // radios settle asynchronously
                actual = shell("$readCmd 2>&1", 3000).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                if (actual == expect) break
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            verdict = if (actual == expect) "PASS" else "CHECK"
            detail = "state ${readCmd.substringAfterLast(' ')}=$actual (expected $expect)"
        }
        return "REQ: toggle $action\n  $verdict: $detail"
    }

    override fun testSecureSettings(): String {
        val out = StringBuilder("REQ: WRITE_SECURE_SETTINGS self-grant\n")
        val g = shell("pm grant $APP_PKG android.permission.WRITE_SECURE_SETTINGS 2>&1", 5000)
            .lineSequence().filter { it.isNotBlank() && !it.startsWith("[exit") }.joinToString(" ").trim()
        val dump = shell("dumpsys package $APP_PKG 2>&1", 8000)
        val granted = dump.lineSequence().any { it.contains("android.permission.WRITE_SECURE_SETTINGS") && it.contains("granted=true") }
        out.appendLine("  ${if (granted) "PASS" else "FAIL"}: WRITE_SECURE_SETTINGS granted=$granted" +
            (if (g.isNotEmpty()) " (pm grant said: $g)" else ""))
        return out.toString().trimEnd()
    }

    override fun testDefaultHome(): String {
        val out = StringBuilder("REQ: default launcher / home role\n")
        val holder = shell("cmd role get-role-holders android.app.role.HOME 2>&1", 5000)
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("[exit") } ?: ""
        val resolved = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1", 5000)
            .lineSequence().map { it.trim() }.lastOrNull { it.contains('/') } ?: ""
        val ok = holder.isNotEmpty() || resolved.isNotEmpty()
        out.appendLine("  ${if (ok) "PASS" else "CHECK"}: role holder=${holder.ifEmpty { "(none reported)" }}; resolves to ${resolved.ifEmpty { "(unknown)" }}")
        return out.toString().trimEnd()
    }

    override fun runAllTests(): String {
        val sb = StringBuilder("===== LauncherProbe v6 requirement report =====\n")
        sb.appendLine(identity()).appendLine()
        val steps: List<Pair<String, () -> String>> = listOf(
            "recents list" to { probeRecents(10) },
            "thumbnails" to { testThumbnails(10) },
            "default home" to { testDefaultHome() },
            "secure settings" to { testSecureSettings() },
            "wifi off" to { testToggle("wifi-off") },
            "wifi on" to { testToggle("wifi-on") },
            "bluetooth off" to { testToggle("bt-off") },
            "bluetooth on" to { testToggle("bt-on") },
            "dnd on" to { testToggle("dnd-on") },
            "dnd off" to { testToggle("dnd-off") },
            "remove-task resolves" to { testRemoveTask(0) },
        )
        for ((name, block) in steps) {
            sb.appendLine("--- $name ---")
            sb.appendLine(try { block() } catch (t: Throwable) { "EXCEPTION: ${describe(t)}" })
            sb.appendLine()
        }
        sb.appendLine("NOTE: switch-to-task and actual task removal are run from their own buttons")
        sb.appendLine("(they change foreground state), and gesture/overlay tests need a real swipe.")
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- v5: animation feasibility

    /** Raw snapshot from the window manager (may be a HARDWARE bitmap); null if unavailable. */
    private fun rawSnapshot(taskId: Int): android.graphics.Bitmap? {
        val wm = systemService("window", WM_STUB)
        val m = wm.javaClass.methods.firstOrNull { it.name == "snapshotTaskForRecents" } ?: return null
        return m.invoke(wm, taskId) as? android.graphics.Bitmap
    }

    override fun snapshotTask(taskId: Int): android.graphics.Bitmap? {
        try {
            var id = taskId
            if (id == 0) {
                val ids = recentTaskIds(5)
                id = if (ids.size > 1) ids[1] else (ids.firstOrNull() ?: return null)
            }
            // Returned as-is: a HARDWARE bitmap is parcelable across Binder (this is how SystemUI passes
            // snapshots). v5.0 copied it to a software bitmap inside this shell process, which needs GPU
            // readback machinery a non-app process lacks and likely killed the service.
            return rawSnapshot(id)
        } catch (t: Throwable) {
            return null
        }
    }

    // ---- v5.2: snapshots of BACKGROUND tasks -------------------------------------------------

    override fun listTaskIds(max: Int): IntArray = try {
        recentTaskIds(max.coerceIn(1, 20)).toIntArray()
    } catch (t: Throwable) {
        IntArray(0)
    }

    /** mode 1 = getTaskSnapshot(id,false) cached, 2 = low-res cached, 3 = takeTaskSnapshot(id,false). */
    private fun taskSnapshotBuffer(taskId: Int, mode: Int): HardwareBuffer? {
        val atm = systemService("activity_task", ATM_STUB)
        val ms = atm.javaClass.methods
        val snap: Any? = when (mode) {
            1 -> ms.firstOrNull { it.name == "getTaskSnapshot" && it.parameterTypes.size == 2 }?.invoke(atm, taskId, false)
            2 -> ms.firstOrNull { it.name == "getTaskSnapshotLowResolution" && it.parameterTypes.size == 1 }?.invoke(atm, taskId)
                ?: ms.firstOrNull { it.name == "getTaskSnapshot" && it.parameterTypes.size == 2 }?.invoke(atm, taskId, true)
            3 -> ms.firstOrNull { it.name == "takeTaskSnapshot" && it.parameterTypes.size == 2 }?.invoke(atm, taskId, false)
            else -> null
        }
        return snap?.javaClass?.getMethod("getHardwareBuffer")?.invoke(snap) as? HardwareBuffer
    }

    /** Wraps the cached snapshot buffer in a hardware Bitmap (a pure wrap, no GPU readback) so it can cross Binder. */
    override fun snapshotBuffer(taskId: Int, mode: Int): android.graphics.Bitmap? = try {
        val hb = taskSnapshotBuffer(taskId, mode)
        if (hb == null) null else android.graphics.Bitmap.wrapHardwareBuffer(hb, null)
    } catch (t: Throwable) {
        null
    }

    override fun snapshotMatrix(max: Int): String {
        val out = StringBuilder("REQ: snapshot paths per recent task (live = only works for the visible task; cached = taken when an app left the screen)\n")
        out.appendLine("  A=snapshotTaskForRecents  B=getTaskSnapshot cached  L=low-res cached  C=takeTaskSnapshot")
        try {
            val ids = recentTaskIds(max.coerceIn(1, 12))
            if (ids.isEmpty()) return out.append("  no recent tasks").toString()
            for ((i, id) in ids.withIndex()) {
                val cells = ArrayList<String>()
                fun cell(tag: String, block: () -> String) {
                    val t0 = System.nanoTime()
                    val r = try { block() } catch (t: Throwable) { "ERR(${describe(t)})" }
                    cells += "$tag:$r ${"%.0f".format((System.nanoTime() - t0) / 1e6)}ms"
                }
                cell("A") {
                    val b = rawSnapshot(id)
                    if (b == null) "null" else "${b.width}x${b.height}"
                }
                for ((tag, mode) in listOf("B" to 1, "L" to 2, "C" to 3)) {
                    cell(tag) {
                        val hb = taskSnapshotBuffer(id, mode)
                        if (hb == null) "null" else { val d = "${hb.width}x${hb.height}"; hb.close(); d }
                    }
                }
                out.appendLine("  #$i task $id${if (i == 0) " (foreground)" else ""}: ${cells.joinToString("  ")}")
            }
        } catch (t: Throwable) {
            out.appendLine("  FAILED: ${describe(t)}")
        }
        return out.toString().trimEnd()
    }

    private fun stats(ms: List<Double>): String {
        if (ms.isEmpty()) return "no samples"
        val s = ms.sorted()
        fun p(q: Double) = s[minOf(s.size - 1, (q * s.size).toInt())]
        return String.format("n=%d min=%.1f avg=%.1f p95=%.1f max=%.1f ms", s.size, s.first(), s.average(), p(0.95), s.last())
    }

    override fun benchSnapshots(repeats: Int): String {
        val out = StringBuilder("REQ: snapshot latency (this is the delay before a custom recents/transition overlay can show an app image)\n")
        try {
            val ids = recentTaskIds(6).take(4)
            if (ids.isEmpty()) return out.append("  no recent tasks (open a few apps first)").toString()
            val reps = repeats.coerceIn(1, 20)
            val raw = ArrayList<Double>()
            var desc = ""
            for (id in ids) {
                for (i in 0 until reps) {
                    val t0 = System.nanoTime()
                    val b = rawSnapshot(id)
                    val t1 = System.nanoTime()
                    if (b == null) { desc = "null for task $id"; continue }
                    raw += (t1 - t0) / 1e6
                    if (desc.isEmpty()) desc = "${b.width}x${b.height} config=${b.config}"
                }
            }
            out.appendLine("  tasks tried: $ids x$reps, bitmap: $desc")
            out.appendLine("  snapshotTaskForRecents: ${stats(raw)}")
            val avg = if (raw.isEmpty()) 0.0 else raw.average()
            out.appendLine("  verdict: ${if (raw.isEmpty()) "FAIL" else if (avg <= 50.0) "GOOD (<= 50 ms per snapshot; prefetch makes it invisible)" else "SLOW (> 50 ms; must prefetch snapshots in the background)"}")
        } catch (t: Throwable) {
            out.appendLine("  FAILED: ${describe(t)}")
        }
        return out.toString().trimEnd()
    }

    // ---------------------------------------------------------------- reflection helpers

    private fun describe(t: Throwable): String {
        val c = if (t is InvocationTargetException) (t.targetException ?: t) else t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private fun systemService(name: String, stubClass: String): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
            ?: throw IllegalStateException("system service '$name' not found")
        return Class.forName(stubClass).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    private var winThread: HandlerThread? = null

    override fun shellWindowTest(type: Int, seconds: Int): String {
        val result = arrayOf("not run")
        val latch = java.util.concurrent.CountDownLatch(1)
        val ht = winThread ?: HandlerThread("shell-window").also { it.start(); winThread = it }
        val h = Handler(ht.looper)
        h.post {
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                var at: Any? = atClass.getMethod("currentActivityThread").invoke(null)
                if (at == null) at = atClass.getMethod("systemMain").invoke(null)
                val sysUi = atClass.getMethod("getSystemUiContext").invoke(at) as android.content.Context
                val wm = sysUi.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                val tv = android.widget.TextView(sysUi)
                tv.text = "SHELL WINDOW type $type"
                tv.setTextColor(-1)
                tv.gravity = android.view.Gravity.CENTER
                tv.setBackgroundColor(0xFFCC2244.toInt())
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, 140, type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                lp.gravity = android.view.Gravity.TOP
                lp.packageName = PKG
                lp.title = "probe-shell-$type"
                wm.addView(tv, lp)
                result[0] = "added OK"
                h.postDelayed({ try { wm.removeView(tv) } catch (t: Throwable) { /* gone */ } }, seconds * 1000L)
            } catch (t: Throwable) {
                result[0] = "FAILED: " + describe(t)
            }
            latch.countDown()
        }
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return "type $type: ${result[0]}"
    }

    private fun callDisable(sb: Any, names: List<String>, what: Int): String {
        for (n in names) {
            val m = sb.javaClass.methods.firstOrNull { it.name == n && it.parameterTypes.size in 3..4 } ?: continue
            return try {
                if (m.parameterTypes.size == 3) m.invoke(sb, what, token, PKG) else m.invoke(sb, what, token, PKG, 0)
                "ok via $n(${m.parameterTypes.size} args)"
            } catch (t: Throwable) {
                "$n FAILED: ${describe(t)}"
            }
        }
        return "no matching method among $names"
    }

    private fun readField(o: Any, name: String): Any? =
        try { o.javaClass.getField(name).get(o) } catch (e: Throwable) { null }

    private fun readInt(o: Any, name: String): Int? = readField(o, name) as? Int

    companion object {
        private const val HOLD_MS = 300L          // finger must stay (nearly) still this long
        private const val HOLD_TRAVEL = 0.10f     // ...after travelling this fraction of screen height
        private const val PKG = "com.android.shell"
        private const val APP_PKG = "dev.spike.launcherprobe"
        private const val STATUSBAR_STUB = "com.android.internal.statusbar.IStatusBarService\$Stub"
        private const val ATM_STUB = "android.app.IActivityTaskManager\$Stub"
        private const val WM_STUB = "android.view.IWindowManager\$Stub"
    }
}
