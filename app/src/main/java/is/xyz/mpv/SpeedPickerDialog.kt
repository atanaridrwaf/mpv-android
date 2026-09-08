package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogSliderBinding
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import kotlin.math.max
import kotlin.math.roundToInt

internal class SpeedPickerDialog : PickerDialog {
    companion object {
        private const val MINIMUM = 0.2
        private const val MAXIMUM = 6.0
        private const val STEP = 0.01
        // Progress units per 1.0x, so each unit changes speed by 0.01x.
        private const val SCALE_FACTOR = 100.0
    }

    private lateinit var binding: DialogSliderBinding

    private fun toSpeed(it: Int): Double {
        return max(MINIMUM, it / SCALE_FACTOR)
    }

    private fun fromSpeed(it: Double): Int {
        return (it.coerceIn(MINIMUM, MAXIMUM) * SCALE_FACTOR).roundToInt()
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
        binding.stepButtons.visibility = View.VISIBLE
        val onClick = { delta: Double ->
            number = (number!! + delta).coerceIn(MINIMUM, MAXIMUM)
        }
        binding.btnMinus.setOnClickListener { onClick(-STEP) }
        binding.btnPlus.setOnClickListener { onClick(STEP) }
        binding.textView.isAllCaps = true // match appearance in controls

        return binding.root
    }

    override fun isInteger(): Boolean = false

    override var number: Double?
        set(v) { binding.seekBar.progress = fromSpeed(v!!) }
        get() = toSpeed(binding.seekBar.progress)
}
