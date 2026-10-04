package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.
// IMPORTANT: add `import <your app's base package>.R` here once this file
// moves into your fork - see the matching note in
// FeedLimiterSettingsFragment.kt for why this sub-package needs it
// explicitly.

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.navArgs

/**
 * Generic friction/cooldown confirm screen, reused for all three
 * loosening actions (feature list items 23-27): disabling the post cap,
 * raising the post limit, and disabling the timer cap. Which action this
 * instance is for comes entirely from its arguments (see
 * FeedLimiterSettingsFragment.navigateToConfirm()) - this class has no
 * action-specific logic beyond what FeedLimiterLoosenAction.describe()
 * and .applying() already provide.
 *
 * Countdown start/reset is driven by DefaultLifecycleObserver's onResume/
 * onPause rather than repeatOnLifecycle(RESUMED): what items 25-26 actually
 * need is "exactly on entering RESUMED, start; exactly on leaving RESUMED,
 * hard-reset" - a direct 1:1 match to ON_RESUME/ON_PAUSE lifecycle events,
 * with no need for repeatOnLifecycle's cancellable-suspend-block machinery.
 * Rotation does NOT trigger this Fragment's onPause in a way that loses
 * state, because the countdown lives in the ViewModel (survives
 * recreation), not in this observer or any View.
 *
 * CAVEAT: unverified against a live build - FLAG_KEEP_SCREEN_ON handling in
 * particular assumes this Fragment's Activity is exclusively this screen's
 * concern while resumed; if your fork's Settings flow hosts multiple
 * fragments' windows simultaneously (e.g. a two-pane tablet layout), the
 * add/clear calls below could race with another screen's own flag usage -
 * worth a check on a tablet if your fork supports that layout.
 */
class FeedLimiterConfirmFragment : Fragment() {

    // Safe-Args generated accessor - requires feedLimiterConfirmFragment's
    // three <argument> entries (site/actionType/newLimit) to be declared in
    // nav_graph.xml exactly as the Phase 2 sync instructions specify, since
    // FeedLimiterConfirmFragmentArgs is generated from that XML at build
    // time and won't exist/compile until those arguments are in place.
    private val args by navArgs<FeedLimiterConfirmFragmentArgs>()
    private val site: String get() = args.feedLimiterConfirmSite
    private val action: FeedLimiterLoosenAction by lazy { actionFromArgs() }

    private val viewModel: FeedLimiterConfirmViewModel by viewModels {
        val bridge = FeedLimiterBridgeHolder.instance
        val duration = bridge.getFrictionDurationMinutes(site, args.feedLimiterConfirmActionType)
        FeedLimiterConfirmViewModel.Factory(action, duration, bridge)
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.startOrResume()
        }

        override fun onPause(owner: LifecycleOwner) {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.pauseAndReset()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_feed_limiter_confirm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvDescription = view.findViewById<TextView>(R.id.tvConfirmDescription)
        val tvCountdown = view.findViewById<TextView>(R.id.tvCountdown)
        val progress = view.findViewById<ProgressBar>(R.id.progressCountdown)
        val btnCancel = view.findViewById<Button>(R.id.btnConfirmCancel)
        val btnApply = view.findViewById<Button>(R.id.btnConfirmApply)

        tvDescription.text = "You're about to ${viewModel.description}."
        btnApply.text = "Confirm"

        // Total duration is fixed once the ViewModel is created (see its
        // Factory above), so this is read once here purely for the
        // progress bar's max-vs-remaining math, not to mutate the
        // countdown itself.
        val totalSecondsAtStart = viewModel.secondsRemaining.value ?: 1

        viewModel.secondsRemaining.observe(viewLifecycleOwner) { secondsLeft ->
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            tvCountdown.text = String.format("%d:%02d", minutes, seconds)
            progress.progress = if (totalSecondsAtStart > 0) {
                ((totalSecondsAtStart - secondsLeft) * 1000) / totalSecondsAtStart
            } else {
                0
            }
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            btnApply.isEnabled = state == FeedLimiterConfirmViewModel.State.COMPLETED
        }

        btnCancel.setOnClickListener {
            // No explicit reset call needed here - popping this Fragment
            // triggers onPause() via lifecycleObserver above, which already
            // resets the countdown before the ViewModel is cleared.
            parentFragmentManager.popBackStack()
        }

        btnApply.setOnClickListener {
            viewModel.confirmAndApply()
            parentFragmentManager.popBackStack()
        }

        viewLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    // Instance method, not companion - needs `site` and `args`, both of
    // which are per-instance (Safe-Args-backed) properties above.
    private fun actionFromArgs(): FeedLimiterLoosenAction {
        return when (val type = args.feedLimiterConfirmActionType) {
            TYPE_DISABLE_POST_CAP -> FeedLimiterLoosenAction.DisablePostCap(site)
            TYPE_DISABLE_TIMER_CAP -> FeedLimiterLoosenAction.DisableTimerCap(site)
            TYPE_DISABLE_SITE -> FeedLimiterLoosenAction.DisableSite(site)
            TYPE_RAISE_POST_LIMIT -> FeedLimiterLoosenAction.RaisePostLimit(site, args.feedLimiterConfirmNewLimit)
            else -> throw IllegalArgumentException("Unknown feed limiter confirm action type: $type")
        }
    }

    companion object {
        private const val TYPE_DISABLE_POST_CAP = "disable_post_cap"
        private const val TYPE_DISABLE_TIMER_CAP = "disable_timer_cap"
        private const val TYPE_RAISE_POST_LIMIT = "raise_post_limit"
        private const val TYPE_DISABLE_SITE = "disable_site"

        /** Encodes a FeedLimiterLoosenAction's type as a plain string for
         *  the nav-graph argument - kept here rather than as a property on
         *  the sealed class itself so FeedLimiterSettingsModel.kt (shared
         *  with the bridge/adapter) doesn't need to know about Fragment
         *  navigation wire formats at all. */
        fun wireTypeFor(action: FeedLimiterLoosenAction): String = when (action) {
            is FeedLimiterLoosenAction.DisablePostCap -> TYPE_DISABLE_POST_CAP
            is FeedLimiterLoosenAction.DisableTimerCap -> TYPE_DISABLE_TIMER_CAP
            is FeedLimiterLoosenAction.RaisePostLimit -> TYPE_RAISE_POST_LIMIT
            is FeedLimiterLoosenAction.DisableSite -> TYPE_DISABLE_SITE
        }
    }
}