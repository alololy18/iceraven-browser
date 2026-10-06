package org.mozilla.fenix.feedlimiter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.fenix.R

/**
 * Each callback passes the row's current settings so the Fragment can decide
 * between applying immediately (tightening) and the confirm screen (loosening).
 */
interface FeedLimiterRowListener {
    fun onMasterEnabledChanged(site: String, current: SiteSettings, turningOn: Boolean)
    fun onPostCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean)
    fun onTimerCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean)
    fun onVideoCapSwitchChanged(site: String, current: SiteSettings, turningOn: Boolean)
    fun onPostLimitStep(site: String, current: SiteSettings, delta: Int)
    fun onTimerLimitStep(site: String, current: SiteSettings, delta: Int)
    fun onVideoLimitStep(site: String, current: SiteSettings, delta: Int)
    fun onCountdownClicked(site: String, current: SiteSettings, cap: CapType)
    fun onDesaturateChanged(site: String, current: SiteSettings, checked: Boolean)
    fun onRemoveClicked(site: String)
}

class FeedLimiterSiteAdapter(
    private val listener: FeedLimiterRowListener,
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
        private val context: Context get() = itemView.context
        private val siteName: TextView = itemView.findViewById(R.id.tvSiteName)
        private val usage: TextView = itemView.findViewById(R.id.tvUsage)
        private val switchEnabled: SwitchCompat = itemView.findViewById(R.id.switchEnabled)
        private val rowPostCap: View = itemView.findViewById(R.id.rowPostCap)
        private val switchPostCap: SwitchCompat = itemView.findViewById(R.id.switchPostCap)
        private val tvPostLimit: TextView = itemView.findViewById(R.id.tvPostLimit)
        private val btnPostMinus: Button = itemView.findViewById(R.id.btnPostMinus)
        private val btnPostPlus: Button = itemView.findViewById(R.id.btnPostPlus)
        private val btnPostCountdown: Button = itemView.findViewById(R.id.btnPostCountdown)
        private val switchTimerCap: SwitchCompat = itemView.findViewById(R.id.switchTimerCap)
        private val tvTimerLimit: TextView = itemView.findViewById(R.id.tvTimerLimit)
        private val btnTimerMinus: Button = itemView.findViewById(R.id.btnTimerMinus)
        private val btnTimerPlus: Button = itemView.findViewById(R.id.btnTimerPlus)
        private val btnTimerCountdown: Button = itemView.findViewById(R.id.btnTimerCountdown)
        private val rowVideoCap: View = itemView.findViewById(R.id.rowVideoCap)
        private val switchVideoCap: SwitchCompat = itemView.findViewById(R.id.switchVideoCap)
        private val tvVideoLimit: TextView = itemView.findViewById(R.id.tvVideoLimit)
        private val btnVideoMinus: Button = itemView.findViewById(R.id.btnVideoMinus)
        private val btnVideoPlus: Button = itemView.findViewById(R.id.btnVideoPlus)
        private val btnVideoCountdown: Button = itemView.findViewById(R.id.btnVideoCountdown)
        private val checkDesaturate: CheckBox = itemView.findViewById(R.id.checkDesaturate)
        private val btnRemoveSite: Button = itemView.findViewById(R.id.btnRemoveSite)

        fun bind(row: FeedLimiterSettingsViewModel.SiteRow, listener: FeedLimiterRowListener) {
            val site = row.site
            val s = row.settings
            val noValue = context.getString(R.string.feed_limiter_no_value)

            siteName.text = row.displayName
            usage.text = buildUsageText(s, row.usage)

            // Cleared before programmatic updates so they don't fire as user input.
            switchEnabled.setOnCheckedChangeListener(null)
            switchPostCap.setOnCheckedChangeListener(null)
            switchTimerCap.setOnCheckedChangeListener(null)
            switchVideoCap.setOnCheckedChangeListener(null)
            checkDesaturate.setOnCheckedChangeListener(null)

            switchEnabled.isChecked = s.enabled
            switchPostCap.isChecked = s.postCapOn
            switchTimerCap.isChecked = s.timerCapOn
            switchVideoCap.isChecked = s.videoCapOn
            checkDesaturate.isChecked = s.desaturate

            tvPostLimit.text = s.postLimit?.toString() ?: noValue
            tvTimerLimit.text = s.timerMinutesLimit?.toString() ?: noValue
            tvVideoLimit.text = s.videoLimit?.toString() ?: noValue

            // Timer-only sites keep the timer on whenever the site is on, so they
            // have no timer switch; turning them off is the site switch.
            rowPostCap.isVisible = CapType.POST in s.capTypes
            btnPostCountdown.isVisible = CapType.POST in s.capTypes
            switchTimerCap.isVisible = s.type == SiteType.BUILT_IN
            rowVideoCap.isVisible = CapType.VIDEO in s.capTypes
            btnVideoCountdown.isVisible = CapType.VIDEO in s.capTypes
            checkDesaturate.isVisible = row.showDesaturate
            btnRemoveSite.isVisible = s.type == SiteType.CUSTOM

            switchPostCap.isEnabled = s.enabled
            switchTimerCap.isEnabled = s.enabled
            switchVideoCap.isEnabled = s.enabled
            btnPostMinus.isEnabled = s.enabled && s.postCapOn
            btnPostPlus.isEnabled = s.enabled && s.postCapOn
            btnTimerMinus.isEnabled = s.enabled && s.timerCapOn
            btnTimerPlus.isEnabled = s.enabled && s.timerCapOn
            btnVideoMinus.isEnabled = s.enabled && s.videoCapOn
            btnVideoPlus.isEnabled = s.enabled && s.videoCapOn

            btnPostCountdown.text = countdownLabel(s, CapType.POST)
            btnTimerCountdown.text = countdownLabel(s, CapType.TIMER)
            btnVideoCountdown.text = countdownLabel(s, CapType.VIDEO)

            switchEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onMasterEnabledChanged(site, s, isChecked)
            }
            switchPostCap.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onPostCapSwitchChanged(site, s, isChecked)
            }
            switchTimerCap.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onTimerCapSwitchChanged(site, s, isChecked)
            }
            switchVideoCap.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onVideoCapSwitchChanged(site, s, isChecked)
            }
            checkDesaturate.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                listener.onDesaturateChanged(site, s, isChecked)
            }
            btnPostMinus.setOnClickListener { listener.onPostLimitStep(site, s, -POST_LIMIT_STEP) }
            btnPostPlus.setOnClickListener { listener.onPostLimitStep(site, s, POST_LIMIT_STEP) }
            btnTimerMinus.setOnClickListener { listener.onTimerLimitStep(site, s, -TIMER_LIMIT_STEP) }
            btnTimerPlus.setOnClickListener { listener.onTimerLimitStep(site, s, TIMER_LIMIT_STEP) }
            btnVideoMinus.setOnClickListener { listener.onVideoLimitStep(site, s, -VIDEO_LIMIT_STEP) }
            btnVideoPlus.setOnClickListener { listener.onVideoLimitStep(site, s, VIDEO_LIMIT_STEP) }
            btnPostCountdown.setOnClickListener { listener.onCountdownClicked(site, s, CapType.POST) }
            btnTimerCountdown.setOnClickListener { listener.onCountdownClicked(site, s, CapType.TIMER) }
            btnVideoCountdown.setOnClickListener { listener.onCountdownClicked(site, s, CapType.VIDEO) }
            btnRemoveSite.setOnClickListener { listener.onRemoveClicked(site) }
        }

        private fun countdownLabel(s: SiteSettings, cap: CapType): String =
            context.getString(R.string.feed_limiter_countdown_button, context.getString(capLabelRes(cap)), s.countdownFor(cap))

        private fun buildUsageText(s: SiteSettings, usage: SiteUsage): String {
            if (!s.enabled) return context.getString(R.string.feed_limiter_usage_off)
            val parts = mutableListOf<String>()
            if (s.postCapOn) {
                parts.add(context.getString(R.string.feed_limiter_usage_posts, usage.postsSeen, s.postLimit))
            }
            if (s.timerCapOn) {
                parts.add(context.getString(R.string.feed_limiter_usage_minutes, usage.minutesUsed, s.timerMinutesLimit))
            }
            if (s.type == SiteType.YOUTUBE_REGULAR) {
                parts.add(
                    if (s.videoCapOn) {
                        context.getString(R.string.feed_limiter_usage_videos_capped, usage.videosWatched, s.videoLimit)
                    } else {
                        context.getString(R.string.feed_limiter_usage_videos, usage.videosWatched)
                    },
                )
            }
            if (usage.blocked) parts.add(context.getString(R.string.feed_limiter_usage_blocked))
            if (parts.isEmpty()) return context.getString(R.string.feed_limiter_usage_no_cap)
            return parts.joinToString(context.getString(R.string.feed_limiter_usage_separator))
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FeedLimiterSettingsViewModel.SiteRow>() {
            override fun areItemsTheSame(
                oldItem: FeedLimiterSettingsViewModel.SiteRow,
                newItem: FeedLimiterSettingsViewModel.SiteRow,
            ): Boolean = oldItem.site == newItem.site

            override fun areContentsTheSame(
                oldItem: FeedLimiterSettingsViewModel.SiteRow,
                newItem: FeedLimiterSettingsViewModel.SiteRow,
            ): Boolean = oldItem == newItem
        }
    }
}
