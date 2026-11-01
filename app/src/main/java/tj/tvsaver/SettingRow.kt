package tj.tvsaver

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Строка настроек для пульта: «Название ........ Значение».
 * Фокусируемая, нажатие OK вызывает onClick, значение обновляет [value].
 */
class SettingRow(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val titleView = TextView(context)
    private val valueView = TextView(context)

    var title: CharSequence
        get() = titleView.text
        set(v) { titleView.text = v }

    var value: CharSequence
        get() = valueView.text
        set(v) { valueView.text = v }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.row_bg)
        setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (6 * dp).toInt()
        }

        titleView.textSize = 16f
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setTextColor(context.getColor(R.color.text_main))
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        valueView.textSize = 16f
        valueView.maxLines = 1
        valueView.setTypeface(valueView.typeface, Typeface.BOLD)
        valueView.setTextColor(context.getColor(R.color.accent))
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (12 * dp).toInt()
        })
    }

    companion object {
        fun onOff(on: Boolean) = if (on) "Вкл" else "Выкл"
    }
}
