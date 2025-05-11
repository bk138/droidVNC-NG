package net.christianbeier.droidvnc_ng

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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

                // Unattended start needs InputService on Android 10 and newer, both for the activity starts from MainService
                // (could be reworked) but most importantly for fallback screen capture
                if (Build.VERSION.SDK_INT >= 30) {
                    MainService.addFallbackScreenCaptureIfNotAppOp(context.applicationContext, intent)

                    // wait for InputService to come up
                    CoroutineScope(Dispatchers.Main).launch {
                        var tries = 0
                        while (!InputService.isConnected() && ++tries <= 5) {
                            Log.w(
                                TAG,
                                "onReceive: on Android 10+ and InputService not yet set up, waiting $tries of 5"
                            )
                            delay(1000)
                        }

                        if (InputService.isConnected()) {
                            Log.i(
                                TAG,
                                "onReceive: on Android 10+ and InputService set up, starting MainService"
                            )
                            context.applicationContext.startForegroundService(intent)
                        } else {
                            Log.e(
                                TAG,
                                "onReceive: on Android 10+ and InputService not set up, bailing out"
                            )
                        }
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
