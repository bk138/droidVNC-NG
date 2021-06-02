/*
 * DroidVNC-NG main activity.
 *
 * Author: Christian Beier <info@christianbeier.net
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
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

import io.reactivex.rxjava3.disposables.Disposable;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private Button mButtonToggle;
    private TextView mAddress;
    private boolean mIsMainServiceRunning;
    private Disposable mMainServiceStatusEventStreamConnection;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButtonToggle = (Button) findViewById(R.id.toggle);
        mButtonToggle.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onClick(View view) {

                Intent intent = new Intent(MainActivity.this, MainService.class);
                if(mIsMainServiceRunning) {
                    intent.setAction(MainService.ACTION_STOP);
                }
                else {
                    intent.setAction(MainService.ACTION_START);
                }
                mButtonToggle.setEnabled(false);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }

            }
        });

        mAddress = findViewById(R.id.address);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        final EditText port = findViewById(R.id.settings_port);
        port.setText(String.valueOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, Constants.DEFAULT_PORT)));
        port.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                try {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, Integer.parseInt(charSequence.toString()));
                    ed.commit();
                } catch(NumberFormatException e) {
                    // nop
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void afterTextChanged(Editable editable) {
                if(port.getText().length() == 0) {
                    // hint that default is set
                    port.setHint(String.valueOf(Constants.DEFAULT_PORT));
                    // and set default
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, Constants.DEFAULT_PORT);
                    ed.commit();
                }
            }
        });

        final EditText password = findViewById(R.id.settings_password);
        password.setText(prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, ""));
        password.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString(Constants.PREFS_KEY_SETTINGS_PASSWORD, charSequence.toString());
                ed.commit();
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });

        final Switch startOnBoot = findViewById(R.id.settings_start_on_boot);
        startOnBoot.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, true));
        startOnBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @SuppressLint("ApplySharedPref")
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, b);
                ed.commit();
            }
        });

        TextView about = findViewById(R.id.about);
        about.setText(getString(R.string.main_activity_about, BuildConfig.VERSION_NAME));

        mMainServiceStatusEventStreamConnection = MainService.getStatusEventStream().subscribe(event -> {

            if(event == MainService.StatusEvent.STARTED) {
                Log.d(TAG, "got MainService started event");
                mButtonToggle.post(() -> {
                    mButtonToggle.setText(R.string.stop);
                    mButtonToggle.setEnabled(true);
                });

                // uhh there must be a nice functional way for this
                ArrayList<String> hostsAndPorts = MainService.getIPv4sAndPorts();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < hostsAndPorts.size(); ++i) {
                    sb.append(hostsAndPorts.get(i));
                    if (i != hostsAndPorts.size() - 1)
                        sb.append(" ").append(getString(R.string.or)).append(" ");
                }
                mAddress.post(() -> {
                    mAddress.setText(getString(R.string.main_activity_connect_to) + " " + sb);
                });

                mIsMainServiceRunning = true;
            }

            if(event == MainService.StatusEvent.STOPPED) {
                Log.d(TAG, "got MainService stopped event");
                mButtonToggle.post(() -> {
                    mButtonToggle.setText(R.string.start);
                    mButtonToggle.setEnabled(true);
                });
                mAddress.post(() -> {
                    mAddress.setText("");
                });

                mIsMainServiceRunning = false;
            }

        });
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onResume() {
        super.onResume();

        /*
            Update Input permission display.
         */
        TextView inputStatus = findViewById(R.id.permission_status_input);
        if(InputService.isEnabled()) {
            inputStatus.setText(R.string.main_activity_granted);
            inputStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            inputStatus.setText(R.string.main_activity_denied);
            inputStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }


        /*
            Update File Access permission display.
         */
        TextView fileAccessStatus = findViewById(R.id.permission_status_file_access);
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            fileAccessStatus.setText(R.string.main_activity_granted);
            fileAccessStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            fileAccessStatus.setText(R.string.main_activity_denied);
            fileAccessStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }


        /*
           Update Screen Capturing permission display.
        */
        TextView screenCapturingStatus = findViewById(R.id.permission_status_screen_capturing);
        if(MainService.isMediaProjectionEnabled() == 1) {
            screenCapturingStatus.setText(R.string.main_activity_granted);
            screenCapturingStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        }
        if(MainService.isMediaProjectionEnabled() == 0) {
            screenCapturingStatus.setText(R.string.main_activity_denied);
            screenCapturingStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }
        if(MainService.isMediaProjectionEnabled() == -1) {
            screenCapturingStatus.setText(R.string.main_activity_unknown);
            screenCapturingStatus.setTextColor(getColor(android.R.color.darker_gray));
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mMainServiceStatusEventStreamConnection.dispose();
    }


}