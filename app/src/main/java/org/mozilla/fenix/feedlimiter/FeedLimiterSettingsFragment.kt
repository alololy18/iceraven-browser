package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.fenix.R

// IMPORTANT: add `import <your app's base package>.R` here once this file
// moves into your fork - e.g. `import org.mozilla.fenix.R`. Because this
// file lives in the org.mozilla.fenix.feedlimiter SUB-package, Kotlin will
// NOT automatically find the app module's R class the way it would if this
// file were directly in the base package - every unqualified `R.layout.*`
// / `R.id.*` reference below will fail to resolve without this import.

/**
 * Entry point for the Phase 2 "Feed Limiter" native settings screen -
 * built-in sites only (7 rows: Instagram, X, TikTok, Facebook, Reddit,
 * LinkedIn, YouTube Shorts). Custom sites and YouTube-regular are Phase 3
 * and deliberately not listed here, per the build plan's phasing.
 *
 * Each row's controls are wired through FeedLimiterRowListener below. The
 * ONLY job of this class, direction-wise, is deciding immediate-apply vs.
 * confirm-screen for a given control interaction - the actual settings
 * math lives in FeedLimiterSettingsModel.kt (withPostCapOn/withTimerCapOn
 * for the "turning on" direction, FeedLimiterLoosenAction.applying() for
 * the "turning off/raising" direction).
 *
 * Navigation to the confirm screen goes through this fork's nav graph
 * (Safe Args), matching how every other Settings sub-screen in this app
 * navigates - see navigateToConfirm() below. This requires
 * feedLimiterSettingsFragment and feedLimiterConfirmFragment to both be
 * registered as real destinations in nav_graph.xml, with an action between
 * them named action_feedLimiterSettingsFragment_to_feedLimiterConfirmFragment
 * - see the Phase 2 sync instructions for the exact XML.
 */
class FeedLimiterSettingsFragment : Fragment(), FeedLimiterRowListener {

    private val viewModel: FeedLimiterSettingsViewModel by viewModels {
        FeedLimiterSettingsViewModel.Factory(FeedLimiterBridgeHolder.instance)
    }

    private lateinit var adapter: FeedLimiterSiteAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_feed_limiter_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FeedLimiterSiteAdapter(this)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerFeedLimiterSites)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submitList(rows)
        }
    }

    // --- FeedLimiterRowListener --------------------------------------------
    // Each method decides, for its one control, whether the attempted
    // change is a tighten (apply immediately) or a loosen (route to the
    // confirm/cooldown screen) - see FeedLimiterSettingsModel.kt's doc
    // comments for why each direction is classified the way it is.

    override fun onMasterEnabledChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean) {
        if (turningOn) {
            viewModel.applyImmediately(site, current.copy(enabled = true))
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisableSite(site))
        }
    }

    override fun onPostCapSwitchChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean) {
        if (turningOn) {
            viewModel.applyImmediately(
                site,
                current.copy(mode = current.mode.withPostCapOn(), enabled = true)
            )
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisablePostCap(site))
        }
    }

    override fun onTimerCapSwitchChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean) {
        if (turningOn) {
            val newLimit = current.timerMinutesLimit ?: DEFAULT_TIMER_LIMIT_ON_ENABLE
            viewModel.applyImmediately(
                site,
                current.copy(mode = current.mode.withTimerCapOn(), timerMinutesLimit = newLimit, enabled = true)
            )
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisableTimerCap(site))
        }
    }

    override fun onPostLimitStep(site: String, current: BuiltInSiteSettings, delta: Int) {
        val newLimit = (current.postLimit + delta).coerceAtLeast(MIN_POST_LIMIT)
        if (delta > 0) {
            navigateToConfirm(FeedLimiterLoosenAction.RaisePostLimit(site, newLimit))
        } else {
            viewModel.applyImmediately(site, current.copy(postLimit = newLimit))
        }
    }

    override fun onTimerLimitStep(site: String, current: BuiltInSiteSettings, delta: Int) {
        val currentLimit = current.timerMinutesLimit ?: DEFAULT_TIMER_LIMIT_ON_ENABLE
        val newLimit = (currentLimit + delta).coerceAtLeast(MIN_TIMER_LIMIT)
        // Per the feature list (items 23-24), only DISABLING the timer cap
        // is friction-gated - raising its duration isn't itemized, so this
        // applies immediately even though it's a loosening change in
        // spirit. If you'd rather gate this too, mirror RaisePostLimit:
        // add a RaiseTimerLimit case to FeedLimiterLoosenAction and route
        // the delta > 0 branch there the same way onPostLimitStep does.
        viewModel.applyImmediately(site, current.copy(timerMinutesLimit = newLimit))
    }

    private fun navigateToConfirm(action: FeedLimiterLoosenAction) {
        val newLimit = (action as? FeedLimiterLoosenAction.RaisePostLimit)?.newLimit ?: 0
        val directions = FeedLimiterSettingsFragmentDirections
            .actionFeedLimiterSettingsFragmentToFeedLimiterConfirmFragment(
                feedLimiterConfirmSite = action.site,
                feedLimiterConfirmActionType = FeedLimiterConfirmFragment.wireTypeFor(action),
                feedLimiterConfirmNewLimit = newLimit,
            )
        findNavController().navigate(directions)
    }
}