package net.christianbeier.droidvnc_ng

import java.io.BufferedReader
import java.io.InputStreamReader

object Utils {

    @JvmStatic
    fun getProp(prop: String) : String {
        val process = ProcessBuilder().command("/system/bin/getprop", prop).start()
        return BufferedReader(InputStreamReader(process.inputStream)).readLine()
    }
}