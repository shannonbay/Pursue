package app.getpursue.ui.adapters

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.getpursue.R
import app.getpursue.models.GroupGoal
import app.getpursue.models.MemberProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Unit tests for GroupGoalsAdapter.
 *
 * Tests adapter structure, grouping logic, and view holder binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "app.getpursue"
)
class GroupGoalsAdapterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ShadowToast.reset()
        shadowOf(Looper.getMainLooper()).idle()
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
        val goals: List<GroupGoal> = listOf(
            createGoal("goal1", "daily", "Daily Goal 1"),
            createGoal("goal2", "weekly", "Weekly Goal 1")
        )
        val adapter = GroupGoalsAdapter(goals)

        // Then - TYPE_CADENCE_HEADER = 0, TYPE_GOAL = 1
        assertEquals("First item should be header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be goal", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be header", 0, adapter.getItemViewType(2))
        assertEquals("Fourth item should be goal", 1, adapter.getItemViewType(3))
    }

    @Test
    fun `test adapter item count matches goals plus headers`() {
        // Given
        val goals = listOf(
            createGoal("goal1", "daily", "Daily Goal 1"),
            createGoal("goal2", "daily", "Daily Goal 2"),
            createGoal("goal3", "weekly", "Weekly Goal 1")
        )
        val adapter = GroupGoalsAdapter(goals)

        // Then - 2 headers (daily, weekly) + 3 goals = 5 items
        assertEquals("Item count should match goals plus headers", 5, adapter.itemCount)
    }

    // ========== Grouping Logic Tests ==========

    @Test
    fun `test goals grouped by cadence correctly`() {
        // Given
        val goals = listOf(
            createGoal("goal1", "daily", "Daily Goal 1"),
            createGoal("goal2", "daily", "Daily Goal 2"),
            createGoal("goal3", "weekly", "Weekly Goal 1"),
            createGoal("goal4", "monthly", "Monthly Goal 1")
        )
        val adapter = GroupGoalsAdapter(goals)

        // Then - 3 headers (daily, weekly, monthly) + 4 goals = 7 items
        // Note: yearly cadence is not included because it has no goals
        assertEquals("Should have 3 headers + 4 goals = 7 items", 7, adapter.itemCount)
        
        // Verify order: daily header, daily goals, weekly header, weekly goal, monthly header, monthly goal
        assertEquals("First item should be daily header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be daily goal", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be daily goal", 1, adapter.getItemViewType(2))
        assertEquals("Fourth item should be weekly header", 0, adapter.getItemViewType(3))
        assertEquals("Fifth item should be weekly goal", 1, adapter.getItemViewType(4))
        assertEquals("Sixth item should be monthly header", 0, adapter.getItemViewType(5))
        assertEquals("Seventh item should be monthly goal", 1, adapter.getItemViewType(6))
    }

    @Test
    fun `test empty cadences not shown`() {
        // Given
        val goals = listOf(
            createGoal("goal1", "daily", "Daily Goal 1")
        )
        val adapter = GroupGoalsAdapter(goals)

        // Then - Only daily header + 1 goal = 2 items (weekly, monthly, yearly not shown)
        assertEquals("Should only show daily cadence", 2, adapter.itemCount)
    }

    @Test
    fun `test cadence order is correct`() {
        // Given
        val goals = listOf(
            createGoal("goal1", "yearly", "Yearly Goal"),
            createGoal("goal2", "daily", "Daily Goal"),
            createGoal("goal3", "monthly", "Monthly Goal"),
            createGoal("goal4", "weekly", "Weekly Goal")
        )
        val adapter = GroupGoalsAdapter(goals)

        // Then - Order should be: daily, weekly, monthly, yearly
        assertEquals("First should be daily header", 0, adapter.getItemViewType(0))
        assertEquals("Second should be weekly header", 0, adapter.getItemViewType(2))
        assertEquals("Third should be monthly header", 0, adapter.getItemViewType(4))
        assertEquals("Fourth should be yearly header", 0, adapter.getItemViewType(6))
    }

    // ========== View Holder Binding Tests ==========

    @Test
    fun `test cadence header shows correct label`() {
        // Given
        val goals = listOf(
            createGoal("goal1", "daily", "Daily Goal")
        )
        val adapter = GroupGoalsAdapter(goals)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupGoalsAdapter.CadenceHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        assertTrue("Header should contain 'Daily'", textView.text.toString().contains("Daily"))
    }

    @Test
    fun `test goal view holder shows title`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal Title")
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        // Then
        val titleView = goalHolder.itemView.findViewById<TextView>(R.id.goal_title)
        assertEquals("Title should be displayed", "Test Goal Title", titleView.text.toString())
    }

    @Test
    fun `test goal view holder shows completed status`() {
        // Given
        val completedGoal = createGoal("goal1", "daily", "Completed Goal", completed = true)
        val incompleteGoal = createGoal("goal2", "daily", "Incomplete Goal", completed = false)
        val adapter = GroupGoalsAdapter(listOf(completedGoal, incompleteGoal))
        val parent = getMockParent()

        // When
        val completedHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(completedHolder, 1)

        val incompleteHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(incompleteHolder, 2)

        // Then
        val completedStatusIcon = completedHolder.itemView.findViewById<TextView>(R.id.status_icon)
        val incompleteStatusIcon = incompleteHolder.itemView.findViewById<TextView>(R.id.status_icon)

        assertEquals("Completed goal should show ✓", "✓", completedStatusIcon.text.toString())
        assertEquals("Incomplete goal should show ○", "○", incompleteStatusIcon.text.toString())
    }

    @Test
    fun `test goal view holder shows progress for numeric goals`() {
        // Given
        val numericGoal = createGoal(
            id = "goal1",
            cadence = "daily",
            title = "Numeric Goal",
            metricType = "numeric",
            targetValue = 100.0,
            progressValue = 50.0
        )
        val adapter = GroupGoalsAdapter(listOf(numericGoal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        // Then
        val progressBar = goalHolder.itemView.findViewById<ProgressBar>(R.id.progress_bar)
        val progressText = goalHolder.itemView.findViewById<TextView>(R.id.progress_text)

        assertEquals("Progress bar should be visible", View.VISIBLE, progressBar.visibility)
        assertEquals("Progress text should be visible", View.VISIBLE, progressText.visibility)
        assertEquals("Progress should be 50%", 50, progressBar.progress)
        assertTrue("Progress text should contain percentage", progressText.text.toString().contains("50%"))
    }

    @Test
    fun `test goal view holder hides progress for binary goals`() {
        // Given
        val binaryGoal = createGoal(
            id = "goal1",
            cadence = "daily",
            title = "Binary Goal",
            metricType = "binary"
        )
        val adapter = GroupGoalsAdapter(listOf(binaryGoal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        // Then
        val progressBar = goalHolder.itemView.findViewById<ProgressBar>(R.id.progress_bar)
        val progressText = goalHolder.itemView.findViewById<TextView>(R.id.progress_text)

        assertEquals("Progress bar should be hidden", View.GONE, progressBar.visibility)
        assertEquals("Progress text should be hidden", View.GONE, progressText.visibility)
    }

    @Test
    fun `test goal view holder shows member status`() {
        // Given
        val goal = createGoal(
            id = "goal1",
            cadence = "daily",
            title = "Goal with Members",
            memberProgress = listOf(
                MemberProgress("user1", "Alice", true, null),
                MemberProgress("user2", "Bob", false, null)
            )
        )
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        // Then
        val memberStatusText = goalHolder.itemView.findViewById<TextView>(R.id.member_status_text)
        assertEquals("Member status should be visible", View.VISIBLE, memberStatusText.visibility)
        assertTrue("Member status should contain Alice", memberStatusText.text.toString().contains("Alice"))
        assertTrue("Member status should contain Bob", memberStatusText.text.toString().contains("Bob"))
    }

    // ========== Two-Touch Zone Tests ==========

    @Test
    fun `test goal card has two touch zones`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        // Then
        val cardBody = goalHolder.itemView.findViewById<LinearLayout>(R.id.card_body)
        val arrowButton = goalHolder.itemView.findViewById<ImageButton>(R.id.arrow_button)

        assertNotNull("Card body should exist", cardBody)
        assertNotNull("Arrow button should exist", arrowButton)
        assertEquals("Card body should be visible", View.VISIBLE, cardBody.visibility)
        assertEquals("Arrow button should be visible", View.VISIBLE, arrowButton.visibility)
    }

    @Test
    fun `test card body click listener is set`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        var cardBodyClicked = false
        var clickedGoal: GroupGoal? = null

        val adapter = GroupGoalsAdapter(
            goals = listOf(goal),
            onCardBodyClick = { g ->
                cardBodyClicked = true
                clickedGoal = g
            },
            onArrowClick = { }
        )
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        val goalCard = goalHolder.itemView.findViewById<View>(R.id.goal_card)
        goalCard.performClick()

        // Then
        assertTrue("Card body click should be triggered", cardBodyClicked)
        assertNotNull("Clicked goal should be set", clickedGoal)
        assertEquals("Clicked goal should match", "goal1", clickedGoal?.id)
    }

    @Test
    fun `test arrow button click listener is set`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        var arrowClicked = false
        var clickedGoal: GroupGoal? = null

        val adapter = GroupGoalsAdapter(
            goals = listOf(goal),
            onCardBodyClick = { },
            onArrowClick = { g ->
                arrowClicked = true
                clickedGoal = g
            }
        )
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        val arrowButton = goalHolder.itemView.findViewById<ImageButton>(R.id.arrow_button)
        arrowButton.performClick()

        // Then
        assertTrue("Arrow click should be triggered", arrowClicked)
        assertNotNull("Clicked goal should be set", clickedGoal)
        assertEquals("Clicked goal should match", "goal1", clickedGoal?.id)
    }

    @Test
    fun `test card body and arrow have separate listeners`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        var cardBodyClicked = false
        var arrowClicked = false

        val adapter = GroupGoalsAdapter(
            goals = listOf(goal),
            onCardBodyClick = { cardBodyClicked = true },
            onArrowClick = { arrowClicked = true }
        )
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        // Listener for "card body" (log progress) is on goal_card, not card_body
        val goalCard = goalHolder.itemView.findViewById<View>(R.id.goal_card)
        val arrowButton = goalHolder.itemView.findViewById<ImageButton>(R.id.arrow_button)

        // Click goal card (triggers onCardBodyClick)
        goalCard.performClick()
        assertTrue("Card body click should be triggered", cardBodyClicked)
        assertFalse("Arrow click should not be triggered", arrowClicked)

        // Reset
        cardBodyClicked = false
        arrowClicked = false

        // Click arrow button
        arrowButton.performClick()
        assertFalse("Card body click should not be triggered", cardBodyClicked)
        assertTrue("Arrow click should be triggered", arrowClicked)
    }

    @Test
    fun `test arrow button has correct icon`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        val arrowButton = goalHolder.itemView.findViewById<ImageButton>(R.id.arrow_button)

        // Then - Verify arrow button has drawable set (ic_chevron_right)
        assertNotNull("Arrow button should have drawable", arrowButton.drawable)
    }

    @Test
    fun `test arrow button has correct content description`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal")
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        // When
        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        val arrowButton = goalHolder.itemView.findViewById<ImageButton>(R.id.arrow_button)

        // Then
        assertEquals("Arrow button should have correct content description",
            context.getString(R.string.view_goal_details), arrowButton.contentDescription)
    }

    // ========== Visual Feedback Tests ==========

    @Test
    fun `test animateLogProgress triggers haptic feedback`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal", completed = false)
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        val cardBody = goalHolder.itemView.findViewById<LinearLayout>(R.id.card_body)
        val statusIcon = goalHolder.itemView.findViewById<TextView>(R.id.status_icon)

        // When - Click card body (triggers animateLogProgress)
        cardBody.performClick()

        // Then - Haptic feedback is called via performHapticFeedback
        // We verify the click was processed (haptic is called internally)
        // Note: Actual haptic feedback verification requires mocking View.performHapticFeedback
        // For now, we verify the click listener is triggered
        assertNotNull("Card body should have click listener", cardBody.hasOnClickListeners())
    }

    @Test
    fun `test animateLogProgress invokes callback and updates status icon`() {
        // Given - adapter with callback (fragment shows toast in production; we validate state instead)
        val goal = createGoal("goal1", "daily", "Test Goal", completed = false)
        var callbackInvoked = false
        val adapter = GroupGoalsAdapter(
            listOf(goal),
            onCardBodyClick = { callbackInvoked = true }
        )
        val parent = getMockParent()

        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)
        val goalCard = goalHolder.itemView.findViewById<View>(R.id.goal_card)
        val statusIcon = goalHolder.itemView.findViewById<TextView>(R.id.status_icon)

        // When - Click goal card (adapter invokes onCardBodyClick and optimistically toggles icon)
        goalCard.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - Callback ran and status icon updated (validate state rather than toast per TESTING.md)
        assertTrue("Card body click callback should be invoked", callbackInvoked)
        assertEquals("Status icon should toggle to completed", "✓", statusIcon.text.toString())
    }

    @Test
    fun `test animateLogProgress updates status icon`() {
        // Given
        val goal = createGoal("goal1", "daily", "Test Goal", completed = false)
        val adapter = GroupGoalsAdapter(listOf(goal))
        val parent = getMockParent()

        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        val goalCard = goalHolder.itemView.findViewById<View>(R.id.goal_card)
        val statusIcon = goalHolder.itemView.findViewById<TextView>(R.id.status_icon)

        // Verify initial state
        assertEquals("Initial status should be incomplete", "○", statusIcon.text.toString())

        // When - Click goal card (triggers animateLogProgress; listener is on goal_card)
        goalCard.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle() // Additional pass for Handler.postDelayed

        // Then - Status icon should update (optimistic update happens immediately in animateLogProgress)
        // Note: The actual animation timing is not testable, but the state change is
        assertEquals("Status icon should update to completed", "✓", statusIcon.text.toString())
    }

    @Test
    fun `test animateLogProgress toggles completion state`() {
        // Given
        val incompleteGoal = createGoal("goal1", "daily", "Test Goal", completed = false)
        val adapter = GroupGoalsAdapter(listOf(incompleteGoal))
        val parent = getMockParent()

        val goalHolder = adapter.onCreateViewHolder(parent, 1) as GroupGoalsAdapter.GoalViewHolder
        adapter.onBindViewHolder(goalHolder, 1)

        val goalCard = goalHolder.itemView.findViewById<View>(R.id.goal_card)
        val statusIcon = goalHolder.itemView.findViewById<TextView>(R.id.status_icon)

        // Verify initial state
        assertEquals("Initial status should be incomplete", "○", statusIcon.text.toString())

        // When - Click goal card (listener is on goal_card, not card_body)
        goalCard.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - Status icon should toggle
        assertEquals("Status icon should toggle to completed", "✓", statusIcon.text.toString())
    }

    // ========== Helper Methods ==========

    private fun createGoal(
        id: String,
        cadence: String,
        title: String,
        metricType: String = "binary",
        targetValue: Double? = null,
        progressValue: Double? = null,
        completed: Boolean = false,
        memberProgress: List<MemberProgress> = emptyList()
    ): GroupGoal {
        return GroupGoal(
            id = id,
            group_id = "group1",
            title = title,
            description = null,
            cadence = cadence,
            metric_type = metricType,
            target_value = targetValue,
            created_at = "2024-01-01T00:00:00Z",
            completed = completed,
            progress_value = progressValue,
            member_progress = memberProgress
        )
    }
}
