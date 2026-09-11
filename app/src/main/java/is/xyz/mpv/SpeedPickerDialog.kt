package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogSpeedBinding
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import kotlin.math.roundToInt

internal class SpeedPickerDialog : PickerDialog {
    companion object {
        // Speed limits in hundredths (0.20x to 6.00x).
        private const val MINIMUM = 20
        private const val MAXIMUM = 600
        private const val SCALE_FACTOR = 100.0
    }

    private lateinit var binding: DialogSpeedBinding

    private fun toSpeed(it: Int): Double {
        return (it + MINIMUM) / SCALE_FACTOR
    }

    private fun fromSpeed(it: Double): Int {
        return (it * SCALE_FACTOR).roundToInt().coerceIn(MINIMUM, MAXIMUM) - MINIMUM
    }

    override fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogSpeedBinding.inflate(layoutInflater)
        val context = layoutInflater.context

        binding.seekBar.max = MAXIMUM - MINIMUM
        binding.seekBar.keyProgressIncrement = 1
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                val progress = toSpeed(p1)
                binding.textView.text = context.getString(R.string.ui_speed, progress)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })
        binding.btnMinus.setOnClickListener {
            binding.seekBar.incrementProgressBy(-1)
        }
        binding.btnPlus.setOnClickListener {
            binding.seekBar.incrementProgressBy(1)
        }
        binding.resetBtn.setOnClickListener {
            number = 1.0
        }
        binding.textView.isAllCaps = true // match appearance in controls
        number = 1.0

        return binding.root
    }

    override fun isInteger(): Boolean = false

    override var number: Double?
        set(v) { binding.seekBar.progress = fromSpeed(v!!) }
        get() = toSpeed(binding.seekBar.progress)
}
