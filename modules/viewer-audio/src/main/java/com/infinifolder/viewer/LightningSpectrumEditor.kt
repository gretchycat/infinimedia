package com.infinifolder.viewer

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** App-owned controls for the reusable lightning-spectrum generator's graph parameters. */
internal class LightningSpectrumEditor(
    private val context: Context,
    private val initial: LightningSpectrumStyle,
    private val onChange: (LightningSpectrumStyle) -> Unit,
) {
    fun show() {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        var style = initial
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        content.addView(TextView(context).apply {
            text = "Sixteen lightning columns span the artwork pane. Drag the controls to update the active InfiniFX graph."
            textSize = 12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        fun addSlider(
            title: String,
            minimum: Float,
            maximum: Float,
            initialValue: Float,
            display: (Float) -> String,
            update: (LightningSpectrumStyle, Float) -> LightningSpectrumStyle,
        ) {
            val value = initialValue.coerceIn(minimum, maximum)
            val label = TextView(context).apply { textSize = 13f }
            val seek = SeekBar(context).apply {
                max = 100
                progress = (((value - minimum) / (maximum - minimum)) * max).toInt().coerceIn(0, max)
            }
            fun render(progress: Int, apply: Boolean) {
                val amount = minimum + (maximum - minimum) * progress / seek.max
                label.text = "$title  ${display(amount)}"
                if (apply) {
                    style = update(style, amount)
                    onChange(style)
                }
            }
            render(seek.progress, false)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = render(progress, fromUser)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            content.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
        fun number(value: Float) = String.format(Locale.US, "%.2f", value)
        addSlider("Spectrum gain", 0f, 5f, style.gain, ::number) { current, value -> current.copy(gain = value) }
        addSlider("Maximum height", .1f, 1f, style.maxHeight, { "${(it * 100).toInt()}%" }) { current, value -> current.copy(maxHeight = value) }
        addSlider("Bar spacing", 0f, .4f, style.barGap, ::number) { current, value -> current.copy(barGap = value) }
        addSlider("Electric glow", 0f, 2f, style.glow, ::number) { current, value -> current.copy(glow = value) }
        addSlider("Idle baseline", 0f, .2f, style.idleHeight, ::number) { current, value -> current.copy(idleHeight = value) }

        val scroll = ScrollView(context).apply { addView(content) }
        AlertDialog.Builder(context)
            .setTitle("Lightning visualizer editor")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
