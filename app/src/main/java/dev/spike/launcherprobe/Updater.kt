package dev.spike.launcherprobe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Downloads the newest CI build from GitHub and installs it silently through Shizuku, then reopens the app. */
class Updater(private val ctx: Context, private val log: (String) -> Unit, private val svc: () -> IProbeService?) {
    private val io = Executors.newSingleThreadExecutor()
    private val repo = "MatheeshaSIlva/requirementchecks"
    private val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true }

    fun update(force: Boolean) {
        val s = svc() ?: run { log("[update] Shizuku service not connected - connect it first (silent install needs it)"); return }
        io.execute {
            try {
                log("[update] checking GitHub...")
                val meta = get("https://api.github.com/repos/$repo/releases/tags/latest").inputStream.bufferedReader().readText()
                val sha = JSONObject(meta).optString("body").trim()
                val have = prefs.getString("sha", "")
                if (!force && sha.isNotEmpty() && sha == have) { log("[update] already on the newest build (${sha.take(7)}). Use the force button to reinstall."); return@execute }
                log("[update] new build ${sha.take(7)} (installed: ${(have ?: "").take(7).ifEmpty { "unknown" }}), downloading...")
                val dir = ctx.getExternalFilesDir(null) ?: run { log("[update] no external files dir"); return@execute }
                val f = File(dir, "update.apk")
                val c = get("https://github.com/$repo/releases/download/latest/LauncherProbe.apk")
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                log("[update] downloaded ${f.length() / 1024} KB, installing silently; the app will close and reopen by itself.")
                prefs.edit().putString("sha", sha).commit()
                val path = f.absolutePath
                s.runShell("setsid nohup sh -c 'sleep 1; pm install -r -d $path > /data/local/tmp/update.log 2>&1; am start -n ${ctx.packageName}/.MainActivity >> /data/local/tmp/update.log 2>&1' >/dev/null 2>&1 &")
            } catch (t: Throwable) {
                log("[update] failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
