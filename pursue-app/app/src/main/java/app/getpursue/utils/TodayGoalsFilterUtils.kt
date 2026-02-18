package app.getpursue.utils

import app.getpursue.models.Group

object TodayGoalsFilterUtils {
    fun shouldIncludeGroup(group: Group): Boolean {
        if (!group.is_challenge) return true
        return group.challenge_status == "active"
    }
}

