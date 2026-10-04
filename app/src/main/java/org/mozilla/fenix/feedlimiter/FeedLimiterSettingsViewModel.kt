package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Backs FeedLimiterSettingsFragment. Pure presentation logic: combines the
 * bridge's settings + live-usage LiveData into one row-per-site list for
 * the RecyclerView adapter, and applies non-friction changes immediately.
 * Friction-gated (loosening) changes are NOT applied here - the Fragment
 * routes those to the confirm/cooldown screen instead, which calls
 * FeedLimiterExtensionBridge.updateSiteSettings() directly once its
 * countdown completes (see FeedLimiterConfirmViewModel).
 */
class FeedLimiterSettingsViewModel(
    private val bridge: FeedLimiterExtensionBridge
) : ViewModel() {

    data class SiteRow(
        val site: String,
        val displayName: String,
        val settings: BuiltInSiteSettings,
        val usage: SiteUsage
    )

    private val _rows = MediatorLiveData<List<SiteRow>>()
    val rows: LiveData<List<SiteRow>> get() = _rows

    init {
        _rows.addSource(bridge.settings) { recompute() }
        _rows.addSource(bridge.liveUsage) { recompute() }
    }

    private fun recompute() {
        val settingsMap = bridge.settings.value.orEmpty()
        val usageMap = bridge.liveUsage.value.orEmpty()
        _rows.value = BUILT_IN_SITES.map { site ->
            SiteRow(
                site = site,
                displayName = displayNameFor(site),
                settings = settingsMap[site] ?: DEFAULT_BUILT_IN_SETTINGS.getValue(site),
                usage = usageMap[site] ?: SiteUsage()
            )
        }
    }

    /** For tightening changes only (lower a limit, enable a cap, switch to
     *  a stricter mode) - applies with no friction. The Fragment is
     *  responsible for never calling this for a loosening change; see
     *  FeedLimiterSettingsFragment.isLoosening(). */
    fun applyImmediately(site: String, newSettings: BuiltInSiteSettings) {
        bridge.updateSiteSettings(site, newSettings)
    }

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
