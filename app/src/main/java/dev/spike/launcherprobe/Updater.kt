package dev.spike.launcherprobe

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Downloads the newest CI build from GitHub (inside the Shizuku shell process) and installs it silently, then reopens the app. */
class Updater(private val ctx: Context, private val log: (String) -> Unit, private val svc: () -> IProbeService?) {
    private val io = Executors.newSingleThreadExecutor()
    private val repo = "MatheeshaSIlva/requirementchecks"
    private val dest = "/data/local/tmp/update.apk"

    private fun installed(): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (t: Throwable) { "?" }

    fun update(force: Boolean) {
        val s = svc() ?: run { log("[update] Shizuku service not connected - connect it first (silent install needs it)"); return }
        io.execute {
            try {
                log("[update] checking GitHub... (this app is build ${installed()})")
                val c = URL("https://api.github.com/repos/$repo/releases/tags/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val sha = JSONObject(c.inputStream.bufferedReader().readText()).optString("body").trim()
                if (!force && sha.isNotEmpty() && sha.take(7) == installed()) { log("[update] already on the newest build (${sha.take(7)}). Use the force button to reinstall."); return@execute }
                log("[update] newest is ${sha.take(7)}; downloading inside the shell process...")
                val r = s.downloadFile("https://github.com/$repo/releases/download/latest/LauncherProbe.apk", dest)
                log("[update] download: $r")
                if (!r.startsWith("OK")) return@execute
                log("[update] installing silently; if it works the app closes and reopens by itself.")
                s.runShell("rm -f /data/local/tmp/update.log; setsid nohup sh -c 'sleep 1; pm install -r -d $dest > /data/local/tmp/update.log 2>&1; am start -n ${ctx.packageName}/.MainActivity >> /data/local/tmp/update.log 2>&1' >/dev/null 2>&1 &")
                // If the install worked this process is killed. If we are still alive after a few seconds, it failed: show why.
                Thread.sleep(9000)
                val why = s.runShell("cat /data/local/tmp/update.log 2>&1; ls -l $dest 2>&1").trim()
                log("[update] still running 9 s later, so the install did not replace the app. Installer said:\n$why")
            } catch (t: Throwable) {
                log("[update] failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
