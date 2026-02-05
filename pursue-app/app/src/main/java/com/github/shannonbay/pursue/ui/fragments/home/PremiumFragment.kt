package com.github.shannonbay.pursue.ui.fragments.home

import android.os.Bundle
import com.github.shannonbay.pursue.ui.activities.MainAppActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.shannonbay.pursue.R
import com.google.android.material.button.MaterialButton

/**
 * Pursue Premium upgrade screen (pursue-subscription-spec).
 * Shows plan features, price, and Subscribe button; launches Google Play Billing when implemented.
 */
class PremiumFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_premium, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.premium_subscribe_button).setOnClickListener {
            (activity as? MainAppActivity)?.launchPremiumPurchaseFlow()
        }
    }

    companion object {
        fun newInstance(): PremiumFragment = PremiumFragment()
    }
}
