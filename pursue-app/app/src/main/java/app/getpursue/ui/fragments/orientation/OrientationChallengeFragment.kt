package app.getpursue.ui.fragments.orientation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.ui.fragments.challenges.ChallengeTemplatesFragment
import com.google.android.material.button.MaterialButton

class OrientationChallengeFragment : Fragment() {

    private lateinit var infoCardContent: View
    private lateinit var infoCardChevron: ImageView
    private var infoExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_challenge, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        infoCardContent = view.findViewById(R.id.info_card_content)
        infoCardChevron = view.findViewById(R.id.info_card_chevron)

        // Setup progress dots for step 2
        setupProgressDots(view.findViewById(R.id.progress_dots), 2)

        // Back / Skip
        view.findViewById<MaterialButton>(R.id.button_back).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.button_skip).setOnClickListener { goToStep3() }
        view.findViewById<MaterialButton>(R.id.button_create_own).setOnClickListener { goToStep3() }

        // Expandable info card
        view.findViewById<View>(R.id.info_card_header).setOnClickListener { toggleInfoCard() }

        // Embed ChallengeTemplatesFragment
        if (savedInstanceState == null) {
            childFragmentManager.commit {
                replace(R.id.challenge_templates_container, ChallengeTemplatesFragment.newInstance())
            }
        }
    }

    private fun toggleInfoCard() {
        infoExpanded = !infoExpanded
        infoCardContent.visibility = if (infoExpanded) View.VISIBLE else View.GONE
        infoCardChevron.rotation = if (infoExpanded) 180f else 0f
    }

    private fun goToStep3() {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, OrientationCreateGroupFragment.newInstance())
            addToBackStack(null)
        }
    }

    companion object {
        fun newInstance(): OrientationChallengeFragment = OrientationChallengeFragment()
    }
}
