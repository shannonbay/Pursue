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
import app.getpursue.models.GroupMember
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

/**
 * Unit tests for GroupMembersAdapter.
 *
 * Tests adapter structure, grouping logic, and view holder binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "app.getpursue"
)
class GroupMembersAdapterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
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
        val members: List<GroupMember> = listOf(
            createMember("user1", "Alice", "creator"),
            createMember("user2", "Bob", "member")
        )
        val adapter = GroupMembersAdapter(members)

        // Then - TYPE_ROLE_HEADER = 0, TYPE_MEMBER = 1
        assertEquals("First item should be header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be member", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be header", 0, adapter.getItemViewType(2))
        assertEquals("Fourth item should be member", 1, adapter.getItemViewType(3))
    }

    @Test
    fun `test adapter item count matches members plus headers`() {
        // Given
        val members = listOf(
            createMember("user1", "Alice", "creator"),
            createMember("user2", "Bob", "member")
        )
        val adapter = GroupMembersAdapter(members)

        // Then - 2 headers (admins, members) + 2 members = 4 items
        assertEquals("Item count should match members plus headers", 4, adapter.itemCount)
    }

    // ========== Grouping Logic Tests ==========

    @Test
    fun `test members grouped by role correctly`() {
        // Given
        val members = listOf(
            createMember("user1", "Alice", "creator"),
            createMember("user2", "Bob", "admin"),
            createMember("user3", "Charlie", "member"),
            createMember("user4", "David", "member")
        )
        val adapter = GroupMembersAdapter(members)

        // Then - Admins header + 2 admins + Members header + 2 members = 6 items
        assertEquals("Should have 6 items", 6, adapter.itemCount)
        
        // Verify order: admins header, admins, members header, members
        assertEquals("First item should be admins header", 0, adapter.getItemViewType(0))
        assertEquals("Second item should be admin", 1, adapter.getItemViewType(1))
        assertEquals("Third item should be admin", 1, adapter.getItemViewType(2))
        assertEquals("Fourth item should be members header", 0, adapter.getItemViewType(3))
    }

    @Test
    fun `test admins include creator and admin roles`() {
        // Given
        val members = listOf(
            createMember("user1", "Creator", "creator"),
            createMember("user2", "Admin", "admin"),
            createMember("user3", "Member", "member")
        )
        val adapter = GroupMembersAdapter(members)

        // Then - Admins header + 2 admins + Members header + 1 member = 5 items
        assertEquals("Should have 5 items", 5, adapter.itemCount)
    }

    @Test
    fun `test members section shows count`() {
        // Given
        val members = listOf(
            createMember("user1", "Member1", "member"),
            createMember("user2", "Member2", "member"),
            createMember("user3", "Member3", "member")
        )
        val adapter = GroupMembersAdapter(members)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupMembersAdapter.RoleHeaderViewHolder
        // Find the members header (should be at position 0 since no admins)
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        assertTrue("Header should contain member count", textView.text.toString().contains("3"))
    }

    @Test
    fun `test empty sections not shown`() {
        // Given
        val members = listOf(
            createMember("user1", "Alice", "creator")
        )
        val adapter = GroupMembersAdapter(members)

        // Then - Only admins header + 1 admin = 2 items (members section not shown)
        assertEquals("Should only show admins section", 2, adapter.itemCount)
    }

    // ========== View Holder Binding Tests ==========

    @Test
    fun `test role header shows correct label`() {
        // Given
        val members = listOf(
            createMember("user1", "Alice", "creator")
        )
        val adapter = GroupMembersAdapter(members)
        val parent = getMockParent()

        // When
        val headerHolder = adapter.onCreateViewHolder(parent, 0) as GroupMembersAdapter.RoleHeaderViewHolder
        adapter.onBindViewHolder(headerHolder, 0)

        // Then
        val textView = headerHolder.itemView.findViewById<TextView>(android.R.id.text1)
        assertTrue("Header should contain 'Admins'", textView.text.toString().contains("Admins"))
    }

    @Test
    fun `test member view holder shows display name`() {
        // Given
        val member = createMember("user1", "Alice Smith", "member")
        val adapter = GroupMembersAdapter(listOf(member))
        val parent = getMockParent()

        // When
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 1)

        // Then
        val nameView = memberHolder.itemView.findViewById<TextView>(R.id.member_display_name)
        assertEquals("Name should be displayed", "Alice Smith", nameView.text.toString())
    }

    @Test
    fun `test member view holder shows (You) suffix for current user`() {
        // Given
        val member = createMember("user1", "Alice", "member")
        val adapter = GroupMembersAdapter(listOf(member), currentUserId = "user1")
        val parent = getMockParent()

        // When
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 1)

        // Then
        val nameView = memberHolder.itemView.findViewById<TextView>(R.id.member_display_name)
        assertTrue("Name should contain (You)", nameView.text.toString().contains("(You)"))
    }

    @Test
    fun `test member view holder shows admin badge for admins`() {
        // Given
        val admin = createMember("user1", "Admin", "admin")
        val member = createMember("user2", "Member", "member")
        val adapter = GroupMembersAdapter(listOf(admin, member))
        val parent = getMockParent()

        // When
        val adminHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(adminHolder, 1, listOf())
        
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 3, listOf())

        // Then
        val adminBadge = adminHolder.itemView.findViewById<TextView>(R.id.admin_badge)
        val memberBadge = memberHolder.itemView.findViewById<TextView>(R.id.admin_badge)
        
        assertEquals("Admin badge should be visible", View.VISIBLE, adminBadge.visibility)
        assertEquals("Member badge should be hidden", View.GONE, memberBadge.visibility)
    }

    @Test
    fun `test member view holder loads avatar when has_avatar is true`() {
        // Given
        val member = createMember("user1", "Alice", "member", hasAvatar = true)
        val adapter = GroupMembersAdapter(listOf(member))
        val parent = getMockParent()

        // When
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 1)

        // Then
        val avatar = memberHolder.itemView.findViewById<ImageView>(R.id.member_avatar)
        val fallback = memberHolder.itemView.findViewById<TextView>(R.id.member_avatar_fallback)
        
        assertEquals("Avatar should be visible", View.VISIBLE, avatar.visibility)
        assertEquals("Fallback should be hidden", View.GONE, fallback.visibility)
    }

    @Test
    fun `test member view holder shows first letter when no avatar`() {
        // Given
        val member = createMember("user1", "Alice", "member", hasAvatar = false)
        val adapter = GroupMembersAdapter(listOf(member))
        val parent = getMockParent()

        // When
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 1)

        // Then
        val avatar = memberHolder.itemView.findViewById<ImageView>(R.id.member_avatar)
        val fallback = memberHolder.itemView.findViewById<TextView>(R.id.member_avatar_fallback)
        
        assertEquals("Avatar should be hidden", View.GONE, avatar.visibility)
        assertEquals("Fallback should be visible", View.VISIBLE, fallback.visibility)
        assertEquals("Fallback should show first letter", "A", fallback.text.toString())
    }

    @Test
    fun `test member view holder formats last active correctly`() {
        // Given
        val member = createMember("user1", "Alice", "member", joinedAt = "2024-01-01T00:00:00Z")
        val adapter = GroupMembersAdapter(listOf(member))
        val parent = getMockParent()

        // When
        val memberHolder = adapter.onCreateViewHolder(parent, 1) as GroupMembersAdapter.MemberViewHolder
        adapter.onBindViewHolder(memberHolder, 1)

        // Then
        val lastActive = memberHolder.itemView.findViewById<TextView>(R.id.last_active)
        assertNotNull("Last active should be displayed", lastActive.text)
        assertTrue("Last active should contain text", lastActive.text.toString().isNotEmpty())
    }

    // ========== Helper Methods ==========

    private fun createMember(
        userId: String,
        displayName: String,
        role: String,
        hasAvatar: Boolean = false,
        joinedAt: String = "2024-01-01T00:00:00Z"
    ): GroupMember {
        return GroupMember(
            user_id = userId,
            display_name = displayName,
            has_avatar = hasAvatar,
            role = role,
            joined_at = joinedAt
        )
    }
}
