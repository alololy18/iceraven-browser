package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the matching comment at the top of
// FeedLimiterExtensionBridge.kt. Both files must share the same package
// line once you move them into your fork.

// Phase 2 data model for the built-in sites' settings + live daily usage.
// Mirrors the shapes defined in feed-limiter-extension/config.js and the
// counts entry in background.js - keep these two in sync by hand; there is
// no shared schema file between the JS and Kotlin sides (deliberately: the
// extension and the native app build/ship independently through the same
// APK, but as separate source trees with no Kotlin<->JS code sharing in
// GeckoView's built-in extension model).

/** One of the 7 sites wired up in Phase 1/2. Each string MUST exactly
 *  match the key used in FEED_LIMITER_CONFIG.sites in config.js - these
 *  strings cross the native<->extension bridge as plain JSON object keys,
 *  so a mismatch here silently desyncs that one site rather than throwing
 *  (the extension's getEffectiveConfig() just won't find an override). */
val BUILT_IN_SITES: List<String> = listOf(
    "instagram.com",
    "x.com",
    "tiktok.com",
    "facebook.com",
    "reddit.com",
    "linkedin.com",
    "youtube.com/shorts"
)

enum class FeedLimiterMode(val wireValue: String) {
    POST("post"),
    TIMER("timer"),
    BOTH("both");

    companion object {
        fun fromWireValue(value: String?): FeedLimiterMode =
            values().firstOrNull { it.wireValue == value } ?: BOTH
    }
}

/** A built-in site's current settings, as owned by the native Settings
 *  screen (the Phase 2 source of truth) and pushed down to the extension
 *  over the bridge whenever it changes. */
data class BuiltInSiteSettings(
    val enabled: Boolean = true,
    val mode: FeedLimiterMode = FeedLimiterMode.BOTH,
    val postLimit: Int = 30,
    // null = "no timer cap configured" - matches config.js's null, NOT
    // zero. A zero would mean "capped at zero minutes", which is different.
    val timerMinutesLimit: Int? = null
)

/** Today's live usage for one site, as reported up from background.js's
 *  storage.local entry (see getEntry()/setEntry() in background.js). Field
 *  names deliberately mirror that JS entry object exactly. */
data class SiteUsage(
    val postsSeen: Int = 0,
    val minutesUsed: Int = 0,
    val blocked: Boolean = false
)

// Phase 1 hardcoded defaults, copied from FEED_LIMITER_CONFIG.sites in
// config.js so the very first native settings snapshot (before the user has
// ever touched the Settings screen) matches what Phase 1 already shipped -
// i.e. adding the Settings screen doesn't silently change anyone's caps on
// first run. If you tune the numbers in config.js later, update them here
// too; nothing keeps these two in sync automatically.
val DEFAULT_BUILT_IN_SETTINGS: Map<String, BuiltInSiteSettings> = mapOf(
    "instagram.com" to BuiltInSiteSettings(mode = FeedLimiterMode.BOTH, postLimit = 30, timerMinutesLimit = 45),
    "x.com" to BuiltInSiteSettings(mode = FeedLimiterMode.POST, postLimit = 20, timerMinutesLimit = null),
    "tiktok.com" to BuiltInSiteSettings(mode = FeedLimiterMode.BOTH, postLimit = 40, timerMinutesLimit = 30),
    "facebook.com" to BuiltInSiteSettings(mode = FeedLimiterMode.BOTH, postLimit = 25, timerMinutesLimit = 45),
    "reddit.com" to BuiltInSiteSettings(mode = FeedLimiterMode.BOTH, postLimit = 25, timerMinutesLimit = 45),
    "linkedin.com" to BuiltInSiteSettings(mode = FeedLimiterMode.POST, postLimit = 20, timerMinutesLimit = null),
    "youtube.com/shorts" to BuiltInSiteSettings(mode = FeedLimiterMode.BOTH, postLimit = 40, timerMinutesLimit = 30)
)

/** Human-readable label for the Settings screen list. Kept here (rather
 *  than in the UI layer, Phase 2's not-yet-built Settings screen) so the
 *  bridge, the screen, and any future debug tooling all agree on one
 *  source for display names instead of three independent copies. */
fun displayNameFor(site: String): String = when (site) {
    "instagram.com" -> "Instagram"
    "x.com" -> "X (Twitter)"
    "tiktok.com" -> "TikTok"
    "facebook.com" -> "Facebook"
    "reddit.com" -> "Reddit"
    "linkedin.com" -> "LinkedIn"
    "youtube.com/shorts" -> "YouTube Shorts"
    else -> site
}

