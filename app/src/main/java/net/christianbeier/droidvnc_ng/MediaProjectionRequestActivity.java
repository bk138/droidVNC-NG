/*
 * DroidVNC-NG activity for (automatically) requesting media projection
 * permission.
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Random;

public class MediaProjectionRequestActivity extends AppCompatActivity {

    private static final String TAG = "MPRequestActivity";
    static final String EXTRA_UPGRADING_FROM_NO_OR_FALLBACK_SCREEN_CAPTURE = "upgrading_from_no_or_fallback_screen_capture";
    static final String EXTRA_OMIT_FALLBACK_SCREEN_CAPTURE_DIALOG = "omit_fallback_screen_capture_dialog";
    private boolean mIsUpgradingFromNoOrFallbackScreenCapture;
    private int mRequestCode;
    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent");
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        // we need this info for our answer to MainService later
        mIsUpgradingFromNoOrFallbackScreenCapture = intent.getBooleanExtra(EXTRA_UPGRADING_FROM_NO_OR_FALLBACK_SCREEN_CAPTURE, false);

        MediaProjectionManager mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // API 34 and newer has possibility to capture one app only, but we have no way of restricting
        // input to one app only so when the user leaves the projected app, they can still input blindly,
        // potentially causing havoc. Thus, limit our projections to whole screen.
        Intent screenCaptureIntent;
        if (Build.VERSION.SDK_INT >= 34)
            screenCaptureIntent = mMediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        else
            screenCaptureIntent = mMediaProjectionManager.createScreenCaptureIntent();

        // this is a new request, code must be >= 0
        mRequestCode = new Random().nextInt(Integer.MAX_VALUE);

        if(!mIsUpgradingFromNoOrFallbackScreenCapture || intent.getBooleanExtra(EXTRA_OMIT_FALLBACK_SCREEN_CAPTURE_DIALOG, false)) {
            // ask for MediaProjection right away
            Log.i(TAG, "Requesting directly");
            // This initiates a prompt dialog for the user to confirm screen projection.
            startActivityForResult(
                    screenCaptureIntent,
                    mRequestCode);
        } else {
            // show user info dialog before asking
            try {
                mDialog.dismiss();
                Log.w(TAG, "Dismissed old dialog");
            } catch (Exception ignored) {
            }
            Log.i(TAG, "Showing dialog");
            mDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.mediaprojection_request_activity_fallback_screen_capture_title)
                    .setMessage(R.string.mediaprojection_request_activity_fallback_screen_capture_msg)
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        Log.i(TAG, "Requesting from dialog");
                        // This initiates a prompt dialog for the user to confirm screen projection.
                        startActivityForResult(
                                screenCaptureIntent,
                                mRequestCode);
                    })
                    .setNegativeButton(getString(R.string.no), (dialog, which) -> finish())
                    .create();
            mDialog.show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == mRequestCode) {
            if (resultCode != Activity.RESULT_OK)
                Log.i(TAG, "User cancelled");
            else
                Log.i(TAG, "User acknowledged");

            Intent intent = new Intent(this, MainService.class);
            intent.setAction(MainService.ACTION_HANDLE_MEDIA_PROJECTION_REQUEST_RESULT);
            intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(this).getAccessKey()));
            intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_REQUEST_RESULT_CODE, resultCode);
            intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_REQUEST_RESULT_DATA, data);
            intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_REQUEST_UPGRADING_FROM_NO_OR_FALLBACK_SCREEN_CAPTURE, mIsUpgradingFromNoOrFallbackScreenCapture);
            ContextCompat.startForegroundService(this, intent);
            finish();
        } else {
            Log.w(TAG, "Ignoring result of old request");
        }
    }

}