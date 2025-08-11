/*
 * DroidVNC-NG activity for requesting input/a11y permissions.
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

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class InputRequestActivity extends AppCompatActivity {

    private static final String TAG = "InputRequestActivity";
    private static final int REQUEST_INPUT = 43;
    private static final String EXTRA_INPUT_REQUESTED = "input_requested";
    private static final String EXTRA_START_ON_BOOT_REQUESTED = "start_on_boot_requested";
    private static final String EXTRA_SKIP_POST = "skip_post";
    private boolean mSkipPost;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate");

        mSkipPost = getIntent().getBooleanExtra(EXTRA_SKIP_POST, false);

        /*
            Decide on message to be shown.
         */
        boolean inputRequested = getIntent().getBooleanExtra(EXTRA_INPUT_REQUESTED, false);
        boolean startOnBootRequested = getIntent().getBooleanExtra(EXTRA_START_ON_BOOT_REQUESTED, false);
        int msg;
        if (inputRequested && startOnBootRequested) {
            // input and boot requested
            msg = R.string.input_a11y_msg_input_and_boot;
        } else if (inputRequested) {
            // input requested
            msg = R.string.input_a11y_msg_input;
        } else {
            // boot requested
            msg = R.string.input_a11y_msg_boot;
        }

        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.input_a11y_title)
                .setMessage(msg)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);

                    // highlight entry on some devices, see https://stackoverflow.com/a/63214655/361413
                    final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";
                    final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
                    Bundle bundle = new Bundle();
                    String showArgs = getPackageName() + "/" + InputService.class.getName();
                    bundle.putString(EXTRA_FRAGMENT_ARG_KEY, showArgs);
                    intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, showArgs);
                    intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle);

                    if (intent.resolveActivity(getPackageManager()) != null && !intent.resolveActivity(getPackageManager()).toString().contains("Stub"))
                        startActivityForResult(intent, REQUEST_INPUT);
                    else
                        new AlertDialog.Builder(InputRequestActivity.this)
                                .setMessage(R.string.input_a11y_act_not_found_msg)
                                .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                                    Intent generalSettingsIntent = new Intent(Settings.ACTION_SETTINGS);
                                    try {
                                        startActivityForResult(generalSettingsIntent, REQUEST_INPUT);
                                    } catch (ActivityNotFoundException ignored) {
                                        // This should not happen, but there were crashes reported from flaky devices
                                        // so in this case do nothing instead of crashing.
                                    }
                                })
                                .show();
                })
                .setNegativeButton(getString(R.string.no), (dialog, which) -> {
                    if (!mSkipPost) {
                        postResult(this, false);
                    }
                    finish();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INPUT) {
            Log.d(TAG, "onActivityResult");
            if (!mSkipPost) {
                postResult(this, InputService.isConnected());
            }
            finish();
        }
    }

    private static void postResult(Context context, boolean isA11yEnabled) {
        Log.d(TAG, "postResult: a11y " + (isA11yEnabled ? "enabled" : "disabled"));

        Intent intent = new Intent(context, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_INPUT_RESULT);
        intent.putExtra(MainService.EXTRA_INPUT_RESULT, isA11yEnabled);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(context).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(context).getAccessKey()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void requestIfNeededAndPostResult(Context context, boolean inputRequested, boolean startOnBootRequested, boolean skipPost) {

        Log.d(TAG, "requestIfNeededAndPostResult: input requested: " + inputRequested + " start on boot requested: " + startOnBootRequested);

        /*
            If neither input nor start-on-boot are requested, bail out early without bothering the user.
         */
        if(!inputRequested && !startOnBootRequested) {
            if (!skipPost) {
                postResult(context, false);
            }
            return;
        }

        if (!InputService.isConnected()) {
            Intent inputRequestIntent = new Intent(context, InputRequestActivity.class);
            inputRequestIntent.putExtra(EXTRA_INPUT_REQUESTED, inputRequested);
            inputRequestIntent.putExtra(EXTRA_START_ON_BOOT_REQUESTED, startOnBootRequested);
            inputRequestIntent.putExtra(EXTRA_SKIP_POST, skipPost);
            inputRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(inputRequestIntent);
        } else {
            if (!skipPost) {
                postResult(context, true);
            }
        }
    }

}