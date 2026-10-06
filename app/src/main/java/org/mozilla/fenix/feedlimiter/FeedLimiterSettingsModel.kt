package org.mozilla.fenix.feedlimiter

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import org.mozilla.fenix.R
import java.net.IDN
import java.util.Locale

// Every wireValue below crosses the bridge as JSON and must match background.js
// and config.js exactly.

internal const val LOG_TAG = "FeedLimiter"

enum class SiteType(val wireValue: String) {
    BUILT_IN("built-in"),
    YOUTUBE_REGULAR("youtube-regular"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromWireValue(value: String): SiteType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class FeedLimiterMode(val wireValue: String) {
    POST("post"),
    TIMER("timer"),
    BOTH("both"),
    ;

    companion object {
        fun fromWireValue(value: String): FeedLimiterMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class LowSaturationMode(val wireValue: String) {
    OFF("off"),
    ALL("all"),
    SELECTED("selected"),
    ;

    companion object {
        fun fromWireValue(value: String): LowSaturationMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A cap that has its own countdown length. Values are persisted, never sent to the extension. */
enum class CapType(val wireValue: String) {
    POST("post"),
    TIMER("timer"),
    VIDEO("video"),
    ;

    companion object {
        fun fromWireValue(value: String): CapType? = entries.firstOrNull { it.wireValue == value }
    }
}

const val YOUTUBE_SITE = "youtube.com"

/** Keys of the built-in feed sites, identical to FEED_LIMITER_CONFIG.sites in config.js. */
val BUILT_IN_SITES: List<String> = listOf(
    "instagram.com",
    "x.com",
    "tiktok.com",
    "facebook.com",
    "reddit.com",
    "linkedin.com",
    "youtube.com/shorts",
)

/** Domains a custom site may not equal or sit under. twitter.com is an alias of x.com. */
private val RESERVED_DOMAINS: List<String> = listOf(
    "instagram.com",
    "x.com",
    "twitter.com",
    "tiktok.com",
    "facebook.com",
    "reddit.com",
    "linkedin.com",
    YOUTUBE_SITE,
)

val COUNTDOWN_OPTIONS_MINUTES: List<Int> = listOf(10, 15, 30, 60)
const val DEFAULT_COUNTDOWN_MINUTES = 15

const val POST_LIMIT_STEP = 5
const val MIN_POST_LIMIT = 5
const val MAX_POST_LIMIT = 10_000
const val TIMER_LIMIT_STEP = 5
const val MIN_TIMER_LIMIT = 5
const val MAX_TIMER_LIMIT = 1_440
const val VIDEO_LIMIT_STEP = 1
const val MIN_VIDEO_LIMIT = 1
const val MAX_VIDEO_LIMIT = 1_000

// Used when a cap is switched on and there's no earlier value to restore.
const val DEFAULT_TIMER_LIMIT_ON_ENABLE = 15
const val DEFAULT_VIDEO_LIMIT = 5
const val DEFAULT_CUSTOM_TIMER_MINUTES = 30

data class SiteSettings(
    val type: SiteType,
    val enabled: Boolean = true,
    val mode: FeedLimiterMode,
    // null means that cap is not available for this site type.
    val postLimit: Int?,
    // null means no timer cap; a zero would mean "capped at zero minutes".
    val timerMinutesLimit: Int?,
    val videoLimit: Int? = null,
    val desaturate: Boolean = false,
    val countdownMinutes: Map<CapType, Int> = emptyMap(),
) {
    val postCapOn: Boolean get() = mode != FeedLimiterMode.TIMER && postLimit != null
    val timerCapOn: Boolean get() = mode != FeedLimiterMode.POST && timerMinutesLimit != null
    val videoCapOn: Boolean get() = videoLimit != null

    val capTypes: List<CapType>
        get() = when (type) {
            SiteType.BUILT_IN -> listOf(CapType.POST, CapType.TIMER)
            SiteType.YOUTUBE_REGULAR -> listOf(CapType.TIMER, CapType.VIDEO)
            SiteType.CUSTOM -> listOf(CapType.TIMER)
        }

    fun countdownFor(cap: CapType): Int = countdownMinutes[cap] ?: DEFAULT_COUNTDOWN_MINUTES
}

/** Today's usage as reported by background.js's counts-update message. */
data class SiteUsage(
    val postsSeen: Int = 0,
    val minutesUsed: Int = 0,
    val blocked: Boolean = false,
    val videosWatched: Int = 0,
)

// Keep in sync with FEED_LIMITER_CONFIG.sites in config.js so the first
// snapshot doesn't change what the extension enforced on first run.
val DEFAULT_SETTINGS: Map<String, SiteSettings> = mapOf(
    "instagram.com" to builtIn(FeedLimiterMode.BOTH, postLimit = 30, timerMinutesLimit = 45),
    "x.com" to builtIn(FeedLimiterMode.POST, postLimit = 20, timerMinutesLimit = null),
    "tiktok.com" to builtIn(FeedLimiterMode.BOTH, postLimit = 40, timerMinutesLimit = 30),
    "facebook.com" to builtIn(FeedLimiterMode.BOTH, postLimit = 25, timerMinutesLimit = 45),
    "reddit.com" to builtIn(FeedLimiterMode.BOTH, postLimit = 25, timerMinutesLimit = 45),
    "linkedin.com" to builtIn(FeedLimiterMode.POST, postLimit = 20, timerMinutesLimit = null),
    "youtube.com/shorts" to builtIn(FeedLimiterMode.BOTH, postLimit = 40, timerMinutesLimit = 30),
    YOUTUBE_SITE to SiteSettings(
        type = SiteType.YOUTUBE_REGULAR,
        mode = FeedLimiterMode.TIMER,
        postLimit = null,
        timerMinutesLimit = 60,
        videoLimit = DEFAULT_VIDEO_LIMIT,
    ),
)

private fun builtIn(mode: FeedLimiterMode, postLimit: Int, timerMinutesLimit: Int?) =
    SiteSettings(type = SiteType.BUILT_IN, mode = mode, postLimit = postLimit, timerMinutesLimit = timerMinutesLimit)

fun newCustomSite(timerMinutesLimit: Int) = SiteSettings(
    type = SiteType.CUSTOM,
    mode = FeedLimiterMode.TIMER,
    postLimit = null,
    timerMinutesLimit = timerMinutesLimit,
)

fun expectedTypeFor(site: String): SiteType = when (site) {
    in BUILT_IN_SITES -> SiteType.BUILT_IN
    YOUTUBE_SITE -> SiteType.YOUTUBE_REGULAR
    else -> SiteType.CUSTOM
}

// Proper nouns and domains, not translatable copy.
fun displayNameFor(site: String): String = when (site) {
    "instagram.com" -> "Instagram"
    "x.com" -> "X (Twitter)"
    "tiktok.com" -> "TikTok"
    "facebook.com" -> "Facebook"
    "reddit.com" -> "Reddit"
    "linkedin.com" -> "LinkedIn"
    "youtube.com/shorts" -> "YouTube Shorts"
    YOUTUBE_SITE -> "YouTube"
    else -> site
}

/** Returns why [settings] can't be stored under [site], or null if it's valid. */
fun validationError(site: String, settings: SiteSettings): String? {
    val expected = expectedTypeFor(site)
    return when {
        settings.type != expected -> "type ${settings.type.wireValue} doesn't match $site"
        expected == SiteType.CUSTOM && !isAllowedCustomDomain(site) -> "$site is not an allowed custom domain"
        expected != SiteType.BUILT_IN && settings.mode != FeedLimiterMode.TIMER -> "$site is timer-only"
        expected == SiteType.BUILT_IN && settings.postLimit !in MIN_POST_LIMIT..MAX_POST_LIMIT ->
            "postLimit ${settings.postLimit} out of range"
        expected != SiteType.BUILT_IN && settings.postLimit != null -> "$site has no post cap"
        settings.mode != FeedLimiterMode.POST && settings.timerMinutesLimit == null -> "timer cap on without a limit"
        settings.timerMinutesLimit != null && settings.timerMinutesLimit !in MIN_TIMER_LIMIT..MAX_TIMER_LIMIT ->
            "timerMinutesLimit ${settings.timerMinutesLimit} out of range"
        expected != SiteType.YOUTUBE_REGULAR && settings.videoLimit != null -> "$site has no video cap"
        settings.videoLimit != null && settings.videoLimit !in MIN_VIDEO_LIMIT..MAX_VIDEO_LIMIT ->
            "videoLimit ${settings.videoLimit} out of range"
        settings.countdownMinutes.any { (cap, minutes) -> cap !in settings.capTypes || minutes !in COUNTDOWN_OPTIONS_MINUTES } ->
            "invalid countdown lengths ${settings.countdownMinutes}"
        else -> null
    }
}

// --- custom-domain input -----------------------------------------------------

sealed class DomainResult {
    data class Valid(val domain: String) : DomainResult()
    data class Invalid(@param:StringRes val messageRes: Int, val coveringSite: String? = null) : DomainResult()
}

// Same rule as HOSTNAME_RE in background.js: at least two labels and a TLD that
// starts with a letter, which also rules out IPv4 addresses.
private val HOSTNAME_REGEX =
    Regex("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$")
private val SCHEME_REGEX = Regex("^[a-z][a-z0-9+.-]*://")
private val PORT_REGEX = Regex(":\\d*$")

private fun isUnderDomain(host: String, domain: String) = host == domain || host.endsWith(".$domain")

private fun isAllowedCustomDomain(host: String) =
    HOSTNAME_REGEX.matches(host) && RESERVED_DOMAINS.none { isUnderDomain(host, it) }

/**
 * Turns free-form user input into the punycode hostname a custom site is keyed
 * by. A subdomain of an existing custom site is rejected: adding is instant and
 * the longest suffix wins, so it would otherwise be a way to give part of an
 * already-capped site a looser limit without the countdown.
 */
fun normalizeCustomDomain(input: String, existingSites: Set<String>): DomainResult {
    val host = input.trim().lowercase(Locale.ROOT)
        .replace(SCHEME_REGEX, "")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
        .replace(PORT_REGEX, "")
        .trimEnd('.')
        .removePrefix("www.")
    if (host.isEmpty()) return DomainResult.Invalid(R.string.feed_limiter_domain_error_invalid)

    val ascii = try {
        IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    } catch (e: IllegalArgumentException) {
        Log.w(LOG_TAG, "Rejected domain input '$input': IDN conversion failed", e)
        return DomainResult.Invalid(R.string.feed_limiter_domain_error_invalid)
    }

    return when {
        !HOSTNAME_REGEX.matches(ascii) -> DomainResult.Invalid(R.string.feed_limiter_domain_error_invalid)
        isUnderDomain(ascii, YOUTUBE_SITE) -> DomainResult.Invalid(R.string.feed_limiter_domain_error_youtube)
        RESERVED_DOMAINS.any { isUnderDomain(ascii, it) } -> DomainResult.Invalid(R.string.feed_limiter_domain_error_built_in)
        ascii in existingSites -> DomainResult.Invalid(R.string.feed_limiter_domain_error_duplicate)
        else -> existingSites.firstOrNull { isUnderDomain(ascii, it) }
            ?.let { DomainResult.Invalid(R.string.feed_limiter_domain_error_covered, coveringSite = it) }
            ?: DomainResult.Valid(ascii)
    }
}

// --- countdown-gated ("loosening") changes -----------------------------------
// Only these go through the confirm screen. Tightening, enabling, adding a
// site, lengthening a countdown and low-saturation changes apply immediately.

sealed class FeedLimiterLoosenAction(open val site: String) {
    data class DisablePostCap(override val site: String) : FeedLimiterLoosenAction(site)
    data class DisableTimerCap(override val site: String) : FeedLimiterLoosenAction(site)
    data class DisableVideoCap(override val site: String) : FeedLimiterLoosenAction(site)
    data class RaisePostLimit(override val site: String, val newLimit: Int) : FeedLimiterLoosenAction(site)
    data class RaiseTimerLimit(override val site: String, val newLimit: Int) : FeedLimiterLoosenAction(site)
    data class RaiseVideoLimit(override val site: String, val newLimit: Int) : FeedLimiterLoosenAction(site)
    data class DisableSite(override val site: String) : FeedLimiterLoosenAction(site)
    data class RemoveSite(override val site: String) : FeedLimiterLoosenAction(site)
    data class ShortenCountdown(override val site: String, val capType: CapType, val newMinutes: Int) :
        FeedLimiterLoosenAction(site)

    fun describe(context: Context): String {
        val name = displayNameFor(site)
        return when (this) {
            is DisablePostCap -> context.getString(R.string.feed_limiter_action_disable_post_cap, name)
            is DisableTimerCap -> context.getString(R.string.feed_limiter_action_disable_timer_cap, name)
            is DisableVideoCap -> context.getString(R.string.feed_limiter_action_disable_video_cap, name)
            is RaisePostLimit -> context.getString(R.string.feed_limiter_action_raise_post_limit, name, newLimit)
            is RaiseTimerLimit -> context.getString(R.string.feed_limiter_action_raise_timer_limit, name, newLimit)
            is RaiseVideoLimit -> context.getString(R.string.feed_limiter_action_raise_video_limit, name, newLimit)
            is DisableSite -> context.getString(R.string.feed_limiter_action_disable_site, name)
            is RemoveSite -> context.getString(R.string.feed_limiter_action_remove_site, name)
            is ShortenCountdown -> context.getString(
                when (capType) {
                    CapType.POST -> R.string.feed_limiter_action_shorten_post_countdown
                    CapType.TIMER -> R.string.feed_limiter_action_shorten_timer_countdown
                    CapType.VIDEO -> R.string.feed_limiter_action_shorten_video_countdown
                },
                name,
                newMinutes,
            )
        }
    }
}

@StringRes
fun capLabelRes(cap: CapType): Int = when (cap) {
    CapType.POST -> R.string.feed_limiter_cap_post
    CapType.TIMER -> R.string.feed_limiter_cap_timer
    CapType.VIDEO -> R.string.feed_limiter_cap_video
}

/**
 * The wait for an action uses the current (not the requested) length, so
 * shortening a countdown costs the longer wait. Site-wide actions use the
 * longest of the site's countdowns, otherwise they'd be a shortcut around
 * the per-cap ones.
 */
fun SiteSettings.countdownMinutesFor(action: FeedLimiterLoosenAction): Int = when (action) {
    is FeedLimiterLoosenAction.DisablePostCap, is FeedLimiterLoosenAction.RaisePostLimit -> countdownFor(CapType.POST)
    is FeedLimiterLoosenAction.DisableTimerCap, is FeedLimiterLoosenAction.RaiseTimerLimit -> countdownFor(CapType.TIMER)
    is FeedLimiterLoosenAction.DisableVideoCap, is FeedLimiterLoosenAction.RaiseVideoLimit -> countdownFor(CapType.VIDEO)
    is FeedLimiterLoosenAction.ShortenCountdown -> countdownFor(action.capType)
    is FeedLimiterLoosenAction.DisableSite, is FeedLimiterLoosenAction.RemoveSite -> capTypes.maxOf { countdownFor(it) }
}

fun FeedLimiterMode.withPostCapOn(): FeedLimiterMode = if (this == FeedLimiterMode.TIMER) FeedLimiterMode.BOTH else this

fun FeedLimiterMode.withTimerCapOn(): FeedLimiterMode = if (this == FeedLimiterMode.POST) FeedLimiterMode.BOTH else this

/**
 * Settings after confirming [action], or null when the site should be removed.
 * Disabling the only remaining cap of a built-in site turns the site off,
 * because a site with no cap would still be listed as "on".
 */
fun SiteSettings.applying(action: FeedLimiterLoosenAction): SiteSettings? = when (action) {
    is FeedLimiterLoosenAction.DisablePostCap -> when (mode) {
        FeedLimiterMode.BOTH -> copy(mode = FeedLimiterMode.TIMER)
        FeedLimiterMode.POST -> copy(enabled = false)
        FeedLimiterMode.TIMER -> this
    }
    is FeedLimiterLoosenAction.DisableTimerCap -> when (mode) {
        FeedLimiterMode.BOTH -> copy(mode = FeedLimiterMode.POST)
        FeedLimiterMode.TIMER -> copy(enabled = false)
        FeedLimiterMode.POST -> this
    }
    is FeedLimiterLoosenAction.DisableVideoCap -> copy(videoLimit = null)
    is FeedLimiterLoosenAction.RaisePostLimit -> copy(postLimit = action.newLimit)
    is FeedLimiterLoosenAction.RaiseTimerLimit -> copy(timerMinutesLimit = action.newLimit)
    is FeedLimiterLoosenAction.RaiseVideoLimit -> copy(videoLimit = action.newLimit)
    is FeedLimiterLoosenAction.DisableSite -> copy(enabled = false)
    is FeedLimiterLoosenAction.RemoveSite -> null
    is FeedLimiterLoosenAction.ShortenCountdown -> copy(countdownMinutes = countdownMinutes + (action.capType to action.newMinutes))
}
