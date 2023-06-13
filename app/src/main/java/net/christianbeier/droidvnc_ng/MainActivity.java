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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
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
import java.util.UUID;

import io.reactivex.rxjava3.disposables.Disposable;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_KEY_REVERSE_VNC_LAST_HOST = "reverse_vnc_last_host" ;
    private static final String PREFS_KEY_REPEATER_VNC_LAST_HOST = "repeater_vnc_last_host" ;
    private static final String PREFS_KEY_REPEATER_VNC_LAST_ID = "repeater_vnc_last_id" ;

    private Button mButtonToggle;
    private TextView mAddress;
    private boolean mIsMainServiceRunning;
    private Disposable mMainServiceStatusEventStreamConnection;
    private BroadcastReceiver mMainServiceBroadcastReceiver;
    private String mLastMainServiceRequestId;
    private String mLastReverseHost;
    private int mLastReversePort;
    private String mLastRepeaterHost;
    private int mLastRepeaterPort;
    private String mLastRepeaterId;
    private Defaults mDefaults;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDefaults = new Defaults(this);

        mButtonToggle = findViewById(R.id.toggle);
        mButtonToggle.setOnClickListener(view -> {

            Intent intent = new Intent(MainActivity.this, MainService.class);
            intent.putExtra(MainService.EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort()));
            intent.putExtra(MainService.EXTRA_PASSWORD, prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, mDefaults.getPassword()));
            intent.putExtra(MainService.EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, mDefaults.getFileTranfer()));
            intent.putExtra(MainService.EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()));
            intent.putExtra(MainService.EXTRA_SCALING, prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, mDefaults.getScaling()));
            intent.putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey()));
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

        Button reverseVNC = findViewById(R.id.reverse_vnc);
        reverseVNC.setOnClickListener(view -> {

            final EditText inputText = new EditText(this);
            inputText.setInputType(InputType.TYPE_CLASS_TEXT);
            inputText.setHint(getString(R.string.main_activity_reverse_vnc_hint));
            String lastHost = prefs.getString(PREFS_KEY_REVERSE_VNC_LAST_HOST, null);
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
                        mLastMainServiceRequestId = UUID.randomUUID().toString();
                        mLastReverseHost = host;
                        mLastReversePort = port;
                        Intent request = new Intent(MainActivity.this, MainService.class);
                        request.putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey()));
                        request.setAction(MainService.ACTION_CONNECT_REVERSE);
                        request.putExtra(MainService.EXTRA_HOST, host);
                        request.putExtra(MainService.EXTRA_PORT, port);
                        request.putExtra(MainService.EXTRA_REQUEST_ID, mLastMainServiceRequestId);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(request);
                        } else {
                            startService(request);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.cancel())
                    .create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
        });

        Button repeaterVNC = findViewById(R.id.repeater_vnc);
        repeaterVNC.setOnClickListener(view -> {

            final EditText hostInputText = new EditText(this);
            hostInputText.setInputType(InputType.TYPE_CLASS_TEXT);
            hostInputText.setHint(getString(R.string.main_activity_repeater_vnc_hint));
            String lastHost = prefs.getString(PREFS_KEY_REPEATER_VNC_LAST_HOST, "");
            hostInputText.setText(lastHost); //host:port
            hostInputText.requestFocus();
            hostInputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            final EditText idInputText = new EditText(this);
            idInputText.setInputType(InputType.TYPE_CLASS_NUMBER);
            idInputText.setHint(getString(R.string.main_activity_repeater_vnc_hint_id));
            String lastID = prefs.getString(PREFS_KEY_REPEATER_VNC_LAST_ID, "");
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
                        mLastMainServiceRequestId = UUID.randomUUID().toString();
                        mLastRepeaterHost = host;
                        mLastRepeaterPort = port;
                        mLastRepeaterId = repeaterId;
                        Intent request = new Intent(MainActivity.this, MainService.class);
                        request.putExtra(MainService.EXTRA_ACCESS_KEY, prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey()));
                        request.setAction(MainService.ACTION_CONNECT_REPEATER);
                        request.putExtra(MainService.EXTRA_HOST, host);
                        request.putExtra(MainService.EXTRA_PORT, port);
                        request.putExtra(MainService.EXTRA_REPEATER_ID, repeaterId);
                        request.putExtra(MainService.EXTRA_REQUEST_ID, mLastMainServiceRequestId);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(request);
                        } else {
                            startService(request);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.cancel())
                    .create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
        });


        final EditText port = findViewById(R.id.settings_port);
        if(prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort()) < 0) {
            port.setHint(R.string.main_activity_settings_port_not_listening);
        } else {
            port.setText(String.valueOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort())));
        }
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
                    // hint that not listening
                    port.setHint(R.string.main_activity_settings_port_not_listening);
                    // and set default
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_PORT, -1);
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
                // only save new value if it differs from the default and was not saved before
                if(!(prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, null) == null && charSequence.toString().equals(mDefaults.getPassword()))) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putString(Constants.PREFS_KEY_SETTINGS_PASSWORD, charSequence.toString());
                    ed.apply();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
        // show/hide password on focus change. NB that this triggers onTextChanged above, so we have
        // to take special precautions there.
        password.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                password.setTransformationMethod(new SingleLineTransformationMethod());
            } else {
                password.setTransformationMethod(new PasswordTransformationMethod());
            }
            // move cursor to end of text
            password.setSelection(password.getText().length());
        });

        final EditText accessKey = findViewById(R.id.settings_access_key);
        accessKey.setText(prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey()));
        accessKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                // only save new value if it differs from the default and was not saved before
                if(!(prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, null) == null && charSequence.toString().equals(mDefaults.getAccessKey()))) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, charSequence.toString());
                    ed.apply();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        // show/hide access key on focus change. NB that this triggers onTextChanged above, so we have
        // to take special precautions there.
        accessKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                accessKey.setTransformationMethod(new SingleLineTransformationMethod());
            } else {
                accessKey.setTransformationMethod(new PasswordTransformationMethod());
            }
            // move cursor to end of text
            accessKey.setSelection(accessKey.getText().length());
        });

        final SwitchMaterial startOnBoot = findViewById(R.id.settings_start_on_boot);
        startOnBoot.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, true));
        startOnBoot.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, b);
            ed.apply();
        });

        final SwitchMaterial fileTransfer = findViewById(R.id.settings_file_transfer);
        fileTransfer.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, mDefaults.getFileTranfer()));
        fileTransfer.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, b);
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

        final SwitchMaterial viewOnly = findViewById(R.id.settings_view_only);
        viewOnly.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()));
        viewOnly.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, b);
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
                mAddress.post(() -> mAddress.setText(getString(R.string.main_activity_address) + " " + sb));

                // show outbound connection interface
                findViewById(R.id.outbound_text).setVisibility(View.VISIBLE);
                findViewById(R.id.outbound_buttons).setVisibility(View.VISIBLE);

                // indicate that changing these settings does not have an effect when the server is running
                findViewById(R.id.settings_port).setEnabled(false);
                findViewById(R.id.settings_password).setEnabled(false);
                findViewById(R.id.settings_access_key).setEnabled(false);
                findViewById(R.id.settings_scaling).setEnabled(false);
                findViewById(R.id.settings_view_only).setEnabled(false);
                findViewById(R.id.settings_file_transfer).setEnabled(false);

                mIsMainServiceRunning = true;
            }

            if(event == MainService.StatusEvent.STOPPED) {
                Log.d(TAG, "got MainService stopped event");
                mButtonToggle.post(() -> {
                    mButtonToggle.setText(R.string.start);
                    mButtonToggle.setEnabled(true);
                });
                mAddress.post(() -> mAddress.setText(""));

                // hide outbound connection interface
                findViewById(R.id.outbound_text).setVisibility(View.GONE);
                findViewById(R.id.outbound_buttons).setVisibility(View.GONE);

                // indicate that changing these settings does have an effect when the server is stopped
                findViewById(R.id.settings_port).setEnabled(true);
                findViewById(R.id.settings_password).setEnabled(true);
                findViewById(R.id.settings_access_key).setEnabled(true);
                findViewById(R.id.settings_scaling).setEnabled(true);
                findViewById(R.id.settings_view_only).setEnabled(true);
                findViewById(R.id.settings_file_transfer).setEnabled(true);


                mIsMainServiceRunning = false;
            }

        });

        mMainServiceBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MainService.ACTION_CONNECT_REVERSE.equals(intent.getAction())
                        && mLastMainServiceRequestId != null
                        && mLastMainServiceRequestId.equals(intent.getStringExtra(MainService.EXTRA_REQUEST_ID))) {
                    // was a CONNECT_REVERSE requested by us
                    if (intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)) {
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_reverse_vnc_success,
                                                mLastReverseHost,
                                                mLastReversePort),
                                        Toast.LENGTH_LONG)
                                .show();
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putString(PREFS_KEY_REVERSE_VNC_LAST_HOST,
                                mLastReverseHost + ":" + mLastReversePort);
                        ed.apply();
                    } else
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_reverse_vnc_fail,
                                                mLastReverseHost,
                                                mLastReversePort),
                                        Toast.LENGTH_LONG)
                                .show();

                    // reset this
                    mLastMainServiceRequestId = null;
                }

                if (MainService.ACTION_CONNECT_REPEATER.equals(intent.getAction())
                        && mLastMainServiceRequestId != null
                        && mLastMainServiceRequestId.equals(intent.getStringExtra(MainService.EXTRA_REQUEST_ID))) {
                    // was a CONNECT_REPEATER requested by us
                    if (intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)) {
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_repeater_vnc_success,
                                                mLastRepeaterHost,
                                                mLastRepeaterPort,
                                                mLastRepeaterId),
                                        Toast.LENGTH_LONG)
                                .show();
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putString(PREFS_KEY_REPEATER_VNC_LAST_HOST,
                                mLastRepeaterHost + ":" + mLastRepeaterPort);
                        ed.putString(PREFS_KEY_REPEATER_VNC_LAST_ID,
                                mLastRepeaterId);
                        ed.apply();
                    }
                    else
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_repeater_vnc_fail,
                                                mLastRepeaterHost,
                                                mLastRepeaterPort,
                                                mLastRepeaterId),
                                        Toast.LENGTH_LONG)
                                .show();

                    // reset this
                    mLastMainServiceRequestId = null;
                }

            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainService.ACTION_CONNECT_REVERSE);
        filter.addAction(MainService.ACTION_CONNECT_REPEATER);
        registerReceiver(mMainServiceBroadcastReceiver, filter);
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onResume() {
        super.onResume();

        /*
            Update Input permission display.
         */
        TextView inputStatus = findViewById(R.id.permission_status_input);
        if(InputService.isConnected()) {
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
        unregisterReceiver(mMainServiceBroadcastReceiver);
    }


}