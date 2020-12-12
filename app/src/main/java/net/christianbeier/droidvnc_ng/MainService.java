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
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Enumeration;

public class MainService extends Service {

    private static final String TAG = "MainService";
    private static final int NOTIFICATION_ID = 11;
    final static String ACTION_START = "start";
    final static String ACTION_STOP = "stop";

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

    private static MainService instance;

    static {
        // order is important here
        System.loadLibrary("vncserver");
        System.loadLibrary("droidvnc-ng");
    }

    private native boolean vncStartServer(int width, int height, int port, String password);
    private native boolean vncStopServer();
    private native boolean vncNewFramebuffer(int width, int height);
    private native boolean vncUpdateFramebuffer(ByteBuffer buf);
    private native int vncGetFramebufferWidth();
    private native int vncGetFramebufferHeight();


    public MainService() {
    }

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
                Create the notification
             */
            Intent notificationIntent = new Intent(this, MainActivity.class);

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                    notificationIntent, 0);

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
            Start the server FIXME move this to intent handling?
         */
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!vncStartServer(displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, 5900),
                prefs.getString(Constants.PREFS_KEY_SETTINGS_PASSWORD, "")))
            stopSelf();
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);

        Log.d(TAG, "onConfigurationChanged: width: " + displayMetrics.widthPixels + " height: " + displayMetrics.heightPixels);

        setUpVirtualDisplay();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        tearDownMediaProjection();
        vncStopServer();
        instance = null;
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if(ACTION_HANDLE_MEDIA_PROJECTION_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle media projection result");
            // Step 4 (optional): coming back from capturing permission check, now starting capturing machinery
            mResultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, 0);
            mResultData = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_RESULT_DATA);
            setUpMediaProjection();
            setUpVirtualDisplay();
        }

        if(ACTION_HANDLE_WRITE_STORAGE_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle write storage result");
            // Step 3: coming back from write storage permission check, now ask for capturing permission
            startScreenCapture();
        }

        if(ACTION_HANDLE_INPUT_RESULT.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: handle input result");
            // Step 2: coming back from input permission check, now ask for write storage permission
            checkWriteStoragePermission();
        }

        if(ACTION_START.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: start");
            // Step 1: check input permission
            checkInputPermission();
        }

        if(ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: stop");
            stopScreenCapture();
            stopSelf();
        }

        return START_STICKY;
    }




    private void setUpMediaProjection() {
        mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
    }

    private void tearDownMediaProjection() {
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    private void startScreenCapture() {

        if (mMediaProjection != null) {
            setUpVirtualDisplay();
        } else if (mResultCode != 0 && mResultData != null) {
            setUpMediaProjection();
            setUpVirtualDisplay();
        } else {
            Log.i(TAG, "Requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            startActivity(new Intent(this, MediaProjectionRequestActivity.class));
        }
    }

    @SuppressLint("WrongConstant")
    private void setUpVirtualDisplay() {

        if(mMediaProjection == null)
            return;

        if (mImageReader != null)
            mImageReader.close();
        if (mVirtualDisplay != null)
            mVirtualDisplay.release();

        final DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);

        // only set this by detecting quirky hardware if the user has not set manually
        if(!mHasPortraitInLandscapeWorkaroundSet && Build.FINGERPRINT.contains("rk3288")  && metrics.widthPixels > 800) {
            Log.w(TAG, "detected >10in rk3288 applying workaround for portrait-in-landscape quirk");
            mHasPortraitInLandscapeWorkaroundApplied = true;
        }

        // use workaround if flag set and in actual portrait mode
        if(mHasPortraitInLandscapeWorkaroundApplied && metrics.widthPixels < metrics.heightPixels) {

            final float portraitInsideLandscapeScaleFactor = (float)metrics.widthPixels/metrics.heightPixels;

            // width and height are swapped here
            final int quirkyLandscapeWidth = (int)((float)metrics.heightPixels/portraitInsideLandscapeScaleFactor);
            final int quirkyLandscapeHeight = (int)((float)metrics.widthPixels/portraitInsideLandscapeScaleFactor);

            mImageReader = ImageReader.newInstance(quirkyLandscapeWidth, quirkyLandscapeHeight, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    Log.d(TAG, "image available");
                    Image image = imageReader.acquireLatestImage();

                    if(image == null)
                        return;

                    final Image.Plane[] planes = image.getPlanes();
                    final ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * quirkyLandscapeWidth;
                    int w = quirkyLandscapeWidth + rowPadding / pixelStride;

                    // create destination Bitmap
                    Bitmap dest =  Bitmap.createBitmap(w, quirkyLandscapeHeight, Bitmap.Config.ARGB_8888);

                    // copy landscape buffer to dest bitmap
                    buffer.rewind();
                    dest.copyPixelsFromBuffer(buffer);

                    // get the portrait portion that's in the center of the landscape bitmap
                    Bitmap croppedDest = Bitmap.createBitmap(dest, quirkyLandscapeWidth/2 - metrics.widthPixels/2, 0, metrics.widthPixels, metrics.heightPixels);

                    ByteBuffer croppedBuffer = ByteBuffer.allocateDirect(metrics.widthPixels * metrics.heightPixels * 4);
                    croppedDest.copyPixelsToBuffer(croppedBuffer);

                    // if needed, setup a new VNC framebuffer that matches the new buffer's dimensions
                    if(metrics.widthPixels != vncGetFramebufferWidth() || metrics.heightPixels != vncGetFramebufferHeight())
                        vncNewFramebuffer(metrics.widthPixels, metrics.heightPixels);

                    vncUpdateFramebuffer(croppedBuffer);

                    image.close();
                }
            }, null);

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                    quirkyLandscapeWidth, quirkyLandscapeHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);

            return;
        }

        /*
            This is the default behaviour.
         */
        mImageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.d(TAG, "image available");
                Image image = imageReader.acquireLatestImage();

                if(image == null)
                    return;

                final Image.Plane[] planes = image.getPlanes();
                final ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * metrics.widthPixels;
                int w = metrics.widthPixels + rowPadding / pixelStride;

                // if needed, setup a new VNC framebuffer that matches the image plane's parameters
                if(w != vncGetFramebufferWidth() || metrics.heightPixels != vncGetFramebufferHeight())
                     vncNewFramebuffer(w, metrics.heightPixels);

                buffer.rewind();

                vncUpdateFramebuffer(buffer);

                image.close();
            }
        }, null);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);

    }

    private void stopScreenCapture() {
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
        mVirtualDisplay = null;

        tearDownMediaProjection();
    }

    private void checkInputPermission() {
        startActivity(new Intent(this, InputRequestActivity.class));
    }

    private void checkWriteStoragePermission() {
        startActivity(new Intent(this, WriteStorageRequestActivity.class));
    }

    /**
     * Get whether Media Projection was granted by the user.
     * @return -1 if unknown, 0 if denied, 1 if granted
     */
    public static int isMediaProjectionEnabled() {
        if(instance == null)
            return -1;
        if(instance.mResultCode == 0 || instance.mResultData == null)
            return 0;

        return 1;
    }

    public static void togglePortraitInLandscapeWorkaround() {
        try {
            // set
            instance.mHasPortraitInLandscapeWorkaroundSet = true;
            instance.mHasPortraitInLandscapeWorkaroundApplied = !instance.mHasPortraitInLandscapeWorkaroundApplied;
            // apply
            instance.setUpVirtualDisplay();
        }
        catch (NullPointerException e) {
            //unused
        }

    }

    /**
     * Get first non-loopback IPv4 address together with the port the user specified.
     * While this is not per definition the Wifi interface's IP, this should work for most users.
     * @return A string in the form IP:port.
     */
    public static String getIPv4AndPort() {

        int port = 5900;

        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(instance);
            port = prefs.getInt(Constants.PREFS_KEY_SETTINGS_PORT, 5900);
        } catch (NullPointerException e) {
            //unused
        }

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
                            return ia.getAddress().toString().replaceAll("/", "") + ":" + port;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            //unused
        }
        return null;
    }
}
