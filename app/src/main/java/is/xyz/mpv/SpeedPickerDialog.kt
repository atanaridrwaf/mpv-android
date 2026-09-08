package `is`.xyz.mpv

// Use the same minus/value/plus control as audio delay, keeping the original speed range.
internal class SpeedPickerDialog : PickerDialog by DecimalPickerDialog(0.2, 6.0, step = 0.01)
