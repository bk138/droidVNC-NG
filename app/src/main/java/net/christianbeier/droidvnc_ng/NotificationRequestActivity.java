
package net.christianbeier.droidvnc_ng;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class NotificationRequestActivity extends AppCompatActivity {

    private static final String TAG = "NotificationRequestActivity";
    private static final int REQUEST_POST_NOTIFICATION = 45;
    private static final String PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE = "post_notification_permission_asked_before";


    @RequiresApi(api = 33)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate");

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!prefs.getBoolean(PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE, false)) {
            new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.notification_title)
                    .setMessage(R.string.notification_msg)
                    .setPositiveButton(R.string.yes, (dialog, which) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATION))
                    .setNegativeButton(getString(R.string.no), (dialog, which) -> {
                        postResult(this, false);
                        finish();
                    })
                    .show();
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE, true);
            ed.apply();
        } else {
            postResult(this, false);
            finish();
        }

    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_POST_NOTIFICATION) {
            postResult(this, grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            finish();
        }
    }

    private static void postResult(Context context, boolean isPermissionGiven) {
        Log.i(TAG, "postResult: permission " + (isPermissionGiven ? "granted" : "denied"));

        Intent intent = new Intent(context, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_NOTIFICATION_RESULT);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(context).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(context).getAccessKey()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Requests the permission if needed, otherwise posts a positive result back to MainService immediately.
     * @param context The calling context
     */
    public static void requestIfNeededAndPostResult(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            Log.i(TAG, "requestIfNeededAndPostResult: no permission needed on API level < 33");
            postResult(context, true);
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "requestIfNeededAndPostResult: Has no permission! Ask!");
            Intent notificationRequestIntent = new Intent(context, NotificationRequestActivity.class);
            notificationRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(notificationRequestIntent);
        } else {
            Log.i(TAG, "requestIfNeededAndPostResult: Permission already given!");
            postResult(context, true);
        }
    }
}