package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.DialogTrackBinding
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView

internal typealias Listener = (MPVView.Track, Boolean) -> Unit

internal class SubTrackDialog(private val player: MPVView) {
    private lateinit var binding: DialogTrackBinding

    private var tracks = listOf<MPVView.Track>()
    private var secondary = false
    // ID of the selected primary track
    private var selectedMpvId = -1
    // ID of the selected secondary track
    private var selectedMpvId2 = -1

    // When the user selects a track while the dialog is open, mpv may take a short moment
    // to reflect the new value in its properties. Keep an optimistic "pending" selection so
    // the UI updates immediately without requiring the dialog to be reopened.
    private var pendingMpvId: Int? = null
    private var pendingMpvId2: Int? = null

    var listener: Listener? = null

    fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogTrackBinding.inflate(layoutInflater)

        binding.primaryBtn.setOnClickListener {
            secondary = false
            refresh()
        }
        binding.secondaryBtn.setOnClickListener {
            secondary = true
            refresh()
        }

        // Set up recycler view
        binding.list.adapter = CustomAdapter(this)
        refresh()

        Utils.handleInsetsAsPadding(binding.root)
        return binding.root
    }

    fun refresh() {
        tracks = player.tracks.getValue(TRACK_TYPE)

        val sidNow = player.sid
        val sid2Now = player.secondarySid

        // Prefer pending values (set by clicks) until mpv reflects them, then clear.
        selectedMpvId = pendingMpvId?.let {
            if (sidNow == it) {
                pendingMpvId = null
                sidNow
            } else it
        } ?: sidNow

        selectedMpvId2 = pendingMpvId2?.let {
            if (sid2Now == it) {
                pendingMpvId2 = null
                sid2Now
            } else it
        } ?: sid2Now

        // this is what you get for not using a proper tab view...
        val darkenDrawable = ContextCompat.getDrawable(binding.root.context, R.drawable.alpha_darken)
        binding.primaryBtn.background = if (secondary) null else darkenDrawable
        binding.secondaryBtn.background = if (secondary) darkenDrawable else null

        // Show primary/secondary toggle when there is at least one real subtitle track.
        // `tracks` always contains a pseudo "Off" track (mpvId == -1).
        // - size == 1 -> only "Off" (no subtitles)
        // - size >= 2 -> at least one real subtitle track
        if (secondary || selectedMpvId2 != -1 || tracks.size > 1) {
            binding.buttonRow.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.buttonRow.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        binding.list.adapter!!.notifyDataSetChanged()
        val index = tracks.indexOfFirst { it.mpvId == if (secondary) selectedMpvId2 else selectedMpvId }
        if (index >= 0) {
            binding.list.scrollToPosition(index)
        }

        // should fix a layout bug with empty space that happens on api 33
        binding.list.post {
            binding.list.parent.requestLayout()
        }
    }

    private fun clickItem(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val item = tracks.getOrNull(position) ?: return

        // Update local selection immediately so the radio/check circle changes instantly.
        if (secondary) {
            pendingMpvId2 = item.mpvId
            selectedMpvId2 = item.mpvId
        } else {
            pendingMpvId = item.mpvId
            selectedMpvId = item.mpvId
        }

        // Re-bind visible rows with the new selection state.
        // (Do NOT rely on player.sid/secondarySid here; mpv updates those asynchronously.)
        binding.list.adapter?.notifyDataSetChanged()

        listener?.invoke(item, secondary)
    }

    class CustomAdapter(private val parent: SubTrackDialog) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        class ViewHolder(private val parent: SubTrackDialog, view: View) :
            RecyclerView.ViewHolder(view) {
            private val textView: CheckedTextView

            init {
                textView = ViewCompat.requireViewById(view, android.R.id.text1)
                view.setOnClickListener {
                    parent.clickItem(bindingAdapterPosition)
                }
            }

            fun bind(track: MPVView.Track, checked: Boolean, disabled: Boolean) {
                with (textView) {
                    text = track.name
                    isChecked = checked
                    isEnabled = !disabled
                }
            }
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.dialog_track_item, viewGroup, false)
            return ViewHolder(parent, view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
            val track = parent.tracks[position]
            var (checked, disabled) = if (parent.secondary) {
                Pair(track.mpvId == parent.selectedMpvId2, track.mpvId == parent.selectedMpvId)
            } else {
                Pair(track.mpvId == parent.selectedMpvId, track.mpvId == parent.selectedMpvId2)
            }
            // selectedMpvId2 may be -1 but this special entry is for disabling a track
            if (track.mpvId == -1)
                disabled = false
            viewHolder.bind(track, checked, disabled)
        }

        override fun getItemCount() = parent.tracks.size
    }

    companion object {
        const val TRACK_TYPE = "sub"
    }
}
