package app.getpursue.ui.fragments.sessions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.FocusSlot
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Personal sessions calendar — shows all upcoming RSVPed/created slots across groups,
 * grouped by day (Today / Tomorrow / weekday name).
 */
class FocusSlotsFragment : Fragment() {

    companion object {
        fun newInstance() = FocusSlotsFragment()
    }

    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var slotsRecycler: RecyclerView

    private val slotItems = mutableListOf<SlotListItem>()
    private lateinit var slotsAdapter: SlotsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_focus_slots, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadingView = view.findViewById(R.id.slots_loading)
        errorView = view.findViewById(R.id.slots_error)
        emptyView = view.findViewById(R.id.slots_empty)
        slotsRecycler = view.findViewById(R.id.slots_recycler)

        view.findViewById<MaterialButton>(R.id.btn_retry_slots)?.setOnClickListener { loadSlots() }

        slotsAdapter = SlotsAdapter(slotItems) { slot, isRsvped ->
            toggleRsvp(slot, isRsvped)
        }
        slotsRecycler.layoutManager = LinearLayoutManager(requireContext())
        slotsRecycler.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        slotsRecycler.adapter = slotsAdapter

        loadSlots()
    }

    override fun onResume() {
        super.onResume()
        loadSlots()
    }

    private fun loadSlots() {
        setState(State.LOADING)
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                SecureTokenManager.getInstance(requireContext()).getAccessToken()
            } ?: return@launch

            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getMySlots(token) }
                val upcoming = response.slots.filter { slot ->
                    slot.cancelled_at == null && isInFuture(slot.scheduled_start)
                }.sortedBy { it.scheduled_start }

                if (upcoming.isEmpty()) {
                    setState(State.EMPTY)
                    return@launch
                }

                slotItems.clear()
                slotItems.addAll(buildSlotListItems(upcoming))
                slotsAdapter.notifyDataSetChanged()
                setState(State.SUCCESS)
            } catch (e: Exception) {
                setState(State.ERROR)
            }
        }
    }

    private fun toggleRsvp(slot: FocusSlot, currentlyRsvped: Boolean) {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                SecureTokenManager.getInstance(requireContext()).getAccessToken()
            } ?: return@launch

            try {
                withContext(Dispatchers.IO) {
                    if (currentlyRsvped) {
                        ApiClient.unrsvpFocusSlot(token, slot.group_id, slot.id)
                    } else {
                        ApiClient.rsvpFocusSlot(token, slot.group_id, slot.id)
                    }
                }
                loadSlots()
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), getString(R.string.focus_slots_rsvp_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildSlotListItems(slots: List<FocusSlot>): List<SlotListItem> {
        val items = mutableListOf<SlotListItem>()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        var lastHeader = ""

        slots.forEach { slot ->
            val dateTime = try {
                OffsetDateTime.parse(slot.scheduled_start)
            } catch (_: Exception) { return@forEach }

            val slotDate = dateTime.toLocalDate()
            val header = when (slotDate) {
                today -> getString(R.string.focus_slots_today)
                tomorrow -> getString(R.string.focus_slots_tomorrow)
                else -> slotDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
            }
            if (header != lastHeader) {
                items.add(SlotListItem.Header(header))
                lastHeader = header
            }
            items.add(SlotListItem.Slot(slot, dateTime))
        }
        return items
    }

    private fun isInFuture(isoDateTime: String): Boolean {
        return try {
            OffsetDateTime.parse(isoDateTime).isAfter(OffsetDateTime.now())
        } catch (_: Exception) { false }
    }

    private fun setState(state: State) {
        loadingView.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        errorView.visibility = if (state == State.ERROR) View.VISIBLE else View.GONE
        emptyView.visibility = if (state == State.EMPTY) View.VISIBLE else View.GONE
        slotsRecycler.visibility = if (state == State.SUCCESS) View.VISIBLE else View.GONE
    }

    private enum class State { LOADING, SUCCESS, EMPTY, ERROR }

    // --- List item types ---

    sealed class SlotListItem {
        data class Header(val label: String) : SlotListItem()
        data class Slot(val slot: FocusSlot, val dateTime: OffsetDateTime) : SlotListItem()
    }

    // --- Adapter ---

    inner class SlotsAdapter(
        private val items: List<SlotListItem>,
        private val onRsvpToggle: (FocusSlot, Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val VIEW_HEADER = 0
        private val VIEW_SLOT = 1

        override fun getItemViewType(position: Int) =
            if (items[position] is SlotListItem.Header) VIEW_HEADER else VIEW_SLOT

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == VIEW_HEADER) {
                val tv = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                tv.textSize = 12f
                tv.alpha = 0.6f
                tv.setPadding(32, 24, 16, 4)
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_focus_slot, parent, false)
                SlotViewHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SlotListItem.Header -> (holder.itemView as TextView).text = item.label
                is SlotListItem.Slot -> (holder as SlotViewHolder).bind(item.slot, item.dateTime)
            }
        }

        inner class SlotViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val slotTime: TextView = itemView.findViewById(R.id.slot_time)
            private val slotGroupDuration: TextView = itemView.findViewById(R.id.slot_group_duration)
            private val slotNote: TextView = itemView.findViewById(R.id.slot_note)
            private val slotRsvpCount: TextView = itemView.findViewById(R.id.slot_rsvp_count)
            private val btnRsvp: MaterialButton = itemView.findViewById(R.id.btn_slot_rsvp)

            fun bind(slot: FocusSlot, dateTime: OffsetDateTime) {
                slotTime.text = dateTime.format(
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                )
                val groupLabel = slot.group_name ?: ""
                slotGroupDuration.text = if (groupLabel.isNotEmpty()) {
                    "$groupLabel · ${slot.focus_duration_minutes} min"
                } else {
                    "${slot.focus_duration_minutes} min"
                }

                if (!slot.note.isNullOrBlank()) {
                    slotNote.text = slot.note
                    slotNote.visibility = View.VISIBLE
                } else {
                    slotNote.visibility = View.GONE
                }

                val rsvpCount = slot.rsvp_count ?: 0
                slotRsvpCount.text = if (rsvpCount > 0) {
                    getString(R.string.focus_card_slot_rsvp_count, rsvpCount)
                } else ""

                val userRsvped = slot.user_rsvped == true
                if (userRsvped) {
                    btnRsvp.text = getString(R.string.focus_session_rsvp_cancel)
                    btnRsvp.setBackgroundColor(0xFF757575.toInt())
                } else {
                    btnRsvp.text = getString(R.string.focus_slots_im_in)
                    btnRsvp.setBackgroundColor(0xFF1976D2.toInt())
                }
                btnRsvp.setOnClickListener { onRsvpToggle(slot, userRsvped) }
            }
        }
    }
}
