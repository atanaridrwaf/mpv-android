package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogSliderBinding
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import kotlin.math.roundToInt

internal class SpeedPickerDialog : PickerDialog {
    companion object {
        private const val MINIMUM = 0.2
        private const val MAXIMUM = 6.0
        private const val STEP = 0.01
        // Progress units per 1.0x above the midpoint.
        private const val SCALE_FACTOR = 100.0
        // Keep 1.0x halfway along the bar, as in the original mapping.
        private const val HALF = (MAXIMUM - 1.0) * SCALE_FACTOR
    }

    private lateinit var binding: DialogSliderBinding

    private fun toSpeed(it: Int): Double {
        val speed = if (it >= HALF)
            (it - HALF) / SCALE_FACTOR + 1.0
        else
            it / HALF
        return (speed.coerceIn(MINIMUM, MAXIMUM) * SCALE_FACTOR).roundToInt() / SCALE_FACTOR
    }

    private fun fromSpeed(it: Double): Int {
        val speed = (it.coerceIn(MINIMUM, MAXIMUM) * SCALE_FACTOR).roundToInt() / SCALE_FACTOR
        return if (speed >= 1.0)
            (HALF + (speed - 1.0) * SCALE_FACTOR).roundToInt()
        else
            (HALF * speed).roundToInt()
    }

    override fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogSliderBinding.inflate(layoutInflater)
        val context = layoutInflater.context

        binding.seekBar.max = fromSpeed(MAXIMUM)
        binding.seekBar.keyProgressIncrement = 1
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                val progress = toSpeed(p1)
                binding.textView.text = context.getString(R.string.ui_speed, progress)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })
        binding.resetBtn.setOnClickListener {
            number = 1.0
        }
        binding.btnMinus.visibility = View.VISIBLE
        binding.btnPlus.visibility = View.VISIBLE
        val onClick = { delta: Double ->
            number = (number!! + delta).coerceIn(MINIMUM, MAXIMUM)
        }
        binding.btnMinus.setOnClickListener { onClick(-STEP) }
        binding.btnPlus.setOnClickListener { onClick(STEP) }
        // The two halves use different progress scales, but keys still step by 0.01x.
        binding.seekBar.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> -STEP
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> STEP
                else -> return@setOnKeyListener false
            }
            onClick(if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -delta else delta)
            true
        }
        binding.textView.isAllCaps = true // match appearance in controls

        return binding.root
    }

    override fun isInteger(): Boolean = false

    override var number: Double?
        set(v) { binding.seekBar.progress = fromSpeed(v!!) }
        get() = toSpeed(binding.seekBar.progress)
}
