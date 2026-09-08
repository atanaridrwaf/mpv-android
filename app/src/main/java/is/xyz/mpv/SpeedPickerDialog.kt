package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogSpeedBinding
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import java.util.Locale
import kotlin.math.roundToInt

internal class SpeedPickerDialog : PickerDialog {
    companion object {
        // Keep the existing 0.20x–6.00x range, with 0.01x steps throughout.
        private const val MINIMUM = 0.2
        private const val MAXIMUM = 6.0
        private const val SCALE_FACTOR = 100.0
    }

    private lateinit var binding: DialogSpeedBinding
    private var updatingControls = false

    private fun toSpeed(progress: Int): Double =
        (MINIMUM * SCALE_FACTOR + progress) / SCALE_FACTOR

    private fun fromSpeed(speed: Double): Int =
        ((speed.coerceIn(MINIMUM, MAXIMUM) - MINIMUM) * SCALE_FACTOR).roundToInt()

    private fun readInput(): Double? {
        val text = binding.editText.text.toString().trim().map { character ->
            val digit = Character.digit(character, 10)
            when {
                digit >= 0 -> '0' + digit
                character == ',' || character == '\u066B' -> '.'
                else -> character
            }
        }.joinToString("")
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun updateControls(speed: Double) {
        val progress = fromSpeed(speed)
        val value = toSpeed(progress)
        updatingControls = true
        binding.seekBar.progress = progress
        binding.textView.text = binding.root.context.getString(R.string.ui_speed, value)
        binding.editText.setText(String.format(Locale.ROOT, "%.2f", value))
        binding.editText.setSelection(binding.editText.text.length)
        updatingControls = false
    }

    override fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogSpeedBinding.inflate(layoutInflater)
        val context = layoutInflater.context

        binding.seekBar.max = fromSpeed(MAXIMUM)
        binding.seekBar.keyProgressIncrement = 1
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                if (!updatingControls)
                    updateControls(toSpeed(p1))
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })
        binding.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (updatingControls)
                    return
                val value = readInput() ?: return
                // Leave incomplete input (such as "0.") alone while typing.
                // Clamp and round only when applying the value or using a control.
                if (value !in MINIMUM..MAXIMUM)
                    return
                updatingControls = true
                binding.seekBar.progress = fromSpeed(value)
                binding.textView.text = context.getString(R.string.ui_speed, value)
                updatingControls = false
            }
        })
        val step = { delta: Int ->
            val progress = readInput()?.let { fromSpeed(it) } ?: binding.seekBar.progress
            updateControls(toSpeed((progress + delta).coerceIn(0, binding.seekBar.max)))
        }
        binding.btnMinus.setOnClickListener { step(-1) }
        binding.btnPlus.setOnClickListener { step(1) }
        binding.resetBtn.setOnClickListener {
            number = 1.0
        }
        binding.textView.isAllCaps = true // match appearance in controls
        number = 1.0

        return binding.root
    }

    override fun isInteger(): Boolean = false

    override var number: Double?
        set(v) { updateControls(v?.takeIf { it.isFinite() } ?: 1.0) }
        get() {
            val value = readInput() ?: return null
            updateControls(value)
            return toSpeed(binding.seekBar.progress)
        }
}
