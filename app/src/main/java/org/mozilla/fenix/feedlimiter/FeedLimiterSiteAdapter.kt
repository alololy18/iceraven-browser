package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.fenix.R
// IMPORTANT: add `import <your app's base package>.R` here once this file
// moves into your fork - see the matching note in
// FeedLimiterSettingsFragment.kt for why this sub-package needs it
// explicitly.

/**
 * One row per built-in site (7 total, Phase 2 scope - custom sites and
 * YouTube-regular are Phase 3, not shown here). Each control reports back
 * through FeedLimiterRowListener with enough context (the site key, its
 * full current BuiltInSiteSettings, and the attempted direction) for the
 * Fragment to decide whether the change applies immediately or needs to
 * route through the friction/cooldown confirm screen first - see the
 * Fragment's isLoosening-style dispatch for the actual rule per control.
 *
 * CAVEAT: untested against a real RecyclerView/ListAdapter setup in your
 * fork - the androidx.recyclerview / androidx.appcompat.widget.SwitchCompat
 * imports assume those libraries are already on your fork's classpath
 * (near-certain for any modern Fenix fork, since the stock Settings screen
 * uses both, but worth a quick confirm in your app module's build.gradle
 * if this doesn't compile).
 */
interface FeedLimiterRowListener {
    fun onMasterEnabledChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean)
    fun onPostCapSwitchChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean)
    fun onTimerCapSwitchChanged(site: String, current: BuiltInSiteSettings, turningOn: Boolean)
    fun onPostLimitStep(site: String, current: BuiltInSiteSettings, delta: Int)
    fun onTimerLimitStep(site: String, current: BuiltInSiteSettings, delta: Int)
}

class FeedLimiterSiteAdapter(
    private val listener: FeedLimiterRowListener
) : ListAdapter<FeedLimiterSettingsViewModel.SiteRow, FeedLimiterSiteAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feed_limiter_site, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), listener)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val siteName: TextView = itemView.findViewById(R.id.tvSiteName)
        private val usage: TextView = itemView.findViewById(R.id.tvUsage)
        private val switchEnabled: SwitchCompat = itemView.findViewById(R.id.switchEnabled)
        private val switchPostCap: SwitchCompat = itemView.findViewById(R.id.switchPostCap)
        private val switchTimerCap: SwitchCompat = itemView.findViewById(R.id.switchTimerCap)
        private val tvPostLimit: TextView = itemView.findViewById(R.id.tvPostLimit)
        private val tvTimerLimit: TextView = itemView.findViewById(R.id.tvTimerLimit)
        private val btnPostMinus: Button = itemView.findViewById(R.id.btnPostMinus)
        private val btnPostPlus: Button = itemView.findViewById(R.id.btnPostPlus)
        private val btnTimerMinus: Button = itemView.findViewById(R.id.btnTimerMinus)
        private val btnTimerPlus: Button = itemView.findViewById(R.id.btnTimerPlus)

        fun bind(row: FeedLimiterSettingsViewModel.SiteRow, listener: FeedLimiterRowListener) {
            val site = row.site
            val s = row.settings

            siteName.text = row.displayName
            usage.text = buildUsageText(s, row.usage)

            val postCapOn = s.mode == FeedLimiterMode.POST || s.mode == FeedLimiterMode.BOTH
            val timerCapOn = s.mode == FeedLimiterMode.TIMER || s.mode == FeedLimiterMode.BOTH

            // Clear listeners before setChecked() so programmatic updates
            // (e.g. after a confirm-screen change comes back through
            // LiveData) don't re-trigger the listener and loop back into
            // the Fragment as if the user had tapped the switch again.
            switchEnabled.setOnCheckedChangeListener(null)
            switchPostCap.setOnCheckedChangeListener(null)
            switchTimerCap.setOnCheckedChangeListener(null)

            switchEnabled.isChecked = s.enabled
            switchPostCap.isChecked = postCapOn
            switchTimerCap.isChecked = timerCapOn
            tvPostLimit.text = s.postLimit.toString()
            tvTimerLimit.text = s.timerMinutesLimit?.toString() ?: "–"

            // Child controls only make sense while the site is enabled and
            // (for the steppers) while that specific cap is active.
            switchPostCap.isEnabled = s.enabled
            switchTimerCap.isEnabled = s.enabled
            btnPostMinus.isEnabled = s.enabled && postCapOn
            btnPostPlus.isEnabled = s.enabled && postCapOn
            btnTimerMinus.isEnabled = s.enabled && timerCapOn
            btnTimerPlus.isEnabled = s.enabled && timerCapOn

            switchEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onMasterEnabledChanged(site, s, isChecked)
            }
            switchPostCap.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onPostCapSwitchChanged(site, s, isChecked)
            }
            switchTimerCap.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onTimerCapSwitchChanged(site, s, isChecked)
            }
            btnPostMinus.setOnClickListener { listener.onPostLimitStep(site, s, -POST_LIMIT_STEP) }
            btnPostPlus.setOnClickListener { listener.onPostLimitStep(site, s, POST_LIMIT_STEP) }
            btnTimerMinus.setOnClickListener { listener.onTimerLimitStep(site, s, -TIMER_LIMIT_STEP) }
            btnTimerPlus.setOnClickListener { listener.onTimerLimitStep(site, s, TIMER_LIMIT_STEP) }
        }

        private fun buildUsageText(s: BuiltInSiteSettings, usage: SiteUsage): String {
            val parts = mutableListOf<String>()
            val postCapOn = s.mode == FeedLimiterMode.POST || s.mode == FeedLimiterMode.BOTH
            val timerCapOn = s.mode == FeedLimiterMode.TIMER || s.mode == FeedLimiterMode.BOTH
            if (!s.enabled) return "Disabled today"
            if (postCapOn) parts.add("${usage.postsSeen} of ${s.postLimit} posts")
            if (timerCapOn) parts.add("${usage.minutesUsed} of ${s.timerMinutesLimit ?: "–"} min")
            if (usage.blocked) parts.add("blocked for today")
            return if (parts.isEmpty()) "No cap active" else parts.joinToString(" · ")
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FeedLimiterSettingsViewModel.SiteRow>() {
            override fun areItemsTheSame(
                oldItem: FeedLimiterSettingsViewModel.SiteRow,
                newItem: FeedLimiterSettingsViewModel.SiteRow
            ): Boolean = oldItem.site == newItem.site

            override fun areContentsTheSame(
                oldItem: FeedLimiterSettingsViewModel.SiteRow,
                newItem: FeedLimiterSettingsViewModel.SiteRow
            ): Boolean = oldItem == newItem
        }
    }
}
