package app.getpursue.ui.fragments.groups

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.models.PendingMember
import app.getpursue.ui.adapters.PendingMembersAdapter
import app.getpursue.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pending Approvals screen (UI-Spec-Approval-Queue).
 * Admin reviews and approves/declines join requests.
 */
class PendingApprovalsFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP_NAME = "group_name"

        fun newInstance(groupId: String, groupName: String): PendingApprovalsFragment {
            return PendingApprovalsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_GROUP_NAME, groupName)
                }
            }
        }
    }

    private var groupId: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyContainer: FrameLayout
    private lateinit var countText: TextView
    private lateinit var approveAllButton: View
    private var adapter: PendingMembersAdapter? = null
    private val pendingList = mutableListOf<PendingMember>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_pending_approvals, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.pending_approvals_recycler)
        loadingView = view.findViewById(R.id.pending_approvals_loading)
        emptyContainer = view.findViewById(R.id.pending_approvals_empty)
        countText = view.findViewById(R.id.pending_approvals_count)
        approveAllButton = view.findViewById(R.id.pending_approvals_approve_all)

        view.findViewById<View>(R.id.pending_approvals_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = PendingMembersAdapter(
            items = pendingList,
            onApprove = { member -> onApproveOne(member) },
            onDecline = { member -> onDeclineOne(member) }
        )
        recyclerView.adapter = adapter

        approveAllButton.setOnClickListener { showApproveAllConfirm() }

        loadPending()
    }

    private fun loadPending() {
        val gid = groupId ?: return

        lifecycleScope.launch {
            showLoading(true)
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                if (accessToken == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    showLoading(false)
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getPendingMembers(accessToken, gid)
                }
                pendingList.clear()
                pendingList.addAll(response.pending_members)
                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    updateListUi()
                }
            } catch (e: ApiException) {
                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.error_loading_members), Toast.LENGTH_SHORT).show()
                    updateListUi()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.error_loading_members), Toast.LENGTH_SHORT).show()
                    updateListUi()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingView.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.GONE else View.VISIBLE
        emptyContainer.visibility = View.GONE
    }

    private fun updateListUi() {
        val count = pendingList.size
        approveAllButton.visibility = if (count >= 2) View.VISIBLE else View.GONE
        countText.text = getString(R.string.pending_requests_count_people, count)
        countText.contentDescription = getString(R.string.content_description_pending_count, count)

        adapter?.notifyDataSetChanged()

        if (pendingList.isEmpty()) {
            recyclerView.visibility = View.GONE
            loadingView.visibility = View.GONE
            emptyContainer.visibility = View.VISIBLE
            if (emptyContainer.childCount == 0) {
                val emptyView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.empty_state_generic, emptyContainer, false)
                emptyView.findViewById<TextView>(R.id.empty_icon).text = "✓"
                emptyView.findViewById<TextView>(R.id.empty_title).text = getString(R.string.empty_pending_title)
                emptyView.findViewById<TextView>(R.id.empty_message).text = getString(R.string.empty_pending_message)
                emptyView.findViewById<View>(R.id.empty_action_button).visibility = View.GONE
                emptyContainer.addView(emptyView)
            }
        } else {
            emptyContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun onApproveOne(member: PendingMember) {
        val gid = groupId ?: return
        adapter?.setLoadingForMember(member.user_id, true)
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        adapter?.setLoadingForMember(member.user_id, false)
                        Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.approveMember(accessToken, gid, member.user_id)
                }
                Handler(Looper.getMainLooper()).post {
                    adapter?.removeMember(member)
                    Toast.makeText(requireContext(), getString(R.string.toast_approved, member.display_name), Toast.LENGTH_SHORT).show()
                    updateListUi()
                }
            } catch (e: ApiException) {
                Handler(Looper.getMainLooper()).post {
                    adapter?.setLoadingForMember(member.user_id, false)
                    val msg = when (e.code) {
                        404 -> getString(R.string.toast_already_approved, member.display_name)
                        else -> getString(R.string.toast_approve_failed, member.display_name)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    if (e.code == 404) {
                        adapter?.removeMember(member)
                        updateListUi()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    adapter?.setLoadingForMember(member.user_id, false)
                    Toast.makeText(requireContext(), getString(R.string.toast_approve_failed, member.display_name), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onDeclineOne(member: PendingMember) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.decline_join_request_title))
            .setMessage(getString(R.string.decline_join_request_message, member.display_name))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.decline)) { _, _ ->
                doDecline(member)
            }
            .create()
            .also { it.show() }
    }

    private fun doDecline(member: PendingMember) {
        val gid = groupId ?: return
        adapter?.setLoadingForMember(member.user_id, true)
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        adapter?.setLoadingForMember(member.user_id, false)
                        Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.declineMember(accessToken, gid, member.user_id)
                }
                Handler(Looper.getMainLooper()).post {
                    adapter?.removeMember(member)
                    Toast.makeText(requireContext(), getString(R.string.toast_request_declined), Toast.LENGTH_SHORT).show()
                    updateListUi()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    adapter?.setLoadingForMember(member.user_id, false)
                    Toast.makeText(requireContext(), getString(R.string.error_loading_members), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showApproveAllConfirm() {
        if (pendingList.size < 2) return
        val names = pendingList.joinToString("\n") { it.display_name }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.approve_all_confirm_title, pendingList.size))
            .setMessage(names)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.approve)) { _, _ ->
                doApproveAll()
            }
            .create()
            .show()
    }

    private fun doApproveAll() {
        val gid = groupId ?: return
        val list = pendingList.toList()
        if (list.isEmpty()) return

        // Progress overlay: show loading
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        approveAllButton.isEnabled = false

        lifecycleScope.launch {
            val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
            val accessToken = tokenManager.getAccessToken()
            if (accessToken == null) {
                Handler(Looper.getMainLooper()).post {
                    loadingView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    approveAllButton.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            var successCount = 0
            for (member in list) {
                try {
                    withContext(Dispatchers.IO) {
                        ApiClient.approveMember(accessToken, gid, member.user_id)
                    }
                    successCount++
                    Handler(Looper.getMainLooper()).post {
                        adapter?.removeMember(member)
                    }
                } catch (_: Exception) {
                    // Continue with next
                }
            }
            Handler(Looper.getMainLooper()).post {
                loadingView.visibility = View.GONE
                updateListUi()
                recyclerView.visibility = if (pendingList.isEmpty()) View.GONE else View.VISIBLE
                emptyContainer.visibility = if (pendingList.isEmpty()) View.VISIBLE else View.GONE
                approveAllButton.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.toast_members_approved, successCount), Toast.LENGTH_SHORT).show()
                if (pendingList.isEmpty()) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }
}
