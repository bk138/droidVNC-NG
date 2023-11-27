/*
 * DroidVNC-NG broadcast receiver that listens for boot-completed events
 * and starts MainService in turn.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2020, 2023 Kitchen Armor.
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */

package net.christianbeier.droidvnc_ng;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.preference.PreferenceManager;
import android.os.SystemClock;
import android.util.Log;


public class OnBootReceiver extends BroadcastReceiver {

    private static final String TAG = "OnBootReceiver";

    public void onReceive(Context context, Intent arg1)
    {
        if(Intent.ACTION_BOOT_COMPLETED.equals(arg1.getAction())) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Defaults defaults = new Defaults(context);
        if(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, defaults.getStartOnBoot())) {
            Intent intent = new Intent(context, MainService.class);
            intent.setAction(MainService.ACTION_START);
            intent.putExtra(MainService.EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, defaults.getPort()));
            intent.putExtra(MainService.EXTRA_PASSWORD, prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, defaults.getPassword()));
            intent.putExtra(MainService.EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, defaults.getFileTransfer()));
            intent.putExtra(MainService.EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, defaults.getViewOnly()));
            intent.putExtra(MainService.EXTRA_SCALING, prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, defaults.getScaling()));
            intent.putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, defaults.getAccessKey()));

            long delayMillis =  1000L * prefs.getInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, defaults.getStartOnBootDelay());
            if(delayMillis > 0) {
                Log.i(TAG, "onReceive: configured to start delayed by " + delayMillis/1000 + "s");
                AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                PendingIntent pendingIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pendingIntent = PendingIntent.getForegroundService(context.getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
                } else {
                    pendingIntent = PendingIntent.getService(context.getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
                }
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMillis, pendingIntent);
            } else {
                Log.i(TAG, "onReceive: configured to start immediately");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.getApplicationContext().startForegroundService(intent);
                } else {
                    context.getApplicationContext().startService(intent);
                }
            }
        } else {
            Log.i(TAG, "onReceive: configured NOT to start");
        }
        }
    }

}
