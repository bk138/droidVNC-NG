package net.christianbeier.droidvnc_ng

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

object Utils {

    @JvmStatic
    fun getProp(prop: String) : String {
        try {
            val process = ProcessBuilder().command("/system/bin/getprop", prop).start()
            return BufferedReader(InputStreamReader(process.inputStream)).readLine()
        } catch (_: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun getDisplayMetrics(ctx: Context, displayId: Int): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(displayId).getRealMetrics(displayMetrics)
        return displayMetrics
    }

    @JvmStatic
    fun getDeviceName(ctx: Context): String {
        return try {
            // This is what we had until targetSDK 33.
            Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")
        } catch (_: SecurityException) {
            // throws on devices with API level 33, so trigger use of fallback
            null
        } ?: run {
            if (Build.VERSION.SDK_INT > 25) {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
        } ?: ctx.getString(R.string.app_name) // if any of these is null, return app name
    }

    @JvmStatic
    fun Context.copyAssetsToDir(assetDir: String, outDir: String) {
        val assetManager = assets
        val outDirFile = File(outDir)

        try {
            val files = assetManager.list(assetDir) ?: return

            // Ensure the output directory exists
            if (!outDirFile.exists()) {
                outDirFile.mkdirs()
            }

            for (filename in files) {
                val assetPath = "$assetDir/$filename"
                val outFile = File(outDirFile, filename)

                // Check if this is a directory or a file
                if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                    // If it's a directory, recursively copy its contents
                    copyAssetsToDir(assetPath, outFile.absolutePath)
                } else {
                    // If it's a file, copy it
                    val inStream: InputStream = assetManager.open(assetPath)
                    val outStream = FileOutputStream(outFile)

                    val buffer = ByteArray(1024)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                    }

                    inStream.close()
                    outStream.flush()
                    outStream.close()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun deleteRecursively(directory: String) {
        val directory = File(directory)
        directory.deleteRecursively()
    }

    /**
     * Run action under lock with returning a value.
     * Kotlin's kotlin.concurrent.withLock for Java.
     */
    @JvmStatic
    fun <T> withLock(lock: Lock, action: () -> T): T {
        return lock.withLock(action)
    }

    /**
     * Run action under lock without returning a value.
     */
    @JvmStatic
    fun withLock(lock: Lock, action: Runnable) {
        lock.lock()
        try {
            action.run()
        } finally {
            lock.unlock()
        }
    }

}