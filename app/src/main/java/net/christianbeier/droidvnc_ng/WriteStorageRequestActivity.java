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
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

public class WriteStorageRequestActivity extends AppCompatActivity {

    private static final String TAG = "WriteStorageRequestActivity";
    private static final int REQUEST_WRITE_STORAGE = 44;
    private static final String PREFS_KEY_PERMISSION_ASKED_BEFORE = "write_storage_permission_asked_before";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 and newer
            if(!Environment.isExternalStorageManager()) {
                Log.i(TAG, "Has no permission! Ask!");
                new AlertDialog.Builder(this)
                        .setCancelable(false)
                        .setTitle(R.string.write_storage_title)
                        .setMessage(R.string.write_storage_msg)
                        .setPositiveButton(R.string.yes, (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                            startActivityForResult(intent, REQUEST_WRITE_STORAGE);
                        })
                        .setNegativeButton(getString(R.string.no), (dialog, which) -> postResultAndFinish(false))
                        .show();
            } else {
                Log.i(TAG, "Permission already given!");
                postResultAndFinish(true);
            }
        } else {
            // Android 10 and older
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Has no permission! Ask!");
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

                /*
                   As per as per https://stackoverflow.com/a/34612503/361413 shouldShowRequestPermissionRationale()
                   returns false also if user was never asked, so keep track of that with a shared preference. Ouch.
                */
                if (!prefs.getBoolean(PREFS_KEY_PERMISSION_ASKED_BEFORE, false) || shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    new AlertDialog.Builder(this)
                            .setCancelable(false)
                            .setTitle(R.string.write_storage_title)
                            .setMessage(R.string.write_storage_msg)
                            .setPositiveButton(R.string.yes, (dialog, which) -> {
                                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
                                SharedPreferences.Editor ed = prefs.edit();
                                ed.putBoolean(PREFS_KEY_PERMISSION_ASKED_BEFORE, true);
                                ed.apply();
                            })
                            .setNegativeButton(getString(R.string.no), (dialog, which) -> postResultAndFinish(false))
                            .show();
                } else {
                    postResultAndFinish(false);
                }
            } else {
                Log.i(TAG, "Permission already given!");
                postResultAndFinish(true);
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {

        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                postResultAndFinish(true);
            } else {
                postResultAndFinish(false);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == REQUEST_WRITE_STORAGE) {
            postResultAndFinish(Environment.isExternalStorageManager());
        }
    }

    private void postResultAndFinish(boolean isPermissionGiven) {

        if (isPermissionGiven)
            Log.i(TAG, "permission granted");
        else
            Log.i(TAG, "permission denied");

        Intent intent = new Intent(this, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_WRITE_STORAGE_RESULT);
        intent.putExtra(MainService.EXTRA_WRITE_STORAGE_RESULT, isPermissionGiven);
        startService(intent);
        finish();
    }

}