package app.litelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import kotlin.math.abs

/**
 * Phosh's top panel: signal and Wi-Fi on the left, the clock in the middle, battery on the right.
 * Everything is drawn here with a handful of Paint calls, so the panel is a single view with no
 * bitmaps. It only redraws when a value actually changes (at most once a minute for the clock).
 *
 * Tapping or pulling down opens Android's notifications (left half) or quick settings (right
 * half), much like Phosh opens its menus from the panel.
 */
class TopBarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var onPull: ((quickSettings: Boolean) -> Unit)? = null

    /**
     * Height of a camera cutout at the top of the screen. Like Android's status bar, the bar then
     * starts at the very top and grows to cover the cutout's strip, instead of sitting below it.
     */
    var cutoutHeight = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** Camera cutouts (in view coordinates) to keep the clock out of. */
    var cutouts: List<Rect> = emptyList()
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val barHeight = (26 * dp).toInt()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt() }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55ffffff }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
    }
    private val cutout = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff000000.toInt() }
    private val rect = RectF()
    private val path = Path()

    private var time = ""
    private var batteryLevel = -1
    private var charging = false
    /** 0..4 bars, or -1 to hide the icon (no SIM / unknown). */
    private var signalLevel = -1
    /** 0..3, or -1 when not on Wi-Fi. */
    private var wifiLevel = -1

    fun setTime(value: String) {
        if (value != time) { time = value; updateDescription(); invalidate() }
    }

    fun setBattery(level: Int, isCharging: Boolean) {
        if (level != batteryLevel || isCharging != charging) {
            batteryLevel = level; charging = isCharging; updateDescription(); invalidate()
        }
    }

    fun setNetwork(signal: Int, wifi: Int) {
        if (signal != signalLevel || wifi != wifiLevel) { signalLevel = signal; wifiLevel = wifi; invalidate() }
    }

    private fun updateDescription() {
        contentDescription = if (batteryLevel >= 0) "$time, battery $batteryLevel%" else time
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As an overlay on Android's status bar, the window gives us that bar's exact height.
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            maxOf(barHeight, cutoutHeight) + paddingTop
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        val top = paddingTop.toFloat()
        val h = (height - paddingTop).toFloat()
        val baseline = top + h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        val icon = 14 * dp
        val iconTop = top + (h - icon) / 2
        val pad = 10 * dp + paddingLeft

        // Phosh centers the clock; if a camera notch is there, it goes on the left instead and
        // the network icons move next to the battery.
        val timeWidth = textPaint.measureText(time)
        val centered = (width - timeWidth) / 2
        val clockBlocked = cutouts.any { it.left < centered + timeWidth && it.right > centered }
        var x = pad
        if (clockBlocked) {
            canvas.drawText(time, pad, baseline, textPaint)
        } else {
            canvas.drawText(time, centered, baseline, textPaint)
        }

        // Network icons: on the left, or right of center when the clock took the left.
        if (clockBlocked) x = width - 10 * dp - paddingRight - 8 * dp - textPaint.measureText("100%") - 12 * dp -
            (if (signalLevel >= 0) icon + 6 * dp else 0f) - (if (wifiLevel >= 0) icon else 0f)
        if (signalLevel >= 0) {
            drawSignal(canvas, x, iconTop, icon)
            x += icon + 6 * dp
        }
        if (wifiLevel >= 0) drawWifi(canvas, x, iconTop, icon)

        // Right: "85%" then a vertical battery, as Phosh shows it.
        if (batteryLevel >= 0) {
            val right = width - 10 * dp - paddingRight
            val bw = 8 * dp
            val bx = right - bw
            drawBattery(canvas, bx, iconTop, bw, icon)
            val label = "$batteryLevel%"
            canvas.drawText(label, bx - 5 * dp - textPaint.measureText(label), baseline, textPaint)
        }
    }

    /** Adwaita's cellular icon: a right triangle, filled up to the signal level. */
    private fun drawSignal(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s)
        path.lineTo(x + s, y + s)
        path.lineTo(x + s, y)
        path.close()
        canvas.drawPath(path, dim)
        if (signalLevel > 0) {
            canvas.save()
            canvas.clipRect(x, y, x + s * signalLevel / 4f, y + s)
            canvas.drawPath(path, fill)
            canvas.restore()
        }
    }

    /** Adwaita's Wi-Fi icon: a 90° wedge, filled out to the signal level. */
    private fun drawWifi(canvas: Canvas, x: Float, y: Float, s: Float) {
        val cx = x + s / 2
        val cy = y + s
        val r = s
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 225f, 90f, true, dim)
        val fr = r * (wifiLevel + 1) / 4f
        rect.set(cx - fr, cy - fr, cx + fr, cy + fr)
        canvas.drawArc(rect, 225f, 90f, true, fill)
    }

    private fun drawBattery(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cap = 2 * dp
        val r = 1.5f * dp
        val half = outline.strokeWidth / 2
        canvas.drawRect(x + w * 0.3f, y, x + w * 0.7f, y + cap, fill)
        rect.set(x + half, y + cap + half, x + w - half, y + h - half)
        canvas.drawRoundRect(rect, r, r, outline)
        val inner = rect.height() - 2 * half
        val level = batteryLevel.coerceIn(0, 100) / 100f
        rect.set(x + 2 * half, y + h - 2 * half - inner * level, x + w - 2 * half, y + h - 2 * half)
        canvas.drawRect(rect, fill)
        if (charging) {
            // A small lightning bolt punched out of the fill.
            val cx = x + w / 2
            val my = y + cap + (h - cap) / 2
            path.reset()
            path.moveTo(cx + 1.5f * dp, my - 4 * dp)
            path.lineTo(cx - 2 * dp, my + 0.5f * dp)
            path.lineTo(cx, my + 0.5f * dp)
            path.lineTo(cx - 1.5f * dp, my + 4 * dp)
            path.lineTo(cx + 2 * dp, my - 0.5f * dp)
            path.lineTo(cx, my - 0.5f * dp)
            path.close()
            canvas.drawPath(path, cutout)
        }
    }

    init {
        setBackgroundColor(0xff000000.toInt())
    }

    private var downY = 0f
    private var pulled = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; pulled = false }
            MotionEvent.ACTION_MOVE -> if (!pulled && event.y - downY > 16 * dp) {
                pulled = true
                onPull?.invoke(event.x > width / 2)
            }
            MotionEvent.ACTION_UP -> if (!pulled && abs(event.y - downY) < 16 * dp) {
                playSoundEffect(SoundEffectConstants.CLICK)
                onPull?.invoke(event.x > width / 2)
            }
        }
        return true
    }
}
