package dev.spike.launcherprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import rikka.shizuku.Shizuku

/** Records what the world looks like right after boot, so we learn what an automatic Shizuku restart could rely on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cr = context.contentResolver
        val alive = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
        val adbEnabled = try { android.provider.Settings.Global.getInt(cr, "adb_enabled", -1) } catch (t: Throwable) { -2 }
        val adbWifi = try { android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled", -1) } catch (t: Throwable) { -2 }
        val hasSecure = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        V6Lab.appendBootLog(
            context,
            "BOOT_COMPLETED, up ${SystemClock.elapsedRealtime() / 1000}s; Shizuku running: $alive; " +
                "adb_enabled=$adbEnabled adb_wifi_enabled=$adbWifi; we hold WRITE_SECURE_SETTINGS: $hasSecure"
        )
        val auto = context.getSharedPreferences("v6", Context.MODE_PRIVATE).getBoolean("autoAdbWifi", false)
        if (auto) {
            val r = try {
                android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                "put adb_wifi_enabled=1 returned ok"
            } catch (t: Throwable) {
                "put adb_wifi_enabled FAILED: ${t.javaClass.simpleName}: ${t.message}"
            }
            V6Lab.appendBootLog(context, "auto-enable wireless debugging: $r")
        }
    }
}
