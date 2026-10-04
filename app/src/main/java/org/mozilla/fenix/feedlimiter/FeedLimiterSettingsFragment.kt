package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
 * CAVEAT: not yet wired into any nav graph or hosting Activity - see
 * navigateToConfirm() below and the Phase 2 wiring instructions for what's
 * still a placeholder. This Fragment will compile and the row logic can be
 * unit-tested in isolation, but it isn't reachable from the app's actual
 * Settings menu until that wiring is done.
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
        val args = Bundle().apply {
            putString(FeedLimiterConfirmFragment.ARG_SITE, action.site)
            putString(FeedLimiterConfirmFragment.ARG_ACTION_TYPE, FeedLimiterConfirmFragment.wireTypeFor(action))
            if (action is FeedLimiterLoosenAction.RaisePostLimit) {
                putInt(FeedLimiterConfirmFragment.ARG_NEW_LIMIT, action.newLimit)
            }
        }
        // PLACEHOLDER NAVIGATION - this fork's real nav graph and Settings
        // container id aren't available from here. R.id.feed_limiter_settings_container
        // below is a stand-in you need to replace with wherever this
        // Fragment actually ends up hosted (most forks' Settings flow uses
        // a single shared fragment container across all settings screens -
        // find that id and use it here, or better, add a proper nav-graph
        // action from this screen to FeedLimiterConfirmFragment and call
        // findNavController().navigate(...) instead of a raw
        // FragmentTransaction). See the Phase 2 wiring instructions.
        parentFragmentManager.beginTransaction()
            .replace(R.id.feed_limiter_settings_container, FeedLimiterConfirmFragment().apply { arguments = args })
            .addToBackStack("feed_limiter_confirm")
            .commit()
    }
}
