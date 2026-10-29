package app.litelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Phosh's home bar: a black strip with an up chevron. */
class HomeBarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val barHeight = (32 * dp).toInt()
    private val chevron = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
        setBackgroundColor(0xff000000.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        chevron.reset()
        chevron.moveTo(cx - 7 * dp, cy + 3.5f * dp)
        chevron.lineTo(cx, cy - 3.5f * dp)
        chevron.lineTo(cx + 7 * dp, cy + 3.5f * dp)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(chevron, paint)
    }
}
