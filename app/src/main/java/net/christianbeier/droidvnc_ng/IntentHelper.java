package net.christianbeier.droidvnc_ng;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class IntentHelper {

	public static void sendIntent(Context context, Intent intent) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
	}
}
