package net.christianbeier.droidvnc_ng

import android.content.Context
import android.hardware.display.DisplayManager
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

}