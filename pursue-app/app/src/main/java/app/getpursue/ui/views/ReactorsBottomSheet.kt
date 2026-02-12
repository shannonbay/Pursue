package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ReactorEntry
import app.getpursue.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet showing the full list of reactors for an activity.
 *
 * Displays tabs: All (N) + emoji tabs with counts.
 * Each row shows avatar, display name, and emoji.
 */
class ReactorsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ACTIVITY_ID = "activity_id"

        fun show(fragmentManager: FragmentManager, activityId: String) {
            ReactorsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACTIVITY_ID, activityId)
                }
            }.show(fragmentManager, "ReactorsBottomSheet")
        }
    }

    private lateinit var loadingIndicator: ProgressBar
    private lateinit var reactorsList: RecyclerView
    private lateinit var tabLayout: TabLayout

    private var activityId: String = ""
    private var reactions: List<ReactorEntry> = emptyList()
    private var sortedEmojis: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        activityId = arguments?.getString(ARG_ACTIVITY_ID) ?: ""
        return inflater.inflate(R.layout.bottom_sheet_reactors, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadingIndicator = view.findViewById(R.id.loading_indicator)
        reactorsList = view.findViewById(R.id.reactors_list)
        tabLayout = view.findViewById(R.id.tab_layout)

        view.findViewById<MaterialButton>(R.id.button_close).setOnClickListener {
            dismiss()
        }

        reactorsList.layoutManager = LinearLayoutManager(requireContext())

        loadReactions()
    }

    private fun loadReactions() {
        loadingIndicator.visibility = View.VISIBLE
        reactorsList.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                    ?: run {
                        loadingIndicator.visibility = View.GONE
                        return@launch
                    }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getReactions(accessToken, activityId)
                }

                reactions = response.reactions

                loadingIndicator.visibility = View.GONE
                reactorsList.visibility = View.VISIBLE

                setupTabs()
                updateList("all")
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun setupTabs() {
        tabLayout.removeAllTabs()

        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.reactions_tab_all, reactions.size)))

        val emojiCounts = reactions.groupBy { it.emoji }.mapValues { it.value.size }
        sortedEmojis = emojiCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }

        sortedEmojis.forEach { emoji ->
            tabLayout.addTab(tabLayout.newTab().setText("$emoji ${emojiCounts[emoji]!!}"))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val filter = when (tab?.position) {
                    0 -> "all"
                    else -> sortedEmojis.getOrNull((tab?.position ?: 1) - 1) ?: "all"
                }
                updateList(filter)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateList(filter: String) {
        val filtered = when (filter) {
            "all" -> reactions
            else -> reactions.filter { it.emoji == filter }
        }
        reactorsList.adapter = ReactorAdapter(filtered)
    }

    private class ReactorAdapter(
        private val entries: List<ReactorEntry>
    ) : RecyclerView.Adapter<ReactorAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reactor, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val avatar: ImageView = itemView.findViewById(R.id.reactor_avatar)
            private val avatarFallback: TextView = itemView.findViewById(R.id.reactor_avatar_fallback)
            private val name: TextView = itemView.findViewById(R.id.reactor_name)
            private val emoji: TextView = itemView.findViewById(R.id.reactor_emoji)

            fun bind(entry: ReactorEntry) {
                name.text = entry.user.display_name
                emoji.text = entry.emoji

                val imageUrl = entry.user.avatar_url?.let { url ->
                    if (url.startsWith("http")) url else {
                        val base = ApiClient.getBaseUrl()
                        val origin = base.substringBeforeLast("/")
                        "$origin$url"
                    }
                }

                if (!imageUrl.isNullOrBlank()) {
                    avatar.visibility = View.VISIBLE
                    avatarFallback.visibility = View.GONE
                    Glide.with(itemView.context)
                        .load(imageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .circleCrop()
                        .error(R.drawable.ic_pursue_logo)
                        .into(avatar)
                } else {
                    avatar.visibility = View.GONE
                    avatarFallback.visibility = View.VISIBLE
                    val initial = entry.user.display_name.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar() ?: '?'
                    avatarFallback.text = initial.toString()
                }
            }
        }
    }
}
