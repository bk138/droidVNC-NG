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

package net.christianbeier.droidvnc_ng.capture.mediaprojection;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.christianbeier.droidvnc_ng.Constants;
import net.christianbeier.droidvnc_ng.Defaults;
import net.christianbeier.droidvnc_ng.MainService;
import net.christianbeier.droidvnc_ng.R;
import net.christianbeier.droidvnc_ng.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;

public class MediaProjectionService extends Service {

    private static final String TAG = "MediaProjectionService";

    // log the first capture failure and then only every Nth one to not flood logcat
    private static final int CAPTURE_FAILURE_LOG_INTERVAL = 1000;
    // part of the message of the UnsupportedOperationException ImageReader throws (API 29 to 35 at least)
    // when the producer's buffer format does not match the RGBA_8888 we asked for
    private static final String FORMAT_MISMATCH_MESSAGE = "doesn't match the ImageReader's configured buffer format";
    // give up on the opaque ImageReader after this many consecutive images that could not be copied
    private static final int OPAQUE_READER_MAX_CONSECUTIVE_FAILURES = 30;
    private static final long OPAQUE_READER_FENCE_TIMEOUT_MS = 100;
    // error codes of MainService.vncCopyHardwareBuffer(), keep in sync with droidvnc-ng.c
    private static final int COPY_ERROR_UNAVAILABLE = -1;
    private static final int COPY_ERROR_ARGUMENTS = -2;

    private int mResultCode;
    private Intent mResultData;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mMediaProjectionCallback;
    private MediaProjectionManager mMediaProjectionManager;
    private Handler mMainHandler;

    private boolean mHasPortraitInLandscapeWorkaroundApplied;
    private boolean mHasPortraitInLandscapeWorkaroundSet;

    /*
        Capture diagnostics and opaque ImageReader state. If the producer hands out buffers in another
        format than the RGBA_8888 requested (seen with BGRA_8888 on Bliss OS 12 in VirtualBox), the
        RGBA_8888 ImageReader rejects every one of them. We then switch once to an opaque
        (ImageFormat.PRIVATE) ImageReader, which accepts any format, and copy its buffers natively.
        If that does not work either, we stop MediaProjection so MainService uses the fallback.
     */
    private int mCaptureFailureCount;
    private boolean mHasLoggedImageLayout;
    private boolean mUseOpaqueReader;
    private boolean mOpaqueReaderFailed;
    private int mOpaqueReaderConsecutiveFailures;
    private int mOpaqueReaderLastBufferFormat;
    private ByteBuffer mOpaqueReaderFrame;

    private static MediaProjectionService instance;


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        instance = this;

