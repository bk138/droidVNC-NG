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
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;

public class MainService extends Service {

    private static final String TAG = "MainService";
    private static final int NOTIFICATION_ID = 11;
    final static String ACTION_START = "start";
    final static String ACTION_STOP = "stop";
    final static String ACTION_HANDLE_RESULT = "handle_result";
    final static String EXTRA_RESULTDATA = "resultdata";
    final static String EXTRA_RESULTCODE = "resultcode";


    private int mResultCode;
    private Intent mResultData;
    private ImageReader mImageReader;
    private int mWidth;
    private int mHeight;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mMediaProjectionManager;


    static {
        // order is important here
        System.loadLibrary("vncserver");
        System.loadLibrary("droidvnc-ng");
    }

    private native boolean vncStartServer(int width, int height);
    private native void vncNewFramebuffer(int width, int height);
    private native boolean vncUpdateFramebuffer(ByteBuffer buf);

    public MainService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

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
            Start the server
         */
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        vncStartServer(displayMetrics.widthPixels, displayMetrics.heightPixels);
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(displayMetrics);

        Log.d(TAG, "onConfigurationChanged: width: " + displayMetrics.widthPixels + " height: " + displayMetrics.heightPixels);

        setUpVirtualDisplay();
        vncNewFramebuffer(displayMetrics.widthPixels, displayMetrics.heightPixels);
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        tearDownMediaProjection();
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d(TAG, "onStartCommand");

        if(ACTION_HANDLE_RESULT.equals(intent.getAction())) {
            mResultCode = intent.getIntExtra(EXTRA_RESULTCODE, 0);
            mResultData = intent.getParcelableExtra(EXTRA_RESULTDATA);
            setUpMediaProjection();
            setUpVirtualDisplay();
        }

        if(ACTION_START.equals(intent.getAction())) {
            Log.d(TAG, "onStartCommand: start");
            startScreenCapture();
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

        DisplayMetrics metrics = new DisplayMetrics();
        Point size = new Point();
        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        wm.getDefaultDisplay().getSize(size);

        mWidth = size.x;
        mHeight = size.y;

        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.d(TAG, "image available");
                Image image = imageReader.acquireLatestImage();

                if(image == null)
                    return;

                final Image.Plane[] planes = image.getPlanes();
                final ByteBuffer buffer = planes[0].getBuffer();

                vncUpdateFramebuffer(buffer);

                image.close();
            }
        }, null);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(getString(R.string.app_name),
                mWidth, mHeight, metrics.densityDpi,
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

}
