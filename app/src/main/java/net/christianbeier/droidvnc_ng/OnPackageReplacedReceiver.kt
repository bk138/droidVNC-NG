package net.christianbeier.droidvnc_ng

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/*
 * Broadcast receiver that's being triggered when the package is replaced/updated.
 * Test with `adb install -r app/build/outputs/apk/debug/app-debug.apk`
 */
class OnPackageReplacedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OnPackageReplacedReceiver"
    }

    override fun onReceive(context: Context, arg: Intent) {
        if (arg.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (MainServicePersistData.loadLastActiveState(context)) {
                Log.i(TAG, "onReceive: server was running before, trying restart")

                // Instead of reading settings like OnBootReceiver does, we use the persisted start
                // Intent here as the service could have been started via the Intent Interface.
                val intent = MainServicePersistData.loadStartIntent(context)

                // Unattended start needs InputService on Android 11 and newer, both for the activity starts from MainService
                // (could be reworked) but most importantly for fallback screen capture
                if (Build.VERSION.SDK_INT >= 30) {
                    MainService.addFallbackScreenCaptureIfNotAppOp(context.applicationContext, intent)

                    // wait for InputService to come up
                    InputService.runWhenConnected {
                        Log.i(
                            TAG,
                            "on Android 11+ and InputService set up, starting MainService"
                        )
                        context.applicationContext.startForegroundService(intent)
                    }
                } else {
                    // start immediately
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.applicationContext.startForegroundService(intent)
                    } else {
                        context.applicationContext.startService(intent)
                    }
                }
            } else {
                Log.i(TAG, "onReceive: server was not running before, not restarting")
            }
        }
    }
}
