package net.christianbeier.droidvnc_ng;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class ControlService extends Service {

	private static final String TAG = "ControlService";
	private static final int NOTIFICATION_ID = 12;
	final static String ACTION_START = "start";
	final static String ACTION_STOP = "stop";
	final static String ACTION_START_REPEATER_CONNECTION = "start_repeater_connection";
	final static String ACTION_START_REVERSE_CONNECTION = "start_reverse_connection";

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.i(TAG, "onCreate");

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            /*
                Create notification channel
             */
			NotificationChannel serviceChannel = new NotificationChannel(
					getPackageName(),
					"Foreground Service Control Channel",
					NotificationManager.IMPORTANCE_DEFAULT
			);
			NotificationManager manager = getSystemService(NotificationManager.class);
			manager.createNotificationChannel(serviceChannel);

            /*
                Create the notification
             */
			Intent notificationIntent = new Intent(this, MainActivity.class);

			PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
					notificationIntent, 0);

			Notification notification = new NotificationCompat.Builder(this, getPackageName())
					.setSmallIcon(R.mipmap.ic_launcher)
					.setContentTitle(getString(R.string.app_name))
					.setContentText("Remote control active...")
					.setContentIntent(pendingIntent).build();

			startForeground(NOTIFICATION_ID, notification);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if(ACTION_START.equals(intent.getAction())) {
			Log.d(TAG, "onStartCommand: start.");

			int port = intent.getIntExtra("PORT", Constants.DEFAULT_PORT);
			String password = intent.getStringExtra("PASSWORD");

			Context context = getApplicationContext();
			SetVncServerSettings(context, port, password);

			Log.d(TAG, "onStartCommand: starting VNC. Port: " + port + " Pw length: " + password.length());
			Intent startIntent = new Intent(context, MainService.class);
			startIntent.setAction(MainService.ACTION_START);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				context.startForegroundService(startIntent);
			} else {
				context.startService(startIntent);
			}
		}

		if(ACTION_STOP.equals(intent.getAction())) {
			Log.d(TAG, "onStartCommand: stop");

			Context context = getApplicationContext();
			Intent stopIntent = new Intent(context, MainService.class);
		}

		if (ACTION_START_REPEATER_CONNECTION.equals(intent.getAction())) {
			Log.d(TAG, "onStartCommand: repeater connection required");

			String host = intent.getStringExtra("HOST");
			int port = intent.getIntExtra("PORT", Constants.DEFAULT_PORT_REPEATER);
			String id = intent.getStringExtra("REPEATER_ID");
			MainService.connectRepeater(host, port, id);
		}

		if (ACTION_START_REVERSE_CONNECTION.equals(intent.getAction())) {
			Log.d(TAG, "onStartCommand: reverse connection required");

			int port = intent.getIntExtra("PORT", Constants.DEFAULT_PORT_REVERSE);
			String host = intent.getStringExtra("HOST");
			MainService.connectReverse(host, port);
		}

		return START_NOT_STICKY;
	}

	private void SetVncServerSettings(Context context, int port, String password) {
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, port);
		ed.putString(Constants.PREFS_KEY_SETTINGS_PASSWORD, password);
		ed.apply();
	}
}
