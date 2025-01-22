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

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

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


    @JvmStatic
    fun getAvailableNICs(upIfOnly: Boolean, ipAvailOnly: Boolean): ArrayList<NetworkInterface> {
        val nics = ArrayList<NetworkInterface>();

        try {
            // Thanks go to https://stackoverflow.com/a/20103869/361413
            val nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement();
                if (upIfOnly) {
                    if (ipAvailOnly) {
                        // Check if there are actual ipv4 addresses, if so, add the NetworkInterface
                        // Should we consider IPv6 also? Technically yes, but the program, at the moment, does not support them.
                        for (ia in ni.getInterfaceAddresses()) {
                            if (ia.getAddress().getAddress().count() == 4) {
                                nics.add(ni);
                                break;
                            }
                        }
                    } else {
                        nics.add(ni);
                    }
                } else {
                    nics.add(ni);
                }
            }
        } catch (e: SocketException) {
            //unused
        }

        return nics;
    }


    @JvmStatic
    fun getAvailableNICs(): ArrayList<NetworkInterface> {
        return getAvailableNICs(false, true);
    }


    @JvmStatic
    fun getIPv4ForInterface(ni: String): ArrayList<String>{
        return getIPv4ForInterface(NetworkInterface.getByName(ni));
    }


    @JvmStatic
    fun getIPv4ForInterface(ni: NetworkInterface): ArrayList<String>{
        val ipv4s = ArrayList<String>();

        for (ia in ni.getInterfaceAddresses()) {
            //filter for ipv4/ipv6
            if (ia.getAddress().getAddress().count() == 4) {
                //4 for ipv4, 16 for ipv6
                ipv4s.add(ia.getAddress().toString().replace("/", ""));
            }
        }

        return ipv4s;
    }
}