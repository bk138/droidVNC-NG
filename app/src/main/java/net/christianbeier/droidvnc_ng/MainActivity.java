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
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;

import io.reactivex.rxjava3.disposables.Disposable;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private Button mButtonToggle;
    private Button mButtonReverseVNC;
    private Button mButtonRepeaterVNC;
    private TextView mAddress;
    private boolean mIsMainServiceRunning;
    private Disposable mMainServiceStatusEventStreamConnection;
    private Defaults mDefaults;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDefaults = new Defaults(this);

        mButtonToggle = (Button) findViewById(R.id.toggle);
        mButtonToggle.setOnClickListener(view -> {

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

        });

        mAddress = findViewById(R.id.address);

        mButtonReverseVNC = (Button) findViewById(R.id.reverse_vnc);
        mButtonReverseVNC.setOnClickListener(view -> {

            final EditText inputText = new EditText(this);
            inputText.setInputType(InputType.TYPE_CLASS_TEXT);
            inputText.setHint(getString(R.string.main_activity_reverse_vnc_hint));
            String lastHost = prefs.getString(Constants.PREFS_KEY_REVERSE_VNC_LAST_HOST, null);
            if(lastHost != null) {
                inputText.setText(lastHost);
                // select all to make new input quicker
                inputText.setSelectAllOnFocus(true);
            }
            inputText.requestFocus();
            inputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout inputLayout = new LinearLayout(this);
            inputLayout.setPadding(
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    0
            );
            inputLayout.addView(inputText);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(inputLayout)
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                        // parse host and port parts
                        String[] parts = inputText.getText().toString().split("\\:");
                        String host = parts[0];
                        int port = mDefaults.getPortReverse();
                        if (parts.length > 1) {
                            try {
                                port = Integer.parseInt(parts[1]);
                            } catch(NumberFormatException unused) {
                                // stays at default reverse port
                            }
                        }
                        Log.d(TAG, "reverse vnc " + host + ":" + port);
                        if(MainService.connectReverse(host,port)) {
                            Toast.makeText(MainActivity.this, getString(R.string.main_activity_reverse_vnc_success, host, port), Toast.LENGTH_LONG).show();
                            SharedPreferences.Editor ed = prefs.edit();
                            ed.putString(Constants.PREFS_KEY_REVERSE_VNC_LAST_HOST, inputText.getText().toString());
                            ed.apply();
                        } else
                            Toast.makeText(MainActivity.this, getString(R.string.main_activity_reverse_vnc_fail, host, port), Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.cancel())
                    .create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
        });

        mButtonRepeaterVNC = (Button) findViewById(R.id.repeater_vnc);
        mButtonRepeaterVNC.setOnClickListener(view -> {

            final EditText hostInputText = new EditText(this);
            hostInputText.setInputType(InputType.TYPE_CLASS_TEXT);
            hostInputText.setHint(getString(R.string.main_activity_repeater_vnc_hint));
            String lastHost = prefs.getString(Constants.PREFS_KEY_REPEATER_VNC_LAST_HOST, "");
            hostInputText.setText(lastHost); //host:port
            hostInputText.requestFocus();
            hostInputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            final EditText idInputText = new EditText(this);
            idInputText.setInputType(InputType.TYPE_CLASS_NUMBER);
            idInputText.setHint(getString(R.string.main_activity_repeater_vnc_hint_id));
            String lastID = prefs.getString(Constants.PREFS_KEY_REPEATER_VNC_LAST_ID, "");
            idInputText.setText(lastID); //host:port
            idInputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout inputLayout = new LinearLayout(this);
            inputLayout.setPadding(
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    0
            );
            inputLayout.setOrientation(LinearLayout.VERTICAL);
            inputLayout.addView(hostInputText);
            inputLayout.addView(idInputText);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(inputLayout)
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                        // parse host and port parts
                        String[] parts = hostInputText.getText().toString().split("\\:");
                        String host = parts[0];
                        int port = mDefaults.getPortRepeater();
                        if (parts.length > 1) {
                            try {
                                port = Integer.parseInt(parts[1]);
                            } catch(NumberFormatException unused) {
                                // stays at default repeater port
                            }
                        }
                        // parse ID
                        String repeaterId = idInputText.getText().toString();
                        // sanity-check
                        if (host.isEmpty() || repeaterId.isEmpty()) {
                            Toast.makeText(MainActivity.this, getString(R.string.main_activity_repeater_vnc_input_missing), Toast.LENGTH_LONG).show();
                            return;
                        }
                        // done
                        Log.d(TAG, "repeater vnc " + host + ":" + port + ":" + repeaterId);
                        if(MainService.connectRepeater(host, port, /*"ID:" +*/ repeaterId)) {
                            Toast.makeText(MainActivity.this, getString(R.string.main_activity_repeater_vnc_success, host, port, repeaterId), Toast.LENGTH_LONG).show();
                            SharedPreferences.Editor ed = prefs.edit();
                            ed.putString(Constants.PREFS_KEY_REPEATER_VNC_LAST_HOST, host + ":" + port);
                            ed.putString(Constants.PREFS_KEY_REPEATER_VNC_LAST_ID, repeaterId);
                            ed.apply();
                        }
                        else
                            Toast.makeText(MainActivity.this, getString(R.string.main_activity_repeater_vnc_fail, host, port, repeaterId), Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.cancel())
                    .create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
        });


        final EditText port = findViewById(R.id.settings_port);
        port.setText(String.valueOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort())));
        port.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                try {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, Integer.parseInt(charSequence.toString()));
                    ed.apply();
                } catch(NumberFormatException e) {
                    // nop
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if(port.getText().length() == 0) {
                    // hint that default is set
                    port.setHint(String.valueOf(mDefaults.getPort()));
                    // and set default
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort());
                    ed.apply();
                }
            }
        });

        final EditText password = findViewById(R.id.settings_password);
        password.setText(prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, mDefaults.getPassword()));
        password.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString(Constants.PREFS_KEY_SETTINGS_PASSWORD, charSequence.toString());
                ed.apply();
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });

        final SwitchMaterial startOnBoot = findViewById(R.id.settings_start_on_boot);
        startOnBoot.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, true));
        startOnBoot.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, b);
            ed.apply();
        });

        Slider scaling = findViewById(R.id.settings_scaling);
        scaling.setValue(prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, mDefaults.getScaling())*100);
        scaling.setLabelFormatter(value -> Math.round(value) + " %");
        scaling.addOnChangeListener((slider, value, fromUser) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putFloat(Constants.PREFS_KEY_SETTINGS_SCALING, value/100);
            ed.apply();
        });

        final SwitchMaterial removePadding = findViewById(R.id.settings_remove_padding);
        removePadding.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_REMOVE_PADDING, mDefaults.getRemovePadding()));
        removePadding.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_REMOVE_PADDING, b);
            ed.apply();
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
                    mAddress.setText(getString(R.string.main_activity_address) + " " + sb);
                });

                // show outbound connection interface
                findViewById(R.id.outbound_text).setVisibility(View.VISIBLE);
                findViewById(R.id.outbound_buttons).setVisibility(View.VISIBLE);

                // indicate that changing these settings does not have an effect when the server is running
                findViewById(R.id.settings_port).setEnabled(false);
                findViewById(R.id.settings_password).setEnabled(false);
                findViewById(R.id.settings_scaling).setEnabled(false);

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

                // hide outbound connection interface
                findViewById(R.id.outbound_text).setVisibility(View.GONE);
                findViewById(R.id.outbound_buttons).setVisibility(View.GONE);

                // indicate that changing these settings does have an effect when the server is stopped
                findViewById(R.id.settings_port).setEnabled(true);
                findViewById(R.id.settings_password).setEnabled(true);
                findViewById(R.id.settings_scaling).setEnabled(true);

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