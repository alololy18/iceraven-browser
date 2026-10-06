package org.mozilla.fenix.feedlimiter

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.nav
import org.mozilla.fenix.ext.showToolbar

/**
 * Lists every site (built-in, YouTube, custom) and decides, per control,
 * whether a change applies immediately or goes through the confirm screen.
 * The settings math lives in FeedLimiterSettingsModel.kt.
 */
class FeedLimiterSettingsFragment : Fragment(), FeedLimiterRowListener, SystemInsetsPaddedFragment {

    private var bridge: FeedLimiterExtensionBridge? = null

    // Only touched after onViewCreated has confirmed the bridge exists.
    private val viewModel: FeedLimiterSettingsViewModel by viewModels {
        FeedLimiterSettingsViewModel.Factory(checkNotNull(bridge))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_feed_limiter_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val readyBridge = FeedLimiterBridgeHolder.instance
        if (readyBridge == null) {
            FeedLimiterBridgeHolder.logUnavailable("Feed Limiter settings")
            view.findViewById<TextView>(R.id.tvFeedLimiterUnavailable).isVisible = true
            view.findViewById<View>(R.id.feedLimiterContent).isVisible = false
            return
        }
        bridge = readyBridge

        val adapter = FeedLimiterSiteAdapter(this)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerFeedLimiterSites)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        viewModel.rows.observe(viewLifecycleOwner) { rows -> adapter.submitList(rows) }

