package app.getpursue.groups

import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.GroupTemplate
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GroupTemplatesE2ETest : E2ETest() {

    private suspend fun firstOngoingTemplate(accessToken: String): GroupTemplate {
        val response = api.getGroupTemplates(accessToken)
        assertThat(response.templates).isNotEmpty()
        return response.templates.first()
    }

    @Test
    fun `getGroupTemplates requires auth`() = runTest {
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: ApiException? = null
        try {
            api.getGroupTemplates(accessToken = "")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    @Test
    fun `getGroupTemplates returns only ongoing templates`() = runTest {
        val user = getOrCreateSharedUser()
        val response = api.getGroupTemplates(user.access_token)

        assertThat(response.templates).isNotEmpty()
        assertThat(response.templates.all { !it.is_challenge }).isTrue()
    }

    @Test
    fun `getGroupTemplates returns non-empty goals for each template`() = runTest {
        val user = getOrCreateSharedUser()
        val response = api.getGroupTemplates(user.access_token)

        assertThat(response.templates).isNotEmpty()
        assertThat(response.templates.all { it.goals.isNotEmpty() }).isTrue()
    }

    @Test
    fun `getGroupTemplates category filter`() = runTest {
        val user = getOrCreateSharedUser()
        val all = api.getGroupTemplates(user.access_token)
        assertThat(all.templates).isNotEmpty()

        val firstCategory = all.templates.first().category
        val filtered = api.getGroupTemplates(user.access_token, category = firstCategory)

        assertThat(filtered.templates).isNotEmpty()
        assertThat(filtered.templates.all { it.category == firstCategory }).isTrue()
    }

    @Test
    fun `createGroup from template succeeds`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Template Group Creator")
        trackUser(user.user!!.id)

        val template = firstOngoingTemplate(user.access_token)

        val response = api.createGroup(
            accessToken = user.access_token,
            name = "Template Group ${System.currentTimeMillis()}",
            templateId = template.id
        )
        trackGroup(response.id)

        assertThat(response.template_id).isEqualTo(template.id)
        assertThat(response.goals).isNotNull()
        assertThat(response.goals!!).isNotEmpty()
    }

    @Test
    fun `createGroup from template copies goals with correct fields`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Template Goals Tester")
        trackUser(user.user!!.id)

        val template = firstOngoingTemplate(user.access_token)

        val response = api.createGroup(
            accessToken = user.access_token,
            name = "Template Goals Group ${System.currentTimeMillis()}",
            templateId = template.id
        )
        trackGroup(response.id)

        val goals = response.goals!!
        assertThat(goals).isNotEmpty()
        for (goal in goals) {
            assertThat(goal.title).isNotEmpty()
            assertThat(goal.cadence).isNotEmpty()
            assertThat(goal.metric_type).isNotEmpty()
        }

        // Journal goals must have a non-null log_title_prompt
        val journalGoals = goals.filter { it.metric_type == "journal" }
        for (goal in journalGoals) {
            assertThat(goal.log_title_prompt).isNotNull()
        }
    }

    @Test
    fun `createGroup from template uses template icon as fallback`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Template Icon Tester")
        trackUser(user.user!!.id)

        val template = firstOngoingTemplate(user.access_token)

        val response = api.createGroup(
            accessToken = user.access_token,
            name = "Template Icon Group ${System.currentTimeMillis()}",
            templateId = template.id
            // no iconEmoji passed
        )
        trackGroup(response.id)

        assertThat(response.icon_emoji).isEqualTo(template.icon_emoji)
    }

    @Test
    fun `createGroup caller icon overrides template icon`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Template Icon Override Tester")
        trackUser(user.user!!.id)

        val template = firstOngoingTemplate(user.access_token)

        val response = api.createGroup(
            accessToken = user.access_token,
            name = "Override Icon Group ${System.currentTimeMillis()}",
            iconEmoji = "🔥",
            templateId = template.id
        )
        trackGroup(response.id)

        assertThat(response.icon_emoji).isEqualTo("🔥")
    }

    @Test
    fun `createGroup with challenge template rejected`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Challenge Template Tester")
        trackUser(user.user!!.id)

        val challengeTemplates = api.getChallengeTemplates(user.access_token)
        assertThat(challengeTemplates.templates).isNotEmpty()
        val challengeTemplateId = challengeTemplates.templates.first().id

        var exception: ApiException? = null
        try {
            api.createGroup(
                accessToken = user.access_token,
                name = "Bad Group ${System.currentTimeMillis()}",
                templateId = challengeTemplateId
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `createGroup with nonexistent template_id returns 404`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Bad Template Tester")
        trackUser(user.user!!.id)

        var exception: ApiException? = null
        try {
            api.createGroup(
                accessToken = user.access_token,
                name = "Bad Template Group ${System.currentTimeMillis()}",
                templateId = "00000000-0000-0000-0000-000000000000"
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }
}
