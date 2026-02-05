package com.github.shannonbay.pursue.ui.handlers

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.models.GoalForLogging
import com.github.shannonbay.pursue.ui.dialogs.LogProgressDialog
import com.github.shannonbay.pursue.ui.activities.MainAppActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared handler for goal card body tap: log progress (binary/numeric), remove, edit.
 * Used by GoalsTabFragment and TodayFragment so dialogs and API calls are not duplicated.
 */
class GoalLogProgressHandler(
    private val fragment: Fragment,
    private val snackbarParentView: View,
    private val tokenSupplier: () -> String?,
    private val userDate: String,
    private val userTimezone: String,
    private val onOptimisticUpdate: ((goalId: String, completed: Boolean, progressValue: Double?) -> Unit)? = null,
    private val onRefresh: (silent: Boolean) -> Unit = {}
) {
    private var currentSnackbar: Snackbar? = null
    private val undoHandler = Handler(Looper.getMainLooper())

    private fun showUpgradeDialog() {
        val ctx = fragment.requireContext()
        val d = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.group_limit_reached_dialog_title)
            .setMessage(R.string.group_read_only_upgrade_message)
            .setNegativeButton(R.string.maybe_later, null)
            .setPositiveButton(R.string.upgrade_to_premium) { _, _ ->
                val intent = Intent(ctx, MainAppActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainAppActivity.EXTRA_OPEN_PREMIUM, true)
                }
                fragment.startActivity(intent)
                fragment.activity?.finish()
            }
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.secondary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    fun handleCardBodyClick(goal: GoalForLogging) {
        when (goal.metricType) {
            "binary" -> {
                if (goal.completed) {
                    showRemoveProgressConfirmationDialog(goal)
                } else {
                    logBinaryProgress(goal)
                }
            }
            "numeric", "duration" -> {
                if (goal.progressValue != null && goal.progressValue > 0) {
                    showEditProgressDialog(goal)
                } else {
                    showLogProgressDialog(goal)
                }
            }
            else -> {
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logBinaryProgress(goal: GoalForLogging) {
        val accessToken = tokenSupplier() ?: run {
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
            return
        }
        val newValue = 1.0
        val previousCompleted = goal.completed
        val previousProgressValue = goal.progressValue
        onOptimisticUpdate?.invoke(goal.id, true, null)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.logProgress(accessToken, goal.id, newValue, null, userDate, userTimezone)
                }
                val entryId = response.id
                fragment.requireActivity().runOnUiThread {
                    currentSnackbar?.dismiss()
                    currentSnackbar = null
                    val snackbar = Snackbar.make(
                        snackbarParentView,
                        fragment.getString(R.string.logged_with_undo),
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(fragment.getString(R.string.undo)) {
                        onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient.deleteProgressEntry(accessToken, entryId)
                                }
                                fragment.requireActivity().runOnUiThread {
                                    Snackbar.make(snackbarParentView, fragment.getString(R.string.undone), Snackbar.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                fragment.requireActivity().runOnUiThread {
                                    Toast.makeText(fragment.requireContext(), "Failed to undo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        snackbar.dismiss()
                        currentSnackbar = null
                    }
                    snackbar.setActionTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.primary))
                    currentSnackbar = snackbar
                    snackbar.show()
                    undoHandler.postDelayed({
                        snackbar.dismiss()
                        currentSnackbar = null
                    }, 3000)
                }
            } catch (e: ApiException) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    if (e.errorCode == "GROUP_READ_ONLY") {
                        showUpgradeDialog()
                    } else {
                        val msg = if (e.code == 400) fragment.getString(R.string.log_progress_duplicate) else fragment.getString(R.string.log_progress_failed)
                        Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRemoveProgressConfirmationDialog(goal: GoalForLogging) {
        val accessToken = tokenSupplier() ?: run {
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.remove_progress_title))
            .setPositiveButton(fragment.getString(R.string.remove_progress_confirm)) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val entryInfo = withContext(Dispatchers.IO) {
                            fetchCurrentPeriodEntry(accessToken, goal.id, goal.cadence)
                        }
                        if (entryInfo != null) {
                            withContext(Dispatchers.IO) {
                                ApiClient.deleteProgressEntry(accessToken, entryInfo.first)
                            }
                            fragment.requireActivity().runOnUiThread {
                                onOptimisticUpdate?.invoke(goal.id, false, null)
                            }
                        } else {
                            fragment.requireActivity().runOnUiThread {
                                Toast.makeText(fragment.requireContext(), "Entry not found", Toast.LENGTH_SHORT).show()
                                // Don't call onRefresh() - it shows skeleton. Just show the toast.
                            }
                        }
                    } catch (e: Exception) {
                        fragment.requireActivity().runOnUiThread {
                            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .show()
            ?.apply {
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    ContextCompat.getColor(fragment.requireContext(), R.color.secondary)
                )
            }
    }

    private fun showLogProgressDialog(goal: GoalForLogging) {
        val dialog = LogProgressDialog.newInstance(goal.title, goal.unit)
        dialog.setLogProgressListener(object : LogProgressDialog.LogProgressListener {
            override fun onLogProgress(value: Double, note: String?) {
                logNumericProgress(goal, value, note)
            }
        })
        dialog.show(fragment.childFragmentManager, "LogProgressDialog")
    }

    private fun showEditProgressDialog(goal: GoalForLogging) {
        val accessToken = tokenSupplier() ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val entryInfo = withContext(Dispatchers.IO) {
                    fetchCurrentPeriodEntry(accessToken, goal.id, goal.cadence)
                }
                fragment.requireActivity().runOnUiThread {
                    if (entryInfo != null) {
                        val (entryId, existingNote) = entryInfo
                        val existingValue = goal.progressValue ?: 0.0
                        val dialog = LogProgressDialog.newInstance(
                            goalTitle = goal.title,
                            unit = goal.unit,
                            isEditMode = true,
                            currentValue = existingValue,
                            currentNote = existingNote
                        )
                        dialog.setLogProgressListener(object : LogProgressDialog.LogProgressListener {
                            override fun onLogProgress(value: Double, note: String?) {
                                updateNumericProgress(goal, entryId, value, note)
                            }
                        })
                        dialog.setDeleteProgressListener(object : LogProgressDialog.DeleteProgressListener {
                            override fun onDeleteProgress() {
                                deleteProgressEntry(goal, entryId)
                            }
                        })
                        dialog.show(fragment.childFragmentManager, "EditProgressDialog")
                    } else {
                        onRefresh(true)
                        Toast.makeText(fragment.requireContext(), "Entry not found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                fragment.requireActivity().runOnUiThread {
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun logNumericProgress(goal: GoalForLogging, value: Double, note: String?) {
        val accessToken = tokenSupplier() ?: return
        val previousProgressValue = goal.progressValue
        val previousCompleted = goal.completed
        val currentProgress = goal.progressValue ?: 0.0
        val newProgressValue = currentProgress + value
        val newCompleted = goal.targetValue != null && newProgressValue >= goal.targetValue!!
        onOptimisticUpdate?.invoke(goal.id, newCompleted, newProgressValue)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.logProgress(accessToken, goal.id, newProgressValue, note, userDate, userTimezone)
                }
                val entryId = response.id
                fragment.requireActivity().runOnUiThread {
                    currentSnackbar?.dismiss()
                    currentSnackbar = null
                    val snackbar = Snackbar.make(
                        snackbarParentView,
                        fragment.getString(R.string.logged_with_undo),
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(fragment.getString(R.string.undo)) {
                        onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient.deleteProgressEntry(accessToken, entryId)
                                }
                                fragment.requireActivity().runOnUiThread {
                                    Snackbar.make(snackbarParentView, fragment.getString(R.string.undone), Snackbar.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                fragment.requireActivity().runOnUiThread {
                                    Toast.makeText(fragment.requireContext(), "Failed to undo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        snackbar.dismiss()
                        currentSnackbar = null
                    }
                    snackbar.setActionTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.primary))
                    currentSnackbar = snackbar
                    snackbar.show()
                    undoHandler.postDelayed({
                        snackbar.dismiss()
                        currentSnackbar = null
                    }, 3000)
                }
            } catch (e: ApiException) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    if (e.errorCode == "GROUP_READ_ONLY") {
                        showUpgradeDialog()
                    } else {
                        val msg = if (e.code == 400) fragment.getString(R.string.log_progress_duplicate) else fragment.getString(R.string.log_progress_failed)
                        Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateNumericProgress(goal: GoalForLogging, entryId: String, newValue: Double, newNote: String?) {
        val accessToken = tokenSupplier() ?: return
        val previousProgressValue = goal.progressValue
        val previousCompleted = goal.completed
        val newCompleted = goal.targetValue != null && newValue >= goal.targetValue!!
        onOptimisticUpdate?.invoke(goal.id, newCompleted, newValue)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.deleteProgressEntry(accessToken, entryId)
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.logProgress(accessToken, goal.id, newValue, newNote, userDate, userTimezone)
                }
                val newEntryId = response.id
                fragment.requireActivity().runOnUiThread {
                    currentSnackbar?.dismiss()
                    currentSnackbar = null
                    val snackbar = Snackbar.make(
                        snackbarParentView,
                        fragment.getString(R.string.logged_with_undo),
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(fragment.getString(R.string.undo)) {
                        onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                        fragment.viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient.deleteProgressEntry(accessToken, newEntryId)
                                }
                                fragment.requireActivity().runOnUiThread {
                                    Snackbar.make(snackbarParentView, fragment.getString(R.string.undone), Snackbar.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                fragment.requireActivity().runOnUiThread {
                                    Toast.makeText(fragment.requireContext(), "Failed to undo", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        snackbar.dismiss()
                        currentSnackbar = null
                    }
                    snackbar.setActionTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.primary))
                    currentSnackbar = snackbar
                    snackbar.show()
                    undoHandler.postDelayed({
                        snackbar.dismiss()
                        currentSnackbar = null
                    }, 3000)
                }
            } catch (e: ApiException) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    if (e.errorCode == "GROUP_READ_ONLY") {
                        showUpgradeDialog()
                    } else {
                        Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, previousCompleted, previousProgressValue)
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteProgressEntry(goal: GoalForLogging, entryId: String) {
        val accessToken = tokenSupplier() ?: return
        onOptimisticUpdate?.invoke(goal.id, false, 0.0)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.deleteProgressEntry(accessToken, entryId)
                }
                fragment.requireActivity().runOnUiThread {
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.progress_removed_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                fragment.requireActivity().runOnUiThread {
                    onOptimisticUpdate?.invoke(goal.id, goal.completed, goal.progressValue)
                    Toast.makeText(fragment.requireContext(), fragment.getString(R.string.log_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun fetchCurrentPeriodEntry(
        accessToken: String,
        goalId: String,
        cadence: String
    ): Pair<String, String?>? {
        return try {
            val periodStart = calculatePeriodStart(cadence, userDate, userTimezone)
            val entriesResponse = ApiClient.getGoalProgressMe(
                accessToken = accessToken,
                goalId = goalId,
                startDate = periodStart,
                endDate = periodStart
            )
            val existingEntry = entriesResponse.entries.firstOrNull { it.period_start == periodStart }
            existingEntry?.let { Pair(it.id, it.note) }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculatePeriodStart(cadence: String, userDate: String, userTimezone: String): String {
        val date = LocalDate.parse(userDate, DateTimeFormatter.ISO_DATE)
        return when (cadence.lowercase()) {
            "daily" -> userDate
            "weekly" -> {
                val dayOfWeek = date.dayOfWeek.value
                val daysToSubtract = (dayOfWeek - 1) % 7
                date.minusDays(daysToSubtract.toLong()).format(DateTimeFormatter.ISO_DATE)
            }
            "monthly" -> date.withDayOfMonth(1).format(DateTimeFormatter.ISO_DATE)
            "yearly" -> date.withDayOfYear(1).format(DateTimeFormatter.ISO_DATE)
            else -> userDate
        }
    }

    companion object {
        fun fromGroupGoal(goal: com.github.shannonbay.pursue.models.GroupGoal): GoalForLogging = goal.run {
            GoalForLogging(
                id = id,
                groupId = group_id,
                title = title,
                metricType = metric_type,
                targetValue = target_value,
                unit = unit,
                completed = completed,
                progressValue = progress_value,
                cadence = cadence
            )
        }

        fun fromTodayGoal(goal: com.github.shannonbay.pursue.models.TodayGoal, groupId: String): GoalForLogging {
            val metricType = goal.metric_type ?: if (goal.target_value != null) "numeric" else "binary"
            val progressValue = goal.progress_value?.toDouble()
            val targetValue = goal.target_value?.toDouble()
            return GoalForLogging(
                id = goal.goal_id,
                groupId = groupId,
                title = goal.title,
                metricType = metricType,
                targetValue = targetValue,
                unit = goal.unit,
                completed = goal.completed,
                progressValue = progressValue,
                cadence = "daily"
            )
        }
    }
}
