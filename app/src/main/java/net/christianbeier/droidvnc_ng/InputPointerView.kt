package net.christianbeier.droidvnc_ng

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.lang.IllegalArgumentException

/**
 * Create an input pointer view to be used on the specified display
 * (displays other than the the default display work on API level >= 32)
 * with the given RGB colour.
 */
@SuppressLint("ViewConstructor")
class InputPointerView(
    context: Context,
    private val displayId: Int,
    val red: Float,
    val green: Float,
    val blue: Float
) : View(context) {

    private val path: Path = Path()
    private val windowManager: WindowManager

    private val paintFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb((0.9f * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
    }

    private val paintStroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 0.8f  * density
    }

    private val density: Float

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
    
    init {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // get density for later drawing in size adapted to display
        val metrics = DisplayMetrics()
        displayManager.getDisplay(displayId).getRealMetrics(metrics)
        density = metrics.density

        windowManager = if (displayId != Display.DEFAULT_DISPLAY) {
            if (Build.VERSION.SDK_INT < 32) {
                // technically, the API is there on API level 30, but WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                // only works on another display starting with API level 32, on 31 we get a BadTokenException
                throw IllegalArgumentException("On API level < 32, can only be called with the default display id")
            }
            // other display's window manager
            val windowContext = context.createDisplayContext(displayManager.getDisplay(displayId))
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            // default display's window manager
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
    }

    // Set the size of the view
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Set the dimensions of the view
        setMeasuredDimension((24f * density).toInt(), (24f * density).toInt())
    }

    // Draw the pointer
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        path.reset()
        path.moveTo(1f * density, 1f * density)
        path.lineTo(12f * density, 8f * density)
        path.lineTo(5f * density, 15f * density)
        path.close()

        canvas.drawPath(path, paintFill)
        canvas.drawPath(path, paintStroke)
    }

    /**
     * Add input pointer view to display specified in constructor.
     */
    fun addView() {
        // attach to display
        layoutParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(this, layoutParams)
    }

    /**
     * Remove input pointer view from display specified in constructor.
     */
    fun removeView() {
        windowManager.removeView(this)
    }

    /**
     * Position input pointer view on display specified in constructor.
     */
    fun positionView(x: Int, y: Int) {
        layoutParams.x = x
        layoutParams.y = y
        windowManager.updateViewLayout(this, layoutParams)
    }

}

