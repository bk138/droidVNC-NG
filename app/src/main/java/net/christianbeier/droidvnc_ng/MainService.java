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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;

public class MainService extends Service {

    private static final String TAG = "MainService";
    private static final int NOTIFICATION_ID = 11;
    public final static String ACTION_START = "net.christianbeier.droidvnc_ng.ACTION_START";
    public final static String ACTION_STOP = "net.christianbeier.droidvnc_ng.ACTION_STOP";
    public static final String ACTION_CONNECT_REVERSE = "net.christianbeier.droidvnc_ng.ACTION_CONNECT_REVERSE";
    public static final String ACTION_CONNECT_REPEATER = "net.christianbeier.droidvnc_ng.ACTION_CONNECT_REPEATER";
    public static final String EXTRA_REQUEST_ID = "net.christianbeier.droidvnc_ng.EXTRA_REQUEST_ID";
    public static final String EXTRA_REQUEST_SUCCESS = "net.christianbeier.droidvnc_ng.EXTRA_REQUEST_SUCCESS";
    public static final String EXTRA_HOST = "net.christianbeier.droidvnc_ng.EXTRA_HOST";
    public static final String EXTRA_PORT = "net.christianbeier.droidvnc_ng.EXTRA_PORT";
    public static final String EXTRA_REPEATER_ID = "net.christianbeier.droidvnc_ng.EXTRA_REPEATER_ID";
    public static final String EXTRA_ACCESS_KEY = "net.christianbeier.droidvnc_ng.EXTRA_ACCESS_KEY";
    public static final String EXTRA_PASSWORD = "net.christianbeier.droidvnc_ng.EXTRA_PASSWORD";
    public static final String EXTRA_VIEW_ONLY = "net.christianbeier.droidvnc_ng.EXTRA_VIEW_ONLY";
    public static final String EXTRA_SHOW_POINTERS = "net.christianbeier.droidvnc_ng.EXTRA_SHOW_POINTERS";
    public static final String EXTRA_SCALING = "net.christianbeier.droidvnc_ng.EXTRA_SCALING";
    /**
     * Only used on Android 12 and earlier.
     */
    public static final String EXTRA_FILE_TRANSFER = "net.christianbeier.droidvnc_ng.EXTRA_FILE_TRANSFER";

    final static String ACTION_HANDLE_MEDIA_PROJECTION_RESULT = "action_handle_media_projection_result";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_DATA = "result_data_media_projection";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_CODE = "result_code_media_projection";

    final static String ACTION_HANDLE_INPUT_RESULT = "action_handle_a11y_result";
    final static String EXTRA_INPUT_RESULT = "result_a11y";

    final static String ACTION_HANDLE_WRITE_STORAGE_RESULT = "action_handle_write_storage_result";
    final static String EXTRA_WRITE_STORAGE_RESULT = "result_write_storage";

    final static String ACTION_HANDLE_NOTIFICATION_RESULT = "action_handle_notification_result";

    private static final String PREFS_KEY_SERVER_LAST_PORT = "server_last_port" ;
    private static final String PREFS_KEY_SERVER_LAST_PASSWORD = "server_last_password" ;
    private static final String PREFS_KEY_SERVER_LAST_FILE_TRANSFER = "server_last_file_transfer" ;
    private static final String PREFS_KEY_SERVER_LAST_SHOW_POINTERS = "server_last_show_pointers" ;
    private static final String PREFS_KEY_SERVER_LAST_START_REQUEST_ID = "server_last_start_request_id" ;

    private int mResultCode;
    private Intent mResultData;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mMediaProjectionManager;

    private boolean mHasPortraitInLandscapeWorkaroundApplied;
    private boolean mHasPortraitInLandscapeWorkaroundSet;

    private PowerManager.WakeLock mWakeLock;

