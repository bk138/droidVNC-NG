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
import android.accessibilityservice.MagnificationConfig;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint("AccessibilityPolicy")
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
		private final boolean withPointer;
		private final float pointerRed;
		private final float pointerGreen;
		private final float pointerBlue;
		InputPointerView pointerView;
		// keyboard-related
		boolean isKeyCtrlDown;
		boolean isKeyAltDown;
		boolean isKeyShiftDown;
		// keysym of the shortcut trigger currently held down, so client key auto-repeat fires the
		// chord only once (0 = none held)
		long heldShortcutTrigger;

		private int displayId = Display.DEFAULT_DISPLAY;

		InputContext(float red, float green, float blue) {
			withPointer = true;
			pointerRed = red;
			pointerGreen = green;
			pointerBlue = blue;
		}

		InputContext() {
			withPointer = false;
			pointerRed = pointerGreen = pointerBlue = 0;
		}

		int getDisplayId() {return displayId;}

		/**
		 * Sets a new display id, recreates pointer view if necessary.
		 */
		void setDisplayId(int displayId) {
			// set display id
			this.displayId = displayId;
			// and if there is a pointer, recreate it with the new display id
			if(pointerView != null) {
				removePointerView();
				addPointerView();
			}
		}

		/**
		 * Creates a new InputPointerView if there is none with {@link #instance} as Context
		 * and adds it to the window manager and this InputContext.
		 */
		@UiThread
		void addPointerView() {
			if (!withPointer || pointerView != null || instance == null) {
				return;
			}
			pointerView = new InputPointerView(
					instance,
					displayId,
					pointerRed,
					pointerGreen,
					pointerBlue
			);
			pointerView.addView();
		}

		/**
		 * Removes InputPointerView from the window manager and this InputContext.
		 */
		@UiThread
		void removePointerView() {
			if (pointerView != null) {
				pointerView.removeView();
				pointerView = null;
			}
		}

		/**
		 * Resets all transient state that should not survive service re-creation.
		 * Called when the service is connected or destroyed to clear stale input state.
		 */
		void resetState() {
			// Button/key state
			isButtonOneDown = false;
			isKeyCtrlDown = false;
			isKeyAltDown = false;
			isKeyShiftDown = false;
			heldShortcutTrigger = 0;

			// Gesture state
			path.reset();
			stroke = null;
			lastGestureStartTime = 0;
			gestureCallback = new GestureCallback();
		}
	}

	private static final String TAG = "InputService";

	private static volatile InputService instance;
	/**
	 * Scaling factor that's applied to incoming pointer events by dividing coordinates by
	 * the given factor.
	 */
	static float scaling;
	static boolean isInputEnabled;
	/**
	 * Magnification state of the default display, cached from a listener so that onPointerEvent()
	 * does not need an IPC per mouse move.
	 */
	private static class MagnificationState {
		final float scale;
		final float centerX;
		final float centerY;
		/**
		 * Centre of the magnified region, which is not the display: it excludes the navigation bar
		 * and a small border. Magnification maps content into this region, so it, and not the
		 * display centre, is the fixed point of the scaling. 0 until magnification has been active
		 * once.
		 */
		final float regionCenterX;
		final float regionCenterY;

		MagnificationState(float scale, float centerX, float centerY, float regionCenterX, float regionCenterY) {
			this.scale = scale;
			this.centerX = centerX;
			this.centerY = centerY;
			this.regionCenterX = regionCenterX;
			this.regionCenterY = regionCenterY;
		}

		/**
		 * A state derived from a listener callback, carrying {@code previous}' region centre forward
		 * when the callback reports an empty region.
		 */
		MagnificationState(MagnificationState previous, Region region, float scale, float centerX, float centerY) {
			Rect bounds = region.getBounds();
			this.scale = scale;
			this.centerX = centerX;
			this.centerY = centerY;
			this.regionCenterX = bounds.isEmpty() ? previous.regionCenterX : bounds.exactCenterX();
			this.regionCenterY = bounds.isEmpty() ? previous.regionCenterY : bounds.exactCenterY();
		}
	}

	/**
	 * volatile: written on the main thread, read on the VNC worker thread. Swapped as a whole so a
	 * reader never sees a new scale together with a stale centre.
	 */
	private volatile MagnificationState mMagnification = new MagnificationState(1.0f, 0, 0, 0, 0);
	private final MagnificationController.OnMagnificationChangedListener mMagnificationListener =
			new MagnificationController.OnMagnificationChangedListener() {
				/**
				 * Only called directly before API 33, where full screen is the only magnification
				 * there is.
				 */
				@Override
				public void onMagnificationChanged(@NonNull MagnificationController controller,
						@NonNull Region region, float scale, float centerX, float centerY) {
					MagnificationState previous = mMagnification;
					mMagnification = new MagnificationState(previous, region, scale, centerX, centerY);
				}

				/**
				 * What the framework actually dispatches from API 33 on. The default implementation
				 * forwards full screen changes only, which would leave a stale scale behind when the
				 * user switches to window magnification.
				 */
				@RequiresApi(33)
				@Override
				public void onMagnificationChanged(@NonNull MagnificationController controller,
						@NonNull Region region, @NonNull MagnificationConfig config) {
					MagnificationState previous = mMagnification;
					mMagnification = config.getMode() == MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN
							? new MagnificationState(previous, region, config.getScale(),
									config.getCenterX(), config.getCenterY())
							// window magnification does not scale the screen coordinate space, so as
							// far as placing the pointer overlay goes there is no magnification
							: new MagnificationState(previous, region, 1.0f, 0, 0);
				}
			};
	/**
	 * Whether the magnification currently applied was set from a VNC client, so that disconnecting
	 * can undo it without touching magnification a local user set up for themselves. Written from
	 * the VNC worker threads, hence volatile.
	 */
	private volatile boolean mMagnifiedByRemote;
	/**
	 * Active keyboard shortcut bindings (per-action chord assignments), rebuilt from prefs/defaults
	 * in onServiceConnected() and live-reloaded from the settings UI via {@link #reloadShortcuts}.
	 * volatile: written on the main thread (onServiceConnected()/reloadShortcuts()) and read on the
	 * VNC worker thread in onKeyEvent(). onServiceConnected() assigns it before publishing
	 * {@code instance}, so it is non-null whenever onKeyEvent() observes a non-null instance.
	 */
	private volatile InputKeyShortcut.Manager mShortcuts;

	private TakeScreenshotCallback mTakeScreenShotCallback;
	private static final int TAKE_SCREEN_SHOT_DELAY_MS_INITIAL = 100;
	private int mTakeScreenShotDelayMs = TAKE_SCREEN_SHOT_DELAY_MS_INITIAL;

	private Handler mMainHandler;

	private static final Map<Long, InputContext> inputContexts = new ConcurrentHashMap<>();
	/**
	 * System keyboard input foci, display-specific starting on Android 10 (really 11 in higher layers),
	 * see <a href="https://source.android.com/docs/core/display/multi_display/displays#focus">Android docs</a>
	 */
	private final Map<Integer, AccessibilityNodeInfo> mKeyboardFocusNodes = new ConcurrentHashMap<>();

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onAccessibilityEvent: " + event);
            }

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
				// #385: a backwards selection (e.g. Shift+Left) reports start > end, so clamp with
				// min/max -- subSequence(start, end) would otherwise throw StringIndexOutOfBounds.
				int selStart = event.getSource().getTextSelectionStart();
				int selEnd = event.getSource().getTextSelectionEnd();
				CharSequence selection = event.getSource().getText().subSequence(Math.min(selStart, selEnd), Math.max(selStart, selEnd));
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
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		Defaults defaults = new Defaults(this);
		// Build the shortcut bindings before publishing `instance`, so onKeyEvent() (VNC worker
		// thread) never sees a non-null instance whose mShortcuts is not yet assigned.
		mShortcuts = buildShortcuts(prefs, defaults);
		instance = this;
		isInputEnabled = prefs.getBoolean(Constants.PREFS_KEY_INPUT_LAST_ENABLED, !defaults.getViewOnly());
		scaling = prefs.getFloat(Constants.PREFS_KEY_SERVER_LAST_SCALING, defaults.getScaling());
		mMainHandler = new Handler(instance.getMainLooper());
		// onServiceConnected() can run more than once, so do not stack listeners
		getMagnificationController().removeListener(mMagnificationListener);
		getMagnificationController().addListener(mMagnificationListener);
		// (re-)add any InputContext's InputPointerViews
		for (InputContext inputContext : inputContexts.values()) {
			inputContext.resetState();
			mMainHandler.post(inputContext::addPointerView);
		}
		Log.i(TAG, "onServiceConnected");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// remove InputPointerViews, they are no use without the InputService
		for (InputContext inputContext : inputContexts.values()) {
			inputContext.resetState();
			mMainHandler.post(inputContext::removePointerView);
		}
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

    @WorkerThread
	public static void addClient(long client, boolean withPointer) {
		// NB runs on a worker thread!
		try {
			int displayId = Display.DEFAULT_DISPLAY;
			InputContext inputContext;
			if(withPointer) {
				int inputContextsSize = inputContexts.size();
				inputContext = new InputContext(0.4f * ((inputContextsSize + 1) % 3), 0.2f * ((inputContextsSize + 1) % 5),  1.0f * ((inputContextsSize + 1) % 2));
				// run this on UI thread (use main handler as view is not yet added)
				instance.mMainHandler.post(inputContext::addPointerView);
			} else {
				inputContext = new InputContext();
			}
			inputContexts.put(client, inputContext);
		} catch (Exception e) {
			Log.e(TAG, "addClient: " + e);
		}
	}

    @WorkerThread
	public static void removeClient(long client) {
		// NB runs on a worker thread!
		try {
			InputContext inputContext = inputContexts.get(client);
			if(inputContext != null && inputContext.pointerView != null) {
				// run this on UI thread
				inputContext.pointerView.post(inputContext::removePointerView);
			}
			inputContexts.remove(client);
			if (inputContexts.isEmpty() && instance != null && instance.mMagnifiedByRemote) {
				// last client gone, so leave the screen as we found it. The device is often
				// unattended, with no local user around to zoom back out.
				instance.mMainHandler.post(() -> {
					instance.getMagnificationController().reset(false);
					instance.mMagnifiedByRemote = false;
				});
			}
		} catch (Exception e) {
			Log.e(TAG, "removeClient: " + e);
		}
	}

	@SuppressWarnings("unused")
    @WorkerThread
	@Keep
	public static void onPointerEvent(int buttonMask, int x, int y, long client) {

		if(!isInputEnabled) {
			return;
		}

		try {
			InputContext inputContext = inputContexts.get(client);

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
				int pointerX = x;
				int pointerY = y;
				MagnificationState magnification = instance.mMagnification;
				if (magnification.scale > 1.0f && inputContext.getDisplayId() == Display.DEFAULT_DISPLAY) {
					// x and y are positions on the magnified screen, but the overlay is placed in
					// unscaled coordinates and only then magnified along with everything else, so
					// undo the magnification here to have it drawn under the remote pointer
					pointerX = (int) (magnification.centerX + (x - magnification.regionCenterX) / magnification.scale);
					pointerY = (int) (magnification.centerY + (y - magnification.regionCenterY) / magnification.scale);
				}
				int finalX = pointerX;
				int finalY = pointerY;
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

				if (inputContext.isKeyCtrlDown) {
					instance.magnify(inputContext, x, y, true);
				} else {
					instance.scroll(inputContext, x, y, true);
				}
			}

			// scroll down
			if ((buttonMask & (1 << 4)) != 0) {

				if (inputContext.isKeyCtrlDown) {
					instance.magnify(inputContext, x, y, false);
				} else {
					instance.scroll(inputContext, x, y, false);
				}
			}
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onPointerEvent: failed: " + Log.getStackTraceString(e));
		}
	}

	/**
	 * Executes an {@link InputKeyShortcut.Action} resolved from the active chord bindings. All actions go through the
	 * accessibility service / AudioManager / MediaProjectionService and reuse the same calls the
	 * shortcuts used when they were hard-coded.
	 */
	private static void performShortcut(InputKeyShortcut.Action action) {
		// instance is a static mutated from other threads, so guard against it racing to null
		// between here and the dereferences below rather than a pre-check that can go stale.
		try {
			switch (action) {
				case RECENTS:
					instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
					break;
				case HOME:
					instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
					break;
				case BACK:
					instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
					break;
				case POWER_DIALOG:
					instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
					break;
				case VOLUME_UP:
					((AudioManager) instance.getSystemService(Context.AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
					break;
				case VOLUME_DOWN:
					((AudioManager) instance.getSystemService(Context.AUDIO_SERVICE)).adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
					break;
				case ROTATE:
					instance.mMainHandler.post(MediaProjectionService::togglePortraitInLandscapeWorkaround);
					break;
				default:
					break;
			}
		} catch (Exception e) {
			Log.e(TAG, "performShortcut: failed: " + e);
		}
	}

	/**
	 * Live-reloads the running service's shortcut bindings from prefs, using the same read path as
	 * onServiceConnected(). A no-op when the service is not connected -- there is nothing to consult
	 * the bindings then, and onServiceConnected() rebuilds them from prefs on the next connect.
	 */
	static void reloadShortcuts() {
		// instance can race to null between here and the dereferences, so snapshot it and let the
		// deref throw rather than pre-checking; onServiceConnected() rebuilds from prefs anyway.
		try {
			InputService s = instance;
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(s);
			Defaults defaults = new Defaults(s);
			s.mShortcuts = buildShortcuts(prefs, defaults);
		} catch (Exception e) {
			Log.e(TAG, "reloadShortcuts: failed: " + e);
		}
	}

	/**
	 * Reads the per-action chords from prefs (falling back to {@code defaults}) into a
	 * {@link InputKeyShortcut.Manager}, warning about anything the config got wrong. Those chords can
	 * come from defaults.json or managed app configuration, which have no UI to reject a bad value,
	 * so the log is the only feedback an administrator gets.
	 */
	private static InputKeyShortcut.Manager buildShortcuts(SharedPreferences prefs, Defaults defaults) {
		// ROTATE toggles the portrait-in-landscape workaround, which only produces a usable picture
		// on the quirky hardware it exists for, so keep it unassigned everywhere else.
		if (!Utils.hasPortraitInLandscapeQuirk()) {
			Log.i(TAG, "buildShortcuts: device has no portrait-in-landscape quirk, "
					+ InputKeyShortcut.Action.ROTATE + " shortcut is off");
		}
		InputKeyShortcut.Manager shortcuts = InputKeyShortcut.Manager.from(action -> {
			if (action == InputKeyShortcut.Action.ROTATE && !Utils.hasPortraitInLandscapeQuirk()) {
				return "";
			}
			return prefs.getString(action.getPrefKey(), action.defaultChord(defaults));
		});
		for (Map.Entry<InputKeyShortcut.Action, String> unparsed : shortcuts.getUnparsed().entrySet()) {
			Log.w(TAG, "buildShortcuts: no usable trigger key in chord \"" + unparsed.getValue()
					+ "\" for " + unparsed.getKey() + ", so that shortcut is off");
		}
		for (InputKeyShortcut.Chord conflict : shortcuts.getConflicts()) {
			Log.w(TAG, "buildShortcuts: chord \"" + conflict
					+ "\" is assigned to more than one action, only the first one gets it");
		}
		return shortcuts;
	}

    @WorkerThread
	@Keep
	public static void onKeyEvent(int down, long keysym, long client) {

		if(!isInputEnabled) {
			return;
		}

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onKeyEvent: keysym 0x" + Long.toHexString(keysym) + " down " + down + " by client " + client);
        }

		try {
			InputContext inputContext = inputContexts.get(client);

			if(inputContext == null) {
				throw new IllegalStateException("Client " + client + " was not added or is already removed");
			}

			/*
				Normalize NumLock-off numpad navigation keys to their plain equivalents for
				both API >= 34 handling and the pre-34 path.
				Android has no distinct KeyEvent.KEYCODE_NUMPAD_* for these (unlike the
				numpad digits), so no information is lost by this remapping.
			 */
			if (keysym == 0xff95) keysym = 0xff50; // KP_Home      -> Home
			if (keysym == 0xff96) keysym = 0xff51; // KP_Left      -> Left
			if (keysym == 0xff97) keysym = 0xff52; // KP_Up        -> Up
			if (keysym == 0xff98) keysym = 0xff53; // KP_Right     -> Right
			if (keysym == 0xff99) keysym = 0xff54; // KP_Down      -> Down
			if (keysym == 0xff9a) keysym = 0xff55; // KP_Page_Up   -> Page_Up
			if (keysym == 0xff9b) keysym = 0xff56; // KP_Page_Down -> Page_Down
			if (keysym == 0xff9c) keysym = 0xff57; // KP_End       -> End
			if (keysym == 0xff9e) keysym = 0xff63; // KP_Insert    -> Insert
			if (keysym == 0xff9f) keysym = 0xffff; // KP_Delete    -> Delete

			/*
				Track Ctrl/Alt/Shift state for the configurable shortcut chords below. Both the left
				and right variants count, matching what Chord.fromString accepts and app_restrictions
				documents ("either side accepted").
			 */
			if(keysym == 0xFFE3 || keysym == 0xFFE4) // Control_L / Control_R
				inputContext.isKeyCtrlDown = down != 0;

			if(keysym == 0xFFE9 || keysym == 0xFFEA || keysym == 0xFF7E) // Alt_L / Alt_R (MacOS clients send Alt as 0xFF7E)
				inputContext.isKeyAltDown = down != 0;

			if(keysym == 0xFFE1 || keysym == 0xFFE2) // Shift_L / Shift_R
				inputContext.isKeyShiftDown = down != 0;

			/*
				Configurable keyboard shortcuts (issue #13): match the current modifier state and this
				key against the chord the user assigned to each action (see InputKeyShortcut /
				Settings). A match is consumed here -- it is not also injected below -- and the
				heldShortcutTrigger latch debounces client key auto-repeat so a held chord fires once.
			 */
			if(down != 0) {
				InputKeyShortcut.Action shortcut = instance.mShortcuts.actionFor(inputContext.isKeyCtrlDown, inputContext.isKeyAltDown, inputContext.isKeyShiftDown, keysym);
				if(shortcut != null) {
					if(inputContext.heldShortcutTrigger != keysym) {
						inputContext.heldShortcutTrigger = keysym;
						performShortcut(shortcut);
					}
					return;
				}
			} else if(inputContext.heldShortcutTrigger == keysym) {
				// release of a consumed shortcut trigger: consume the up too and re-arm
				inputContext.heldShortcutTrigger = 0;
				return;
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
					//  Up/Down
					if (keysym == 0xff52) keyCode = KeyEvent.KEYCODE_DPAD_UP;
					if (keysym == 0xff54) keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
					// KP_Begin (numpad 5 w/ NumLock off)
					if (keysym == 0xff9d) keyCode = KeyEvent.KEYCODE_DPAD_CENTER;
					// Backspace/Delete
					if (keysym == 0xff08) keyCode = KeyEvent.KEYCODE_DEL;
					if (keysym == 0xffff) keyCode = KeyEvent.KEYCODE_FORWARD_DEL;
					// Insert
					if (keysym == 0xff63) keyCode = KeyEvent.KEYCODE_INSERT;
					// Shift: deliver as a real modifier key so the editor's MetaKeyKeyListener tracks
					// it as held, which is what makes Shift+navigation extend the text selection (#385).
					// Without this the Shift keysym maps to KEYCODE_UNKNOWN and the held state is lost.
					if (keysym == 0xffe1) keyCode = KeyEvent.KEYCODE_SHIFT_LEFT;
					if (keysym == 0xffe2) keyCode = KeyEvent.KEYCODE_SHIFT_RIGHT;
					// Enter
					if (keysym == 0xff0d) keyCode = KeyEvent.KEYCODE_ENTER;
					// Tab
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
						Numpad input. The doNumlock flag only needs to be set for
					    XK_KP_0 - XK_KP_9 and XK_KP_Decimal.
					 */
					boolean doNumLock = false;
					if (keysym == 0xff8d) keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER;
					if (keysym == 0xffaa) keyCode = KeyEvent.KEYCODE_NUMPAD_MULTIPLY;
					if (keysym == 0xffab) keyCode = KeyEvent.KEYCODE_NUMPAD_ADD;
					if (keysym == 0xffac) keyCode = KeyEvent.KEYCODE_NUMPAD_COMMA;
					if (keysym == 0xffad) keyCode = KeyEvent.KEYCODE_NUMPAD_SUBTRACT;
					if (keysym == 0xffae) { keyCode = KeyEvent.KEYCODE_NUMPAD_DOT; doNumLock = true; }
					if (keysym == 0xffaf) keyCode = KeyEvent.KEYCODE_NUMPAD_DIVIDE;
					if (keysym == 0xffb0) { keyCode = KeyEvent.KEYCODE_NUMPAD_0; doNumLock = true; }
					if (keysym == 0xffb1) { keyCode = KeyEvent.KEYCODE_NUMPAD_1; doNumLock = true; }
					if (keysym == 0xffb2) { keyCode = KeyEvent.KEYCODE_NUMPAD_2; doNumLock = true; }
					if (keysym == 0xffb3) { keyCode = KeyEvent.KEYCODE_NUMPAD_3; doNumLock = true; }
					if (keysym == 0xffb4) { keyCode = KeyEvent.KEYCODE_NUMPAD_4; doNumLock = true; }
					if (keysym == 0xffb5) { keyCode = KeyEvent.KEYCODE_NUMPAD_5; doNumLock = true; }
					if (keysym == 0xffb6) { keyCode = KeyEvent.KEYCODE_NUMPAD_6; doNumLock = true; }
					if (keysym == 0xffb7) { keyCode = KeyEvent.KEYCODE_NUMPAD_7; doNumLock = true; }
					if (keysym == 0xffb8) { keyCode = KeyEvent.KEYCODE_NUMPAD_8; doNumLock = true; }
					if (keysym == 0xffb9) { keyCode = KeyEvent.KEYCODE_NUMPAD_9; doNumLock = true; }

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
									(doShift ? KeyEvent.META_SHIFT_ON : 0) |
									(doNumLock ? KeyEvent.META_NUM_LOCK_ON : 0)
					);

					/*
						Rest of ISO-8859-1 input using KeyEvent from characters, plus
						legacy Cyrillic keysyms and the RFB Unicode-keysym range so
						non-Latin scripts can be typed too. API does not allow setting
						meta state for these.
					 */
					int charCodePoint = InputKeysymToUnicode.keysymToUnicode(keysym);
					if (charCodePoint >= 0xa0 && down != 0) {
						keyEvent = new KeyEvent(SystemClock.uptimeMillis(), new String(Character.toChars(charCodePoint)), 0, 0);
					}

					/*
						Send
					 */
					Objects.requireNonNull(Objects.requireNonNull(instance.getInputMethod()).getCurrentInputConnection()).sendKeyEvent(keyEvent);
					// if this succeeds, don't do the AccessibilityNodeInfo approach
					return;
				} catch (NullPointerException ignored) {
					Log.w(TAG, "onKeyEvent: AccessibilityInputConnection key event handling failed, falling back to AccessibilityNodeInfo API");
				}
			}

			/*
				Get current keyboard focus node for input context's display.
			 */
			AccessibilityNodeInfo currentFocusNode = instance.mKeyboardFocusNodes.get(inputContext.getDisplayId());
			// refresh() is important to load the represented view's current text into the node
			if (currentFocusNode != null) {
				currentFocusNode.refresh();
			}

			/*
			   DPAD Left/Right/Up/Down
			 */
            if ((keysym == 0xff51 || keysym == 0xff52 || keysym == 0xff53 || keysym == 0xff54) && down != 0) {
                if (Build.VERSION.SDK_INT >= 33) {
                    // On API 33 and newer, simply send DPAD events and leave choice of text vs focus
                    // traversal to the system
                    if (keysym == 0xff51) {
                        instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT);
                    }
                    if (keysym == 0xff52) {
                        instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_UP);
                    }
                    if (keysym == 0xff53) {
                        instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT);
                    }
                    if (keysym == 0xff54) {
                        instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN);
                    }
                } else {
                    // On API before 33, do text/focus traversal
                    if (currentFocusNode == null) {
                        Log.w(TAG, "onKeyEvent: no focus node for display " + inputContext.getDisplayId() + ", trying to find one");
                        AccessibilityNodeInfo focusableNode = findFocusableNodeFromRoot(inputContext.getDisplayId());
                        if (focusableNode != null) {
                            focusableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            currentFocusNode = focusableNode;
                            Log.i(TAG, "onKeyEvent: Found and focused a new node.");
                        }
                    }

                    if (currentFocusNode == null) {
                        Log.e(TAG, "onKeyEvent: Could not find any focusable node on display " + inputContext.getDisplayId() + ". Ignoring key event.");
                        return;
                    }

                   /*
                       Try to do text traversal first, if this does not happen due the widget not being
                       editable or text at end, do focus traversal.
                    */
                    boolean didTextTraversal = false;
                    if (currentFocusNode.isEditable()) {
                        // Text Traversal
                        Bundle action = new Bundle();
                        int granularity = (keysym == 0xff51 || keysym == 0xff53) ?
                                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER :
                                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;
                        action.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity);
                        action.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);

                        if (keysym == 0xff51 || keysym == 0xff52)
                            didTextTraversal = currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY.getId(), action);
                        else
                            didTextTraversal = currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY.getId(), action);
                    }

                    if (!didTextTraversal) {
                        // Focus Traversal
                        int direction = 0;
                        if (keysym == 0xff51) direction = View.FOCUS_LEFT;
                        if (keysym == 0xff52) direction = View.FOCUS_UP;
                        if (keysym == 0xff53) direction = View.FOCUS_RIGHT;
                        if (keysym == 0xff54) direction = View.FOCUS_DOWN;
                        AccessibilityNodeInfo nextFocus = currentFocusNode.focusSearch(direction);
                        if (nextFocus != null) {
                            boolean focusChanged = nextFocus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            nextFocus.recycle();
                            Log.d(TAG, "onKeyEvent: next focus found, change: " + focusChanged);
                        } else {
                            Log.d(TAG, "onKeyEvent: no next focus found, looking for new one");
                            AccessibilityNodeInfo newFocus = findFocusableNodeFromRoot(inputContext.getDisplayId());
                            if (newFocus != null) {
                                boolean focusChanged = newFocus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                                newFocus.recycle();
                                Log.d(TAG, "onKeyEvent: new focus found, change: " + focusChanged);
                            } else {
                                Log.w(TAG, "onKeyEvent: no new focus found");
                            }
                        }
                    }
                }
            }

            /*
                XK_KP_Begin (numpad 5 w/ NumLock off)
             */
            if (keysym == 0xff9d && down != 0 && Build.VERSION.SDK_INT >= 33) {
                Log.i(TAG, "onKeyEvent: got KP_Begin");
                instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER);
            }

            /*
                Tab
            */
            if (keysym == 0xff09 && down != 0) {
                if (currentFocusNode == null) {
                    Log.w(TAG, "onKeyEvent: no focus node for display " + inputContext.getDisplayId() + ", trying to find one");
                    AccessibilityNodeInfo focusableNode = findFocusableNodeFromRoot(inputContext.getDisplayId());
                    if (focusableNode != null) {
                        focusableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        currentFocusNode = focusableNode;
                        Log.i(TAG, "onKeyEvent: Found and focused a new node.");
                    }
                }

                if (currentFocusNode == null) {
                    Log.e(TAG, "onKeyEvent: Could not find any focusable node on display " + inputContext.getDisplayId() + ". Ignoring key event.");
                    return;
                }

                AccessibilityNodeInfo nextFocus = currentFocusNode.focusSearch(View.FOCUS_FORWARD);
                if (nextFocus != null) {
                    boolean focusChanged = nextFocus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    nextFocus.recycle();
                    Log.d(TAG, "onKeyEvent: next focus found, change: " + focusChanged);
                } else {
                    Log.d(TAG, "onKeyEvent: no next focus found, looking for new one");
                    AccessibilityNodeInfo newFocus = findFocusableNodeFromRoot(inputContext.getDisplayId());
                    if (newFocus != null) {
                        boolean focusChanged = newFocus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        newFocus.recycle();
                        Log.d(TAG, "onKeyEvent: new focus found, change: " + focusChanged);
                    } else {
                        Log.w(TAG, "onKeyEvent: no new focus found");
                    }
                }
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
			    XK_Return or XK_KP_Enter, doing ACTION_IME_ENTER or ACTION_CLICK or GLOBAL_ACTION_DPAD_CENTER
			 */
			if ((keysym == 0xff0d || keysym == 0xff8d) && down != 0) {
				Bundle action = new Bundle();
				if (Build.VERSION.SDK_INT >= 30 && Objects.requireNonNull(currentFocusNode).getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER)) {
					Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId(), action);
				} else if (Objects.requireNonNull(currentFocusNode).getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
					Objects.requireNonNull(currentFocusNode).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.getId(), action);
				} else if (Build.VERSION.SDK_INT >= 33) {
                    // do this after ACTION_IME_ENTER and ACTION_CLICK are tried
                    instance.performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
                }
            }

			/*
			    Numpad input (numlock on)
			 */
			if (down != 0 && ((keysym >= 0xffb0 && keysym <= 0xffb9) || (keysym >= 0xffaa && keysym <= 0xffaf))) {
				char[] numpadOps = {'*', '+', ',', '-', '.', '/'};
				char ch = (keysym >= 0xffb0) ? (char) ('0' + (keysym - 0xffb0)) : numpadOps[(int) (keysym - 0xffaa)];
				CharSequence currentFocusText = Objects.requireNonNull(currentFocusNode).getText();
				if (currentFocusText == null)
					currentFocusText = "";
				int cursorPos = getCursorPos(currentFocusNode);
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
				Bundle action = new Bundle();
				action.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textBeforeCursor + ch + textAfterCursor);
				currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), action);
				setCursorPos(currentFocusNode, cursorPos > 0 ? cursorPos + 1 : 1);
			}

			/*
			    Printable character input: Latin-1, legacy Cyrillic keysyms and the
			    RFB Unicode-keysym range (see keysymToUnicode). This is the path used
			    on older devices (e.g. Android 7) that don't take the API 34+ branch.
			 */
			int typedCodePoint = InputKeysymToUnicode.keysymToUnicode(keysym);
			if (typedCodePoint >= 32 && down != 0) {
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
				String typed = new String(Character.toChars(typedCodePoint));
				String newFocusText = textBeforeCursor + typed + textAfterCursor;

				Bundle action = new Bundle();
				action.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFocusText);
				currentFocusNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(), action);

				// ACTION_SET_TEXT moves cursor to the end, move cursor back to where it should be
				setCursorPos(currentFocusNode, cursorPos > 0 ? cursorPos + typed.length() : typed.length());
			}

		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onKeyEvent: failed: " + e);
		}
	}

    @WorkerThread
	@Keep
	public static void onCutText(String text, long client) {

		if(!isInputEnabled) {
			return;
		}

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onCutText: text '" + text + "' by client " + client);
        }

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

							// Bitmap buffers are contiguous (no padding), so rowStride = width * 4 bytes per pixel
							MainService.vncUpdateFramebuffer(byteBuffer, bitmap.getWidth() * 4);

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

	private void scroll(InputContext inputContext, int x, int y, boolean scrollUp)
	{
			/*
			   Ignore if another gesture is still ongoing. Especially true for scroll events:
			   These mouse button 4,5 events come per each virtual scroll wheel click, an incoming
			   event would cancel the preceding one, only actually scrolling when the user stopped
			   scrolling.
			 */
			if(!inputContext.gestureCallback.mCompleted)
				return;

			// half a screen per wheel click, on the display the client is attached to
			int displayHeight = Utils.getDisplayMetrics(this, inputContext.getDisplayId()).heightPixels;
			int scrollAmount = scrollUp ? -displayHeight / 2 : displayHeight / 2;

			inputContext.gestureCallback.mCompleted = false;
			dispatchGesture(createSwipe(inputContext, x, y, x, y - scrollAmount, ViewConfiguration.getScrollDefaultDelay()), inputContext.gestureCallback, null);
	}

	private void magnify(InputContext inputContext, int x, int y, boolean zoomIn) {
		if (inputContext.getDisplayId() != Display.DEFAULT_DISPLAY) {
			// getMagnificationController() is hardwired to the default display and the display
			// scoped variant is @hide, so there is no way to magnify any other one
			Log.w(TAG, "magnify: can only magnify the default display, not " + inputContext.getDisplayId());
			return;
		}

		// the default display, as that is the only one that can be magnified, see above
		DisplayMetrics displayMetrics = Utils.getDisplayMetrics(this, Display.DEFAULT_DISPLAY);
		MagnificationController mc = getMagnificationController();

		// current magnification, defaulting to none if it cannot be read
		float fromScale = 1.0f;
		float fromCenterX = displayMetrics.widthPixels / 2f;
		float fromCenterY = displayMetrics.heightPixels / 2f;
		if (Build.VERSION.SDK_INT >= 33) {
			// null while the service is not connected
			MagnificationConfig from = mc.getMagnificationConfig();
			if (from != null) {
				fromScale = from.getScale();
				fromCenterX = from.getCenterX();
				fromCenterY = from.getCenterY();
			}
		} else {
			//noinspection deprecation
			fromScale = mc.getScale();
			//noinspection deprecation
			fromCenterX = mc.getCenterX();
			//noinspection deprecation
			fromCenterY = mc.getCenterY();
		}

		// magnification step per scroll wheel click, desktop browsers use about 10%
		final float scaleStep = 1.1f;
		final float maxScale = 8.0f;
		float toScale = Math.max(1.0f, Math.min(fromScale * (zoomIn ? scaleStep : 1 / scaleStep), maxScale));
		// magnification maps content into the magnified region rather than the whole display, so
		// that region's centre is the fixed point the scaling happens around
		MagnificationState magnification = mMagnification;
		float regionCenterX = magnification.regionCenterX > 0 ? magnification.regionCenterX : displayMetrics.widthPixels / 2f;
		float regionCenterY = magnification.regionCenterY > 0 ? magnification.regionCenterY : displayMetrics.heightPixels / 2f;
		if (fromScale <= 1.0f) {
			// no meaningful centre is reported while magnification is off
			fromCenterX = regionCenterX;
			fromCenterY = regionCenterY;
		}
		// zoom around the pointer instead of centring on it: whatever is under the cursor stays
		// where it is, so the target cannot drift across clicks and the user can retarget simply by
		// moving the mouse.
		float toCenterX = fromCenterX + (x - regionCenterX) * (1 / fromScale - 1 / toScale);
		float toCenterY = fromCenterY + (y - regionCenterY) * (1 / fromScale - 1 / toScale);

		if (toScale <= 1.0f) {
			// setScale(1.0f) leaves the magnifier activated, which keeps the system's border on
			// screen; only reset() actually deactivates it
			mc.reset(false);
		} else if (Build.VERSION.SDK_INT >= 33) {
			// always full screen: a remote viewer wants the whole screen bigger, and window
			// magnification would need its own coordinate transform for the pointer overlay.
			// scale and centre in one update, so there is no scale-then-pan flicker
			mc.setMagnificationConfig(new MagnificationConfig.Builder()
					.setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
					.setScale(toScale)
					.setCenterX(toCenterX)
					.setCenterY(toCenterY)
					.build(), false);
		} else {
			// this is the right order for pre-API-33 devices
			//noinspection deprecation
			mc.setScale(toScale, false);
			//noinspection deprecation
			mc.setCenter(toCenterX, toCenterY, false);
		}
		mMagnifiedByRemote = toScale > 1.0f;
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

    private static AccessibilityNodeInfo findFocusableNodeFromRoot(int displayId) {
        if (Build.VERSION.SDK_INT >= 30) {
            for (AccessibilityWindowInfo window : instance.getWindows()) {
                if (window.getDisplayId() == displayId) {
                    return findFocusableNode(window.getRoot());
                }
            }
        } else {
            return findFocusableNode(instance.getRootInActiveWindow());
        }
        return null;
    }

	private static AccessibilityNodeInfo findFocusableNode(AccessibilityNodeInfo node) {
		if (node == null) {
			return null;
		}

		if (node.isFocusable()) {
			return node;
		}

		for (int i = 0; i < node.getChildCount(); i++) {
			AccessibilityNodeInfo focusableChild = findFocusableNode(node.getChild(i));
			if (focusableChild != null) {
				return focusableChild;
			}
		}

		return null;
	}
}
