package net.asksakis.massdroid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class ColorPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Preference(context, attrs, defStyleAttr) {

    data class ColorOption(val key: String, val color: Int)

    private val colors = listOf(
        ColorOption("purple", Color.parseColor("#6750A4")),
        ColorOption("indigo", Color.parseColor("#303F9F")),
        ColorOption("blue", Color.parseColor("#1976D2")),
        ColorOption("teal", Color.parseColor("#00796B")),
        ColorOption("green", Color.parseColor("#388E3C")),
        ColorOption("orange", Color.parseColor("#F57C00")),
        ColorOption("red", Color.parseColor("#D32F2F")),
        ColorOption("pink", Color.parseColor("#C2185B"))
    )

    private var selectedColor: String = "purple"
    private var colorViews = mutableListOf<View>()

    init {
        layoutResource = R.layout.preference_color_picker
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val title = holder.findViewById(R.id.title) as? TextView
        title?.text = getTitle()

        val container = holder.findViewById(R.id.color_container) as? LinearLayout ?: return
        container.removeAllViews()
        colorViews.clear()

        // Get persisted value
        selectedColor = getPersistedString("purple")

        val circleSize = dpToPx(40)
        val checkSize = dpToPx(20)
        val margin = dpToPx(8)

        colors.forEach { colorOption ->
            val frameLayout = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                    marginEnd = margin
                }
            }

            // Color circle with ripple
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorOption.color)
                setSize(circleSize, circleSize)
            }

            // Add stroke for selected
            val isSelected = colorOption.key == selectedColor
            if (isSelected) {
                circleDrawable.setStroke(dpToPx(3), getContrastColor(colorOption.color))
            }

            // Ripple effect
            val rippleColor = ColorStateList.valueOf(Color.argb(50, 255, 255, 255))
            val rippleDrawable = RippleDrawable(rippleColor, circleDrawable, null)

            val circleView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(circleSize, circleSize)
                background = rippleDrawable
                isClickable = true
                isFocusable = true
                contentDescription = colorOption.key
                setOnClickListener {
                    selectColor(colorOption.key)
                }
            }

            frameLayout.addView(circleView)

            // Check mark for selected
            if (isSelected) {
                val checkView = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(checkSize, checkSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_check_circle)
                    imageTintList = ColorStateList.valueOf(getContrastColor(colorOption.color))
                }
                frameLayout.addView(checkView)
            }

            container.addView(frameLayout)
            colorViews.add(frameLayout)
        }
    }

    private fun selectColor(colorKey: String) {
        if (selectedColor != colorKey) {
            selectedColor = colorKey
            persistString(colorKey)
            notifyChanged()
            callChangeListener(colorKey)
        }
    }

    private fun getContrastColor(color: Int): Int {
        // Calculate luminance and return white or dark color for contrast
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return if (luminance > 0.5) Color.parseColor("#333333") else Color.WHITE
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        selectedColor = getPersistedString((defaultValue as? String) ?: "purple")
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any {
        return a.getString(index) ?: "purple"
    }
}
