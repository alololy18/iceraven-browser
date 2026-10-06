package org.mozilla.fenix.feedlimiter

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Combines the bridge's settings, usage and low-saturation mode into one row
 * per site. Countdown-gated changes never go through here; the Fragment sends
 * those to the confirm screen.
 */
class FeedLimiterSettingsViewModel(
    private val bridge: FeedLimiterExtensionBridge,
) : ViewModel() {

    data class SiteRow(
        val site: String,
        val displayName: String,
        val settings: SiteSettings,
        val usage: SiteUsage,
        val showDesaturate: Boolean,
    )

    private val _rows = MediatorLiveData<List<SiteRow>>()
    val rows: LiveData<List<SiteRow>> get() = _rows

    val lowSaturation: LiveData<LowSaturationMode> get() = bridge.lowSaturation

    init {
        _rows.addSource(bridge.settings) { recompute() }
        _rows.addSource(bridge.liveUsage) { recompute() }
        _rows.addSource(bridge.lowSaturation) { recompute() }
    }

    private fun recompute() {
        val settingsMap = bridge.settings.value.orEmpty()
        val usageMap = bridge.liveUsage.value.orEmpty()
        val showDesaturate = bridge.lowSaturation.value == LowSaturationMode.SELECTED
        val customSites = settingsMap.filterValues { it.type == SiteType.CUSTOM }.keys.sorted()
        _rows.value = (BUILT_IN_SITES + YOUTUBE_SITE + customSites).mapNotNull { site ->
            val siteSettings = settingsMap[site] ?: return@mapNotNull null
            SiteRow(
                site = site,
                displayName = displayNameFor(site),
                settings = siteSettings,
                usage = usageMap[site] ?: SiteUsage(),
                showDesaturate = showDesaturate,
            )
        }
    }

    fun applyImmediately(site: String, newSettings: SiteSettings): Boolean =
        bridge.updateSiteSettings(site, newSettings)

    fun addCustomSite(input: String, timerMinutesLimit: Int): DomainResult =
        bridge.addCustomSite(input, timerMinutesLimit)

    fun setLowSaturationMode(mode: LowSaturationMode) = bridge.setLowSaturationMode(mode)

    class Factory(private val bridge: FeedLimiterExtensionBridge) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(FeedLimiterSettingsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
            }
            @Suppress("UNCHECKED_CAST")
            return FeedLimiterSettingsViewModel(bridge) as T
        }
    }
}
