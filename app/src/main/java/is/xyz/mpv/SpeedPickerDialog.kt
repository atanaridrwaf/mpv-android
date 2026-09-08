package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogDecimalBinding
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import java.math.BigDecimal

internal class SpeedPickerDialog : PickerDialog {
    companion object {
        // Preserve the original speed picker's range and reset value.
        private const val MINIMUM = 0.2
        private const val MAXIMUM = 6.0
        private const val DEFAULT_SPEED = 1.0
        private val STEP = BigDecimal("0.10")
    }

    private lateinit var binding: DialogDecimalBinding

    override fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogDecimalBinding.inflate(layoutInflater)

        // Use the same single-row controls as the audio delay picker.
        arrayOf(binding.label1, binding.label2, binding.rowSecondary).forEach {
            it.visibility = View.GONE
        }

        binding.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
                val valueBounded = value.coerceIn(MINIMUM, MAXIMUM)
                if (valueBounded != value)
                    number = valueBounded
            }
        })
        binding.btnMinus.setOnClickListener { adjustSpeed(-STEP) }
        binding.btnPlus.setOnClickListener { adjustSpeed(STEP) }
        binding.resetBtn.visibility = View.VISIBLE
        binding.resetBtn.setOnClickListener {
            number = DEFAULT_SPEED
        }

        return binding.root
    }

    private fun adjustSpeed(delta: BigDecimal) {
        // Decimal arithmetic keeps repeated 0.10 steps free of floating-point drift.
        val value = (number ?: DEFAULT_SPEED).toBigDecimal()
        number = (value + delta).toDouble()
    }

    override fun isInteger(): Boolean = false

    override var number: Double?
        set(v) {
            val value = (v?.takeIf { it.isFinite() } ?: DEFAULT_SPEED).coerceIn(MINIMUM, MAXIMUM)
            val decimal = value.toBigDecimal().stripTrailingZeros()
            // Show at least two decimal places without rounding a manually entered speed.
            binding.editText.setText(decimal.setScale(maxOf(2, decimal.scale())).toPlainString())
        }
        get() = binding.editText.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
}
