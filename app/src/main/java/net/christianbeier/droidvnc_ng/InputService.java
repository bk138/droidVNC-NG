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
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class InputService extends AccessibilityService {

	/**
	 * This tracks gesture completion per client.
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

	/**
	 * Per-client input context.
	 */
	private static class InputContext {
		// pointer-related
		boolean isButtonOneDown;
		Path path = new Path();
		long lastGestureStartTime;
		GestureCallback gestureCallback = new GestureCallback();
		InputPointerView pointerView;
		// keyboard-related
		boolean isKeyCtrlDown;
		boolean isKeyAltDown;
		boolean isKeyShiftDown;
		boolean isKeyDelDown;
		boolean isKeyEscDown;

		private int displayId;

		int getDisplayId() {return displayId;}

		/**
		 * Sets a new display id, recreates pointer view if necessary.
		 */
		void setDisplayId(int displayId) {
			// set display id
			this.displayId = displayId;
			// and if there is a pointer, recreate it with the new display id
			if(pointerView != null) {
				pointerView.removeView();
				pointerView = new InputPointerView(
						instance,
						displayId,
						pointerView.getRed(),
						pointerView.getGreen(),
						pointerView.getBlue()
				);
				pointerView.addView();
			}
		}
	}

	private static final String TAG = "InputService";

	private static InputService instance;
	/**
        * Scaling factor that's applied to incoming pointer events by dividing coordinates by
        * the given factor.
        */
	static float scaling;
	static boolean isInputEnabled;
	private boolean mTakeScreenShots;
	private int mTakeScreenShotDelayMs = 100;

	private Handler mMainHandler;

	private final Map<Long, InputContext> mInputContexts = new ConcurrentHashMap<>();


	@Override
	public void onAccessibilityEvent( AccessibilityEvent event ) { }

	@Override
	public void onInterrupt() { }

	@Override
	public void onServiceConnected()
	{
		super.onServiceConnected();
		instance = this;
		isInputEnabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(Constants.PREFS_KEY_INPUT_LAST_ENABLED, !new Defaults(this).getViewOnly());
		scaling = PreferenceManager.getDefaultSharedPreferences(this).getFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, new Defaults(this).getScaling());
		mMainHandler = new Handler(instance.getMainLooper());
		Log.i(TAG, "onServiceConnected");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;
		Log.i(TAG, "onDestroy");
	}

	public static boolean isConnected()
	{
		return instance != null;
	}

	public static void addClient(long client, boolean withPointer) {
		// NB runs on a worker thread!
		try {
			int displayId = Display.DEFAULT_DISPLAY;
			InputContext inputContext = new InputContext();
			inputContext.setDisplayId(displayId);
			if(withPointer) {
				inputContext.pointerView = new InputPointerView(
						instance,
						displayId,
						0.4f * ((instance.mInputContexts.size() + 1) % 3),
						0.2f * ((instance.mInputContexts.size() + 1) % 5),
						1.0f * ((instance.mInputContexts.size() + 1) % 2)
				);
				// run this on UI thread (use main handler as view is not yet added)
				instance.mMainHandler.post(() -> inputContext.pointerView.addView());
			}
			instance.mInputContexts.put(client, inputContext);
		} catch (Exception e) {
			Log.e(TAG, "addClient: " + e);
		}
	}

	public static void removeClient(long client) {
		// NB runs on a worker thread!
		try {
			InputContext inputContext = instance.mInputContexts.get(client);
			if(inputContext != null && inputContext.pointerView != null) {
				// run this on UI thread
				inputContext.pointerView.post(inputContext.pointerView::removeView);
			}
			instance.mInputContexts.remove(client);
		} catch (Exception e) {
			Log.e(TAG, "removeClient: " + e);
		}
	}

	@SuppressWarnings("unused")
	public static void onPointerEvent(int buttonMask, int x, int y, long client) {

		if(!isInputEnabled) {
			return;
		}

		try {
			InputContext inputContext = instance.mInputContexts.get(client);

			if(inputContext == null) {
				throw new IllegalStateException("Client " + client + " was not added or is already removed");
			}

			x = (int) (x / scaling);
			y = (int) (y / scaling);

			/*
				draw pointer
			 */
			InputPointerView pointerView = inputContext.pointerView;
			if (pointerView != null) {
				// showing pointers is enabled
				int finalX = x;
				int finalY = y;
				pointerView.post(() -> pointerView.positionView(finalX, finalY));
			}

			/*
			    left mouse button
			 */

			// down, was up
			if ((buttonMask & (1 << 0)) != 0 && !inputContext.isButtonOneDown) {
				inputContext.isButtonOneDown = true;
				instance.startGesture(inputContext, x, y);
			}

			// down, was down
			if ((buttonMask & (1 << 0)) != 0 && inputContext.isButtonOneDown) {
				instance.continueGesture(inputContext, x, y);
			}

			// up, was down
			if ((buttonMask & (1 << 0)) == 0 && inputContext.isButtonOneDown) {
				inputContext.isButtonOneDown = false;
				instance.endGesture(inputContext, x, y);
			}


			// right mouse button
			if ((buttonMask & (1 << 2)) != 0) {
				instance.longPress(inputContext, x, y);
			}

			// scroll up
			if ((buttonMask & (1 << 3)) != 0) {

				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(inputContext, x, y, -displayMetrics.heightPixels / 2);
			}

			// scroll down
			if ((buttonMask & (1 << 4)) != 0) {

				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(inputContext, x, y, displayMetrics.heightPixels / 2);
			}
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onPointerEvent: failed: " + Log.getStackTraceString(e));
		}
	}

	public static void onKeyEvent(int down, long keysym, long client) {

		if(!isInputEnabled) {
			return;
		}

		Log.d(TAG, "onKeyEvent: keysym " + keysym + " down " + down + " by client " + client);

		/*
			Special key handling.
		 */
		try {
			InputContext inputContext = instance.mInputContexts.get(client);

			if(inputContext == null) {
				throw new IllegalStateException("Client " + client + " was not added or is already removed");
			}

			/*
				Save states of some keys for combo handling.
			 */
			if(keysym == 0xFFE3)
				inputContext.isKeyCtrlDown = down != 0;

			if(keysym == 0xFFE9 || keysym == 0xFF7E) // MacOS clients send Alt as 0xFF7E
				inputContext.isKeyAltDown = down != 0;

			if(keysym == 0xFFE1)
				inputContext.isKeyShiftDown = down != 0;

			if(keysym == 0xFFFF)
				inputContext.isKeyDelDown = down != 0;

			if(keysym == 0xFF1B)
				inputContext.isKeyEscDown = down != 0;

			/*
				Ctrl-Alt-Del combo.
		 	*/
			if(inputContext.isKeyCtrlDown && inputContext.isKeyAltDown && inputContext.isKeyDelDown) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Alt-Del");
				instance.mMainHandler.post(MediaProjectionService::togglePortraitInLandscapeWorkaround);
			}

			/*
				Ctrl-Shift-Esc combo.
		 	*/
			if(inputContext.isKeyCtrlDown && inputContext.isKeyShiftDown && inputContext.isKeyEscDown) {
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
				End
			*/
			if (keysym == 0xFF57 && down != 0) {
				Log.i(TAG, "onKeyEvent: got End");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
			}

			/*
				Esc
			 */
			if(keysym == 0xFF1B && down != 0)  {
				Log.i(TAG, "onKeyEvent: got Esc");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
			}

		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onKeyEvent: failed: " + e);
		}
	}

	public static void onCutText(String text, long client) {

		if(!isInputEnabled) {
			return;
		}

		Log.d(TAG, "onCutText: text '" + text + "' by client " + client);

		try {
			instance.mMainHandler.post(() -> {
						try {
							((ClipboardManager) instance.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(text, text));
						} catch (Exception e) {
							// some other error on main thread
							Log.e(TAG, "onCutText: failed: " + e);
						}
					}
			);
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onCutText: failed: " + e);
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.R)
	public static void takeScreenShots(boolean enable) {
		try {
			instance.mTakeScreenShots = enable;
			if (instance.mTakeScreenShots) {
				TakeScreenshotCallback callback = new TakeScreenshotCallback() {
					@Override
					public void onSuccess(@NonNull ScreenshotResult screenshot) {
						try {
							// create hardware bitmap from HardwareBuffer
							Bitmap bitmap = Bitmap.wrapHardwareBuffer(screenshot.getHardwareBuffer(), screenshot.getColorSpace());
							// create software bitmap from hardware bitmap to be able to use copyPixelsToBuffer()
							bitmap = Objects.requireNonNull(bitmap).copy(Bitmap.Config.ARGB_8888, false);
							// apply scaling. fast NOP when scaling == 1.0
							bitmap = Bitmap.createScaledBitmap(bitmap,
									(int) (bitmap.getWidth() * scaling),
									(int) (bitmap.getHeight() * scaling),
									true); // use filter as this makes text more readable, we're slow in this mode anyway

							ByteBuffer byteBuffer = ByteBuffer.allocateDirect(Objects.requireNonNull(bitmap).getByteCount());
							bitmap.copyPixelsToBuffer(byteBuffer);

							// if needed, setup a new VNC framebuffer that matches the new buffer's dimensions
							if (bitmap.getWidth() != MainService.vncGetFramebufferWidth() || bitmap.getHeight() != MainService.vncGetFramebufferHeight())
								MainService.vncNewFramebuffer(bitmap.getWidth(), bitmap.getHeight());

							MainService.vncUpdateFramebuffer(byteBuffer);

							// important, otherwise getting "A resource failed to call close." warnings from System
							screenshot.getHardwareBuffer().close();

							// further screenshots
							if (instance.mTakeScreenShots) {
								// try again later, using but not incrementing delay
								instance.mMainHandler.postDelayed(() -> instance.takeScreenshot(Display.DEFAULT_DISPLAY,
										instance.getMainExecutor(),
										this
								), instance.mTakeScreenShotDelayMs);
							} else {
								Log.d(TAG, "takeScreenShots: stop");
							}
						} catch (Exception e) {
							Log.e(TAG, "takeScreenShots: onSuccess exception " + e);
						}
					}

					@Override
					public void onFailure(int errorCode) {
						try {
							if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && instance.mTakeScreenShots) {
								// try again later, incrementing delay
								instance.mMainHandler.postDelayed(() -> instance.takeScreenshot(Display.DEFAULT_DISPLAY,
										instance.getMainExecutor(),
										this
								), instance.mTakeScreenShotDelayMs += 50);
								Log.w(TAG, "takeScreenShots: onFailure with ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT - upped delay to " + instance.mTakeScreenShotDelayMs);
								return;
							}
							Log.e(TAG, "takeScreenShots: onFailure with error code " + errorCode);
							instance.mTakeScreenShots = false;
						} catch (Exception e) {
							Log.e(TAG, "takeScreenShots: onFailure exception " + e);
						}
					}
				};

				// first screenshot
				Log.d(TAG, "takeScreenShots: start");
				instance.takeScreenshot(Display.DEFAULT_DISPLAY,
						instance.getMainExecutor(),
						callback
				);
			}
		} catch (Exception e) {
			Log.e(TAG, "takeScreenShots: exception " + e);
		}
	}

	public static boolean isTakingScreenShots() {
		try {
			return instance.mTakeScreenShots;
		} catch (Exception ignored) {
			return false;
		}
	}

	private void startGesture(InputContext inputContext, int x, int y) {
		inputContext.path.reset();
		inputContext.path.moveTo( x, y );
		inputContext.lastGestureStartTime = System.currentTimeMillis();
	}

	private void continueGesture(InputContext inputContext, int x, int y) {
		inputContext.path.lineTo( x, y );
	}

	private void endGesture(InputContext inputContext, int x, int y) {
		inputContext.path.lineTo( x, y );
		long duration = System.currentTimeMillis() - inputContext.lastGestureStartTime;
		// gesture ended very very shortly after start (< 1ms). make it 1ms to get dispatched to the system
		if (duration == 0) duration = 1;
		GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription( inputContext.path, 0, duration);
		GestureDescription.Builder builder = new GestureDescription.Builder();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.setDisplayId(inputContext.getDisplayId());
		}
		builder.addStroke(stroke);
		// Docs says: Any gestures currently in progress, whether from the user, this service, or another service, will be cancelled.
		// But at least on API level 32, setting different display ids with the builder allows for parallel input.
		dispatchGesture(builder.build(), null, null);
	}


	private  void longPress(InputContext inputContext, int x, int y )
	{
			dispatchGesture( createClick(inputContext, x, y, ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout()), null, null );
	}

	private void scroll(InputContext inputContext, int x, int y, int scrollAmount )
	{
			/*
			   Ignore if another gesture is still ongoing. Especially true for scroll events:
			   These mouse button 4,5 events come per each virtual scroll wheel click, an incoming
			   event would cancel the preceding one, only actually scrolling when the user stopped
			   scrolling.
			 */
			if(!inputContext.gestureCallback.mCompleted)
				return;

			inputContext.gestureCallback.mCompleted = false;
			dispatchGesture(createSwipe(inputContext, x, y, x, y - scrollAmount, ViewConfiguration.getScrollDefaultDelay()), inputContext.gestureCallback, null);
	}

	private static GestureDescription createClick(InputContext inputContext,  int x, int y, int duration )
	{
		Path clickPath = new Path();
		clickPath.moveTo( x, y );
		GestureDescription.StrokeDescription clickStroke = new GestureDescription.StrokeDescription( clickPath, 0, duration );
		GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			clickBuilder.setDisplayId(inputContext.getDisplayId());
		}
		clickBuilder.addStroke( clickStroke );
		return clickBuilder.build();
	}

	private static GestureDescription createSwipe(InputContext inputContext, int x1, int y1, int x2, int y2, int duration )
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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			swipeBuilder.setDisplayId(inputContext.getDisplayId());
		}
		swipeBuilder.addStroke( swipeStroke );
		return swipeBuilder.build();
	}
}
