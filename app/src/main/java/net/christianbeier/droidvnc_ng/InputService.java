package net.christianbeier.droidvnc_ng;

/*
 * DroidVNC-NG InputService that binds to the Android a11y API and posts input events sent by the native backend to Android.
 *
 * Its original version was copied from https://github.com/anyvnc/anyvnc/blob/master/apps/ui/android/src/com/anyvnc/AnyVncAccessibilityService.java at
 * f32015d9d29d2d022217f52a99f676ace90cc29e.
 *
 * Original author is Tobias Junghans <tobydox@veyon.io>
 *
 * Licensed under GPL-2.0 as per https://github.com/anyvnc/anyvnc/blob/master/COPYING.
 *
 * Swipe fixes and gesture handling by Christian Beier <info@christianbeier.net>.
 *
 */

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;

public class InputService extends AccessibilityService {

	/**
	 * This globally tracks gesture completion status and is _not_ per gesture.
	 */
	private static class GestureCallback extends AccessibilityService.GestureResultCallback {
		private boolean mCompleted = true; // initially true so we can actually dispatch something

		@Override
		public synchronized void onCompleted(GestureDescription gestureDescription) {
			mCompleted = true;
		}

		@Override
		public synchronized void onCancelled(GestureDescription gestureDescription) {
			mCompleted = true;
		}
	}

	private static final String TAG = "InputService";

	private static InputService instance;

	private Handler mMainHandler;

	private boolean mIsButtonOneDown;
	private Path mPath;
	private long mLastGestureStartTime;

	private boolean mIsKeyCtrlDown;
	private boolean mIsKeyAltDown;
	private boolean mIsKeyShiftDown;
	private boolean mIsKeyDelDown;
	private boolean mIsKeyEscDown;

	private float mScaling;

	private final GestureCallback mGestureCallback = new GestureCallback();


	@Override
	public void onAccessibilityEvent( AccessibilityEvent event ) { }

	@Override
	public void onInterrupt() { }

	@Override
	public void onServiceConnected()
	{
		super.onServiceConnected();
		instance = this;
		mMainHandler = new Handler(instance.getMainLooper());
		Log.i(TAG, "onServiceConnected");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;
		Log.i(TAG, "onDestroy");
	}

	public static boolean isEnabled()
	{
		return instance != null;
	}

	/**
	 * Set scaling factor that's applied to incoming pointer events by dividing coordinates by
	 * the given factor.
	 * @param scaling The scaling factor as a real number.
	 * @return Whether scaling was applied or not.
	 */
	public static boolean setScaling(float scaling) {
		try {
			instance.mScaling = scaling;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@SuppressWarnings("unused")
	public static void onPointerEvent(int buttonMask, int x, int y, long client) {

		try {
			x /= instance.mScaling;
			y /= instance.mScaling;

			/*
			    left mouse button
			 */

			// down, was up
			if ((buttonMask & (1 << 0)) != 0 && !instance.mIsButtonOneDown) {
				instance.mIsButtonOneDown = true;
				instance.startGesture(x, y);
			}

			// down, was down
			if ((buttonMask & (1 << 0)) != 0 && instance.mIsButtonOneDown) {
				instance.continueGesture(x, y);
			}

			// up, was down
			if ((buttonMask & (1 << 0)) == 0 && instance.mIsButtonOneDown) {
				instance.mIsButtonOneDown = false;
				instance.endGesture(x, y);
			}


			// right mouse button
			if ((buttonMask & (1 << 2)) != 0) {
				instance.longPress(x, y);
			}

			// scroll up
			if ((buttonMask & (1 << 3)) != 0) {

				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(x, y, -displayMetrics.heightPixels / 2);
			}

			// scroll down
			if ((buttonMask & (1 << 4)) != 0) {

				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(x, y, displayMetrics.heightPixels / 2);
			}
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onPointerEvent: failed: " + Log.getStackTraceString(e));
		}
	}

	public static void onKeyEvent(int down, long keysym, long client) {
		Log.d(TAG, "onKeyEvent: keysym " + keysym + " down " + down + " by client " + client);

		/*
			Special key handling.
		 */
		try {
			/*
				Save states of some keys for combo handling.
			 */
			if(keysym == 0xFFE3)
				instance.mIsKeyCtrlDown = down != 0;

			if(keysym == 0xFFE9 || keysym == 0xFF7E) // MacOS clients send Alt as 0xFF7E
				instance.mIsKeyAltDown = down != 0;

			if(keysym == 0xFFE1)
				instance.mIsKeyShiftDown = down != 0;

			if(keysym == 0xFFFF)
				instance.mIsKeyDelDown = down != 0;

			if(keysym == 0xFF1B)
				instance.mIsKeyEscDown = down != 0;

			/*
				Ctrl-Alt-Del combo.
		 	*/
			if(instance.mIsKeyCtrlDown && instance.mIsKeyAltDown && instance.mIsKeyDelDown) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Alt-Del");
				instance.mMainHandler.post(MainService::togglePortraitInLandscapeWorkaround);
			}

			/*
				Ctrl-Shift-Esc combo.
		 	*/
			if(instance.mIsKeyCtrlDown && instance.mIsKeyShiftDown && instance.mIsKeyEscDown) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Shift-Esc");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
			}

			/*
				Home/Pos1
		 	*/
			if (keysym == 0xFF50 && down != 0) {
				Log.i(TAG, "onKeyEvent: got Home/Pos1");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
			}

			/*
				Esc
			 */
			if(keysym == 0xFF1B && down != 0)  {
				Log.i(TAG, "onKeyEvent: got Home/Pos1");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
			}

		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onKeyEvent: failed: " + e);
		}
	}

