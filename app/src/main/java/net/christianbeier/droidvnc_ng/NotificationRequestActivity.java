
package net.christianbeier.droidvnc_ng;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class NotificationRequestActivity extends AppCompatActivity {

    private static final String TAG = "NotificationRequestActivity";
    private static final int REQUEST_POST_NOTIFICATION = 45;
    private static final String PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE = "post_notification_permission_asked_before";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(Build.VERSION.SDK_INT < 33) {
            // no permission needed on API level < 33
            postResultAndFinish(true);
            return;
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Has no permission! Ask!");

            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

            if (!prefs.getBoolean(PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE, false)) {
                new AlertDialog.Builder(this)
                        .setCancelable(false)
                        .setTitle(R.string.notification_title)
                        .setMessage(R.string.notification_msg)
                        .setPositiveButton(R.string.yes, (dialog, which) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATION))
                        .setNegativeButton(getString(R.string.no), (dialog, which) -> postResultAndFinish(false))
                        .show();
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean(PREFS_KEY_POST_NOTIFICATION_PERMISSION_ASKED_BEFORE, true);
                ed.apply();
            } else {
                postResultAndFinish(false);
            }
        } else {
            Log.i(TAG, "Permission already given!");
            postResultAndFinish(true);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_POST_NOTIFICATION) {
            postResultAndFinish(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    private void postResultAndFinish(boolean isPermissionGiven) {

        if (isPermissionGiven)
            Log.i(TAG, "permission granted");
        else
            Log.i(TAG, "permission denied");

        Intent intent = new Intent(this, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_NOTIFICATION_RESULT);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(this).getAccessKey()));
        startService(intent);
        finish();
    }

}