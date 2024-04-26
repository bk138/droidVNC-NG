package net.christianbeier.droidvnc_ng

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import java.io.BufferedReader
import java.io.InputStreamReader

object Utils {

    @JvmStatic
    fun getProp(prop: String) : String {
        val process = ProcessBuilder().command("/system/bin/getprop", prop).start()
        return BufferedReader(InputStreamReader(process.inputStream)).readLine()
    }

    @JvmStatic
    fun getDisplayMetrics(ctx: Context, displayId: Int): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(displayId).getRealMetrics(displayMetrics)
        return displayMetrics
    }

    @JvmStatic
    fun getDeviceName(ctx: Context): String? {
        // get device name
        return try {
            // This is what we had until targetSDK 33.
            Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")
        } catch (ignored: SecurityException) {
            // throws on devices with API level 33, so use fallback
            if (Build.VERSION.SDK_INT > 25) {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                ctx.getString(R.string.app_name)
            }
        }
    }

}