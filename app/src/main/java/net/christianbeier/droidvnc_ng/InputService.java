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
 * Swipe fixes, gesture handling, cursor handling and screen-shooter by Christian Beier <info@christianbeier.net>.
 *
 */

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;
import android.view.accessibility.AccessibilityNodeInfo;

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
		GestureDescription.StrokeDescription stroke;
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

	private TakeScreenshotCallback mTakeScreenShotCallback;
	private static final int TAKE_SCREEN_SHOT_DELAY_MS_INITIAL = 100;
	private int mTakeScreenShotDelayMs = TAKE_SCREEN_SHOT_DELAY_MS_INITIAL;

	private Handler mMainHandler;

	private final Map<Long, InputContext> mInputContexts = new ConcurrentHashMap<>();
	/**
	 * System keyboard input foci, display-specific starting on Android 10 (really 11 in higher layers),
	 * see <a href="https://source.android.com/docs/core/display/multi_display/displays#focus">Android docs</a>
	 */
	private final Map<Integer, AccessibilityNodeInfo> mKeyboardFocusNodes = new ConcurrentHashMap<>();


	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		try {
			Log.d(TAG, "onAccessibilityEvent: " + event);

			int displayId;
			if (Build.VERSION.SDK_INT >= 30) {
				// be display-specific
				displayId = Objects.requireNonNull(event.getSource()).getWindow().getDisplayId();
			} else {
				// assume default display
				displayId = Display.DEFAULT_DISPLAY;
			}

			// recycle old node if there
			AccessibilityNodeInfo previousFocusNode = mKeyboardFocusNodes.get(displayId);
			try {
				Objects.requireNonNull(previousFocusNode).recycle();
			} catch (Exception e) {
				// can be NullPointerException or IllegalStateException("Already in the pool!")
				Log.i(TAG, "onAccessibilityEvent: could not recycle previousFocusNode: " + e);
			}

			// and put new one
			mKeyboardFocusNodes.put(displayId, event.getSource());

			// send any text selection over to the client as cut text
			if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
					&& Objects.requireNonNull(event.getSource()).getTextSelectionStart() != event.getSource().getTextSelectionEnd()) {
				CharSequence selection = event.getSource().getText().subSequence(event.getSource().getTextSelectionStart(), event.getSource().getTextSelectionEnd());
				MainService.vncSendCutText(selection.toString());
			}
		} catch (Exception e) {
			Log.e(TAG, "onAccessibilityEvent: " + Log.getStackTraceString(e));
		}
	}

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

	/**
	 * If the service is already connected, request is run immediately.
	 * If the service is not yet connected, the function schedules a sensible
	 * number of retries on the main thread Handler before returning.
	 * @param request code to be run
	 */
	public static void runWhenConnected(Runnable request) {
		if (isConnected()) {
			Log.i(TAG, "runWhenConnected: already connected, running request");
			request.run();
		} else {
			Log.w(TAG, "runWhenConnected: not yet connected, retrying");
			final Handler handler = new Handler(Looper.getMainLooper());
			final int[] retries = {0};
			final int MAX_RETRIES = 5;

			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					retries[0]++;
					if (isConnected()) {
						Log.i(TAG, "runWhenConnected: connected after " + retries[0] + " retries, running request");
						request.run();
					} else {
						if (retries[0] < MAX_RETRIES) {
							Log.w(TAG, "runWhenConnected: not yet connected after " + retries[0] + " of " + MAX_RETRIES + " retries");
							handler.postDelayed(this, 1000);
						} else {
							Log.e(TAG, "runWhenConnected: not connected after " + retries[0] + " of " + MAX_RETRIES + " retries");
						}
					}
				}
			}, 1000);
		}
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
				instance.startStroke(inputContext, x, y);
			}

			// down, was down
			if ((buttonMask & (1 << 0)) != 0 && inputContext.isButtonOneDown) {
				instance.continueStroke(inputContext, x, y);
			}

			// up, was down
			if ((buttonMask & (1 << 0)) == 0 && inputContext.isButtonOneDown) {
				inputContext.isButtonOneDown = false;
				instance.endStroke(inputContext, x, y);
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

		Log.d(TAG, "onKeyEvent: keysym 0x" + Long.toHexString(keysym) + " down " + down + " by client " + client);

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

			/*
				Ctrl-Alt-PageUp
			 */
			if(inputContext.isKeyCtrlDown && inputContext.isKeyAltDown && keysym == 0xff55 && down != 0) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Alt-PageUp");
				((AudioManager) instance.getSystemService(Context.AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
			}

			/*
				Ctrl-Alt-PageDown
			 */
			if(inputContext.isKeyCtrlDown && inputContext.isKeyAltDown && keysym == 0xff56 && down != 0) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Alt-PageDown");
				((AudioManager) instance.getSystemService(Context.AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
			}

			/*
				API 33+ way of sending key events. This is preferred there as it also works with non-
				AccessibilityNode-widgets.
				We are only using this on API level 34+ though as level 33 does not have
				https://cs.android.com/android/_/android/platform/frameworks/base/+/89025ff06e71f9e37b4bb6f94e43ff50f246d581
				applied.
			 */
			if (Build.VERSION.SDK_INT >= 34) {
				// If this fails, it falls back to the usual AccessibilityNodeInfo approach
				try {
					/*
						Translate RFB/X11 key sym to Android key code.
					 */
					int keyCode = KeyEvent.KEYCODE_UNKNOWN;

					/*
						First, non-character keys
					 */
					//  Left/Right
					if (keysym == 0xff51) keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
					if (keysym == 0xff53) keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
					//  Up/Down - the AccessibilityNodeInfo approach does not have this
					if (keysym == 0xff52) keyCode = KeyEvent.KEYCODE_DPAD_UP;
					if (keysym == 0xff54) keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
					// Backspace/Delete
					if (keysym == 0xff08) keyCode = KeyEvent.KEYCODE_DEL;
					if (keysym == 0xffff) keyCode = KeyEvent.KEYCODE_FORWARD_DEL;
					// Insert
					if (keysym == 0xff63) keyCode = KeyEvent.KEYCODE_INSERT;
					// Enter
					if (keysym == 0xff0d) keyCode = KeyEvent.KEYCODE_ENTER;
					// Tab - the AccessibilityNodeInfo approach does not have this
					if (keysym == 0xff09) keyCode = KeyEvent.KEYCODE_TAB;
					// PageUp/PageDown - the AccessibilityNodeInfo approach does not have this
					if (keysym == 0xff55) keyCode = KeyEvent.KEYCODE_PAGE_UP;
					if (keysym == 0xff56) keyCode = KeyEvent.KEYCODE_PAGE_DOWN;
					// Function keys - the AccessibilityNodeInfo approach does not have this
					if (keysym == 0xffbe) keyCode = KeyEvent.KEYCODE_F1;
					if (keysym == 0xffbf) keyCode = KeyEvent.KEYCODE_F2;
					if (keysym == 0xffc0) keyCode = KeyEvent.KEYCODE_F3;
					if (keysym == 0xffc1) keyCode = KeyEvent.KEYCODE_F4;
					if (keysym == 0xffc2) keyCode = KeyEvent.KEYCODE_F5;
					if (keysym == 0xffc3) keyCode = KeyEvent.KEYCODE_F6;
					if (keysym == 0xffc4) keyCode = KeyEvent.KEYCODE_F7;
					if (keysym == 0xffc5) keyCode = KeyEvent.KEYCODE_F8;
					if (keysym == 0xffc6) keyCode = KeyEvent.KEYCODE_F9;
					if (keysym == 0xffc7) keyCode = KeyEvent.KEYCODE_F10;
					if (keysym == 0xffc8) keyCode = KeyEvent.KEYCODE_F11;
					if (keysym == 0xffc9) keyCode = KeyEvent.KEYCODE_F12;

					/*
					    ASCII input, we use a translation to KeyEvents w/ keycodes as some apps
					    don't eat the ones with characters only.
					    Android internally uses a US keyboard layout, so for some incoming keysyms
					    we have to generate the right output with an additional Shift operation
					    that is sometimes, but not always present on the sending side: this means
					    we don't use Shift key state from the sending side for keysyms 0x20 to 0x7e.
					 */
					boolean doShift = false;
					if (keysym == 0x20) keyCode = KeyEvent.KEYCODE_SPACE;
					if (keysym == 0x21) { keyCode = KeyEvent.KEYCODE_1; doShift = true; } // '!' is generated by '1' w/ Shift
					if (keysym == 0x22) { keyCode = KeyEvent.KEYCODE_APOSTROPHE; doShift = true; }// '"' is generated by ''' w/ Shift
					if (keysym == 0x23) keyCode = KeyEvent.KEYCODE_POUND;
					if (keysym == 0x24) { keyCode = KeyEvent.KEYCODE_4; doShift = true; } // '$' is generated by '4' w/ Shift
					if (keysym == 0x25) { keyCode = KeyEvent.KEYCODE_5; doShift = true; } // '%' is generated by '5' w/ Shift
					if (keysym == 0x26) { keyCode = KeyEvent.KEYCODE_7; doShift = true; } // '&' is generated by '7' w/ Shift
					if (keysym == 0x27) keyCode = KeyEvent.KEYCODE_APOSTROPHE;
					if (keysym == 0x28) { keyCode = KeyEvent.KEYCODE_9; doShift = true; } // '(' is generated by '9' w/ Shift
					if (keysym == 0x29) { keyCode = KeyEvent.KEYCODE_0; doShift = true; } // ')' is generated by '0' w/ Shift
					if (keysym == 0x2A) keyCode = KeyEvent.KEYCODE_STAR;
					if (keysym == 0x2B) keyCode = KeyEvent.KEYCODE_PLUS;
					if (keysym == 0x2C) keyCode = KeyEvent.KEYCODE_COMMA;
					if (keysym == 0x2D) keyCode = KeyEvent.KEYCODE_MINUS;
					if (keysym == 0x2E) keyCode = KeyEvent.KEYCODE_PERIOD;
					if (keysym == 0x2F) keyCode = KeyEvent.KEYCODE_SLASH;
					if (keysym == 0x30) keyCode = KeyEvent.KEYCODE_0;
					if (keysym == 0x31) keyCode = KeyEvent.KEYCODE_1;
					if (keysym == 0x32) keyCode = KeyEvent.KEYCODE_2;
					if (keysym == 0x33) keyCode = KeyEvent.KEYCODE_3;
					if (keysym == 0x34) keyCode = KeyEvent.KEYCODE_4;
					if (keysym == 0x35) keyCode = KeyEvent.KEYCODE_5;
					if (keysym == 0x36) keyCode = KeyEvent.KEYCODE_6;
					if (keysym == 0x37) keyCode = KeyEvent.KEYCODE_7;
					if (keysym == 0x38) keyCode = KeyEvent.KEYCODE_8;
					if (keysym == 0x39) keyCode = KeyEvent.KEYCODE_9;
					if (keysym == 0x3A) { keyCode = KeyEvent.KEYCODE_SEMICOLON; doShift = true; } // ':' is generated by ';' w/ Shift
					if (keysym == 0x3B) keyCode = KeyEvent.KEYCODE_SEMICOLON;
					if (keysym == 0x3C) { keyCode = KeyEvent.KEYCODE_COMMA; doShift = true; } // '<' is generated by ',' w/ Shift
					if (keysym == 0x3D) keyCode = KeyEvent.KEYCODE_EQUALS;
					if (keysym == 0x3E) { keyCode = KeyEvent.KEYCODE_PERIOD; doShift = true; } // '>' is generated by '.' w/ Shift
					if (keysym == 0x3F) { keyCode = KeyEvent.KEYCODE_SLASH; doShift = true; } // '?' is generated by '/' w/ Shift
					if (keysym == 0x40) keyCode = KeyEvent.KEYCODE_AT;
					if (keysym == 0x41) { keyCode = KeyEvent.KEYCODE_A; doShift = true; } // 'A' is generated by 'a' w/ Shift
					if (keysym == 0x42) { keyCode = KeyEvent.KEYCODE_B; doShift = true; } // 'B' is generated by 'b' w/ Shift
					if (keysym == 0x43) { keyCode = KeyEvent.KEYCODE_C; doShift = true; } // 'C' is generated by 'c' w/ Shift
					if (keysym == 0x44) { keyCode = KeyEvent.KEYCODE_D; doShift = true; } // 'D' is generated by 'd' w/ Shift
					if (keysym == 0x45) { keyCode = KeyEvent.KEYCODE_E; doShift = true; } // 'E' is generated by 'e' w/ Shift
					if (keysym == 0x46) { keyCode = KeyEvent.KEYCODE_F; doShift = true; } // 'F' is generated by 'f' w/ Shift
					if (keysym == 0x47) { keyCode = KeyEvent.KEYCODE_G; doShift = true; } // 'G' is generated by 'g' w/ Shift
					if (keysym == 0x48) { keyCode = KeyEvent.KEYCODE_H; doShift = true; } // 'H' is generated by 'h' w/ Shift
					if (keysym == 0x49) { keyCode = KeyEvent.KEYCODE_I; doShift = true; } // 'I' is generated by 'i' w/ Shift
					if (keysym == 0x4A) { keyCode = KeyEvent.KEYCODE_J; doShift = true; } // 'J' is generated by 'j' w/ Shift
					if (keysym == 0x4B) { keyCode = KeyEvent.KEYCODE_K; doShift = true; } // 'K' is generated by 'k' w/ Shift
					if (keysym == 0x4C) { keyCode = KeyEvent.KEYCODE_L; doShift = true; } // 'L' is generated by 'l' w/ Shift
					if (keysym == 0x4D) { keyCode = KeyEvent.KEYCODE_M; doShift = true; } // 'M' is generated by 'm' w/ Shift
					if (keysym == 0x4E) { keyCode = KeyEvent.KEYCODE_N; doShift = true; } // 'N' is generated by 'n' w/ Shift
					if (keysym == 0x4F) { keyCode = KeyEvent.KEYCODE_O; doShift = true; } // 'O' is generated by 'o' w/ Shift
					if (keysym == 0x50) { keyCode = KeyEvent.KEYCODE_P; doShift = true; } // 'P' is generated by 'p' w/ Shift
					if (keysym == 0x51) { keyCode = KeyEvent.KEYCODE_Q; doShift = true; } // 'Q' is generated by 'q' w/ Shift
					if (keysym == 0x52) { keyCode = KeyEvent.KEYCODE_R; doShift = true; } // 'R' is generated by 'r' w/ Shift
					if (keysym == 0x53) { keyCode = KeyEvent.KEYCODE_S; doShift = true; } // 'S' is generated by 's' w/ Shift
					if (keysym == 0x54) { keyCode = KeyEvent.KEYCODE_T; doShift = true; } // 'T' is generated by 't' w/ Shift
					if (keysym == 0x55) { keyCode = KeyEvent.KEYCODE_U; doShift = true; } // 'U' is generated by 'u' w/ Shift
					if (keysym == 0x56) { keyCode = KeyEvent.KEYCODE_V; doShift = true; } // 'V' is generated by 'v' w/ Shift
					if (keysym == 0x57) { keyCode = KeyEvent.KEYCODE_W; doShift = true; } // 'W' is generated by 'w' w/ Shift
					if (keysym == 0x58) { keyCode = KeyEvent.KEYCODE_X; doShift = true; } // 'X' is generated by 'x' w/ Shift
					if (keysym == 0x59) { keyCode = KeyEvent.KEYCODE_Y; doShift = true; } // 'Y' is generated by 'y' w/ Shift
					if (keysym == 0x5A) { keyCode = KeyEvent.KEYCODE_Z; doShift = true; } // 'Z' is generated by 'z' w/ Shift
					if (keysym == 0x5B) keyCode = KeyEvent.KEYCODE_LEFT_BRACKET;
					if (keysym == 0x5C) keyCode = KeyEvent.KEYCODE_BACKSLASH;
					if (keysym == 0x5D) keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET;
					if (keysym == 0x5E) { keyCode = KeyEvent.KEYCODE_6; doShift = true; } // '^' is generated by '6' w/ Shift
					if (keysym == 0x5F) { keyCode = KeyEvent.KEYCODE_MINUS; doShift = true; } // '_' is generated by '-' w/ Shift
					if (keysym == 0x60) keyCode = KeyEvent.KEYCODE_GRAVE;
					if (keysym == 0x61) keyCode = KeyEvent.KEYCODE_A;
					if (keysym == 0x62) keyCode = KeyEvent.KEYCODE_B;
					if (keysym == 0x63) keyCode = KeyEvent.KEYCODE_C;
					if (keysym == 0x64) keyCode = KeyEvent.KEYCODE_D;
					if (keysym == 0x65) keyCode = KeyEvent.KEYCODE_E;
					if (keysym == 0x66) keyCode = KeyEvent.KEYCODE_F;
					if (keysym == 0x67) keyCode = KeyEvent.KEYCODE_G;
					if (keysym == 0x68) keyCode = KeyEvent.KEYCODE_H;
					if (keysym == 0x69) keyCode = KeyEvent.KEYCODE_I;
					if (keysym == 0x6A) keyCode = KeyEvent.KEYCODE_J;
					if (keysym == 0x6B) keyCode = KeyEvent.KEYCODE_K;
					if (keysym == 0x6C) keyCode = KeyEvent.KEYCODE_L;
					if (keysym == 0x6D) keyCode = KeyEvent.KEYCODE_M;
					if (keysym == 0x6E) keyCode = KeyEvent.KEYCODE_N;
					if (keysym == 0x6F) keyCode = KeyEvent.KEYCODE_O;
					if (keysym == 0x70) keyCode = KeyEvent.KEYCODE_P;
					if (keysym == 0x71) keyCode = KeyEvent.KEYCODE_Q;
					if (keysym == 0x72) keyCode = KeyEvent.KEYCODE_R;
					if (keysym == 0x73) keyCode = KeyEvent.KEYCODE_S;
					if (keysym == 0x74) keyCode = KeyEvent.KEYCODE_T;
					if (keysym == 0x75) keyCode = KeyEvent.KEYCODE_U;
					if (keysym == 0x76) keyCode = KeyEvent.KEYCODE_V;
					if (keysym == 0x77) keyCode = KeyEvent.KEYCODE_W;
					if (keysym == 0x78) keyCode = KeyEvent.KEYCODE_X;
					if (keysym == 0x79) keyCode = KeyEvent.KEYCODE_Y;
					if (keysym == 0x7A) keyCode = KeyEvent.KEYCODE_Z;
					if (keysym == 0x7B) { keyCode = KeyEvent.KEYCODE_LEFT_BRACKET; doShift = true; } // '{' is generated by '[' w/ Shift
					if (keysym == 0x7C) { keyCode = KeyEvent.KEYCODE_BACKSLASH; doShift = true; } // '|' is generated by '\' w/ Shift
					if (keysym == 0x7D) { keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET; doShift = true; } // '}' is generated by ']' w/ Shift
					if (keysym == 0x7E) { keyCode = KeyEvent.KEYCODE_GRAVE; doShift = true; } // '~' is generated by '`' w/ Shift

					KeyEvent keyEvent = new KeyEvent(
							SystemClock.uptimeMillis(),
							SystemClock.uptimeMillis(),
							down != 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
							keyCode,
							0,
							(inputContext.isKeyAltDown ? KeyEvent.META_ALT_ON : 0) |
									(inputContext.isKeyCtrlDown ? KeyEvent.META_CTRL_ON : 0) |
									(doShift ? KeyEvent.META_SHIFT_ON : 0)
					);

					/*
						Rest of ISO-8859-1 input using KeyEvent from characters.
						API does not allow setting meta state for these.
					 */
					if (keysym >= 0xa0 && keysym <= 0xff && down != 0) {
						keyEvent = new KeyEvent(SystemClock.uptimeMillis(), Character.toString((char) keysym), 0, 0);
					}

					/*
						Send
					 */
					Objects.requireNonNull(Objects.requireNonNull(instance.getInputMethod()).getCurrentInputConnection()).sendKeyEvent(keyEvent);
					// if this succeeds, don't do the AccessibilityNodeInfo approach
					return;
				} catch (NullPointerException ignored) {
				}
			}

			/*
				Get current keyboard focus node for input context's display.
			 */
			AccessibilityNodeInfo currentFocusNode = instance.mKeyboardFocusNodes.get(inputContext.getDisplayId());
			// refresh() is important to load the represented view's current text into the node
			Objects.requireNonNull(currentFocusNode).refresh();

			/*
			   Left/Right
			 */
			if ((keysym == 0xff51 || keysym == 0xff53) && down != 0) {
				Bundle action = new Bundle();
				action.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
				action.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
				if(keysym == 0xff51)
					Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY.getId(), action);
				else
					Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY.getId(), action);
			}

			/*
			    Backspace/Delete
			    TODO: implement deletions of text selections, right now it's only 1 char at a time
			 */
			if ((keysym == 0xff08 || keysym == 0xffff) && down != 0) {
				CharSequence currentFocusText = Objects.requireNonNull(currentFocusNode).getText();
				int cursorPos = getCursorPos(currentFocusNode);

				// set new text
				String newFocusText;
				if (keysym == 0xff08) {
					// backspace
					newFocusText = String.valueOf(currentFocusText.subSequence(0, cursorPos - 1)) + currentFocusText.subSequence(cursorPos, currentFocusText.length());
				} else {
					// delete
					newFocusText = String.valueOf(currentFocusText.subSequence(0, cursorPos)) + currentFocusText.subSequence(cursorPos + 1, currentFocusText.length());
				}
				Bundle action = new Bundle();
				action.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFocusText);
				currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), action);

				// ACTION_SET_TEXT moves cursor to the end, move cursor back to where it should be
				setCursorPos(currentFocusNode, keysym == 0xff08 ? cursorPos - 1 : cursorPos);
			}

			/*
			   Insert
			 */
			if (keysym == 0xff63 && down != 0) {
				Bundle action = new Bundle();
				Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.getId(), action);
			}

			/*
			    Enter, for API level 30+
			 */
			if (keysym == 0xff0d && down != 0) {
				if (Build.VERSION.SDK_INT >= 30) {
					Bundle action = new Bundle();
					Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId(), action);
				}
			}

			/*
			    ISO-8859-1 input
			 */
			if (keysym >= 32 && keysym <= 255 && down != 0) {
				CharSequence currentFocusText = Objects.requireNonNull(currentFocusNode).getText();
				// some implementations return null for empty text, work around that
				if (currentFocusText == null)
					currentFocusText = "";

				int cursorPos = getCursorPos(currentFocusNode);

				// set new text
				String textBeforeCursor = "";
				try {
					textBeforeCursor = String.valueOf(currentFocusText.subSequence(0, cursorPos));
				} catch (IndexOutOfBoundsException ignored) {
				}
				String textAfterCursor = "";
				try {
					textAfterCursor = String.valueOf(currentFocusText.subSequence(cursorPos, currentFocusText.length()));
				} catch (IndexOutOfBoundsException ignored) {
				}
				String newFocusText = textBeforeCursor + (char) keysym + textAfterCursor;

				Bundle action = new Bundle();
				action.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFocusText);
				currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), action);

				// ACTION_SET_TEXT moves cursor to the end, move cursor back to where it should be
				setCursorPos(currentFocusNode, cursorPos > 0 ? cursorPos + 1 : 1);
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
	public static void takeScreenShots(boolean enable, int displayId) {
		try {
			if (enable) {
				instance.mTakeScreenShotCallback = new TakeScreenshotCallback() {
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
							if (instance.mTakeScreenShotCallback != null) {
								// try again later, using but not incrementing delay
								instance.mMainHandler.postDelayed(() ->
										{
											try {
												instance.takeScreenshot(displayId,
														instance.getMainExecutor(),
														this);
											} catch (Exception ignored) {
												// instance might be gone
											}
										},
										this, // use instance.mTakeScreenShotCallback as token
										instance.mTakeScreenShotDelayMs);
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
							if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && instance.mTakeScreenShotCallback != null) {
								// try again later, incrementing delay
								instance.mMainHandler.postDelayed(() -> {
									try {
										instance.takeScreenshot(displayId,
												instance.getMainExecutor(),
												this
										);
									} catch (Exception ignored) {
										// instance might be gone
									}
								}, this, instance.mTakeScreenShotDelayMs += 50); // use instance.mTakeScreenShotCallback as token
								Log.w(TAG, "takeScreenShots: onFailure with ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT - upped delay to " + instance.mTakeScreenShotDelayMs);
								return;
							}
							Log.e(TAG, "takeScreenShots: onFailure with error code " + errorCode);
							// prevent further calls
							instance.mTakeScreenShotCallback = null;
						} catch (Exception e) {
							Log.e(TAG, "takeScreenShots: onFailure exception " + e);
						}
					}
				};

				// first screenshot
				Log.d(TAG, "takeScreenShots: start");
				instance.takeScreenshot(displayId,
						instance.getMainExecutor(),
						instance.mTakeScreenShotCallback
				);
			} else {
				Log.d(TAG, "takeScreenShots: stop");
				// use instance.mTakeScreenShotCallback as token
				instance.mMainHandler.removeCallbacksAndMessages(instance.mTakeScreenShotCallback);
				instance.mTakeScreenShotCallback = null;
				instance.mTakeScreenShotDelayMs = TAKE_SCREEN_SHOT_DELAY_MS_INITIAL;
			}
		} catch (Exception e) {
			Log.e(TAG, "takeScreenShots: exception " + e);
		}
	}

	public static boolean isTakingScreenShots() {
		try {
			return instance.mTakeScreenShotCallback != null;
		} catch (Exception ignored) {
			return false;
		}
	}

	private void startStroke(InputContext inputContext, int x, int y) {
		inputContext.path.reset();
		inputContext.path.moveTo( x, y );
		inputContext.lastGestureStartTime = SystemClock.elapsedRealtime();
		// On API level 26 and newer, we can submit the stroke via multiple gestures, one per
		// continued stroke. Reset the stroke here to mark the start of a stroke which will be
		// continued in continueStroke() or ended in endStroke()
		// On older API levels, the stroke is constructed and submitted at the very end from the whole path.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			inputContext.stroke = null;
		}
	}

	private void continueStroke(InputContext inputContext, int x, int y) {
		inputContext.path.lineTo(x, y);
		// On API level 26 and newer, we can dispatch the stroke via multiple gestures.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			long currentTime = SystemClock.elapsedRealtime();
			long duration = currentTime - inputContext.lastGestureStartTime;
			// if passing 0, getting "IllegalArgumentException: Duration must be positive"
			if (duration == 0) duration = 1;

			// Either create a new stroke if this is the first continueStroke() after startStroke()
			// or create a continued stroke.
			if (inputContext.stroke == null) {
				inputContext.stroke = new GestureDescription.StrokeDescription(inputContext.path, 0, duration, true);
			} else {
				inputContext.stroke = inputContext.stroke.continueStroke(inputContext.path, 0, duration, true);
			}

			// and dispatch it to OS
			dispatchStrokeAsGesture(inputContext.stroke, inputContext.getDisplayId());

			// start a new gesture
			inputContext.lastGestureStartTime = currentTime;

			// start a new path
			inputContext.path.reset();
			inputContext.path.moveTo(x, y);
		}
	}

	private void endStroke(InputContext inputContext, int x, int y) {
		inputContext.path.lineTo( x, y );
		long duration = SystemClock.elapsedRealtime() - inputContext.lastGestureStartTime;
		// gesture ended very very shortly after start (< 1ms). make it 1ms to get dispatched to the system
		if (duration == 0) duration = 1;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// On API level 26 and newer, we can submit the stroke via multiple gestures, so end it here.
			// Either create a new stroke if this is endStroke comes right after startStroke()
			// or create a continued stroke.
			if (inputContext.stroke == null) {
				inputContext.stroke = new GestureDescription.StrokeDescription(inputContext.path, 0, duration, false);
			} else {
				inputContext.stroke = inputContext.stroke.continueStroke(inputContext.path, 0, duration, false);
			}
		} else {
			// On older API levels, the stroke is constructed and submitted at the very end from the whole path
			inputContext.stroke = new GestureDescription.StrokeDescription(inputContext.path, 0, duration);
		}

		dispatchStrokeAsGesture(inputContext.stroke, inputContext.getDisplayId());
	}

	/// Dispatch the given stroke to the OS, display-specific starting at API level 30
	private void dispatchStrokeAsGesture(GestureDescription.StrokeDescription stroke, int displayId) {
		GestureDescription.Builder builder = new GestureDescription.Builder();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.setDisplayId(displayId);
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

	/**
	 * Returns current cursor position or -1 if no text for node.
	 */
	private static int getCursorPos(AccessibilityNodeInfo node) {
		return node.getTextSelectionEnd();
	}

	private static void setCursorPos(AccessibilityNodeInfo node, int cursorPos) {
		Bundle action = new Bundle();
		action.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursorPos);
		action.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursorPos);
		node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.getId(), action);
	}
}