        val radioGroup = view.findViewById<RadioGroup>(R.id.radioLowSaturation)
        val modeChangeListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
            radioIdToMode(checkedId)?.let { viewModel.setLowSaturationMode(it) }
        }
        viewModel.lowSaturation.observe(viewLifecycleOwner) { mode ->
            radioGroup.setOnCheckedChangeListener(null)
            radioGroup.check(modeToRadioId(mode))
            radioGroup.setOnCheckedChangeListener(modeChangeListener)
        }

        view.findViewById<Button>(R.id.btnAddSite).setOnClickListener { showAddSiteDialog() }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.preferences_feed_limiter))
    }

    // --- FeedLimiterRowListener ------------------------------------------------

    override fun onMasterEnabledChanged(site: String, current: SiteSettings, turningOn: Boolean) {
        if (turningOn) {
            viewModel.applyImmediately(site, current.copy(enabled = true))
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisableSite(site))
        }
    }

    override fun onPostCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean) {
        if (turningOn) {
            viewModel.applyImmediately(site, current.copy(mode = current.mode.withPostCapOn(), enabled = true))
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisablePostCap(site))
        }
    }

    override fun onTimerCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean) {
        if (turningOn) {
            val newLimit = current.timerMinutesLimit ?: DEFAULT_TIMER_LIMIT_ON_ENABLE
            viewModel.applyImmediately(
                site,
                current.copy(mode = current.mode.withTimerCapOn(), timerMinutesLimit = newLimit, enabled = true),
            )
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisableTimerCap(site))
        }
    }

    override fun onVideoCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean) {
        if (turningOn) {
            viewModel.applyImmediately(site, current.copy(videoLimit = DEFAULT_VIDEO_LIMIT))
        } else {
            navigateToConfirm(FeedLimiterLoosenAction.DisableVideoCap(site))
        }
    }

    override fun onPostLimitStep(site: String, current: SiteSettings, delta: Int) {
        val currentLimit = current.postLimit ?: return
        val newLimit = (currentLimit + delta).coerceIn(MIN_POST_LIMIT, MAX_POST_LIMIT)
        when {
            newLimit > currentLimit -> navigateToConfirm(FeedLimiterLoosenAction.RaisePostLimit(site, newLimit))
            newLimit < currentLimit -> viewModel.applyImmediately(site, current.copy(postLimit = newLimit))
        }
    }

    override fun onTimerLimitStep(site: String, current: SiteSettings, delta: Int) {
        val currentLimit = current.timerMinutesLimit ?: return
        val newLimit = (currentLimit + delta).coerceIn(MIN_TIMER_LIMIT, MAX_TIMER_LIMIT)
        when {
            newLimit > currentLimit -> navigateToConfirm(FeedLimiterLoosenAction.RaiseTimerLimit(site, newLimit))
            newLimit < currentLimit -> viewModel.applyImmediately(site, current.copy(timerMinutesLimit = newLimit))
        }
    }

    override fun onVideoLimitStep(site: String, current: SiteSettings, delta: Int) {
        val currentLimit = current.videoLimit ?: return
        val newLimit = (currentLimit + delta).coerceIn(MIN_VIDEO_LIMIT, MAX_VIDEO_LIMIT)
        when {
            newLimit > currentLimit -> navigateToConfirm(FeedLimiterLoosenAction.RaiseVideoLimit(site, newLimit))
            newLimit < currentLimit -> viewModel.applyImmediately(site, current.copy(videoLimit = newLimit))
        }
    }

    override fun onCountdownClicked(site: String, current: SiteSettings, cap: CapType) {
        val currentMinutes = current.countdownFor(cap)
        val labels = COUNTDOWN_OPTIONS_MINUTES.map { getString(R.string.feed_limiter_countdown_option, it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.feed_limiter_countdown_dialog_title, getString(capLabelRes(cap))))
            .setSingleChoiceItems(labels, COUNTDOWN_OPTIONS_MINUTES.indexOf(currentMinutes)) { dialog, which ->
                dialog.dismiss()
                val chosen = COUNTDOWN_OPTIONS_MINUTES[which]
                // Lengthening is a tightening; shortening would weaken the gate, so it waits out the current length.
                when {
                    chosen > currentMinutes -> viewModel.applyImmediately(
                        site,
                        current.copy(countdownMinutes = current.countdownMinutes + (cap to chosen)),
                    )
                    chosen < currentMinutes ->
                        navigateToConfirm(FeedLimiterLoosenAction.ShortenCountdown(site, cap, chosen))
                }
            }
            .setNegativeButton(R.string.feed_limiter_cancel, null)
            .show()
    }

    override fun onDesaturateChanged(site: String, current: SiteSettings, checked: Boolean) {
        viewModel.applyImmediately(site, current.copy(desaturate = checked))
    }

    override fun onRemoveClicked(site: String) {
        navigateToConfirm(FeedLimiterLoosenAction.RemoveSite(site))
    }

    // --- helpers -----------------------------------------------------------------

    private fun showAddSiteDialog() {
        val context = requireContext()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val domainInput = EditText(context).apply {
            hint = getString(R.string.feed_limiter_add_site_domain_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val minutesInput = EditText(context).apply {
            hint = getString(R.string.feed_limiter_add_site_minutes_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(DEFAULT_CUSTOM_TIMER_MINUTES.toString())
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(domainInput)
            addView(minutesInput)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.feed_limiter_add_site_title)
            .setView(container)
            .setPositiveButton(R.string.feed_limiter_add_site_confirm, null)
            .setNegativeButton(R.string.feed_limiter_cancel, null)
            .create()
        // Replaces the default click handler so the dialog stays open to show errors.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = minutesInput.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes !in MIN_TIMER_LIMIT..MAX_TIMER_LIMIT) {
                    minutesInput.error = getString(R.string.feed_limiter_add_site_minutes_error, MIN_TIMER_LIMIT, MAX_TIMER_LIMIT)
                    return@setOnClickListener
                }
                when (val result = viewModel.addCustomSite(domainInput.text.toString(), minutes)) {
                    is DomainResult.Valid -> dialog.dismiss()
                    is DomainResult.Invalid -> domainInput.error = result.coveringSite
                        ?.let { getString(result.messageRes, it) }
                        ?: getString(result.messageRes)
                }
            }
        }
        dialog.show()
    }

    private fun radioIdToMode(id: Int): LowSaturationMode? = when (id) {
        R.id.radioLowSaturationOff -> LowSaturationMode.OFF
        R.id.radioLowSaturationAll -> LowSaturationMode.ALL
        R.id.radioLowSaturationSelected -> LowSaturationMode.SELECTED
        else -> null
    }

    private fun modeToRadioId(mode: LowSaturationMode): Int = when (mode) {
        LowSaturationMode.OFF -> R.id.radioLowSaturationOff
        LowSaturationMode.ALL -> R.id.radioLowSaturationAll
        LowSaturationMode.SELECTED -> R.id.radioLowSaturationSelected
    }

    private fun navigateToConfirm(action: FeedLimiterLoosenAction) {
        val newValue = when (action) {
            is FeedLimiterLoosenAction.RaisePostLimit -> action.newLimit
            is FeedLimiterLoosenAction.RaiseTimerLimit -> action.newLimit
            is FeedLimiterLoosenAction.RaiseVideoLimit -> action.newLimit
            is FeedLimiterLoosenAction.ShortenCountdown -> action.newMinutes
            else -> 0
        }
        val directions = FeedLimiterSettingsFragmentDirections
            .actionFeedLimiterSettingsFragmentToFeedLimiterConfirmFragment(
                feedLimiterConfirmSite = action.site,
                feedLimiterConfirmActionType = FeedLimiterConfirmFragment.wireTypeFor(action),
                feedLimiterConfirmNewLimit = newValue,
            )
        // nav() ignores a second tap that arrives after we've already left this screen.
        nav(R.id.feedLimiterSettingsFragment, directions)
    }
}
