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
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.Subject;

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
    public static final String EXTRA_SCALING = "net.christianbeier.droidvnc_ng.EXTRA_SCALING";
    public static final String EXTRA_FILE_TRANSFER = "net.christianbeier.droidvnc_ng.EXTRA_FILE_TRANSFER";

    final static String ACTION_HANDLE_MEDIA_PROJECTION_RESULT = "action_handle_media_projection_result";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_DATA = "result_data_media_projection";
    final static String EXTRA_MEDIA_PROJECTION_RESULT_CODE = "result_code_media_projection";

    final static String ACTION_HANDLE_INPUT_RESULT = "action_handle_a11y_result";
    final static String EXTRA_INPUT_RESULT = "result_a11y";

    final static String ACTION_HANDLE_WRITE_STORAGE_RESULT = "action_handle_write_storage_result";
    final static String EXTRA_WRITE_STORAGE_RESULT = "result_write_storage";

    private int mResultCode;
    private Intent mResultData;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mMediaProjectionManager;

    private boolean mHasPortraitInLandscapeWorkaroundApplied;
    private boolean mHasPortraitInLandscapeWorkaroundSet;

    private PowerManager.WakeLock mWakeLock;

    private Defaults mDefaults;

    private static MainService instance;

    private static final Subject<StatusEvent> mStatusEventStream = BehaviorSubject.createDefault(StatusEvent.STOPPED).toSerialized();
    public enum StatusEvent {
        STARTED,
        STOPPED,
    }

    static {
        // order is important here
        System.loadLibrary("vncserver");
        System.loadLibrary("droidvnc-ng");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private native boolean vncStartServer(int width, int height, int port, String desktopName, String password);
    private native boolean vncStopServer();
    private native boolean vncIsActive();
    private native boolean vncConnectReverse(String host, int port);
    private native boolean vncConnectRepeater(String host, int port, String repeaterIdentifier);
    private native boolean vncNewFramebuffer(int width, int height);
    private native boolean vncUpdateFramebuffer(ByteBuffer buf);
    private native int vncGetFramebufferWidth();
    private native int vncGetFramebufferHeight();


    public MainService() {
    }

    static Observable<StatusEvent> getStatusEventStream() {
        return mStatusEventStream;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        instance = this;

        mStatusEventStream.onNext(StatusEvent.STARTED);

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
                Create the notification
             */
            Intent notificationIntent = new Intent(this, MainActivity.class);

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                    notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, getPackageName())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Doing some work...")
                    .setContentIntent(pendingIntent).build();

            startForeground(NOTIFICATION_ID, notification);
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

        DisplayMetrics displayMetrics = getDisplayMetrics();
        Log.d(TAG, "onConfigurationChanged: width: " + displayMetrics.widthPixels + " height: " + displayMetrics.heightPixels);

        startScreenCapture();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopScreenCapture();
        vncStopServer();
        mStatusEventStream.onNext(StatusEvent.STOPPED);
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
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if(ACTION_HANDLE_MEDIA_PROJECTION_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle media projection result");
            // Step 4 (optional): coming back from capturing permission check, now starting capturing machinery
            mResultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, 0);
            mResultData = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_RESULT_DATA);
            DisplayMetrics displayMetrics = getDisplayMetrics();
            if (!vncStartServer(displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    PreferenceManager.getDefaultSharedPreferences(this).getInt(Constants.PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort()),
                    Settings.Secure.getString(getContentResolver(), "bluetooth_name"),
                    PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword())))
                stopSelf();
            startScreenCapture();
            // if we got here, we want to restart if we were killed
            return START_REDELIVER_INTENT;
        }

        if(ACTION_HANDLE_WRITE_STORAGE_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle write storage result");
            // Step 3: coming back from write storage permission check, start capturing
            // or ask for ask for capturing permission first (then going in step 4)
            if (mResultCode != 0 && mResultData != null) {
                DisplayMetrics displayMetrics = getDisplayMetrics();
                if (!vncStartServer(displayMetrics.widthPixels,
                        displayMetrics.heightPixels,
                        PreferenceManager.getDefaultSharedPreferences(this).getInt(Constants.PREFS_KEY_SERVER_LAST_PORT, mDefaults.getPort()),
                        Settings.Secure.getString(getContentResolver(), "bluetooth_name"),
                        PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SERVER_LAST_PASSWORD, mDefaults.getPassword())))
                    stopSelf();
                startScreenCapture();
                // if we got here, we want to restart if we were killed
                return START_REDELIVER_INTENT;
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
            // Step 2: coming back from input permission check, now setup InputService and ask for write storage permission
            InputService.isEnabled = intent.getBooleanExtra(EXTRA_INPUT_RESULT, false);
            Intent writeStorageRequestIntent = new Intent(this, WriteStorageRequestActivity.class);
            writeStorageRequestIntent.putExtra(
                    EXTRA_FILE_TRANSFER,
                    PreferenceManager.getDefaultSharedPreferences(this).getBoolean(Constants.PREFS_KEY_SERVER_LAST_FILE_TRANSFER, mDefaults.getFileTranfer()));
            writeStorageRequestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(writeStorageRequestIntent);
            // if screen capturing was not started, we don't want a restart if we were killed
            // especially, we don't want the permission asking to replay.
            return START_NOT_STICKY;
        }

        if(ACTION_START.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: start");
            // Step 0: persist given arguments to be able to recover from possible crash later
            SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(this).edit();
            ed.putInt(Constants.PREFS_KEY_SERVER_LAST_PORT, intent.getIntExtra(EXTRA_PORT, mDefaults.getPort()));
            ed.putString(Constants.PREFS_KEY_SERVER_LAST_PASSWORD, intent.getStringExtra(EXTRA_PASSWORD) != null ? intent.getStringExtra(EXTRA_PASSWORD) : mDefaults.getPassword());
            ed.putBoolean(Constants.PREFS_KEY_SERVER_LAST_FILE_TRANSFER, intent.getBooleanExtra(EXTRA_FILE_TRANSFER, mDefaults.getFileTranfer()));
            ed.putBoolean(Constants.PREFS_KEY_INPUT_LAST_ENABLED, !intent.getBooleanExtra(EXTRA_VIEW_ONLY, mDefaults.getViewOnly()));
            ed.putFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, intent.getFloatExtra(EXTRA_SCALING, mDefaults.getScaling()));
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
            stopSelf();
            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REVERSE.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect reverse");
            boolean status = false;
            if(vncIsActive()) {
                try {
                    //TODO run on worker thread
                    status = instance.vncConnectReverse(intent.getStringExtra(EXTRA_HOST), intent.getIntExtra(EXTRA_PORT, mDefaults.getPortReverse()));
                } catch (NullPointerException ignored) {
                }
            } else {
                stopSelf();
            }

            Intent answer = new Intent(ACTION_CONNECT_REVERSE);
            // use request's extras
            answer.putExtras(intent);
            // but don't leak the access key!
            answer.removeExtra(EXTRA_ACCESS_KEY);
            answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
            sendBroadcast(answer);
            return START_NOT_STICKY;
        }

        if(ACTION_CONNECT_REPEATER.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: connect repeater");
            boolean status = false;
            if(vncIsActive()) {
                try {
                    //TODO run on worker thread
                    status = instance.vncConnectRepeater(
                            intent.getStringExtra(EXTRA_HOST),
                            intent.getIntExtra(EXTRA_PORT, mDefaults.getPortRepeater()),
                            intent.getStringExtra(EXTRA_REPEATER_ID));
                } catch (NullPointerException ignored) {
                }
            } else {
                stopSelf();
            }

            Intent answer = new Intent(ACTION_CONNECT_REPEATER);
            // use request's extras
            answer.putExtras(intent);
            // but don't leak the access key!
            answer.removeExtra(EXTRA_ACCESS_KEY);
            answer.putExtra(EXTRA_REQUEST_SUCCESS, status);
            sendBroadcast(answer);
            return START_NOT_STICKY;
        }

        // no known action was given, stop the _service_ again if the _server_ is not active
        if(!vncIsActive()) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    @SuppressWarnings("unused")
    static void onClientConnected(long client) {
        Log.d(TAG, "onClientConnected: client " + client);

        try {
            instance.mWakeLock.acquire();
        } catch (Exception e) {
            // instance probably null
            Log.e(TAG, "onClientConnected: wake lock acquiring failed: " + e);
        }
    }

    @SuppressWarnings("unused")
    static void onClientDisconnected(long client) {
        Log.d(TAG, "onClientDisconnected: client " + client);

        try {
            instance.mWakeLock.release();
        } catch (Exception e) {
            // instance probably null
            Log.e(TAG, "onClientDisconnected: wake lock releasing failed: " + e);
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
        if (mVirtualDisplay != null)
            mVirtualDisplay.release();

        final DisplayMetrics metrics = getDisplayMetrics();

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
                Log.d(TAG, "image available");
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
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                        quirkyLandscapeWidth, quirkyLandscapeHeight, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mImageReader.getSurface(), null, null);
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
            Log.d(TAG, "image available");
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
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                    scaledWidth, scaledHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
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
     * Get non-loopback IPv4 addresses together with the port the user specified.
     * @return A list of strings in the form IP:port.
     */
    static ArrayList<String> getIPv4sAndPorts() {

        int port = 5900;

        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
            port = prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, 5900);
        } catch (NullPointerException e) {
            //unused
        }

        ArrayList<String> hostsAndPorts = new ArrayList<>();

        // if running on Chrome OS, this prop is set and contains the device's IPv4 address,
        // see https://chromeos.dev/en/games/optimizing-games-networking
        String prop = Utils.getProp("arc.net.ipv4.host_address");
        if(!prop.isEmpty()) {
            hostsAndPorts.add(prop + ":" + port);
            return hostsAndPorts;
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
                            hostsAndPorts.add(ia.getAddress().toString().replaceAll("/", "") + ":" + port);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            //unused
        }

        return hostsAndPorts;
    }

    private DisplayMetrics getDisplayMetrics() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);
        return displayMetrics;
    }
}