        mMainHandler = new Handler(Looper.getMainLooper());

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
            } catch (Exception e) {
                Log.e(TAG, "Failed to start", e);
                // cancel framework timer waiting for startForeground()
                stopSelf();
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
                    postResult(false);
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

        mMainHandler.removeCallbacksAndMessages(null);

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

    private void startScreenCapture() {

        // init case
        if(mMediaProjection == null)
            try {
                mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
                Objects.requireNonNull(mMediaProjection).registerCallback(mMediaProjectionCallback, null);
            } catch (SecurityException e) {
                Log.w(TAG, "startScreenCapture: got SecurityException", e);
                // tell MainService, it will take care of stopping us and maybe use a fallback
                postResult(false);
                return;
            } catch (NullPointerException e) {
                Log.e(TAG, "startScreenCapture: did not get a media projection, probably user denied");
                return;
            }

        // restart case
        if (mImageReader != null) {
            //  Important: detach image reader's surface from virtual display before closing
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(null);
            }
            mImageReader.close();
        }

        // the reader created below keeps its kind for its whole lifetime
        final boolean useOpaqueReader = mUseOpaqueReader;
        mHasLoggedImageLayout = false;
        mOpaqueReaderConsecutiveFailures = 0;
        mOpaqueReaderLastBufferFormat = 0;

        final DisplayMetrics metrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);

        // apply selected scaling
        float scaling = PreferenceManager.getDefaultSharedPreferences(this).getFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, new Defaults(this).getScaling());
        int scaledWidth = (int) (metrics.widthPixels * scaling);
        int scaledHeight = (int) (metrics.heightPixels * scaling);

        // only set this by detecting quirky hardware if the user has not set manually
        if(!mHasPortraitInLandscapeWorkaroundSet && Utils.hasPortraitInLandscapeQuirk() && metrics.widthPixels > 800) {
            Log.w(TAG, "detected >10in rk3288 applying workaround for portrait-in-landscape quirk");
            mHasPortraitInLandscapeWorkaroundApplied = true;
        }

        // use workaround if flag set and in actual portrait mode
        if(mHasPortraitInLandscapeWorkaroundApplied && scaledWidth < scaledHeight) {

            final float portraitInsideLandscapeScaleFactor = (float)scaledWidth/scaledHeight;

            // width and height are swapped here
            final int quirkyLandscapeWidth = (int)((float)scaledHeight/portraitInsideLandscapeScaleFactor);
            final int quirkyLandscapeHeight = (int)((float)scaledWidth/portraitInsideLandscapeScaleFactor);

            mImageReader = newImageReader(quirkyLandscapeWidth, quirkyLandscapeHeight, useOpaqueReader);
            mImageReader.setOnImageAvailableListener(imageReader -> {
                try (Image image = imageReader.acquireLatestImage()) {

                    if (image == null)
                        return;

                    final ByteBuffer buffer;
                    final int pixelStride;
                    final int rowStride;
                    if (useOpaqueReader) {
                        // contiguous RGBA_8888 copy of the image, so rowStride = width * 4 bytes per pixel
                        buffer = copyOpaqueImage(image, quirkyLandscapeWidth, quirkyLandscapeHeight);
                        if (buffer == null)
                            return;
                        pixelStride = 4;
                        rowStride = quirkyLandscapeWidth * 4;
                    } else {
                        if (!checkImageLayout(image, quirkyLandscapeWidth, quirkyLandscapeHeight))
                            return;
                        final Image.Plane[] planes = image.getPlanes();
                        buffer = planes[0].getBuffer();
                        pixelStride = planes[0].getPixelStride();
                        rowStride = planes[0].getRowStride();
                    }
                    int rowPadding = rowStride - pixelStride * quirkyLandscapeWidth;
                    int w = quirkyLandscapeWidth + rowPadding / pixelStride;

                    // create destination Bitmap
                    Bitmap dest = Bitmap.createBitmap(w, quirkyLandscapeHeight, Bitmap.Config.ARGB_8888);

                    // copy landscape buffer to dest bitmap
                    buffer.rewind();
                    dest.copyPixelsFromBuffer(buffer);

                    // get the portrait portion that's in the center of the landscape bitmap
                    Bitmap croppedDest = Bitmap.createBitmap(dest, quirkyLandscapeWidth / 2 - scaledWidth / 2, 0, scaledWidth, scaledHeight);
                    dest.recycle();

                    ByteBuffer croppedBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
                    croppedDest.copyPixelsToBuffer(croppedBuffer);
                    croppedDest.recycle();

                    // if needed, setup a new VNC framebuffer that matches the new buffer's dimensions
                    if (scaledWidth != MainService.vncGetFramebufferWidth() || scaledHeight != MainService.vncGetFramebufferHeight())
                        MainService.vncNewFramebuffer(scaledWidth, scaledHeight);

                    // croppedBuffer is contiguous (no padding), so rowStride = width * 4 bytes per pixel
                    MainService.vncUpdateFramebuffer(croppedBuffer, scaledWidth * 4);
                } catch (Exception e) {
                    handleCaptureException(e, useOpaqueReader);
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
                Log.w(TAG, "startScreenCapture: got SecurityException", e);
                // tell MainService, it will take care of stopping us and maybe use a fallback
                postResult(false);
                return;
            }

            // tell MainService that MediaProjection is up
            postResult(true);
            return;
        }

        /*
            This is the default behaviour.
         */
        mImageReader = newImageReader(scaledWidth, scaledHeight, useOpaqueReader);
        mImageReader.setOnImageAvailableListener(imageReader -> {
            try (Image image = imageReader.acquireLatestImage()) {

                if (image == null)
                    return;

                final ByteBuffer buffer;
                final int rowStride;
                if (useOpaqueReader) {
                    // contiguous RGBA_8888 copy of the image, so rowStride = width * 4 bytes per pixel
                    buffer = copyOpaqueImage(image, scaledWidth, scaledHeight);
                    if (buffer == null)
                        return;
                    rowStride = scaledWidth * 4;
                } else {
                    if (!checkImageLayout(image, scaledWidth, scaledHeight))
                        return;
                    final Image.Plane[] planes = image.getPlanes();
                    buffer = planes[0].getBuffer();
                    rowStride = planes[0].getRowStride();
                }

                // if needed, set up a new VNC framebuffer that matches the actual image dimensions
                if (scaledWidth != MainService.vncGetFramebufferWidth() || scaledHeight != MainService.vncGetFramebufferHeight())
                    MainService.vncNewFramebuffer(scaledWidth, scaledHeight);

                buffer.rewind();

                MainService.vncUpdateFramebuffer(buffer, rowStride);
            } catch (Exception e) {
                handleCaptureException(e, useOpaqueReader);
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
            Log.w(TAG, "startScreenCapture: got SecurityException", e);
            // tell MainService, it will take care of stopping us and maybe use a fallback
            postResult(false);
            return;
        }

        // tell MainService that MediaProjection is up
        postResult(true);
    }

    /**
     * Creates the ImageReader the virtual display renders into: the original RGBA_8888 one or, if
     * requested and possible, an opaque one whose buffers are CPU-readable for vncCopyHardwareBuffer().
     * Opaque ImageReaders do not check the producer's buffer format.
     */
    private static ImageReader newImageReader(int width, int height, boolean opaque) {
        if (opaque && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 2, HardwareBuffer.USAGE_CPU_READ_OFTEN);
        }
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
    }

    /**
     * Checks an RGBA_8888 image's plane before its pixels get copied: the copy reads width * 4 bytes
     * from each of height rows that are rowStride bytes apart.
     */
    private boolean checkImageLayout(Image image, int width, int height) {
        final Image.Plane plane = image.getPlanes()[0];
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        final long capacity = plane.getBuffer().capacity();

        if (!mHasLoggedImageLayout) {
            Log.i(TAG, "checkImageLayout: image " + image.getWidth() + "x" + image.getHeight() + " (expected " + width + "x" + height + ")"
                    + ", pixelStride " + pixelStride + ", rowStride " + rowStride + ", capacity " + capacity);
            mHasLoggedImageLayout = true;
        }

        if (pixelStride != 4 || rowStride < width * 4 || capacity < (long) rowStride * (height - 1) + width * 4L) {
            logCaptureFailure("unexpected image layout for " + width + "x" + height + ": pixelStride " + pixelStride
                    + ", rowStride " + rowStride + ", capacity " + capacity, null);
            return false;
        }
        return true;
    }

    /**
     * Copies an opaque image's buffer into a contiguous RGBA_8888 buffer of width x height.
     * @return that buffer, valid until the next call, or null if the image could not be copied.
     */
    private ByteBuffer copyOpaqueImage(Image image, int width, int height) {
        // wait until the producer has finished writing the buffer. No public API for this below API 33.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !awaitImageFence(image))
            return null;

        final int frameSize = width * height * 4;
        if (mOpaqueReaderFrame == null || mOpaqueReaderFrame.capacity() != frameSize) {
            mOpaqueReaderFrame = ByteBuffer.allocateDirect(frameSize);
        }

        int result;
        try (HardwareBuffer hardwareBuffer = image.getHardwareBuffer()) {
            result = hardwareBuffer != null ? MainService.vncCopyHardwareBuffer(hardwareBuffer, mOpaqueReaderFrame, width, height) : COPY_ERROR_ARGUMENTS;
        }

        if (result < 0) {
            mOpaqueReaderConsecutiveFailures++;
            logCaptureFailure("opaque reader: could not copy image, error " + result, null);
            // no AHardwareBuffer API or a call that can never work is not worth retrying
            if (result == COPY_ERROR_UNAVAILABLE || result == COPY_ERROR_ARGUMENTS
                    || mOpaqueReaderConsecutiveFailures >= OPAQUE_READER_MAX_CONSECUTIVE_FAILURES) {
                fallBackFromOpaqueReader("error " + result + " after " + mOpaqueReaderConsecutiveFailures + " consecutive failure(s)");
            }
            return null;
        }

        mOpaqueReaderConsecutiveFailures = 0;
        if (result != mOpaqueReaderLastBufferFormat) {
            Log.i(TAG, "copyOpaqueImage: buffer format now 0x" + Integer.toHexString(result) + ", " + width + "x" + height);
            mOpaqueReaderLastBufferFormat = result;
        }
        return mOpaqueReaderFrame;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private boolean awaitImageFence(Image image) {
        try (SyncFence fence = image.getFence()) {
            if (fence.isValid() && !fence.await(Duration.ofMillis(OPAQUE_READER_FENCE_TIMEOUT_MS))) {
                logCaptureFailure("opaque reader: timed out waiting for buffer fence", null);
                return false;
            }
            return true;
        } catch (IOException e) {
            logCaptureFailure("opaque reader: could not get buffer fence", e);
            return false;
        }
    }

    private void handleCaptureException(Exception e, boolean usingOpaqueReader) {
        if (!usingOpaqueReader
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && e instanceof UnsupportedOperationException
                && e.getMessage() != null && e.getMessage().contains(FORMAT_MISMATCH_MESSAGE)) {
            switchToOpaqueReader(e);
            return;
        }
        logCaptureFailure("failed to process image", e);
    }

    /**
     * Replaces the RGBA_8888 ImageReader by an opaque one, at most once per service lifetime.
     */
    private void switchToOpaqueReader(Exception e) {
        if (mUseOpaqueReader || mOpaqueReaderFailed) {
            // already done or given up; the old reader's pending images are of no interest
            return;
        }
        Log.w(TAG, "switchToOpaqueReader: " + e.getMessage() + " Switching to an opaque ImageReader.");
        mUseOpaqueReader = true;
        // do not replace the reader from within its own listener
        mMainHandler.post(() -> {
            if (mMediaProjection != null)
                startScreenCapture();
        });
    }

    /**
     * Gives up on MediaProjection when the opaque ImageReader does not work either, so that MainService
     * uses the fallback screen capture, like when MediaProjection is stopped by the user or system.
     */
    private void fallBackFromOpaqueReader(String reason) {
        if (mOpaqueReaderFailed) {
            return;
        }
        mOpaqueReaderFailed = true;
        Log.e(TAG, "fallBackFromOpaqueReader: opaque ImageReader unusable (" + reason + "), stopping MediaProjection");
        mMainHandler.post(() -> {
            if (mMediaProjection == null)
                return;
            // we tell MainService ourselves, so keep the callback from doing it a second time
            mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            stopScreenCapture();
            if (MainService.isServerActive()) {
                // tell MainService, it will take care of stopping us and use a fallback
                postResult(false);
            }
        });
    }

    private void logCaptureFailure(String message, Exception e) {
        if (mCaptureFailureCount++ % CAPTURE_FAILURE_LOG_INTERVAL == 0)
            Log.w(TAG, "capture: " + message + " (" + mCaptureFailureCount + " failure(s) so far)", e);
    }

    private void stopScreenCapture() {
        if (mImageReader != null) {
            //  Important: detach image reader's surface from virtual display before closing
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(null);
            }
            mImageReader.close();
            mImageReader = null;
        }

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

    private void postResult(boolean isMediaProjectionEnabled) {
        Intent intent = new Intent(this, MainService.class);
        intent.setAction(MainService.ACTION_HANDLE_MEDIA_PROJECTION_RESULT);
        intent.putExtra(MainService.EXTRA_ACCESS_KEY, PreferenceManager.getDefaultSharedPreferences(this).getString(Constants.PREFS_KEY_SETTINGS_ACCESS_KEY, new Defaults(this).getAccessKey()));
        intent.putExtra(MainService.EXTRA_MEDIA_PROJECTION_STATE, isMediaProjectionEnabled);
        ContextCompat.startForegroundService(this, intent);
    }


    /**
     * Get whether Media Projection is currently running.
     */
    public static boolean isMediaProjectionEnabled() {
        return instance != null && instance.mMediaProjection != null;
    }

    public static void togglePortraitInLandscapeWorkaround() {
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
