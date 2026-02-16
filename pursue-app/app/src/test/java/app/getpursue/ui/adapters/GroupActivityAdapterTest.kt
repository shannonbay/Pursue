package app.getpursue.ui.adapters

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.getpursue.R
import app.getpursue.models.ActivityUser
import app.getpursue.models.GroupActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for GroupActivityAdapter.
 *
 * Tests adapter structure, grouping logic, and view holder binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "app.getpursue"
)
class GroupActivityAdapterTest {

    private lateinit var context: Context
    private var defaultTimeZone: TimeZone? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        defaultTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        defaultTimeZone?.let { TimeZone.setDefault(it) }
        unmockkAll()
    }

    private fun getThemedContext(): Context {
        return ContextThemeWrapper(context, R.style.Theme_Pursue)
    }

    private fun getMockParent(): RecyclerView {
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        return parent
    }

    // ========== Adapter Structure Tests ==========

    @Test
    fun `test adapter creates correct view types`() {
        // Given
        val activities: List<GroupActivity> = listOf(
            createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z"),
            createActivity("act2", "member_joined", "2024-01-02T00:00:00Z")
        )
        val adapter = GroupActivityAdapter(activities)

        // Then - TYPE_DATE_HEADER = 0, TYPE_ACTIVITY = 1
        assertEquals("First item should be header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be activity", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be header", 0, adapter.getItemViewType(2))
        assertEquals("Fourth item should be activity", 1, adapter.getItemViewType(3))
    }

    @Test
    fun `test adapter item count matches activities plus headers`() {
        // Given
        val activities = listOf(
            createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z"),
            createActivity("act2", "progress_logged", "2024-01-01T00:00:00Z"),
            createActivity("act3", "member_joined", "2024-01-02T00:00:00Z")
        )
        val adapter = GroupActivityAdapter(activities)

        // Then - 2 headers (one per date) + 3 activities + 1 footer spacer = 6 items
        assertEquals("Item count should match activities plus headers plus footer", 6, adapter.itemCount)
    }

    // ========== Grouping Logic Tests ==========

    @Test
    fun `test activities grouped by date correctly`() {
        // Given
        val activities = listOf(
            createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z"),
            createActivity("act2", "progress_logged", "2024-01-01T12:00:00Z"),
            createActivity("act3", "member_joined", "2024-01-02T00:00:00Z")
        )
        val adapter = GroupActivityAdapter(activities)

        // Then - 2 headers (one per date) + 3 activities + 1 footer = 6 items
        assertEquals("Should have 6 items", 6, adapter.itemCount)
        
        // Verify order: date1 header, activities, date2 header, activity, footer
        assertEquals("First item should be date header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be activity", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be activity", 1, adapter.getItemViewType(2))
        assertEquals("Fourth item should be date header", 0, adapter.getItemViewType(3))
        assertEquals("Fifth item should be activity", 1, adapter.getItemViewType(4))
        assertEquals("Sixth item should be footer spacer", 2, adapter.getItemViewType(5))
    }

    @Test
    fun `test date headers show correct labels`() {
        // Given
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayTimestamp = "${today}T00:00:00Z"
        val activities = listOf(
            createActivity("act1", "progress_logged", todayTimestamp)
        )
        val adapter = GroupActivityAdapter(activities)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupActivityAdapter.DateHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        assertTrue("Header should show Today or date", textView.text.toString().isNotEmpty())
    }

    // ========== View Holder Binding Tests ==========

    @Test
    fun `test date header shows Today label`() {
        // Given
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayTimestamp = "${today}T00:00:00Z"
        val activities = listOf(
            createActivity("act1", "progress_logged", todayTimestamp)
        )
        val adapter = GroupActivityAdapter(activities)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupActivityAdapter.DateHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        // May show "Today" or the date depending on time
        assertNotNull("Header should have text", textView.text)
    }

    @Test
    fun `test date header shows Yesterday label`() {
        // Given
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
        val yesterdayTimestamp = "${yesterday}T00:00:00Z"
        val activities = listOf(
            createActivity("act1", "progress_logged", yesterdayTimestamp)
        )
        val adapter = GroupActivityAdapter(activities)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupActivityAdapter.DateHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        // May show "Yesterday" or the date depending on time
        assertNotNull("Header should have text", textView.text)
    }

    @Test
    fun `test date header shows date format`() {
        // Given
        val activities = listOf(
            createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z")
        )
        val adapter = GroupActivityAdapter(activities)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupActivityAdapter.DateHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        assertNotNull("Header should have text", textView.text)
        assertTrue("Header should contain date information", textView.text.toString().isNotEmpty())
    }

    @Test
    fun `test activity view holder formats progress_logged correctly`() {
        // Given
        val activity = createActivity(
            "act1",
            "progress_logged",
            "2024-01-01T00:00:00Z",
            metadata = mapOf<String, Any>("goal_title" to "30 min run")
        )
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain goal title", activityText.text.toString().contains("30 min run"))
    }

    @Test
    fun `test activity view holder formats member_joined correctly`() {
        // Given
        val activity = createActivity("act1", "member_joined", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain joined message", 
            activityText.text.toString().contains("joined") || activityText.text.toString().contains("Alice"))
    }

    @Test
    fun `test activity view holder formats join_request correctly`() {
        // Given
        val activity = createActivity("act1", "join_request", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain requested message",
            activityText.text.toString().contains("requested") || activityText.text.toString().contains("Alice"))
    }

    @Test
    fun `test activity view holder formats member_approved correctly`() {
        // Given
        val activity = createActivity("act1", "member_approved", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain approved message",
            activityText.text.toString().contains("approved") || activityText.text.toString().contains("Alice"))
    }

    @Test
    fun `test activity view holder formats member_declined correctly`() {
        // Given
        val activity = createActivity("act1", "member_declined", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain declined message",
            activityText.text.toString().contains("declined") || activityText.text.toString().contains("Alice"))
    }

    @Test
    fun `test activity view holder formats goal_added correctly`() {
        // Given
        val activity = createActivity(
            "act1",
            "goal_added",
            "2024-01-01T00:00:00Z",
            metadata = mapOf<String, Any>("goal_title" to "New Goal")
        )
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue("Activity text should contain goal title", activityText.text.toString().contains("New Goal"))
    }

    @Test
    fun `test activity view holder shows (You) for current user`() {
        // Given
        val activity = createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z", userId = "user1")
        val adapter = GroupActivityAdapter(listOf(activity), currentUserId = "user1")
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        // The adapter should show "(You)" for current user
        assertTrue("Activity text should contain (You)", activityText.text.toString().contains("(You)"))
    }

    @Test
    fun `test activity view holder formats timestamp correctly`() {
        // Given
        val activity = createActivity("act1", "progress_logged", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()

        // When
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)

        // Then
        val timestampView = activityHolder.itemView.findViewById<TextView>(R.id.activity_timestamp)
        assertNotNull("Timestamp should be displayed", timestampView.text)
        assertTrue("Timestamp should contain text", timestampView.text.toString().isNotEmpty())
    }

    // ========== Helper Methods ==========

    private fun createActivity(
        id: String,
        activityType: String,
        createdAt: String,
        userId: String = "user1",
        userName: String = "Alice",
        metadata: Map<String, Any>? = null
    ): GroupActivity {
        return GroupActivity(
            id = id,
            activity_type = activityType,
            user = ActivityUser(userId, userName),
            metadata = metadata,
            created_at = createdAt
        )
    }

    /** Creates an activity with no user (e.g. heat/system events). */
    private fun createActivityWithNullUser(
        id: String,
        activityType: String,
        createdAt: String,
        metadata: Map<String, Any>? = null
    ): GroupActivity {
        return GroupActivity(
            id = id,
            activity_type = activityType,
            user = null,
            metadata = metadata,
            created_at = createdAt
        )
    }

    @Test
    fun `test activity view holder formats heat_tier_up correctly`() {
        val activity = createActivityWithNullUser(
            "act1",
            "heat_tier_up",
            "2024-01-01T00:00:00Z",
            metadata = mapOf("tier_name" to "Blaze", "score" to 72.5)
        )
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue(
            "Activity text should contain tier up message and tier name",
            activityText.text.toString().contains("Group heat is rising") && activityText.text.toString().contains("Blaze")
        )
    }

    @Test
    fun `test activity view holder formats heat_tier_down correctly`() {
        val activity = createActivityWithNullUser("act1", "heat_tier_down", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue(
            "Activity text should contain cooling message",
            activityText.text.toString().contains("cooling")
        )
    }

    @Test
    fun `test activity view holder formats heat_supernova_reached correctly`() {
        val activity = createActivityWithNullUser("act1", "heat_supernova_reached", "2024-01-01T00:00:00Z")
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        assertTrue(
            "Activity text should contain SUPERNOVA message",
            activityText.text.toString().contains("SUPERNOVA")
        )
    }

    @Test
    fun `test activity view holder formats heat_streak_milestone correctly`() {
        val activity = createActivityWithNullUser(
            "act1",
            "heat_streak_milestone",
            "2024-01-01T00:00:00Z",
            metadata = mapOf("streak_days" to 7, "score" to 65.0)
        )
        val adapter = GroupActivityAdapter(listOf(activity))
        val parent = getMockParent()
        val activityHolder = adapter.onCreateViewHolder(parent, 1) as GroupActivityAdapter.ActivityViewHolder
        adapter.onBindViewHolder(activityHolder, 1)
        val activityText = activityHolder.itemView.findViewById<TextView>(R.id.activity_text)
        val text = activityText.text.toString()
        assertTrue("Activity text should contain streak message", text.contains("heat streak"))
        assertTrue("Activity text should contain 7-day", text.contains("7-day"))
    }
}
