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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;


import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.IOException;


import net.christianbeier.droidvnc_ng.ifaceutils.NetworkInterfaceTester;



public class OnBootReceiver extends BroadcastReceiver {
    private static final String TAG = "OnBootReceiver";

    // Stuff for the notification, in case the listenIf is not available
    private static NotificationManager notifManager;
    private static String NTCHN_NAME = "NTCHN onBootReceiver";
    private static int NOTIFICATION_ID = 34;
    private static NotificationChannel notifChannel;




    public void onReceive(Context context, Intent arg1) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(arg1.getAction())) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            Defaults defaults = new Defaults(context);
            if (prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, defaults.getStartOnBoot())) {

                // autostart needs InputService on Android 11 and newer, both for the activity starts from MainService
                // (could be reworked) but most importantly for fallback screen capture
                if (Build.VERSION.SDK_INT >= 30 && !InputService.isConnected()) {
                    Log.w(TAG, "onReceive: configured to start, but on Android 11+ and InputService not set up, bailing out");
                    return;
                }

                // Check for availability of listenIf
                String listenIf = prefs.getString(Constants.PREFS_KEY_SETTINGS_LISTEN_INTERFACE, defaults.getListenInterface());
                NetworkInterfaceTester nit = NetworkInterfaceTester.getInstance(context);
                if (!nit.isIfEnabled(listenIf)) {
                    Log.w(TAG, "onReceive: interface \"" + listenIf + "\" not available, sending a notification");
                    this.sendNotification(context, "Failed to connect to interface \"%s\", is it down perhaps?", listenIf);
                    return;
                }

                Intent intent = new Intent(context.getApplicationContext(), MainService.class);
                intent.setAction(MainService.ACTION_START);
                intent.putExtra(MainService.EXTRA_LISTEN_INTERFACE, listenIf);
                intent.putExtra(MainService.EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, defaults.getPort()));
                intent.putExtra(MainService.EXTRA_PASSWORD, prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, defaults.getPassword()));
                intent.putExtra(MainService.EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, defaults.getFileTransfer()));
                intent.putExtra(MainService.EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, defaults.getViewOnly()));
                intent.putExtra(MainService.EXTRA_SCALING, prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, defaults.getScaling()));
                intent.putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, defaults.getAccessKey()));
                MainService.addFallbackScreenCaptureIfNotAppOp(context.getApplicationContext(), intent);

                long delayMillis = 1000L * prefs.getInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, defaults.getStartOnBootDelay());
                if (delayMillis > 0) {
                    Log.i(TAG, "onReceive: configured to start delayed by " + delayMillis / 1000 + "s");
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
                    ContextCompat.startForegroundService(context.getApplicationContext(), intent);
                }
            } else {
                Log.i(TAG, "onReceive: configured NOT to start");
            }
        }
    }



    /*
     * Stuff for notifications
     */
    private void sendNotification(Context context, String message, String ...messageItems) {
        this.setupNotificationChannel(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, context.getPackageName())
            .setSmallIcon(R.drawable.ic_notification_normal)
            .setContentTitle(context.getResources().getString(R.string.app_name))
            .setContentText(
                 String.format(message, messageItems))
            .setSilent(true)
            .setOngoing(false);

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        OnBootReceiver.notifManager.notify(NOTIFICATION_ID, builder.build());
    }


    private void setupNotificationChannel(Context context) {
        if (OnBootReceiver.notifManager == null) {
            OnBootReceiver.notifManager = context.getSystemService(NotificationManager.class);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (OnBootReceiver.notifChannel == null) {
                OnBootReceiver.notifChannel = new NotificationChannel(
                    context.getPackageName(),
                    NTCHN_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                );

                OnBootReceiver.notifManager.createNotificationChannel(OnBootReceiver.notifChannel);
            }
        }
    }
}
