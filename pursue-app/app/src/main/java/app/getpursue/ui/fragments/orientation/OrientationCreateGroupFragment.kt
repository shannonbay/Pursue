package app.getpursue.ui.fragments.orientation

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.OrientationActivity
import app.getpursue.ui.fragments.groups.CreateGroupFragment
import com.google.android.material.button.MaterialButton

/**
 * Step 3 of orientation: Create a group.
 * Wraps [CreateGroupFragment] with orientation-specific UI (progress dots, skip).
 */
class OrientationCreateGroupFragment : Fragment(), CreateGroupFragment.Callbacks {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_create_group, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup progress dots for step 4
        setupProgressDots(view.findViewById(R.id.progress_dots), 4)

        // Back / Skip
        view.findViewById<MaterialButton>(R.id.button_back).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.button_skip).setOnClickListener {
            (requireActivity() as OrientationActivity).completeOrientation()
        }

        // Embed CreateGroupFragment
        if (savedInstanceState == null) {
            val formFragment = CreateGroupFragment.newInstance(hideCancelButton = true)
            childFragmentManager.commit {
                replace(R.id.form_container, formFragment, "CreateGroupForm")
            }
        }
    }

    override fun onAttachFragment(childFragment: Fragment) {
        super.onAttachFragment(childFragment)
        if (childFragment is CreateGroupFragment) {
            childFragment.setCallbacks(this)
        }
    }

    // Callback from CreateGroupFragment when group and goal are successfully created
    override fun onGroupCreated(groupId: String, groupName: String, hasIcon: Boolean, iconEmoji: String?) {
        val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
            putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
            putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
            putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, hasIcon)
            putExtra(GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI, iconEmoji)
            putExtra(GroupDetailActivity.EXTRA_OPEN_INVITE_SHEET, true)
        }
        val activity = requireActivity() as OrientationActivity
        activity.setOrientationOutcome(AnalyticsEvents.OrientationOutcome.GROUP_CREATED_STEP_4)
        activity.completeOrientation(intent)
    }

    companion object {
        fun newInstance(): OrientationCreateGroupFragment = OrientationCreateGroupFragment()
    }
}
