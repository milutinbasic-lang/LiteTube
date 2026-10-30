package app.litelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The home screen: a postmarketOS-style mountain wallpaper with Phosh's big clock and date, and
 * (as a child view) the favorites dock. Swiping up anywhere opens the app drawer, like pulling up
 * Phosh's overview; a long press on empty space opens the launcher menu.
 *
 * The wallpaper is a gradient and three filled paths, drawn straight onto the canvas rather than
 * kept as a full-screen bitmap, and it's only redrawn when the clock changes.
 */
class HomeView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var onSwipeUp: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    /** False shows the system wallpaper through the (transparent) window instead. */
    var drawWallpaper = true
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = 48 * dp

    private val sky = Paint()
    private val ridgePaints = RIDGE_COLORS.map { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it } }
    private val ridges = List(RIDGES.size) { Path() }
    private val clockPaint = textPaint(76f, Typeface.create("sans-serif-light", Typeface.NORMAL))
    private val datePaint = textPaint(18f, Typeface.create("sans-serif", Typeface.NORMAL))

    private var time = ""
    private var date = ""

    init {
        setWillNotDraw(false)
    }

    private fun textPaint(sp: Float, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        typeface = face
        setShadowLayer(3 * dp, 0f, dp, 0x80000000.toInt())
    }

    fun setTime(time: String, date: String) {
        if (time != this.time || date != this.date) {
            this.time = time
            this.date = date
            contentDescription = "$time, $date"
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sky.shader = LinearGradient(0f, 0f, 0f, h * 0.65f, SKY_TOP, SKY_BOTTOM, Shader.TileMode.CLAMP)
        RIDGES.forEachIndexed { i, points ->
            ridges[i].apply {
                reset()
                moveTo(0f, h.toFloat())
                for (p in points.indices step 2) lineTo(points[p] * w, points[p + 1] * h)
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (drawWallpaper) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
            ridges.forEachIndexed { i, path -> canvas.drawPath(path, ridgePaints[i]) }
        }
        val clockY = height * 0.22f
        canvas.drawText(time, width / 2f, clockY, clockPaint)
        canvas.drawText(date, width / 2f, clockY + datePaint.textSize * 2, datePaint)
    }

    // --- Gestures ---

    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private val longPress = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    /** Takes a vertical swipe over from the dock's icons, so swiping up works anywhere. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiped = false }
            MotionEvent.ACTION_MOVE -> return downY - ev.y > touchSlop && downY - ev.y > abs(ev.x - downX)
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; swiped = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop) removeCallbacks(longPress)
                if (!swiped && downY - event.y > swipeDistance) {
                    swiped = true
                    onSwipeUp?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private companion object {
        const val SKY_TOP = 0xff0b1d2a.toInt()
        const val SKY_BOTTOM = 0xff1b4150.toInt()
        val RIDGE_COLORS = intArrayOf(0xff235a52.toInt(), 0xff1a4436.toInt(), 0xff112a22.toInt())

        /** x,y pairs as fractions of the view, far ridge first. */
        val RIDGES = arrayOf(
            floatArrayOf(0f, .60f, .12f, .55f, .25f, .58f, .38f, .50f, .52f, .56f, .66f, .47f, .8f, .53f, .92f, .49f, 1f, .52f),
            floatArrayOf(0f, .70f, .15f, .64f, .3f, .69f, .45f, .61f, .6f, .67f, .78f, .60f, 1f, .66f),
            floatArrayOf(0f, .80f, .2f, .74f, .4f, .79f, .58f, .72f, .75f, .78f, .9f, .73f, 1f, .76f),
        )
    }
}
