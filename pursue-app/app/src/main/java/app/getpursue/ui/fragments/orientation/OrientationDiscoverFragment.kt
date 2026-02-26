package app.getpursue.ui.fragments.orientation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.ui.activities.OrientationActivity
import app.getpursue.ui.fragments.discover.DiscoverFragment
import com.google.android.material.button.MaterialButton

/**
 * Step 2 of orientation: Browse public groups via Discover.
 * Wraps [DiscoverFragment] with orientation-specific UI (progress dots, skip, create own).
 */
class OrientationDiscoverFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_discover, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup progress dots for step 2
        setupProgressDots(view.findViewById(R.id.progress_dots), 2)

        view.findViewById<MaterialButton>(R.id.button_back).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.button_skip).setOnClickListener { goToChallenge() }
        view.findViewById<MaterialButton>(R.id.button_create_own).setOnClickListener { goToChallenge() }

        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.discover_container, DiscoverFragment.newInstance())
            }
        }
    }

    override fun onAttachFragment(childFragment: Fragment) {
        super.onAttachFragment(childFragment)
        if (childFragment is DiscoverFragment) {
            childFragment.setGroupJoinedCallback {
                (requireActivity() as OrientationActivity).setOrientationOutcome(
                    AnalyticsEvents.OrientationOutcome.JOIN_REQUESTED_STEP_2
                )
            }
        }
    }

    private fun goToChallenge() {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, OrientationChallengeFragment.newInstance())
            addToBackStack(null)
        }
    }

    companion object {
        fun newInstance(): OrientationDiscoverFragment = OrientationDiscoverFragment()
    }
}
