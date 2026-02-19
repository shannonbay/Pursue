package app.getpursue.ui.views

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.getpursue.R
import app.getpursue.utils.IconUrlUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Bottom sheet for selecting group icon (icons, emoji, color, or upload).
 *
 * Implements the icon picker UI from section 4.8 of the UI spec.
 */
class IconPickerBottomSheet : BottomSheetDialogFragment() {

    interface IconSelectionListener {
        fun onIconSelected(emoji: String?, color: String?, iconUrl: String? = null)
    }

    private var listener: IconSelectionListener? = null
    private var selectedEmoji: String? = null
    private var selectedColor: String = getRandomDefaultColor()
    private var selectedIconUrl: String? = null

    fun setIconSelectionListener(listener: IconSelectionListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_icon_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply initial selection (e.g. for Edit Group) before tabs read state
        arguments?.getString(ARG_INITIAL_EMOJI)?.let { setSelectedEmoji(it) }
        val initColor = arguments?.getString(ARG_INITIAL_COLOR)
        if (initColor != null) setSelectedColor(initColor)
        else if (arguments?.getString(ARG_INITIAL_EMOJI) != null) setSelectedColor(getRandomDefaultColor())

        val titleText = view.findViewById<TextView>(R.id.title)
        val titleRes = arguments?.getInt(ARG_TITLE_RES, R.string.icon_picker_title)
            ?: R.string.icon_picker_title
        titleText.setText(titleRes)

        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = view.findViewById<ViewPager2>(R.id.view_pager)
        val closeButton = view.findViewById<MaterialButton>(R.id.button_close)

        // Setup ViewPager with tabs
        val adapter = IconPickerPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.icons_tab)
                1 -> getString(R.string.emoji_tab)
                2 -> getString(R.string.upload_tab)
                3 -> getString(R.string.color_tab)
                else -> ""
            }
        }.attach()

        closeButton.setOnClickListener {
            dismiss()
        }
    }

    fun getSelectedEmoji(): String? = selectedEmoji
    fun getSelectedColor(): String = selectedColor
    fun getSelectedIconUrl(): String? = selectedIconUrl

    fun setSelectedEmoji(emoji: String?) {
        selectedEmoji = emoji
    }

    fun setSelectedColor(color: String) {
        selectedColor = color
    }

    fun setSelectedIconUrl(iconUrl: String?) {
        selectedIconUrl = iconUrl
    }

    fun notifyIconSelected() {
        listener?.onIconSelected(selectedEmoji, selectedColor, selectedIconUrl)
        dismiss()
    }

    /**
     * ViewPager adapter for icon picker tabs.
     */
    private class IconPickerPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> IconsGridFragment()
                1 -> EmojiPickerFragment()
                2 -> UploadPickerFragment()
                3 -> ColorPickerFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }

    /**
     * Bundled icons grid fragment.
     */
    class IconsGridFragment : Fragment() {

        data class IconItem(val drawableName: String, val label: String, val category: String)

        private val icons = listOf(
            // Fitness & Health
            IconItem("ic_icon_running", "Running", "Fitness"),
            IconItem("ic_icon_walking", "Walking", "Fitness"),
            IconItem("ic_icon_planking", "Planking", "Fitness"),
            IconItem("ic_icon_strength", "Strength", "Fitness"),
            IconItem("ic_icon_steps", "Steps", "Fitness"),
            IconItem("ic_icon_salad", "Salad", "Health"),
            IconItem("ic_icon_coldshower", "Cold Shower", "Health"),
            IconItem("ic_icon_sleep", "Sleep", "Health"),
            IconItem("ic_icon_brain", "Mindset", "Health"),
            
            // Diet & Cooking
            IconItem("ic_icon_frypan", "Cooking", "Diet"),
            
            // Learning & Productivity
            IconItem("ic_icon_book", "Reading", "Learning"),
            IconItem("ic_icon_books", "Studying", "Learning"),
            IconItem("ic_icon_journal", "Journal", "Learning"),
            IconItem("ic_icon_laptop", "Coding", "Learning"),
            IconItem("ic_icon_speaking", "Public Speaking", "Learning"),
            IconItem("ic_icon_inbox", "Inbox Zero", "Productivity"),
            IconItem("ic_icon_alarmclock", "Waking Up", "Productivity"),
            IconItem("ic_icon_lightning", "Focus", "Productivity"),
            IconItem("ic_icon_socialmediaban", "Social Media Ban", "Productivity"),
            
            // Finance & Faith
            IconItem("ic_icon_cash", "Saving", "Finance"),
            IconItem("ic_icon_budgeting", "Budgeting", "Finance"),
            IconItem("ic_icon_prayer", "Prayer", "Faith"),
            IconItem("ic_icon_prayerhands", "Prayer Hands", "Faith"),
            
            // General
            IconItem("ic_icon_sunrise", "Early Morning", "General"),
            IconItem("ic_icon_phone", "Screen Time", "General")
        )

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_icon_picker_icons, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val iconsGrid = view.findViewById<RecyclerView>(R.id.icons_grid)
            val adapter = IconGridAdapter(requireContext(), icons) { iconItem ->
                val bottomSheet = parentFragment as? IconPickerBottomSheet
                bottomSheet?.setSelectedIconUrl("res://drawable/${iconItem.drawableName}")
                bottomSheet?.setSelectedEmoji(null)
                bottomSheet?.notifyIconSelected()
            }

            iconsGrid.layoutManager = GridLayoutManager(requireContext(), 4)
            iconsGrid.adapter = adapter
        }
    }

    /**
     * Emoji picker fragment.
     */
    class EmojiPickerFragment : Fragment() {
        private val emojis = listOf(
            "🏃", "💪", "📚", "🎯", "🎨", "💻", "🍎", "⚽",
            "🏋️", "🧘", "🚴", "🏊", "🎵", "✍️", "🌟", "🔥",
            "💼", "📊", "🎓", "🌱", "☕", "🏡", "✨", "🎉",
            "🎮", "📷", "🎬", "🎸", "🏀", "⚡", "🌈", "🎪",
            "🌅", "📖", "💧", "☀️", "🌙", "⭐", "🚀", "🎤",
            "📝", "🌿", "🍃", "🎭", "🏖️", "🥗", "🎧", "🌺"
        )

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_icon_picker_emoji, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val searchEdit = view.findViewById<TextInputEditText>(R.id.search_edit)
            val emojiGrid = view.findViewById<RecyclerView>(R.id.emoji_grid)

            val adapter = EmojiAdapter(emojis) { emoji ->
                val bottomSheet = parentFragment as? IconPickerBottomSheet
                bottomSheet?.setSelectedEmoji(emoji)
                bottomSheet?.setSelectedIconUrl(null)
                // If no color selected yet, use random default
                if (bottomSheet?.getSelectedColor() == null) {
                    bottomSheet?.setSelectedColor(getRandomDefaultColor())
                }
                bottomSheet?.notifyIconSelected()
            }

            emojiGrid.layoutManager = GridLayoutManager(requireContext(), 4)
            emojiGrid.adapter = adapter

            // Search functionality
            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.lowercase() ?: ""
                    val filtered = if (query.isEmpty()) {
                        emojis
                    } else {
                        emojis.filter { it.contains(query, ignoreCase = true) }
                    }
                    adapter.updateEmojis(filtered)
                }
            })
        }
    }

    /**
     * Upload picker fragment (placeholder for MVP).
     */
    class UploadPickerFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_icon_picker_upload, container, false)
        }
    }

    /**
     * Color picker fragment.
     */
    class ColorPickerFragment : Fragment() {
        private val colors = listOf(
            "#1976D2" to R.string.color_blue,
            "#F9A825" to R.string.color_yellow,
            "#388E3C" to R.string.color_green,
            "#D32F2F" to R.string.color_red,
            "#7B1FA2" to R.string.color_purple,
            "#F57C00" to R.string.color_orange,
            "#C2185B" to R.string.color_pink,
            "#00796B" to R.string.color_teal
        )

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_icon_picker_color, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val colorGrid = view.findViewById<RecyclerView>(R.id.color_grid)
            val previewEmoji = view.findViewById<TextView>(R.id.preview_emoji)
            val previewContainer = view.findViewById<View>(R.id.color_preview)

            val bottomSheet = parentFragment as? IconPickerBottomSheet
            val currentEmoji = bottomSheet?.getSelectedEmoji() ?: "🏃"
            previewEmoji.text = currentEmoji

            val currentColor = bottomSheet?.getSelectedColor() ?: getRandomDefaultColor()
            previewContainer.setBackgroundColor(Color.parseColor(currentColor))

            val adapter = ColorAdapter(colors) { color ->
                bottomSheet?.setSelectedColor(color)
                previewContainer.setBackgroundColor(Color.parseColor(color))
                bottomSheet?.setSelectedIconUrl(null)
                // Notify parent of color change and dismiss bottom sheet
                bottomSheet?.notifyIconSelected()
            }

            colorGrid.layoutManager = GridLayoutManager(requireContext(), 4)
            colorGrid.adapter = adapter
        }
    }

    /**
     * Emoji adapter for RecyclerView.
     */
    private class EmojiAdapter(
        private var emojis: List<String>,
        private val onEmojiClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

        class EmojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val emojiText: TextView = itemView as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_emoji, parent, false)
            return EmojiViewHolder(view)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            holder.emojiText.text = emojis[position]
            holder.itemView.setOnClickListener {
                onEmojiClick(emojis[position])
            }
        }

        override fun getItemCount(): Int = emojis.size

        fun updateEmojis(newEmojis: List<String>) {
            emojis = newEmojis
            notifyDataSetChanged()
        }
    }

    /**
     * Icon grid adapter for bundled icon drawables.
     */
    private class IconGridAdapter(
        private val context: android.content.Context,
        private val icons: List<IconsGridFragment.IconItem>,
        private val onIconClick: (IconsGridFragment.IconItem) -> Unit
    ) : RecyclerView.Adapter<IconGridAdapter.IconViewHolder>() {

        class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iconImage: ImageView = itemView.findViewById(R.id.icon_image)
            val iconLabel: TextView = itemView.findViewById(R.id.icon_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_icon_picker_icon, parent, false)
            return IconViewHolder(view)
        }

        override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
            val item = icons[position]
            val resId = IconUrlUtils.resolveDrawableResId(context, "res://drawable/${item.drawableName}")
            if (resId != 0) {
                holder.iconImage.setImageResource(resId)
            }
            holder.iconLabel.text = item.label
            holder.itemView.setOnClickListener {
                onIconClick(item)
            }
        }

        override fun getItemCount(): Int = icons.size
    }

    /**
     * Color adapter for RecyclerView.
     */
    private class ColorAdapter(
        private val colors: List<Pair<String, Int>>,
        private val onColorClick: (String) -> Unit
    ) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {

        class ColorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_color, parent, false)
            return ColorViewHolder(view)
        }

        override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
            val (colorHex, _) = colors[position]
            holder.itemView.setBackgroundColor(Color.parseColor(colorHex))
            holder.itemView.setOnClickListener {
                onColorClick(colorHex)
            }
        }

        override fun getItemCount(): Int = colors.size
    }

    companion object {
        private const val ARG_TITLE_RES = "title_res"
        private const val ARG_INITIAL_EMOJI = "initial_emoji"
        private const val ARG_INITIAL_COLOR = "initial_color"

        // Available icon background colors
        private val AVAILABLE_COLORS = listOf(
            "#1976D2", // Blue
            "#F9A825", // Yellow
            "#388E3C", // Green
            "#D32F2F", // Red
            "#7B1FA2", // Purple
            "#F57C00", // Orange
            "#C2185B", // Pink
            "#00796B"  // Teal
        )

        /**
         * Returns a randomly selected color from the available icon background colors.
         */
        fun getRandomDefaultColor(): String {
            return AVAILABLE_COLORS.random()
        }

        fun newInstance(
            titleRes: Int = R.string.icon_picker_title,
            initialEmoji: String? = null,
            initialColor: String? = null
        ): IconPickerBottomSheet {
            return IconPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE_RES, titleRes)
                    initialEmoji?.let { putString(ARG_INITIAL_EMOJI, it) }
                    initialColor?.let { putString(ARG_INITIAL_COLOR, it) }
                }
            }
        }
    }
}
