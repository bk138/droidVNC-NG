/*
 * DroidVNC-NG activity for requesting external storage read/write permissions.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2020 Kitchen Armor.
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

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class WriteStorageRequestActivity extends AppCompatActivity {

    private static final String TAG = "WriteStorageRequestActivity";
    private static final int REQUEST_WRITE_STORAGE = 44;
    private static final String PREFS_KEY_PERMISSION_ASKED_BEFORE = "write_storage_permission_asked_before";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate");

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

            /*
                As per as per https://stackoverflow.com/a/34612503/361413 shouldShowRequestPermissionRationale()
                returns false also if user was never asked, so keep track of that with a shared preference. Ouch.
             */
        if (!prefs.getBoolean(PREFS_KEY_PERMISSION_ASKED_BEFORE, false) || shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.write_storage_title)
                    .setMessage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? R.string.write_storage_msg_android_11 : R.string.write_storage_msg)
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putBoolean(PREFS_KEY_PERMISSION_ASKED_BEFORE, true);
                        ed.apply();
                    })
                    .setNegativeButton(getString(R.string.no), (dialog, which) -> {
                        postResult(this, false);
                        finish();
                    })
                    .show();
        } else {
            postResult(this, false);
            finish();
        }

    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE) {
            postResult(this,grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            finish();
        }
    }

    private static void postResult(Context context, boolean isPermissionGiven) {
        Log.i(TAG, "postResult: permission " + (isPermissionGiven ? "granted" : "denied"));

        Intent intent = new Intent(context, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_WRITE_STORAGE_RESULT);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(context).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(context).getAccessKey()));
        intent.putExtra(MainService.EXTRA_WRITE_STORAGE_RESULT, isPermissionGiven);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Requests the permission if needed, otherwise posts a positive result back to MainService immediately.
     * @param context The calling context
     * @param skipRequest True if the request should be skipped and a negative result posted back to MainService.
     */
    public static void requestIfNeededAndPostResult(Context context, boolean skipRequest) {
        if (skipRequest) {
            Log.i(TAG, "requestIfNeededAndPostResult: skipping request");
            postResult(context, false);
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "requestIfNeededAndPostResult: Has no permission! Ask!");
            Intent writeStorageRequestIntent = new Intent(context, WriteStorageRequestActivity.class);
            writeStorageRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(writeStorageRequestIntent);
        } else {
            Log.i(TAG, "requestIfNeededAndPostResult: Permission already given!");
            postResult(context, true);
        }
    }

}