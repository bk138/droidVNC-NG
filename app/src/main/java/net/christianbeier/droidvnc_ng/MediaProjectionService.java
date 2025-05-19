/*
 * DroidVNC-NG MediaProjection service.
 *
 * Author: Christian Beier <info@christianbeier.net>
 *
 * Copyright (C) 2024 IFA mbh.
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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.util.Objects;

public class MediaProjectionService extends Service {

    private static final String TAG = "MediaProjectionService";

    private int mResultCode;
    private Intent mResultData;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mMediaProjectionCallback;
    private MediaProjectionManager mMediaProjectionManager;

    private boolean mHasPortraitInLandscapeWorkaroundApplied;
    private boolean mHasPortraitInLandscapeWorkaroundSet;

    private static MediaProjectionService instance;


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
                startForeground() w/ notification; bit hacky re-using MainService's ;-)
             */
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    // throws NullPointerException if no notification
                    startForeground(MainService.NOTIFICATION_ID, Objects.requireNonNull(MainService.getCurrentNotification()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    // throws IllegalArgumentException if no notification
                    startForeground(MainService.NOTIFICATION_ID, MainService.getCurrentNotification());
                }
            } catch (Exception ignored) {
                Log.e(TAG, "Not starting because MainService quit");
            }
        }

        /*
            Setup MediaProjection stuff we can setup now
         */
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "callback: onStop");
                super.onStop();

                // make sure isMediaProjectionEnabled() reports the right status
                stopScreenCapture();

                if(MainService.isServerActive()) {
                    // tell MainService, it will take care of stopping us and maybe use a fallback
                    Intent intent = new Intent(MediaProjectionService.this, MainService.class);
                    intent.setAction(MainService.ACTION_HANDLE_MEDIA_PROJECTION_RESULT);
                    intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(MediaProjectionService.this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(MediaProjectionService.this).getAccessKey()));
                    intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_STATE, false); // off
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                }
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                Log.d(TAG, "callback: onCapturedContentResize " + width + "x" + height);
            }

            @Override
            public void onCapturedContentVisibilityChanged(boolean isVisible) {
                Log.d(TAG, "callback: onCapturedContentVisibilityChanged " + isVisible);
            }
        };
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics displayMetrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);
        Log.d(TAG, "onConfigurationChanged: width: " + displayMetrics.widthPixels + " height: " + displayMetrics.heightPixels);

        startScreenCapture();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        stopScreenCapture();

        instance = null;
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        mResultCode = intent.getIntExtra(MainService.EXTRA_MEDIA_PROJECTION_REQUEST_RESULT_CODE, 0);
        mResultData = intent.getParcelableExtra(MainService.EXTRA_MEDIA_PROJECTION_REQUEST_RESULT_DATA);

        startScreenCapture();

        // in case of a crash, we will be restarted by MainService
        return START_NOT_STICKY;
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

        // (Re)setup callback (we might come here several times on screen rotation)
        mMediaProjection.unregisterCallback(mMediaProjectionCallback);
        mMediaProjection.registerCallback(mMediaProjectionCallback, null);

        if (mImageReader != null)
            mImageReader.close();

        final DisplayMetrics metrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);

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
                    if (scaledWidth != MainService.vncGetFramebufferWidth() || scaledHeight != MainService.vncGetFramebufferHeight())
                        MainService.vncNewFramebuffer(scaledWidth, scaledHeight);

                    MainService.vncUpdateFramebuffer(croppedBuffer);
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
                if (w != MainService.vncGetFramebufferWidth() || scaledHeight != MainService.vncGetFramebufferHeight())
                    MainService.vncNewFramebuffer(w, scaledHeight);

                buffer.rewind();

                MainService.vncUpdateFramebuffer(buffer);
            } catch (Exception ignored) {
            }
        }, null);

        try {
            if(mVirtualDisplay == null) {
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                        scaledWidth, scaledHeight, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        mImageReader.getSurface(), null,null);
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

        // tell MainService that MediaProjection is up
        Intent intent = new Intent(MediaProjectionService.this, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_MEDIA_PROJECTION_RESULT);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(MediaProjectionService.this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(MediaProjectionService.this).getAccessKey()));
        intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_STATE, true); // on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
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
     * Get whether Media Projection is currently running.
     */
    static boolean isMediaProjectionEnabled() {
        return instance != null && instance.mMediaProjection != null;
    }

    static void togglePortraitInLandscapeWorkaround() {
        try {
            // set
            instance.mHasPortraitInLandscapeWorkaroundSet = true;
            instance.mHasPortraitInLandscapeWorkaroundApplied = !instance.mHasPortraitInLandscapeWorkaroundApplied;
            Log.d(TAG, "togglePortraitInLandscapeWorkaround: now " + instance.mHasPortraitInLandscapeWorkaroundApplied);
            // apply
            instance.startScreenCapture();
        }
        catch (NullPointerException e) {
            //unused
        }

    }

}
