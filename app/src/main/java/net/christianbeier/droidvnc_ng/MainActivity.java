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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.text.BidiFormatter;
import androidx.preference.PreferenceManager;

import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_KEY_REVERSE_VNC_LAST_HOST = "reverse_vnc_last_host" ;
    private static final String PREFS_KEY_REPEATER_VNC_LAST_HOST = "repeater_vnc_last_host" ;
    private static final String PREFS_KEY_REPEATER_VNC_LAST_ID = "repeater_vnc_last_id" ;

    private Button mButtonToggle;
    private TextView mAddress;
    private boolean mIsMainServiceRunning;
    private BroadcastReceiver mMainServiceBroadcastReceiver;
    private AlertDialog mOutgoingConnectionWaitDialog;
    private String mLastMainServiceRequestId;
    private String mLastReverseHost;
    private int mLastReversePort;
    private String mLastRepeaterHost;
    private int mLastRepeaterPort;
    private String mLastRepeaterId;
    private Defaults mDefaults;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private BroadcastReceiver mWifiApStateChangedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // On Android 15 and later, calling enableEdgeToEdge ensures system bar icon colors update
        // when the device theme changes. Because calling it on pre-Android 15 has the side effect of
        // enabling EdgeToEdge there as well, we only use it on Android 15 and later.
        if (Build.VERSION.SDK_INT >= 35) {
            EdgeToEdge.enable(this);
        }
        setContentView(R.layout.activity_main);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDefaults = new Defaults(this);

        mButtonToggle = findViewById(R.id.toggle);
        mButtonToggle.setOnClickListener(view -> {

            Intent intent = new Intent(MainActivity.this, MainService.class);
            intent.putExtra(MainService.EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort()));
            intent.putExtra(MainService.EXTRA_PASSWORD, prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, mDefaults.getPassword()));
            intent.putExtra(MainService.EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, mDefaults.getFileTransfer()));
            intent.putExtra(MainService.EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()));
            intent.putExtra(MainService.EXTRA_SHOW_POINTERS, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, mDefaults.getShowPointers()));
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

            EditText reconnectTriesInputText = new EditText(this);
            reconnectTriesInputText.setInputType(InputType.TYPE_CLASS_NUMBER);
            reconnectTriesInputText.setHint(getString(R.string.main_activity_reconnect_tries_hint));
            reconnectTriesInputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout inputLayout = new LinearLayout(this);
            inputLayout.setPadding(
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, Resources.getSystem().getDisplayMetrics()),
                    (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, Resources.getSystem().getDisplayMetrics()),
                    0
            );
            inputLayout.setOrientation(LinearLayout.VERTICAL);
            inputLayout.addView(inputText);
            inputLayout.addView(reconnectTriesInputText);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.main_activity_reverse_vnc_button)
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
                        try {
                            request.putExtra(MainService.EXTRA_RECONNECT_TRIES, Integer.parseInt(reconnectTriesInputText.getText().toString()));
                        } catch (NumberFormatException ignored) {
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(request);
                        } else {
                            startService(request);
                        }

                        // show a progress dialog
                        ProgressBar progressBar = new ProgressBar(this);
                        progressBar.setPadding(0,0,0, (int) (30 * getResources().getDisplayMetrics().density));
                        mOutgoingConnectionWaitDialog = new AlertDialog.Builder(this)
                                .setCancelable(false)
                                .setTitle(R.string.main_activity_reverse_vnc_button)
                                .setMessage(getString(R.string.main_activity_connecting_to, BidiFormatter.getInstance().unicodeWrap(host + ":" + port)))
                                .setView(progressBar)
                                .show();
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

            EditText reconnectTriesInputText = new EditText(this);
            reconnectTriesInputText.setInputType(InputType.TYPE_CLASS_NUMBER);
            reconnectTriesInputText.setHint(getString(R.string.main_activity_reconnect_tries_hint));
            reconnectTriesInputText.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
            inputLayout.addView(reconnectTriesInputText);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.main_activity_repeater_vnc_button)
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
                        try {
                            request.putExtra(MainService.EXTRA_RECONNECT_TRIES, Integer.parseInt(reconnectTriesInputText.getText().toString()));
                        } catch (NumberFormatException ignored) {
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(request);
                        } else {
                            startService(request);
                        }
                        // show a progress dialog
                        ProgressBar progressBar = new ProgressBar(this);
                        progressBar.setPadding(0,0,0, (int) (30 * getResources().getDisplayMetrics().density));
                        mOutgoingConnectionWaitDialog = new AlertDialog.Builder(this)
                                .setCancelable(false)
                                .setTitle(R.string.main_activity_repeater_vnc_button)
                                .setMessage(getString(R.string.main_activity_connecting_to, BidiFormatter.getInstance().unicodeWrap(host + ":" + port + " - " + repeaterId)))
                                .setView(progressBar)
                                .show();
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
        port.setOnFocusChangeListener((v, hasFocus) -> {
            // move cursor to end of text
            port.setSelection(port.getText().length());
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
                // if value just saved was empty, reset preference and UI back to default
                String savedAccessKey = prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, null);
                if(savedAccessKey != null && savedAccessKey.isEmpty()) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey());
                    ed.apply();
                    accessKey.setText(mDefaults.getAccessKey());
                }
            }
            // move cursor to end of text
            accessKey.setSelection(accessKey.getText().length());
        });

        final EditText startOnBootDelay = findViewById(R.id.settings_start_on_boot_delay);
        startOnBootDelay.setText(String.valueOf(prefs.getInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, mDefaults.getStartOnBootDelay())));
        startOnBootDelay.setEnabled(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, mDefaults.getStartOnBoot()));
        startOnBootDelay.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                try {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY, Integer.parseInt(charSequence.toString()));
                    ed.apply();
                } catch(NumberFormatException e) {
                    // nop
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
                // if value just saved was empty, reset preference and UI back to default
                String savedAccessKey = prefs.getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, null);
                if(savedAccessKey != null && savedAccessKey.isEmpty()) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey());
                    ed.apply();
                    accessKey.setText(mDefaults.getAccessKey());
                }

                if(startOnBootDelay.getText().length() == 0) {
                    // reset to default
                    startOnBootDelay.setHint(String.valueOf(mDefaults.getStartOnBootDelay()));
                    // and remove preference
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.remove(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT_DELAY);
                    ed.apply();
                }
            }
        });
        // move cursor to end of text
        startOnBootDelay.setOnFocusChangeListener((v, hasFocus) -> startOnBootDelay.setSelection(startOnBootDelay.getText().length()));

        final SwitchMaterial startOnBoot = findViewById(R.id.settings_start_on_boot);
        startOnBoot.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, mDefaults.getStartOnBoot()));
        startOnBoot.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, b);
            ed.apply();
            startOnBootDelay.setEnabled(b);
        });

        if(Build.VERSION.SDK_INT >= 33) {
            // no use asking for permission on Android 13+, always denied.
            // users can always read/write Documents and Downloads tough.
            findViewById(R.id.settings_row_file_transfer).setVisibility(View.GONE);
        } else {
            final SwitchMaterial fileTransfer = findViewById(R.id.settings_file_transfer);
            fileTransfer.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, mDefaults.getFileTransfer()));
            fileTransfer.setOnCheckedChangeListener((compoundButton, b) -> {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, b);
                ed.apply();
            });
        }

        Slider scaling = findViewById(R.id.settings_scaling);
        scaling.setValue(
                prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING,
                        (float) Math.ceil(mDefaults.getScaling() * 100 / scaling.getStepSize()) * scaling.getStepSize() / 100)
                        * 100);
        scaling.setLabelFormatter(value -> Math.round(value) + " %");
        scaling.addOnChangeListener((slider, value, fromUser) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putFloat(Constants.PREFS_KEY_SETTINGS_SCALING, value/100);
            ed.apply();
        });

        final SwitchMaterial showPointers = findViewById(R.id.settings_show_pointers);
        showPointers.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, mDefaults.getShowPointers()));
        showPointers.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, b);
            ed.apply();
        });
        showPointers.setEnabled(!prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()));

        final SwitchMaterial viewOnly = findViewById(R.id.settings_view_only);
        viewOnly.setChecked(prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()));
        viewOnly.setOnCheckedChangeListener((compoundButton, b) -> {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, b);
            ed.apply();
            // pointers depend on this one
            showPointers.setEnabled(!b);
        });

        TextView about = findViewById(R.id.about);
        about.setText(getString(R.string.main_activity_about, BuildConfig.VERSION_NAME));

        mMainServiceBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MainService.ACTION_START.equals(intent.getAction())) {
                    if(intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)) {
                        // was a successful START requested by anyone (but sent by MainService, as the receiver is not exported!)
                        Log.d(TAG, "got MainService started success event");
                        onServerStarted();
                    } else {
                        // was a failed START requested by anyone (but sent by MainService, as the receiver is not exported!)
                        Log.d(TAG, "got MainService started fail event");
                        // if it was, by us, re-enable the button!
                        mButtonToggle.setEnabled(true);
                        // let focus stay on button
                        mButtonToggle.requestFocus();
                    }
                }

                if (MainService.ACTION_STOP.equals(intent.getAction())
                        && (intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, true))) {
                    // was a successful STOP requested by anyone (but sent by MainService, as the receiver is not exported!)
                    // or a STOP without any extras
                    Log.d(TAG, "got MainService stopped event");
                    onServerStopped();
                }

                if (MainService.ACTION_CONNECT_REVERSE.equals(intent.getAction())
                        && mLastMainServiceRequestId != null
                        && mLastMainServiceRequestId.equals(intent.getStringExtra(MainService.EXTRA_REQUEST_ID))) {
                    // was a CONNECT_REVERSE requested by us
                    if (intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)) {
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_reverse_vnc_success,
                                                BidiFormatter.getInstance().unicodeWrap(mLastReverseHost + ":" + mLastReversePort)),
                                        Toast.LENGTH_LONG)
                                .show();
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putString(PREFS_KEY_REVERSE_VNC_LAST_HOST,
                                mLastReverseHost + ":" + mLastReversePort);
                        ed.apply();
                    } else
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_reverse_vnc_fail,
                                                BidiFormatter.getInstance().unicodeWrap(mLastReverseHost + ":" + mLastReversePort)),
                                        Toast.LENGTH_LONG)
                                .show();

                    // reset this
                    mLastMainServiceRequestId = null;
                    try {
                        mOutgoingConnectionWaitDialog.dismiss();
                    } catch(NullPointerException ignored) {
                    }
                }

                if (MainService.ACTION_CONNECT_REPEATER.equals(intent.getAction())
                        && mLastMainServiceRequestId != null
                        && mLastMainServiceRequestId.equals(intent.getStringExtra(MainService.EXTRA_REQUEST_ID))) {
                    // was a CONNECT_REPEATER requested by us
                    if (intent.getBooleanExtra(MainService.EXTRA_REQUEST_SUCCESS, false)) {
                        Toast.makeText(MainActivity.this,
                                        getString(R.string.main_activity_repeater_vnc_success,
                                                BidiFormatter.getInstance().unicodeWrap(mLastRepeaterHost + ":" + mLastRepeaterPort),
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
                                                BidiFormatter.getInstance().unicodeWrap(mLastRepeaterHost + ":" + mLastRepeaterPort),
                                                mLastRepeaterId),
                                        Toast.LENGTH_LONG)
                                .show();

                    // reset this
                    mLastMainServiceRequestId = null;
                    try {
                        mOutgoingConnectionWaitDialog.dismiss();
                    } catch(NullPointerException ignored) {
                    }
                }

            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(MainService.ACTION_START);
        filter.addAction(MainService.ACTION_STOP);
        filter.addAction(MainService.ACTION_CONNECT_REVERSE);
        filter.addAction(MainService.ACTION_CONNECT_REPEATER);
        // register the receiver as NOT_EXPORTED so it only receives broadcasts sent by MainService,
        // not a malicious fake broadcaster like
        // `adb shell am broadcast -a net.christianbeier.droidvnc_ng.ACTION_STOP --ez net.christianbeier.droidvnc_ng.EXTRA_REQUEST_SUCCESS true`
        // for instance
        ContextCompat.registerReceiver(this, mMainServiceBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        /*
            Let UI update on any network interface changes.
         */
        // Client networks
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "NetworkCallback onAvailable");
                updateAddressesDisplay();
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                Log.d(TAG, "NetworkCallback onLinkPropertiesChanged");
                updateAddressesDisplay();
            }


            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "NetworkCallback onLost");
                updateAddressesDisplay();
            }
        };
        ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).registerNetworkCallback(
                new NetworkRequest.Builder().build(),
                mNetworkCallback);
        // Access Points opened by us
        mWifiApStateChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "WIFI_AP_STATE_CHANGED");
                updateAddressesDisplay();
            }
        };
        ContextCompat.registerReceiver(
                this,
                mWifiApStateChangedReceiver,
                new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"),
                ContextCompat.RECEIVER_EXPORTED);

        /*
            setup UI initial state
         */
        if (MainService.isServerActive()) {
            Log.d(TAG, "Found server to be started");
            onServerStarted();
        } else {
            Log.d(TAG, "Found server to be stopped");
            onServerStopped();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // onWindowFocusChanged() works OK on API level 26 and newer
        if(Build.VERSION.SDK_INT >= 26) {
            updatePermissionsDisplay();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onResume() is needed on API levels earlier than 26
        if(Build.VERSION.SDK_INT < 26) {
            updatePermissionsDisplay();
        }
    }

    private void updatePermissionsDisplay() {

        /*
            Update Input permission display.
         */
        TextView inputStatus = findViewById(R.id.permission_status_input);
        if(InputService.isConnected()) {
            inputStatus.setText(R.string.main_activity_granted);
            inputStatus.setTextColor(getColor(R.color.granted));
        } else {
            inputStatus.setText(R.string.main_activity_denied);
            inputStatus.setTextColor(getColor(R.color.denied));
        }


        /*
            Update File Access permission display. Only show on < Android 13.
         */
        if(Build.VERSION.SDK_INT < 33) {
            TextView fileAccessStatus = findViewById(R.id.permission_status_file_access);
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                fileAccessStatus.setText(R.string.main_activity_granted);
                fileAccessStatus.setTextColor(getColor(R.color.granted));
            } else {
                fileAccessStatus.setText(R.string.main_activity_denied);
                fileAccessStatus.setTextColor(getColor(R.color.denied));
            }
        } else {
            findViewById(R.id.permission_row_file_access).setVisibility(View.GONE);
        }


        /*
            Update Notification permission display. Only show on >= Android 13.
         */
        if(Build.VERSION.SDK_INT >= 33) {
            TextView notificationStatus = findViewById(R.id.permission_status_notification);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationStatus.setText(R.string.main_activity_granted);
                notificationStatus.setTextColor(getColor(R.color.granted));
            } else {
                notificationStatus.setText(R.string.main_activity_denied);
                notificationStatus.setTextColor(getColor(R.color.denied));
            }
            notificationStatus.setOnClickListener(view -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
        } else {
            findViewById(R.id.permission_row_notification).setVisibility(View.GONE);
        }


        /*
           Update Screen Capturing permission display.
        */
        TextView screenCapturingStatus = findViewById(R.id.permission_status_screen_capturing);
        if(MediaProjectionService.isMediaProjectionEnabled()) {
            screenCapturingStatus.setText(R.string.main_activity_granted);
            screenCapturingStatus.setTextColor(getColor(R.color.granted));
            screenCapturingStatus.setOnClickListener(null);
        }
        if(!MediaProjectionService.isMediaProjectionEnabled()) {
            screenCapturingStatus.setText(R.string.main_activity_denied);
            screenCapturingStatus.setTextColor(getColor(R.color.denied));
            screenCapturingStatus.setOnClickListener(null);
        }
        if(!MediaProjectionService.isMediaProjectionEnabled() && InputService.isTakingScreenShots()) {
            screenCapturingStatus.setText(R.string.main_activity_fallback);
            screenCapturingStatus.setTextColor(getColor(R.color.fallback));
            // if fallback is on, this means the server is running, safe to start MediaProjectionRequestActivity
            // with EXTRA_UPGRADING_FROM_FALLBACK_SCREEN_CAPTURE which will call back into MainService
            screenCapturingStatus.setOnClickListener(view -> {
                Intent mediaProjectionRequestIntent = new Intent(this, MediaProjectionRequestActivity.class);
                mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mediaProjectionRequestIntent.putExtra(MediaProjectionRequestActivity.EXTRA_UPGRADING_FROM_NO_OR_FALLBACK_SCREEN_CAPTURE, true);
                mediaProjectionRequestIntent.putExtra(MediaProjectionRequestActivity.EXTRA_OMIT_FALLBACK_SCREEN_CAPTURE_DIALOG, true);
                startActivity(mediaProjectionRequestIntent);
            });
        }

        /*
            Update start-on-boot permission display. Only show on >= Android 10.
         */
        if (Build.VERSION.SDK_INT >= 30) {
            TextView startOnBootStatus = findViewById(R.id.permission_status_start_on_boot);
            if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, mDefaults.getStartOnBoot())
                    && InputService.isConnected()) {
                startOnBootStatus.setText(R.string.main_activity_granted);
                startOnBootStatus.setTextColor(getColor(R.color.granted));
                startOnBootStatus.setOnClickListener(null);
            } else {
                startOnBootStatus.setText(R.string.main_activity_denied);
                startOnBootStatus.setTextColor(getColor(R.color.denied));
                // wire this up only for denied status
                startOnBootStatus.setOnClickListener(view -> {
                    InputRequestActivity.requestIfNeededAndPostResult(this,
                            false,
                            PreferenceManager.getDefaultSharedPreferences(this).getBoolean(Constants.PREFS_KEY_SETTINGS_START_ON_BOOT, mDefaults.getStartOnBoot()),
                            true);
                });
            }
        } else {
            findViewById(R.id.permission_row_start_on_boot).setVisibility(View.GONE);
        }

    }

    private void updateAddressesDisplay() {

        if(!MainService.isServerActive()) {
            return;
        }

        Log.d(TAG, "updateAddressesDisplay");

        if(MainService.getPort() >= 0) {
            HashMap<ClickableSpan, Pair<Integer,Integer>> spans = new HashMap<>();
            // uhh there must be a nice functional way for this
            ArrayList<String> hosts = MainService.getIPv4s();
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.main_activity_address)).append(" ");
            if(hosts.isEmpty()) {
                sb.append("localhost");
            } else {
                for (int i = 0; i < hosts.size(); ++i) {
                    String host = hosts.get(i);
                    sb.append(host).append(":").append(MainService.getPort()).append(" (");
                    int start = sb.length();
                    sb.append(getString(R.string.main_activity_share_link));
                    ClickableSpan clickableSpan = new ClickableSpan() {
                        @Override
                        public void onClick(@NonNull View widget) {
                            Intent sendIntent = new Intent();
                            sendIntent.setAction(Intent.ACTION_SEND);
                            sendIntent.putExtra(Intent.EXTRA_TEXT,
                                    "http://" + host + ":" + (MainService.getPort() - 100) + "/vnc.html?autoconnect=true&show_dot=true&&host=" + host + "&port=" + MainService.getPort());
                            sendIntent.setType("text/plain");
                            startActivity(Intent.createChooser(sendIntent, null));
                        }
                    };
                    spans.put(clickableSpan, Pair.create(start, sb.length()));
                    sb.append(")");
                    if (i != hosts.size() - 1)
                        sb.append(" ").append(getString(R.string.or)).append(" ");
                }
            }
            sb.append(".");
            // done with string and span creation, put it all together
            SpannableString spannableString = new SpannableString(sb);
            spans.forEach((clickableSpan, startEnd) -> spannableString.setSpan(clickableSpan, startEnd.first, startEnd.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE));
            mAddress.post(() -> {
                mAddress.setText(spannableString);
                mAddress.setMovementMethod(LinkMovementMethod.getInstance());
            });
        } else {
            mAddress.post(() -> mAddress.setText(R.string.main_activity_not_listening));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        unregisterReceiver(mMainServiceBroadcastReceiver);
        ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(mNetworkCallback);
        unregisterReceiver(mWifiApStateChangedReceiver);
    }

    private void onServerStarted() {
        mButtonToggle.post(() -> {
            mButtonToggle.setText(R.string.stop);
            mButtonToggle.setEnabled(true);
            // let focus stay on button
            mButtonToggle.requestFocus();
        });

        updateAddressesDisplay();

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
        findViewById(R.id.settings_show_pointers).setEnabled(false);

        mIsMainServiceRunning = true;
    }

    private void onServerStopped() {
        mButtonToggle.post(() -> {
            mButtonToggle.setText(R.string.start);
            mButtonToggle.setEnabled(true);
            // let focus stay on button
            mButtonToggle.requestFocus();
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
        if(!((SwitchMaterial)findViewById(R.id.settings_view_only)).isChecked()) {
            // pointers depend on view-only being disabled
            findViewById(R.id.settings_show_pointers).setEnabled(true);
        }

        mIsMainServiceRunning = false;
    }

}