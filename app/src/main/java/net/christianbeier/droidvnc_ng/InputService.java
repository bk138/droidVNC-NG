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
 */

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;

public class InputService extends AccessibilityService {

	private static final String TAG = "InputService";

	private static InputService instance;

	@Override
	public void onAccessibilityEvent( AccessibilityEvent event ) { }

	@Override
	public void onInterrupt() { }

	@Override
	public void onServiceConnected()
	{
		super.onServiceConnected();
		instance = this;
		Log.i(TAG, "onServiceConnected");
	}

	public static void tap( int x, int y )
	{
		if( instance != null )
			instance.dispatchGesture( createClick( x, y, ViewConfiguration.getTapTimeout() + 50 ), null, null );
	}

	public static void longPress( int x, int y )
	{
		if( instance != null )
			instance.dispatchGesture( createClick( x, y, ViewConfiguration.getLongPressTimeout() + 200 ), null, null );
	}

	public static void swipeDown( int x, int y )
	{
		if( instance != null )
			instance.dispatchGesture( createSwipe( x, y, x, y+100 ), null, null );
	}

	public static void swipeUp( int x, int y )
	{
		if( instance != null )
			instance.dispatchGesture( createSwipe( x, y, x, y-100 ), null, null );
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

	private static GestureDescription createSwipe( int x1, int y1, int x2, int y2 )
	{
		Path swipePath = new Path();
		swipePath.moveTo( x1, y1 );
		swipePath.moveTo( x2, y2 );
		GestureDescription.StrokeDescription swipeStroke = new GestureDescription.StrokeDescription( swipePath, 0, 100 );
		GestureDescription.Builder swipeBuilder = new GestureDescription.Builder();
		swipeBuilder.addStroke( swipeStroke );
		return swipeBuilder.build();
	}
}
