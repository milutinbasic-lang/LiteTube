package app.litetube.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.litetube.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/** The "More" sheet: extra tools tucked away so the player screen stays uncluttered. */
class ToolsSheet(private val context: Context) {

    class Tool(val icon: Int, val title: Int, val subtitle: Int?, val action: () -> Unit)

    fun show(tools: List<Tool>) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        }
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        val tint = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.textColorSecondary, it, true)
        }
        for (tool in tools) {
            val row = TextView(context).apply {
                val title = context.getString(tool.title)
                text = tool.subtitle?.let { "$title\n${context.getString(it)}" } ?: title
                textSize = 15f
                minHeight = (56 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
                compoundDrawablePadding = (20 * density).toInt()
                setBackgroundResource(ripple)
                val icon = ContextCompat.getDrawable(context, tool.icon)?.mutate()
                if (icon != null && tint.resourceId != 0) {
                    icon.setTint(ContextCompat.getColorStateList(context, tint.resourceId)?.defaultColor ?: 0)
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                setOnClickListener {
                    dialog.dismiss()
                    tool.action()
                }
            }
            list.addView(row)
        }
        dialog.setContentView(list)
        dialog.show()
    }
}
