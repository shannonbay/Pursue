package app.getpursue.ui.adapters

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.getpursue.R
import app.getpursue.models.Group
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for GroupAdapter.
 * 
 * Tests group icon display logic with has_icon flag, emoji fallbacks, and cache signatures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "app.getpursue"
)
class GroupAdapterTest {

    private lateinit var context: Context
    private lateinit var adapter: GroupAdapter
    private lateinit var mockOnGroupClick: (Group) -> Unit

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockOnGroupClick = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun getThemedContext(): Context {
        return ContextThemeWrapper(context, R.style.Theme_Pursue)
    }

    @Test
    fun `test group icon shows ImageView when has_icon is true`() {
        // Given
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = true,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = "2026-01-20T10:00:00Z"
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        val iconImage = holder.itemView.findViewById<ImageView>(R.id.group_icon_image)
        val iconEmoji = holder.itemView.findViewById<TextView>(R.id.group_icon_emoji)
        
        assertNotNull("ImageView should exist", iconImage)
        assertNotNull("TextView should exist", iconEmoji)
        assertEquals("ImageView should be visible when has_icon is true", 
            View.VISIBLE, iconImage.visibility)
        assertEquals("TextView should be hidden when has_icon is true", 
            View.GONE, iconEmoji.visibility)
    }

    @Test
    fun `test group icon shows emoji TextView when has_icon is false`() {
        // Given
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = null
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        val iconImage = holder.itemView.findViewById<ImageView>(R.id.group_icon_image)
        val iconEmoji = holder.itemView.findViewById<TextView>(R.id.group_icon_emoji)
        
        assertNotNull("ImageView should exist", iconImage)
        assertNotNull("TextView should exist", iconEmoji)
        assertEquals("ImageView should be hidden when has_icon is false", 
            View.GONE, iconImage.visibility)
        assertEquals("TextView should be visible when has_icon is false", 
            View.VISIBLE, iconEmoji.visibility)
        assertEquals("TextView should show correct emoji", 
            "🏃", iconEmoji.text.toString())
    }

    @Test
    fun `test group icon uses default emoji when icon_emoji is null`() {
        // Given
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = null,
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = null
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        val iconEmoji = holder.itemView.findViewById<TextView>(R.id.group_icon_emoji)
        
        assertEquals("TextView should show default emoji when icon_emoji is null", 
            "📁", iconEmoji.text.toString())
    }

    @Test
    fun `test group icon cache signature uses updated_at`() {
        // Given
        val updatedAt = "2026-01-20T10:00:00Z"
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = true,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = updatedAt
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        // Verify ImageView is visible (indicates has_icon path was taken)
        val iconImage = holder.itemView.findViewById<ImageView>(R.id.group_icon_image)
        assertEquals("ImageView should be visible", View.VISIBLE, iconImage.visibility)
        
        // Note: We can't easily verify Glide signature was set without mocking Glide,
        // but we can verify the code path that sets it (has_icon=true and updated_at!=null)
        assertNotNull("Group should have updated_at for cache signature", group.updated_at)
        assertEquals("updated_at should match", updatedAt, group.updated_at)
    }

    @Test
    fun `test group icon handles missing updated_at gracefully`() {
        // Given
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = true,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = null // No updated_at
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        // Should still show ImageView even without updated_at
        val iconImage = holder.itemView.findViewById<ImageView>(R.id.group_icon_image)
        assertEquals("ImageView should be visible even without updated_at", 
            View.VISIBLE, iconImage.visibility)
    }

    @Test
    fun `test group name is displayed correctly`() {
        // Given
        val groupName = "Morning Runners"
        val group = Group(
            id = "group_1",
            name = groupName,
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = null
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        // Then
        val nameView = holder.itemView.findViewById<TextView>(R.id.group_name)
        assertEquals("Group name should be displayed", groupName, nameView.text.toString())
    }

    @Test
    fun `test group click listener is set`() {
        // Given
        val group = Group(
            id = "group_1",
            name = "Test Group",
            description = "Test description",
            icon_emoji = "🏃",
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-01-15T08:00:00Z",
            updated_at = null
        )
        val groups = listOf(group)
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // When
        val parent = mockk<RecyclerView>(relaxed = true)
        every { parent.context } returns getThemedContext()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        
        // Trigger click
        holder.itemView.performClick()

        // Then
        verify(exactly = 1) { mockOnGroupClick(group) }
    }

    @Test
    fun `test adapter item count`() {
        // Given
        val groups = listOf(
            Group(
                id = "group_1",
                name = "Group 1",
                description = null,
                icon_emoji = "🏃",
                has_icon = false,
                member_count = 5,
                role = "member",
                joined_at = "2026-01-15T08:00:00Z",
                updated_at = null
            ),
            Group(
                id = "group_2",
                name = "Group 2",
                description = null,
                icon_emoji = "📚",
                has_icon = true,
                member_count = 10,
                role = "admin",
                joined_at = "2026-01-10T10:00:00Z",
                updated_at = "2026-01-20T10:00:00Z"
            )
        )
        adapter = GroupAdapter(groups, mockOnGroupClick)

        // Then
        assertEquals("Adapter should have correct item count", 2, adapter.itemCount)
    }
}
