/*
 * DroidVNC-NG main service.
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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.content.IntentSanitizer;
import androidx.preference.PreferenceManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.core.app.NotificationCompat;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class MainService extends Service {

    private static final String TAG = "MainService";
    static final int NOTIFICATION_ID = 11;
    public final static String ACTION_START = "net.christianbeier.droidvnc_ng.ACTION_START";
    public final static String ACTION_STOP = "net.christianbeier.droidvnc_ng.ACTION_STOP";
    public static final String ACTION_CONNECT_REVERSE = "net.christianbeier.droidvnc_ng.ACTION_CONNECT_REVERSE";
    public static final String ACTION_CONNECT_REPEATER = "net.christianbeier.droidvnc_ng.ACTION_CONNECT_REPEATER";
    public static final String EXTRA_REQUEST_ID = "net.christianbeier.droidvnc_ng.EXTRA_REQUEST_ID";
    public static final String EXTRA_REQUEST_SUCCESS = "net.christianbeier.droidvnc_ng.EXTRA_REQUEST_SUCCESS";
    public static final String EXTRA_HOST = "net.christianbeier.droidvnc_ng.EXTRA_HOST";
    public static final String EXTRA_PORT = "net.christianbeier.droidvnc_ng.EXTRA_PORT";
    public static final String EXTRA_REPEATER_ID = "net.christianbeier.droidvnc_ng.EXTRA_REPEATER_ID";
    public static final String EXTRA_RECONNECT_TRIES = "net.christianbeier.droidvnc_ng.EXTRA_RECONNECT_TRIES";
    public static final String EXTRA_ACCESS_KEY = "net.christianbeier.droidvnc_ng.EXTRA_ACCESS_KEY";
    public static final String EXTRA_PASSWORD = "net.christianbeier.droidvnc_ng.EXTRA_PASSWORD";
    public static final String EXTRA_VIEW_ONLY = "net.christianbeier.droidvnc_ng.EXTRA_VIEW_ONLY";
    public static final String EXTRA_SHOW_POINTERS = "net.christianbeier.droidvnc_ng.EXTRA_SHOW_POINTERS";
    public static final String EXTRA_SCALING = "net.christianbeier.droidvnc_ng.EXTRA_SCALING";
    /**
     * Only used on Android 12 and earlier.
     */
    public static final String EXTRA_FILE_TRANSFER = "net.christianbeier.droidvnc_ng.EXTRA_FILE_TRANSFER";
    /**
     * Only used on Android 10 and later.
     */
    public static final String EXTRA_FALLBACK_SCREEN_CAPTURE = "net.christianbeier.droidvnc_ng.EXTRA_FALLBACK_SCREEN_CAPTURE";

    final static String ACTION_HANDLE_MEDIA_PROJECTION_RESULT = "action_handle_media_projection_result";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_DATA = "result_data_media_projection";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_CODE = "result_code_media_projection";
    final static String EXTRA_MEDIA_PROJECTION_UPGRADING_FROM_FALLBACK_SCREEN_CAPTURE = "upgrading_from_fallback_screen_capture";

    final static String ACTION_HANDLE_INPUT_RESULT = "action_handle_a11y_result";
    final static String EXTRA_INPUT_RESULT = "result_a11y";

    final static String ACTION_HANDLE_WRITE_STORAGE_RESULT = "action_handle_write_storage_result";
    final static String EXTRA_WRITE_STORAGE_RESULT = "result_write_storage";

    final static String ACTION_HANDLE_NOTIFICATION_RESULT = "action_handle_notification_result";

    private static final String PREFS_KEY_SERVER_LAST_PORT = "server_last_port" ;
    private static final String PREFS_KEY_SERVER_LAST_PASSWORD = "server_last_password" ;
    private static final String PREFS_KEY_SERVER_LAST_FILE_TRANSFER = "server_last_file_transfer" ;
    private static final String PREFS_KEY_SERVER_LAST_SHOW_POINTERS = "server_last_show_pointers" ;
    private static final String PREFS_KEY_SERVER_LAST_FALLBACK_SCREEN_CAPTURE = "server_last_fallback_screen_capture" ;
    private static final String PREFS_KEY_SERVER_LAST_START_REQUEST_ID = "server_last_start_request_id" ;

    private int mResultCode;
    private Intent mResultData;
    private PowerManager.WakeLock mWakeLock;
    private Notification mNotification;

    private int mNumberOfClients;

    private static class OutboundClientReconnectData {
        Intent intent;
        long client;
        int reconnectTriesLeft;
        int backoff;
        static final int BACKOFF_INIT = 2;
        static final int BACKOFF_LIMIT = 64;
    }
    /// This maps the Intent's request id to an OutboundClientReconnectData entry
    private final ConcurrentHashMap<String, OutboundClientReconnectData> mOutboundClientsToReconnect = new ConcurrentHashMap<>();
    private final Handler mOutboundClientReconnectHandler = new Handler(Looper.getMainLooper());

    private boolean mIsStopping;
    // service is stopping on OUR initiative, NOT by stopService()
    private boolean mIsStoppingByUs;

    private Defaults mDefaults;

    private static MainService instance;

    private final NsdManager.RegistrationListener mNSDRegistrationListener = new NsdManager.RegistrationListener() {
        @Override
        public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
            Log.e(TAG, "NSD register failed for " + nsdServiceInfo + " with code " + i);
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
            Log.e(TAG, "NSD unregister failed for " + nsdServiceInfo + " with code " + i);
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
            Log.d(TAG, "NSD register for " + nsdServiceInfo);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo nsdServiceInfo) {
            Log.d(TAG, "NSD unregister for " + nsdServiceInfo);
        }
    };

    static {
        // order is important here
        System.loadLibrary("droidvnc-ng");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private native boolean vncStartServer(int width, int height, int port, String desktopName, String password);
    private native boolean vncStopServer();
    private native boolean vncIsActive();
    private native long vncConnectReverse(String host, int port);
    private native long vncConnectRepeater(String host, int port, String repeaterIdentifier);
    static native boolean vncNewFramebuffer(int width, int height);
    static native boolean vncUpdateFramebuffer(ByteBuffer buf);
    static native int vncGetFramebufferWidth();
    static native int vncGetFramebufferHeight();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        instance = this;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            /*
                Create notification channel
             */
            NotificationChannel serviceChannel = new NotificationChannel(
                    getPackageName(),
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);

            /*
                startForeground() w/ notification
             */
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, getNotification(null, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, getNotification(null, true));
            }
        }

        /*
            Get a wake lock
         */
        //noinspection deprecation
        mWakeLock = ((PowerManager) instance.getSystemService(Context.POWER_SERVICE)).newWakeLock((PowerManager.SCREEN_DIM_WAKE_LOCK| PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE), TAG + ":clientsConnected");

        /*
            Register a listener for network-up events
         */
        ((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // fires when wifi lost and mobile data selected as well, but that won't hurt...
                Log.d(TAG, "DefaultNetworkCallback: now available: " + network);
                /*
                    A new default network came up: try to reconnect disconnected outbound clients immediately
                 */
                mOutboundClientsToReconnect
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getValue().client == 0) // is disconnected
                        .forEach(entry -> {
                            // if the client is set to reconnect, it definitely has tries left on disconnect
                            // (otherwise it wouldn't be in the list), so fire up reconnect action
                            Log.d(TAG, "DefaultNetworkCallback: resetting backoff and reconnecting outbound connection w/ request id " + entry.getKey());

                            // remove other callbacks as we don't want 2 runnables for this request on the handler queue at the same time!
                            mOutboundClientReconnectHandler.removeCallbacksAndMessages(entry.getKey());
                            // reset backoff for this connection
                            entry.getValue().backoff = OutboundClientReconnectData.BACKOFF_INIT;
                            entry.getValue().reconnectTriesLeft = entry.getValue().intent.getIntExtra(EXTRA_RECONNECT_TRIES, 0);
                            // NB that onAvailable() runs on internal ConnectivityService thread, so still use mOutboundClientReconnectHandler here
                            mOutboundClientReconnectHandler.postAtTime(
                                    () -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(entry.getValue().intent);
                                        } else {
                                            startService(entry.getValue().intent);
                                        }
                                    },
                                    entry.getKey(),
                                    SystemClock.uptimeMillis() + entry.getValue().backoff * 1000L
                            );
                        });
            }
        });


        /*
            Load defaults
         */
        mDefaults = new Defaults(this);
    }



    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        mIsStopping = true;

        if(!mIsStoppingByUs && vncIsActive()) {
            // stopService() from OS or other component
            Log.d(TAG, "onDestroy: sending ACTION_STOP");
            sendBroadcastToOthersAndUs(new Intent(ACTION_STOP));
        }

        try {
            ((NsdManager) getSystemService(Context.NSD_SERVICE)).unregisterService(mNSDRegistrationListener);
        } catch (Exception ignored) {
            // was not registered
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API levels < 26 don't have the mandatory foreground notification and need manual notification dismissal
            getSystemService(NotificationManager.class).cancelAll();
        }

        // remove all pending client reconnects
        mOutboundClientReconnectHandler.removeCallbacksAndMessages(null);

        stopScreenCapture();
        vncStopServer();
        instance = null;
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        String accessKey = intent.getStringExtra(EXTRA_ACCESS_KEY);
        if (accessKey == null
                || accessKey.isEmpty()
                || !accessKey.equals(PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, mDefaults.getAccessKey()))) {
            Log.e(TAG, "Access key missing or incorrect");
            if(!vncIsActive()) {
                stopSelfByUs();
            }
            return START_NOT_STICKY;
        }

        if(ACTION_HANDLE_MEDIA_PROJECTION_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle media projection result");
            // Step 4 (optional): coming back from capturing permission check, now starting capturing machinery
            mResultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, 0);
            mResultData = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_RESULT_DATA);

            if(intent.getBooleanExtra(EXTRA_MEDIA_PROJECTION_UPGRADING_FROM_FALLBACK_SCREEN_CAPTURE, false)) {
                // just restart screen capture
                stopScreenCapture();
                startScreenCapture();
            } else {
                DisplayMetrics displayMetrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);
                int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort());
                // get device name
                String name = Utils.getDeviceName(this);

                boolean status = vncStartServer(displayMetrics.widthPixels,
                        displayMetrics.heightPixels,
                        port,
                        name,
                        PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword()));
                Intent answer = new Intent(ACTION_START);
                answer.putExtra(EXTRA_REQUEST_ID, PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_START_REQUEST_ID, null));
                answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
                sendBroadcastToOthersAndUs(answer);

                if (status) {
                    startScreenCapture();
                    registerNSD(name, port);
                    updateNotification();
                    // if we got here, we want to restart if we were killed
                    return START_REDELIVER_INTENT;
                } else {
                    stopSelfByUs();
                    return START_NOT_STICKY;
                }
            }
        }

        if(ACTION_HANDLE_WRITE_STORAGE_RESULT.equals(intent.getAction()) || ACTION_HANDLE_NOTIFICATION_RESULT.equals(intent.getAction())) {
            if(ACTION_HANDLE_WRITE_STORAGE_RESULT.equals(intent.getAction())) {
                Log.d(TAG, "onStartCommand: handle write storage result");
                // Step 3 on Android < 13: coming back from write storage permission check, start capturing
                // or ask for capturing permission first (then going in step 4)
            }
            if(ACTION_HANDLE_NOTIFICATION_RESULT.equals(intent.getAction())) {
                Log.d(TAG, "onStartCommand: handle notification result");
                // Step 3 on Android >= 13: coming back from notification permission check, start capturing
                // or ask for capturing permission first (then going in step 4)
            }

            if (mResultCode != 0 && mResultData != null
                    || (Build.VERSION.SDK_INT >= 30 && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREFS_KEY_SERVER_LAST_FALLBACK_SCREEN_CAPTURE, false))) {
                DisplayMetrics displayMetrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);
                int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort());
                String name = Utils.getDeviceName(this);
                boolean status = vncStartServer(displayMetrics.widthPixels,
                        displayMetrics.heightPixels,
                        port,
                        name,
                        PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword()));

                Intent answer = new Intent(ACTION_START);
                answer.putExtra(EXTRA_REQUEST_ID, PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_START_REQUEST_ID, null));
                answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
                sendBroadcastToOthersAndUs(answer);

                if(status) {
                    startScreenCapture();
                    registerNSD(name, port);
                    updateNotification();
                    // if we got here, we want to restart if we were killed
                    return START_REDELIVER_INTENT;
                } else {
                    stopSelfByUs();
                    return START_NOT_STICKY;
                }
            } else {
                Log.i(TAG, "Requesting confirmation");
                // This initiates a prompt dialog for the user to confirm screen projection.
                Intent mediaProjectionRequestIntent = new Intent(this, MediaProjectionRequestActivity.class);
                mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mediaProjectionRequestIntent);
                // if screen capturing was not started, we don't want a restart if we were killed
                // especially, we don't want the permission asking to replay.
                return START_NOT_STICKY;
            }
        }

        if(ACTION_HANDLE_INPUT_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle input result");
            // Step 2: coming back from input permission check, now setup InputService and ask for write storage permission or notification permission
            InputService.isInputEnabled = intent.getBooleanExtra(EXTRA_INPUT_RESULT, false);
            if(Build.VERSION.SDK_INT < 33) {
                Intent writeStorageRequestIntent = new Intent(this, WriteStorageRequestActivity.class);
                writeStorageRequestIntent.putExtra(
                        EXTRA_FILE_TRANSFER,
                        PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREFS_KEY_SERVER_LAST_FILE_TRANSFER, mDefaults.getFileTransfer()));
                writeStorageRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(writeStorageRequestIntent);
            } else {
                Intent notificationRequestIntent = new Intent(this, NotificationRequestActivity.class);
                notificationRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(notificationRequestIntent);
            }
            // if screen capturing was not started, we don't want a restart if we were killed
            // especially, we don't want the permission asking to replay.
            return START_NOT_STICKY;
        }

        if(ACTION_START.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: start");

            if(vncIsActive()) {
                Intent answer = new Intent(ACTION_START);
                answer.putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID));
                answer.putExtra(EXTRA_REQUEST_SUCCESS, false);
                sendBroadcastToOthersAndUs(answer);
                return START_NOT_STICKY;
            }

            // Step 0: persist given arguments to be able to recover from possible crash later
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor ed = prefs.edit();
            ed.putInt(PREFS_KEY_SERVER_LAST_PORT, intent.getIntExtra(EXTRA_PORT, prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, mDefaults.getPort())));
            ed.putString(PREFS_KEY_SERVER_LAST_PASSWORD, intent.getStringExtra(EXTRA_PASSWORD) != null ? intent.getStringExtra(EXTRA_PASSWORD) : prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, mDefaults.getPassword()));
            ed.putBoolean(PREFS_KEY_SERVER_LAST_FILE_TRANSFER, intent.getBooleanExtra(EXTRA_FILE_TRANSFER, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_FILE_TRANSFER, mDefaults.getFileTransfer())));
            ed.putBoolean(Constants.PREFS_KEY_INPUT_LAST_ENABLED, !intent.getBooleanExtra(EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly())));
            ed.putFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, intent.getFloatExtra(EXTRA_SCALING, prefs.getFloat(Constants.PREFS_KEY_SETTINGS_SCALING, mDefaults.getScaling())));
            ed.putString(PREFS_KEY_SERVER_LAST_START_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID));
            // showing pointers depends on view-only being false
            ed.putBoolean(PREFS_KEY_SERVER_LAST_SHOW_POINTERS,
                    !intent.getBooleanExtra(EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()))
                            && intent.getBooleanExtra(EXTRA_SHOW_POINTERS, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_SHOW_POINTERS, mDefaults.getShowPointers())));
            // using fallback screen capture depends on view-only being false
            ed.putBoolean(PREFS_KEY_SERVER_LAST_FALLBACK_SCREEN_CAPTURE,
                    !intent.getBooleanExtra(EXTRA_VIEW_ONLY, prefs.getBoolean(Constants.PREFS_KEY_SETTINGS_VIEW_ONLY, mDefaults.getViewOnly()))
                            && intent.getBooleanExtra(EXTRA_FALLBACK_SCREEN_CAPTURE, false));
            ed.apply();
            // also set new value for InputService
            InputService.scaling = PreferenceManager.getDefaultSharedPreferences(this).getFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, new Defaults(this).getScaling());

            // Step 1: check input/start-on-boot permission
            Intent inputRequestIntent = new Intent(this, InputRequestActivity.class);
            inputRequestIntent.putExtra(EXTRA_VIEW_ONLY, intent.getBooleanExtra(EXTRA_VIEW_ONLY, mDefaults.getViewOnly()));
            inputRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(inputRequestIntent);
            // if screen capturing was not started, we don't want a restart if we were killed
            // especially, we don't want the permission asking to replay.
            return START_NOT_STICKY;
        }

        if(ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: stop");
            stopSelfByUs();
            Intent answer = new Intent(ACTION_STOP);
            answer.putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID));
            answer.putExtra(EXTRA_REQUEST_SUCCESS, vncIsActive());
            sendBroadcastToOthersAndUs(answer);
            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REVERSE.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect reverse, id " + intent.getStringExtra(EXTRA_REQUEST_ID));
            if(vncIsActive()) {
                // run on worker thread
                new Thread(() -> {
                    long client = 0;
                    try {
                        client = instance.vncConnectReverse(intent.getStringExtra(EXTRA_HOST), intent.getIntExtra(EXTRA_PORT, mDefaults.getPortReverse()));
                    } catch (NullPointerException ignored) {
                    }
                    Intent answer = new Intent(ACTION_CONNECT_REVERSE);
                    answer.putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID));
                    answer.putExtra(EXTRA_REQUEST_SUCCESS, client != 0);
                    sendBroadcastToOthersAndUs(answer);
                    // check if set to reconnect and handle accordingly
                    handleClientReconnect(intent, client, "reverse");
                }).start();
            } else {
                stopSelfByUs();
            }

            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REPEATER.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect repeater, id " + intent.getStringExtra(EXTRA_REQUEST_ID));

            if(vncIsActive()) {
                // run on worker thread
                new Thread(() -> {
                    long client = 0;
                    try {
                        client = instance.vncConnectRepeater(
                                intent.getStringExtra(EXTRA_HOST),
                                intent.getIntExtra(EXTRA_PORT, mDefaults.getPortRepeater()),
                                intent.getStringExtra(EXTRA_REPEATER_ID));
                    } catch (NullPointerException ignored) {
                    }
                    Intent answer = new Intent(ACTION_CONNECT_REPEATER);
                    answer.putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID));
                    answer.putExtra(EXTRA_REQUEST_SUCCESS, client != 0);
                    sendBroadcastToOthersAndUs(answer);
                    // check if set to reconnect and handle accordingly
                    handleClientReconnect(intent, client, "repeater");
                }).start();
            } else {
                stopSelfByUs();
            }

            return START_NOT_STICKY;
        }

        // no known action was given, stop the _service_ again if the _server_ is not active
        if(!vncIsActive()) {
            stopSelfByUs();
        }

        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    @SuppressWarnings("unused")
    static void onClientConnected(long client) {
        Log.d(TAG, "onClientConnected: client " + client);

        try {
            instance.mWakeLock.acquire();
            instance.mNumberOfClients++;
            instance.updateNotification();
            InputService.addClient(client, PreferenceManager.getDefaultSharedPreferences(instance).getBoolean(PREFS_KEY_SERVER_LAST_SHOW_POINTERS, new Defaults(instance).getShowPointers()));
            if(!MediaProjectionService.isMediaProjectionEnabled() && InputService.isTakingScreenShots()) {
                Log.d(TAG, "onClientConnected: in fallback screen capture mode, asking for upgrade");
                Intent mediaProjectionRequestIntent = new Intent(instance, MediaProjectionRequestActivity.class);
                mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mediaProjectionRequestIntent.putExtra(MediaProjectionRequestActivity.EXTRA_UPGRADING_FROM_FALLBACK_SCREEN_CAPTURE, true);
                instance.startActivity(mediaProjectionRequestIntent);
            }
        } catch (Exception e) {
            // instance probably null
            Log.e(TAG, "onClientConnected: error: " + e);
        }
    }

    @SuppressWarnings("unused")
    static void onClientDisconnected(long client) {
        Log.d(TAG, "onClientDisconnected: client " + client);

        try {
            instance.mWakeLock.release();
            instance.mNumberOfClients--;
            if(!instance.mIsStopping) {
                // don't show notifications when clients are disconnected on orderly server shutdown
                instance.updateNotification();
            }
            InputService.removeClient(client);

            // check if the gone client was part of a reconnect entry
            instance.mOutboundClientsToReconnect
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue().client == client )
                    .forEach(entry -> {
                        // unset entry's client as it's now disconnected
                        entry.getValue().client = 0;
                        // if the connections is set to reconnect, it definitely has tries left on disconnect
                        // (otherwise it wouldn't be in the list), so fire up reconnect action
                        Log.d(TAG, "onClientDisconnected: outbound connection " + entry.getKey() + " set to reconnect, reconnecting with delay of " + entry.getValue().backoff + " seconds");
                        instance.mOutboundClientReconnectHandler.postAtTime(() -> {
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            instance.startForegroundService(entry.getValue().intent);
                                        } else {
                                            instance.startService(entry.getValue().intent);
                                        }
                                    } catch (NullPointerException ignored) {
                                        // onClientDisconnected() is triggered by vncStopServer() from onDestroy(),
                                        // but the actual call might happen well after instance is set to null in onDestroy()
                                    }
                                },
                                entry.getKey(),
                                SystemClock.uptimeMillis() + entry.getValue().backoff * 1000L);
                    });
        } catch (Exception e) {
            // instance probably null
            Log.e(TAG, "onClientDisconnected: error: " + e);
        }
    }

    private void handleClientReconnect(Intent reconnectIntent, long client, String logTag) {

        final Intent intent = new IntentSanitizer.Builder()
                .allowComponent(new ComponentName(this, getClass()))
                .allowAction(ACTION_CONNECT_REPEATER)
                .allowAction(ACTION_CONNECT_REVERSE)
                .allowExtra(EXTRA_REQUEST_ID, String.class)
                .allowExtra(EXTRA_RECONNECT_TRIES, Integer.class)
                .allowExtra(EXTRA_ACCESS_KEY, String.class)
                .allowExtra(EXTRA_HOST, String.class)
                .allowExtra(EXTRA_PORT, Integer.class)
                .allowExtra(EXTRA_REPEATER_ID, String.class)
                .build()
                .sanitizeByFiltering(reconnectIntent);

        String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (intent.getIntExtra(EXTRA_RECONNECT_TRIES, 0) > 0 && requestId != null) {
            if(client != 0) {
                Log.d(TAG, "handleClientReconnect: "
                        + logTag
                        + ": request id "
                        + intent.getStringExtra(EXTRA_REQUEST_ID)
                        + " successfully (re)connected");
                // connection successful, save Intent and client for later and set backoff and tries-left to init values
                OutboundClientReconnectData data = new OutboundClientReconnectData();
                data.intent = intent;
                data.client = client;
                data.backoff = OutboundClientReconnectData.BACKOFF_INIT;
                data.reconnectTriesLeft = intent.getIntExtra(EXTRA_RECONNECT_TRIES, 0);
                mOutboundClientsToReconnect.put(requestId, data);
            } else {
                // connection fail, check if entry in reconnect list
                OutboundClientReconnectData reconnectData = mOutboundClientsToReconnect.get(requestId);
                // also, get exact key reference (needed for cancellation at the Handler, as this does a "==" comparison, not .equals())
                String key = mOutboundClientsToReconnect
                        .entrySet()
                        .stream()
                        .filter(entry -> Objects.equals(entry.getValue(), reconnectData))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);

                if(reconnectData != null && key != null) {
                    // if we come here, there was already 1 reconnect from the client disconnect handler.
                    // thus unset client, decrease reconnect tries, increase backoff
                    reconnectData.client = 0;
                    reconnectData.reconnectTriesLeft--;
                    reconnectData.backoff = Math.min(reconnectData.backoff * 2, OutboundClientReconnectData.BACKOFF_LIMIT);
                    // then check if reconnect tries left
                    if (reconnectData.reconnectTriesLeft > 0) {
                        // yes, fire up another reconnect action
                        Log.d(TAG, "handleClientReconnect: "
                                + logTag
                                + ": request id "
                                + intent.getStringExtra(EXTRA_REQUEST_ID)
                                + " reconnect failed, has "
                                + reconnectData.reconnectTriesLeft
                                + " reconnect tries left, reconnecting with delay of "
                                + reconnectData.backoff
                                + " seconds");
                        mOutboundClientReconnectHandler.postAtTime(() -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(intent);
                                    } else {
                                        startService(intent);
                                    }
                                },
                                key, // important to use exact key reference here, see above!
                                SystemClock.uptimeMillis() + reconnectData.backoff * 1000L);
                    } else {
                        // no, delete entry
                        Log.d(TAG, "handleClientReconnect: "
                                + logTag
                                + ": request id "
                                + intent.getStringExtra(EXTRA_REQUEST_ID)
                                + "reconnect failed, exceeded reconnect tries, removing from reconnect list");
                        mOutboundClientsToReconnect.remove(requestId);
                    }
                }
            }
        }
    }

    private void startScreenCapture() {
        if (mResultCode != 0 && mResultData != null) {
            Log.d(TAG, "startScreenCapture: using MediaProjection backend");
            Intent intent = new Intent(this, MediaProjectionService.class);
            intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_RESULT_CODE, mResultCode);
            intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_RESULT_DATA, mResultData);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.d(TAG, "startScreenCapture: trying takeScreenShot backend");
                InputService.takeScreenShots(true);
            } else {
                Log.w(TAG, "startScreenCapture: no backend available");
            }
        }
    }

    private void stopScreenCapture() {
        // stop all backends unconditionally
        stopService(new Intent(this, MediaProjectionService.class));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            InputService.takeScreenShots(false);
        }
    }

    /**
     * Wrapper around stopSelf() that indicates that the stop was on our initiative.
     */
    private void stopSelfByUs() {
        mIsStoppingByUs = true;
        stopSelf();
    }

    private void sendBroadcastToOthersAndUs(Intent intent) {
        // Send implicit Intent to others (and us on API level < 34).
        sendBroadcast(intent);
        // Send explicit Intent to us, i.e. MainActivity which has a not-exported broadcast receiver
        // and needs an explicit Intent from Android 14 on as per https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
        // This will not be delivered to others as per https://developer.android.com/develop/background-work/background-tasks/broadcasts#security-and-best-practices
        if(Build.VERSION.SDK_INT >= 34) {
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }

    static boolean isServerActive() {
        try {
            return instance.vncIsActive();
        } catch (Exception ignored) {
            return false;
        }
    }


    /**
     * Get non-loopback IPv4 addresses.
     * @return A list of strings, each containing one IPv4 address.
     */
    static ArrayList<String> getIPv4s() {

        ArrayList<String> hosts = new ArrayList<>();

        // if running on Chrome OS, this prop is set and contains the device's IPv4 address,
        // see https://chromeos.dev/en/games/optimizing-games-networking
        String prop = Utils.getProp("arc.net.ipv4.host_address");
        if(!prop.isEmpty()) {
            hosts.add(prop);
            return hosts;
        }

        // not running on Chrome OS
        try {
            // thanks go to https://stackoverflow.com/a/20103869/361413
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            NetworkInterface ni;
            while (nis.hasMoreElements()) {
                ni = nis.nextElement();
                if (!ni.isLoopback()/*not loopback*/ && ni.isUp()/*it works now*/) {
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        //filter for ipv4/ipv6
                        if (ia.getAddress().getAddress().length == 4) {
                            //4 for ipv4, 16 for ipv6
                            hosts.add(ia.getAddress().toString().replaceAll("/", ""));
                        }
                    }
                }
            }
        } catch (SocketException e) {
            //unused
        }

        return hosts;
    }

    static int getPort() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
            return prefs.getInt(PREFS_KEY_SERVER_LAST_PORT, new Defaults(instance).getPort());
        } catch (Exception e) {
            return -2;
        }
    }

    private void registerNSD(String name, int port) {
        // unregister old one
        try {
            ((NsdManager) getSystemService(Context.NSD_SERVICE)).unregisterService(mNSDRegistrationListener);
        } catch (Exception ignored) {
            // was not registered
        }

        if(port < 0) {
            // no service offered
            return;
        }

        if(name == null || name.isEmpty()) {
            name = "Android";
        }

        // register new one
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(name);
        serviceInfo.setServiceType("_rfb._tcp.");
        serviceInfo.setPort(port);

        ((NsdManager)getSystemService(Context.NSD_SERVICE)).registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                mNSDRegistrationListener
        );
    }

    private Notification getNotification(String text, boolean isSilent){
        Intent notificationIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getPackageName())
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSilent(isSilent)
                .setOngoing(true)
                .setContentIntent(pendingIntent);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        mNotification = builder.build();
        return mNotification;
    }

    private void updateNotification() {
        int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort());
        if (port < 0) {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID,
                            getNotification(getString(R.string.main_service_notification_not_listening),
                                    false));
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID,
                            getNotification(getResources().getQuantityString(
                                            R.plurals.main_service_notification_listening,
                                            mNumberOfClients,
                                            port,
                                            mNumberOfClients),
                                    false));
        }
    }

    static Notification getCurrentNotification() {
        try {
            return instance.mNotification;
        } catch (Exception ignored) {
            return null;
        }
    }

}