    private int mNumberOfClients;
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
    private native boolean vncNewFramebuffer(int width, int height);
    private native boolean vncUpdateFramebuffer(ByteBuffer buf);
    private native int vncGetFramebufferWidth();
    private native int vncGetFramebufferHeight();

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
            startForeground(NOTIFICATION_ID, getNotification(null, true));
        }

        /*
            Get the MediaProjectionManager
         */
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        /*
            Get a wake lock
         */
        //noinspection deprecation
        mWakeLock = ((PowerManager) instance.getSystemService(Context.POWER_SERVICE)).newWakeLock((PowerManager.SCREEN_DIM_WAKE_LOCK| PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE), TAG + ":clientsConnected");

        /*
            Load defaults
         */
        mDefaults = new Defaults(this);
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics displayMetrics = getDisplayMetrics(Display.DEFAULT_DISPLAY);
        Log.d(TAG, "onConfigurationChanged: width: " + displayMetrics.widthPixels + " height: " + displayMetrics.heightPixels);

        startScreenCapture();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        mIsStopping = true;

        if(!mIsStoppingByUs && vncIsActive()) {
            // stopService() from OS or other component
            Log.d(TAG, "onDestroy: sending ACTION_STOP");
            sendBroadcast(new Intent(ACTION_STOP));
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
            DisplayMetrics displayMetrics = getDisplayMetrics(Display.DEFAULT_DISPLAY);
            int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort());
            // get device name
            String name;
            try {
                // This is what we had until targetSDK 33.
                name = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
            } catch (SecurityException ignored) {
                // throws on devices with API level 33, so use fallback
                if (Build.VERSION.SDK_INT > 25) {
                    name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
                } else {
                    name = getString(R.string.app_name);
                }
            }

            boolean status = vncStartServer(displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    port,
                    name,
                    PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword()));
            Intent answer = new Intent(ACTION_START);
            answer.putExtra(EXTRA_REQUEST_ID, PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_START_REQUEST_ID, null));
            answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
            sendBroadcast(answer);

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

            if (mResultCode != 0 && mResultData != null) {
                DisplayMetrics displayMetrics = getDisplayMetrics(Display.DEFAULT_DISPLAY);
                int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort());
                String name = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
                boolean status = vncStartServer(displayMetrics.widthPixels,
                        displayMetrics.heightPixels,
                        port,
                        name,
                        PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword()));

                Intent answer = new Intent(ACTION_START);
                answer.putExtra(EXTRA_REQUEST_ID, PreferenceManager.getDefaultSharedPreferences(this).getString(PREFS_KEY_SERVER_LAST_START_REQUEST_ID, null));
                answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
                sendBroadcast(answer);

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
            InputService.isEnabled = intent.getBooleanExtra(EXTRA_INPUT_RESULT, false);
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
                sendBroadcast(answer);
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
            ed.apply();
            // also set new value for InputService
            InputService.scaling = intent.getFloatExtra(EXTRA_SCALING, mDefaults.getScaling());

            // Step 1: check input permission
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
            sendBroadcast(answer);
            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REVERSE.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect reverse");
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
                    sendBroadcast(answer);
                }).start();
            } else {
                stopSelfByUs();
            }

            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REPEATER.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect repeater");

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
                    sendBroadcast(answer);
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
        } catch (Exception e) {
            // instance probably null
            Log.e(TAG, "onClientDisconnected: error: " + e);
        }
    }

    @SuppressLint("WrongConstant")
    private void startScreenCapture() {

        if(mMediaProjection == null)
            try {
                mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
            } catch (SecurityException e) {
                Log.w(TAG, "startScreenCapture: got SecurityException, re-requesting confirmation");
                // This initiates a prompt dialog for the user to confirm screen projection.
                Intent mediaProjectionRequestIntent = new Intent(this, MediaProjectionRequestActivity.class);
                mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mediaProjectionRequestIntent);
                return;
            }

        if (mMediaProjection == null) {
            Log.e(TAG, "startScreenCapture: did not get a media projection, probably user denied");
            return;
        }

        if (mImageReader != null)
            mImageReader.close();

        final DisplayMetrics metrics = getDisplayMetrics(Display.DEFAULT_DISPLAY);

        // apply selected scaling
        float scaling = PreferenceManager.getDefaultSharedPreferences(this).getFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, new Defaults(this).getScaling());
        int scaledWidth = (int) (metrics.widthPixels * scaling);
        int scaledHeight = (int) (metrics.heightPixels * scaling);

        // only set this by detecting quirky hardware if the user has not set manually
        if(!mHasPortraitInLandscapeWorkaroundSet && Build.FINGERPRINT.contains("rk3288")  && metrics.widthPixels > 800) {
            Log.w(TAG, "detected >10in rk3288 applying workaround for portrait-in-landscape quirk");
            mHasPortraitInLandscapeWorkaroundApplied = true;
        }

        // use workaround if flag set and in actual portrait mode
        if(mHasPortraitInLandscapeWorkaroundApplied && scaledWidth < scaledHeight) {

            final float portraitInsideLandscapeScaleFactor = (float)scaledWidth/scaledHeight;

            // width and height are swapped here
            final int quirkyLandscapeWidth = (int)((float)scaledHeight/portraitInsideLandscapeScaleFactor);
            final int quirkyLandscapeHeight = (int)((float)scaledWidth/portraitInsideLandscapeScaleFactor);

            mImageReader = ImageReader.newInstance(quirkyLandscapeWidth, quirkyLandscapeHeight, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(imageReader -> {
                try (Image image = imageReader.acquireLatestImage()) {

                    if (image == null)
                        return;

                    final Image.Plane[] planes = image.getPlanes();
                    final ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * quirkyLandscapeWidth;
                    int w = quirkyLandscapeWidth + rowPadding / pixelStride;

                    // create destination Bitmap
                    Bitmap dest = Bitmap.createBitmap(w, quirkyLandscapeHeight, Bitmap.Config.ARGB_8888);

                    // copy landscape buffer to dest bitmap
                    buffer.rewind();
                    dest.copyPixelsFromBuffer(buffer);

                    // get the portrait portion that's in the center of the landscape bitmap
                    Bitmap croppedDest = Bitmap.createBitmap(dest, quirkyLandscapeWidth / 2 - scaledWidth / 2, 0, scaledWidth, scaledHeight);

                    ByteBuffer croppedBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
                    croppedDest.copyPixelsToBuffer(croppedBuffer);

                    // if needed, setup a new VNC framebuffer that matches the new buffer's dimensions
                    if (scaledWidth != vncGetFramebufferWidth() || scaledHeight != vncGetFramebufferHeight())
                        vncNewFramebuffer(scaledWidth, scaledHeight);

                    vncUpdateFramebuffer(croppedBuffer);
                } catch (Exception ignored) {
                }
            }, null);

            try {
                if(mVirtualDisplay == null) {
                    mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                            quirkyLandscapeWidth, quirkyLandscapeHeight, metrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            mImageReader.getSurface(), null, null);
                } else {
                    mVirtualDisplay.resize(quirkyLandscapeWidth, quirkyLandscapeHeight, metrics.densityDpi);
                    mVirtualDisplay.setSurface(mImageReader.getSurface());
                }
            } catch (SecurityException e) {
                Log.w(TAG, "startScreenCapture: got SecurityException, re-requesting confirmation");
                // This initiates a prompt dialog for the user to confirm screen projection.
                Intent mediaProjectionRequestIntent = new Intent(this, MediaProjectionRequestActivity.class);
                mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mediaProjectionRequestIntent);
            }

            return;
        }

        /*
            This is the default behaviour.
         */
        mImageReader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2);
        mImageReader.setOnImageAvailableListener(imageReader -> {
            try (Image image = imageReader.acquireLatestImage()) {

                if (image == null)
                    return;

                final Image.Plane[] planes = image.getPlanes();
                final ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * scaledWidth;
                int w = scaledWidth + rowPadding / pixelStride;

                // if needed, setup a new VNC framebuffer that matches the image plane's parameters
                if (w != vncGetFramebufferWidth() || scaledHeight != vncGetFramebufferHeight())
                    vncNewFramebuffer(w, scaledHeight);

                buffer.rewind();

                vncUpdateFramebuffer(buffer);
            } catch (Exception ignored) {
            }
        }, null);

        try {
            if(mVirtualDisplay == null) {
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                        scaledWidth, scaledHeight, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mImageReader.getSurface(), null, null);
            } else {
                mVirtualDisplay.resize(scaledWidth, scaledHeight, metrics.densityDpi);
                mVirtualDisplay.setSurface(mImageReader.getSurface());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "startScreenCapture: got SecurityException, re-requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            Intent mediaProjectionRequestIntent = new Intent(this, MediaProjectionRequestActivity.class);
            mediaProjectionRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(mediaProjectionRequestIntent);
        }

    }

    private void stopScreenCapture() {
        try {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        } catch (Exception e) {
            //unused
        }

        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    /**
     * Wrapper around stopSelf() that indicates that the stop was on our initiative.
     */
    private void stopSelfByUs() {
        mIsStoppingByUs = true;
        stopSelf();
    }

    /**
     * Get whether Media Projection was granted by the user.
     * @return -1 if unknown, 0 if denied, 1 if granted
     */
    static int isMediaProjectionEnabled() {
        if(instance == null)
            return -1;
        if(instance.mResultCode == 0 || instance.mResultData == null)
            return 0;

        return 1;
    }

    static boolean isServerActive() {
        try {
            return instance.vncIsActive();
        } catch (Exception ignored) {
            return false;
        }
    }

    static void togglePortraitInLandscapeWorkaround() {
        try {
            // set
            instance.mHasPortraitInLandscapeWorkaroundSet = true;
            instance.mHasPortraitInLandscapeWorkaroundApplied = !instance.mHasPortraitInLandscapeWorkaroundApplied;
            // apply
            instance.startScreenCapture();
        }
        catch (NullPointerException e) {
            //unused
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

    /** @noinspection SameParameterValue*/
    private DisplayMetrics getDisplayMetrics(int displayId) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.getDisplay(displayId).getRealMetrics(displayMetrics);
        return displayMetrics;
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
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSilent(isSilent)
                .setOngoing(true)
                .setContentIntent(pendingIntent);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
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

}
