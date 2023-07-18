package net.christianbeier.droidvnc_ng

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

object Utils {

    @JvmStatic
    fun getProp(prop: String) : String {
        val process = ProcessBuilder().command("/system/bin/getprop", prop).start()
        return BufferedReader(InputStreamReader(process.inputStream)).readLine()
    }

    @JvmStatic
    fun getDisplayInset(context: Context, displayId: Int) : Rect {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        if (Build.VERSION.SDK_INT >= 29) {
            val cutout = displayManager.getDisplay(displayId).cutout
            if(cutout != null) {
                return Rect(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom)
            }
        }

        @SuppressLint("InternalInsetResource")
        @SuppressLint("DiscouragedApi")
        if (Build.VERSION.SDK_INT <= 30) {
            // Android up to and including API level 30 have a status bar offset from the display metrics
            val resources = context.resources
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                return Rect(0, context.resources.getDimensionPixelSize(resourceId), 0, 0)
            }
        }

        return Rect()
    }

}