// --- Friction-gated ("loosening") changes, feature list items 23-24 --------
// Only these specific changes require the confirm/cooldown screen:
// disabling the post cap, raising the post limit, and disabling the timer
// cap. Everything else (tightening a limit, enabling a cap, switching to a
// stricter mode) applies immediately via
// FeedLimiterExtensionBridge.updateSiteSettings() with no friction - the
// Fragment decides which path a given UI interaction takes (see
// FeedLimiterSettingsFragment's isLoosening() check) and only routes
// loosening changes through here.
sealed class FeedLimiterLoosenAction(open val site: String) {
    data class DisablePostCap(override val site: String) : FeedLimiterLoosenAction(site)
    data class DisableTimerCap(override val site: String) : FeedLimiterLoosenAction(site)
    data class RaisePostLimit(override val site: String, val newLimit: Int) : FeedLimiterLoosenAction(site)
    // Not explicitly itemized in the feature list, but turning the whole
    // site off is strictly more permissive than either single-cap action
    // above, so it goes through the same gate by the same logic - flagging
    // this as an interpretive call in case you'd rather leave the overall
    // enable/disable switch frictionless.
    data class DisableSite(override val site: String) : FeedLimiterLoosenAction(site)

    /** Short, user-facing description for the confirm/cooldown screen -
     *  item 14 calls for the person to see exactly what they're about to
     *  loosen, not a generic "are you sure?". */
    fun describe(): String = when (this) {
        is DisablePostCap -> "disable the post cap on ${displayNameFor(site)}"
        is DisableTimerCap -> "disable the timer cap on ${displayNameFor(site)}"
        is RaisePostLimit -> "raise the post cap on ${displayNameFor(site)} to $newLimit"
        is DisableSite -> "turn off Feed Limiter entirely for ${displayNameFor(site)}"
    }
}

// Step sizes and floors for the Settings screen's +/- steppers. Shared here
// so the Fragment/adapter and any future screen agree on the same numbers.
const val POST_LIMIT_STEP = 5
const val MIN_POST_LIMIT = 5
const val TIMER_LIMIT_STEP = 5
const val MIN_TIMER_LIMIT = 5
// Used when the user turns the timer cap ON for a site whose
// timerMinutesLimit is currently null (e.g. x.com, linkedin.com, whose
// Phase 1 defaults have no timer cap at all) - there's no prior value to
// restore, so this is the starting point instead.
const val DEFAULT_TIMER_LIMIT_ON_ENABLE = 15

/** Turns the post cap on/off within `mode` WITHOUT touching `enabled` -
 *  used only for the "turning a cap ON" direction, which is always
 *  immediate/frictionless. Turning a cap OFF goes through
 *  FeedLimiterLoosenAction.DisablePostCap + applying() instead (see
 *  below), not this function, because that direction needs the confirm
 *  screen's gate. */
fun FeedLimiterMode.withPostCapOn(): FeedLimiterMode = if (this == FeedLimiterMode.TIMER) FeedLimiterMode.BOTH else this

/** Timer-cap equivalent of withPostCapOn() - see that doc for why there's
 *  no symmetric "off" function here. */
fun FeedLimiterMode.withTimerCapOn(): FeedLimiterMode = if (this == FeedLimiterMode.POST) FeedLimiterMode.BOTH else this

/** Produces the settings that result from confirming one loosening action.
 *  CAVEAT - interpretive logic, not spelled out in the feature list: a
 *  "both" mode site has two independent caps, so "disable the post cap"
 *  there just drops it to timer-only (and vice versa); a site whose mode
 *  is ALREADY single-cap has no other cap left to fall back to, so
 *  disabling its only cap is treated as disabling the site entirely. The
 *  Fragment should only ever present a "disable post cap" control when
 *  mode is POST or BOTH (and symmetrically for timer), so the no-op
 *  branches below are a defensive fallback, not an expected path. */
fun BuiltInSiteSettings.applying(action: FeedLimiterLoosenAction): BuiltInSiteSettings = when (action) {
    is FeedLimiterLoosenAction.DisablePostCap -> when (mode) {
        FeedLimiterMode.BOTH -> copy(mode = FeedLimiterMode.TIMER)
        FeedLimiterMode.POST -> copy(enabled = false)
        FeedLimiterMode.TIMER -> this // no post cap active to disable - no-op
    }
    is FeedLimiterLoosenAction.DisableTimerCap -> when (mode) {
        FeedLimiterMode.BOTH -> copy(mode = FeedLimiterMode.POST)
        FeedLimiterMode.TIMER -> copy(enabled = false)
        FeedLimiterMode.POST -> this // no timer cap active to disable - no-op
    }
    is FeedLimiterLoosenAction.RaisePostLimit -> copy(postLimit = action.newLimit)
    is FeedLimiterLoosenAction.DisableSite -> copy(enabled = false)
}
