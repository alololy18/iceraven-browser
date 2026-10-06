package org.mozilla.fenix.feedlimiter

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.navArgs
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.showToolbar
import java.util.Locale

/**
 * Countdown confirm screen shared by every loosening action; which action it
 * confirms comes entirely from the nav arguments.
 */
class FeedLimiterConfirmFragment : Fragment(), SystemInsetsPaddedFragment {

    private val args by navArgs<FeedLimiterConfirmFragmentArgs>()
    private val action: FeedLimiterLoosenAction by lazy { actionFromArgs() }

    private var bridge: FeedLimiterExtensionBridge? = null
    private var durationMinutes = DEFAULT_COUNTDOWN_MINUTES

    // Only touched after onViewCreated has confirmed the bridge and site exist.
    private val viewModel: FeedLimiterConfirmViewModel by viewModels {
        FeedLimiterConfirmViewModel.Factory(action, durationMinutes, checkNotNull(bridge))
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
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_feed_limiter_confirm, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val readyBridge = FeedLimiterBridgeHolder.instance
        val current = readyBridge?.settingsFor(action.site)
        if (readyBridge == null || current == null) {
            val message = if (readyBridge == null) {
                FeedLimiterBridgeHolder.logUnavailable("Feed Limiter confirm screen")
                R.string.feed_limiter_unavailable
            } else {
                Log.w(LOG_TAG, "Confirm screen opened for ${action.site}, which is no longer configured")
                R.string.feed_limiter_confirm_site_missing
            }
            view.findViewById<TextView>(R.id.tvConfirmUnavailable).apply {
                setText(message)
                isVisible = true
            }
            view.findViewById<View>(R.id.confirmContent).isVisible = false
            return
        }
        bridge = readyBridge
        durationMinutes = current.countdownMinutesFor(action)

        val tvDescription = view.findViewById<TextView>(R.id.tvConfirmDescription)
        val tvCountdown = view.findViewById<TextView>(R.id.tvCountdown)
        val progress = view.findViewById<ProgressBar>(R.id.progressCountdown)
        val btnCancel = view.findViewById<Button>(R.id.btnConfirmCancel)
        val btnApply = view.findViewById<Button>(R.id.btnConfirmApply)

        tvDescription.text = getString(R.string.feed_limiter_confirm_description, action.describe(requireContext()))

        val totalSeconds = viewModel.totalSeconds
        viewModel.secondsRemaining.observe(viewLifecycleOwner) { secondsLeft ->
            tvCountdown.text = String.format(Locale.ROOT, "%d:%02d", secondsLeft / 60, secondsLeft % 60)
            progress.progress = if (totalSeconds > 0) ((totalSeconds - secondsLeft) * 1000) / totalSeconds else 0
        }
        viewModel.state.observe(viewLifecycleOwner) { state ->
            btnApply.isEnabled = state == FeedLimiterConfirmViewModel.State.COMPLETED
        }

        // Popping triggers onPause, which already resets the countdown.
        btnCancel.setOnClickListener { parentFragmentManager.popBackStack() }
        btnApply.setOnClickListener {
            if (!viewModel.confirmAndApply()) Log.w(LOG_TAG, "Confirmed change was not applied: $action")
            parentFragmentManager.popBackStack()
        }

        viewLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.preferences_feed_limiter))
    }

    private fun actionFromArgs(): FeedLimiterLoosenAction {
        val site = args.feedLimiterConfirmSite
        val value = args.feedLimiterConfirmNewLimit
        return when (val type = args.feedLimiterConfirmActionType) {
            TYPE_DISABLE_POST_CAP -> FeedLimiterLoosenAction.DisablePostCap(site)
            TYPE_DISABLE_TIMER_CAP -> FeedLimiterLoosenAction.DisableTimerCap(site)
            TYPE_DISABLE_VIDEO_CAP -> FeedLimiterLoosenAction.DisableVideoCap(site)
            TYPE_RAISE_POST_LIMIT -> FeedLimiterLoosenAction.RaisePostLimit(site, value)
            TYPE_RAISE_TIMER_LIMIT -> FeedLimiterLoosenAction.RaiseTimerLimit(site, value)
            TYPE_RAISE_VIDEO_LIMIT -> FeedLimiterLoosenAction.RaiseVideoLimit(site, value)
            TYPE_DISABLE_SITE -> FeedLimiterLoosenAction.DisableSite(site)
            TYPE_REMOVE_SITE -> FeedLimiterLoosenAction.RemoveSite(site)
            else -> {
                val cap = type.removePrefix(TYPE_SHORTEN_COUNTDOWN_PREFIX)
                    .takeIf { type.startsWith(TYPE_SHORTEN_COUNTDOWN_PREFIX) }
                    ?.let { CapType.fromWireValue(it) }
                    ?: throw IllegalArgumentException("Unknown feed limiter confirm action type: $type")
                FeedLimiterLoosenAction.ShortenCountdown(site, cap, value)
            }
        }
    }

    companion object {
        private const val TYPE_DISABLE_POST_CAP = "disable_post_cap"
        private const val TYPE_DISABLE_TIMER_CAP = "disable_timer_cap"
        private const val TYPE_DISABLE_VIDEO_CAP = "disable_video_cap"
        private const val TYPE_RAISE_POST_LIMIT = "raise_post_limit"
        private const val TYPE_RAISE_TIMER_LIMIT = "raise_timer_limit"
        private const val TYPE_RAISE_VIDEO_LIMIT = "raise_video_limit"
        private const val TYPE_DISABLE_SITE = "disable_site"
        private const val TYPE_REMOVE_SITE = "remove_site"
        private const val TYPE_SHORTEN_COUNTDOWN_PREFIX = "shorten_countdown_"

        /** Nav-argument encoding of an action; the numeric part travels in feedLimiterConfirmNewLimit. */
        fun wireTypeFor(action: FeedLimiterLoosenAction): String = when (action) {
            is FeedLimiterLoosenAction.DisablePostCap -> TYPE_DISABLE_POST_CAP
            is FeedLimiterLoosenAction.DisableTimerCap -> TYPE_DISABLE_TIMER_CAP
            is FeedLimiterLoosenAction.DisableVideoCap -> TYPE_DISABLE_VIDEO_CAP
            is FeedLimiterLoosenAction.RaisePostLimit -> TYPE_RAISE_POST_LIMIT
            is FeedLimiterLoosenAction.RaiseTimerLimit -> TYPE_RAISE_TIMER_LIMIT
            is FeedLimiterLoosenAction.RaiseVideoLimit -> TYPE_RAISE_VIDEO_LIMIT
            is FeedLimiterLoosenAction.DisableSite -> TYPE_DISABLE_SITE
            is FeedLimiterLoosenAction.RemoveSite -> TYPE_REMOVE_SITE
            is FeedLimiterLoosenAction.ShortenCountdown -> TYPE_SHORTEN_COUNTDOWN_PREFIX + action.capType.wireValue
        }
    }
}