	public static void onCutText(String text, long client) {
		Log.d(TAG, "onCutText: text '" + text + "' by client " + client);

		try {
			instance.mMainHandler.post(() -> ((ClipboardManager) instance.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(text, text)));
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onCutText: failed: " + e);
		}
	}

	private void startGesture(int x, int y) {
		mPath = new Path();
		mPath.moveTo( x, y );
		mLastGestureStartTime = System.currentTimeMillis();
	}

	private void continueGesture(int x, int y) {
		mPath.lineTo( x, y );
	}

	private void endGesture(int x, int y) {
		mPath.lineTo( x, y );
		long duration = System.currentTimeMillis() - mLastGestureStartTime;
		// gesture ended very very shortly after start (< 1ms). make it 1ms to get dispatched to the system
		if (duration == 0) duration = 1;
		GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription( mPath, 0, duration);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		builder.addStroke(stroke);
		dispatchGesture(builder.build(), null, null);
	}


	private  void longPress( int x, int y )
	{
			dispatchGesture( createClick( x, y, ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout()), null, null );
	}

	private void scroll( int x, int y, int scrollAmount )
	{
			/*
			   Ignore if another gesture is still ongoing. Especially true for scroll events:
			   These mouse button 4,5 events come per each virtual scroll wheel click, an incoming
			   event would cancel the preceding one, only actually scrolling when the user stopped
			   scrolling.
			 */
			if(!mGestureCallback.mCompleted)
				return;

			mGestureCallback.mCompleted = false;
			dispatchGesture(createSwipe(x, y, x, y - scrollAmount, ViewConfiguration.getScrollDefaultDelay()), mGestureCallback, null);
	}

	private static GestureDescription createClick( int x, int y, int duration )
	{
		Path clickPath = new Path();
		clickPath.moveTo( x, y );
		GestureDescription.StrokeDescription clickStroke = new GestureDescription.StrokeDescription( clickPath, 0, duration );
		GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
		clickBuilder.addStroke( clickStroke );
		return clickBuilder.build();
	}

	private static GestureDescription createSwipe( int x1, int y1, int x2, int y2, int duration )
	{
		Path swipePath = new Path();

		x1 = Math.max(x1, 0);
		y1 = Math.max(y1, 0);
		x2 = Math.max(x2, 0);
		y2 = Math.max(y2, 0);

		swipePath.moveTo( x1, y1 );
		swipePath.lineTo( x2, y2 );
		GestureDescription.StrokeDescription swipeStroke = new GestureDescription.StrokeDescription( swipePath, 0, duration );
		GestureDescription.Builder swipeBuilder = new GestureDescription.Builder();
		swipeBuilder.addStroke( swipeStroke );
		return swipeBuilder.build();
	}
}
