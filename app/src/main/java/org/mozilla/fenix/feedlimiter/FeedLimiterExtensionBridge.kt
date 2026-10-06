package org.mozilla.fenix.feedlimiter

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.Port
import org.mozilla.geckoview.WebExtension.PortDelegate

/**
 * Native half of the settings bridge, and the source of truth for every site's
 * settings. The extension only mirrors the last snapshot pushed from here.
 *
 * State is held in plain fields and mirrored into LiveData, because
 * LiveData.postValue is asynchronous: reading `.value` right after posting
 * returns the previous value, which used to push stale snapshots.
 */
class FeedLimiterExtensionBridge(context: Context) : WebExtension.MessageDelegate, PortDelegate {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var port: Port? = null
    private var currentSettings: Map<String, SiteSettings> = loadSettings()
    private var currentLowSaturation: LowSaturationMode = loadLowSaturation()
    private var currentUsage: Map<String, SiteUsage> = emptyMap()
    private var pictureInPicture = false

    private val _settings = MutableLiveData(currentSettings)
    val settings: LiveData<Map<String, SiteSettings>> get() = _settings

    private val _lowSaturation = MutableLiveData(currentLowSaturation)
    val lowSaturation: LiveData<LowSaturationMode> get() = _lowSaturation

    // Empty for a site until the extension reports it; treat missing as zero.
    private val _liveUsage = MutableLiveData<Map<String, SiteUsage>>(emptyMap())
    val liveUsage: LiveData<Map<String, SiteUsage>> get() = _liveUsage

    fun settingsFor(site: String): SiteSettings? = currentSettings[site]

    // --- WebExtension.MessageDelegate ----------------------------------------

    override fun onConnect(port: Port) {
        this.port = port
        port.setDelegate(this)
        // Sent without waiting for request-settings so either side can start first.
        pushSettingsSnapshot()
        pushPictureInPicture()
    }

    // --- PortDelegate --------------------------------------------------------

    override fun onPortMessage(message: Any, port: Port) {
        val json = message as? JSONObject
        if (json == null) {
            Log.w(LOG_TAG, "Ignoring non-JSON port message: ${message.javaClass.name}")
            return
        }
        // LiveData can only be set on the main thread.
        runOnMain {
            when (val type = json.optString("type")) {
                MSG_REQUEST_SETTINGS -> pushSettingsSnapshot()
                MSG_COUNTS_UPDATE -> handleCountsUpdate(json)
                else -> Log.w(LOG_TAG, "Ignoring port message with unknown type '$type'")
            }
        }
    }

    override fun onDisconnect(port: Port) {
        if (this.port === port) this.port = null
    }

    // --- called by the settings and confirm screens ---------------------------

    /** Applies changes that need no countdown. Returns false if they were rejected. */
    fun updateSiteSettings(site: String, newSettings: SiteSettings): Boolean {
        if (site !in currentSettings) {
            Log.w(LOG_TAG, "Rejected update for unknown site $site")
            return false
        }
        validationError(site, newSettings)?.let {
            Log.w(LOG_TAG, "Rejected settings for $site: $it")
            return false
        }
        commitSettings(currentSettings + (site to newSettings))
        return true
    }

    fun addCustomSite(input: String, timerMinutesLimit: Int): DomainResult {
        val result = normalizeCustomDomain(input, currentSettings.keys)
        if (result !is DomainResult.Valid) return result
        val siteSettings = newCustomSite(timerMinutesLimit)
        validationError(result.domain, siteSettings)?.let {
            Log.w(LOG_TAG, "Rejected new custom site ${result.domain}: $it")
            return DomainResult.Invalid(R.string.feed_limiter_domain_error_invalid)
        }
        commitSettings(currentSettings + (result.domain to siteSettings))
        return result
    }

    /** Commits a countdown-confirmed change, including removing a custom site. */
    fun applyConfirmedLoosen(action: FeedLimiterLoosenAction): Boolean {
        val current = currentSettings[action.site]
        if (current == null) {
            Log.w(LOG_TAG, "Confirmed $action for a site that no longer exists")
            return false
        }
        val updated = current.applying(action)
        if (updated != null) return updateSiteSettings(action.site, updated)
        if (current.type != SiteType.CUSTOM) {
            Log.w(LOG_TAG, "Refusing to remove non-custom site ${action.site}")
            return false
        }
        // The extension keeps today's counts, so re-adding the site the same day keeps its usage.
        commitSettings(currentSettings - action.site)
        return true
    }

    fun setLowSaturationMode(mode: LowSaturationMode) {
        if (mode == currentLowSaturation) return
        currentLowSaturation = mode
        prefs.edit().putString(PREFS_KEY_LOW_SATURATION, mode.wireValue).apply()
        _lowSaturation.value = mode
        pushSettingsSnapshot()
    }

    /** Called from the browser screen; PiP must not count toward the timer even though the page stays visible. */
    fun setPictureInPicture(active: Boolean) {
        runOnMain {
            pictureInPicture = active
            pushPictureInPicture()
        }
    }

    // --- internals ------------------------------------------------------------

    private fun commitSettings(updated: Map<String, SiteSettings>) {
        currentSettings = updated
        persistSettings(updated)
        _settings.value = updated
        pushSettingsSnapshot()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun postToExtension(message: JSONObject) {
        val currentPort = port ?: return
        try {
            currentPort.postMessage(message)
        } catch (e: RuntimeException) {
            // The next onConnect() pushes a fresh snapshot, so this only delays the change.
            Log.w(LOG_TAG, "Failed to post ${message.optString("type")} to the extension", e)
        }
    }

    private fun pushSettingsSnapshot() {
        val sites = JSONObject()
        for ((site, siteSettings) in currentSettings) {
            sites.put(site, siteSettings.toWireJson())
        }
        postToExtension(
            JSONObject()
                .put("type", MSG_SETTINGS_SNAPSHOT)
                .put("settings", sites)
                .put("lowSaturation", currentLowSaturation.wireValue),
        )
    }

    private fun pushPictureInPicture() {
        postToExtension(JSONObject().put("type", MSG_PIP).put("active", pictureInPicture))
    }

    private fun handleCountsUpdate(json: JSONObject) {
        val site = json.opt("site") as? String
        val entry = json.optJSONObject("entry")
        if (site == null || entry == null) {
            Log.w(LOG_TAG, "Ignoring malformed counts-update: $json")
            return
        }
        // Counts for a site that was just removed are expected and harmless.
        if (site !in currentSettings) return
        val usage = try {
            SiteUsage(
                postsSeen = entry.requireCount("postsSeen"),
                minutesUsed = entry.requireCount("minutesUsed"),
                blocked = entry.get("blocked") as? Boolean ?: throw JSONException("blocked is not a boolean"),
                videosWatched = entry.requireCount("videosWatched"),
            )
        } catch (e: JSONException) {
            Log.w(LOG_TAG, "Ignoring counts-update for $site: ${e.message}")
            return
        }
        currentUsage = currentUsage + (site to usage)
        _liveUsage.value = currentUsage
    }

    private fun SiteSettings.toWireJson(): JSONObject {
        val json = JSONObject()
            .put("type", type.wireValue)
            .put("enabled", enabled)
            .put("mode", mode.wireValue)
            // JSONObject.NULL, because put(name, null) removes the key instead.
            .put("postLimit", postLimit ?: JSONObject.NULL)
            .put("timerMinutesLimit", timerMinutesLimit ?: JSONObject.NULL)
            .put("desaturate", desaturate)
        if (type == SiteType.YOUTUBE_REGULAR) json.put("videoLimit", videoLimit ?: JSONObject.NULL)
        return json
    }

    private fun SiteSettings.toPersistedJson(): JSONObject {
        val countdowns = JSONObject()
        for ((cap, minutes) in countdownMinutes) countdowns.put(cap.wireValue, minutes)
        return toWireJson().put("countdownMinutes", countdowns)
    }

    private fun persistSettings(settingsMap: Map<String, SiteSettings>) {
        val json = JSONObject()
        for ((site, siteSettings) in settingsMap) {
            json.put(site, siteSettings.toPersistedJson())
        }
        prefs.edit().putString(PREFS_KEY_SETTINGS, json.toString()).apply()
    }

    /**
     * Fixed sites always exist (defaults fill any gap); custom sites exist only
     * if stored. Also reads the pre-custom-sites format, which had no `type`,
     * `videoLimit`, `desaturate` or `countdownMinutes` fields.
     */
    private fun loadSettings(): Map<String, SiteSettings> {
        val result = DEFAULT_SETTINGS.toMutableMap()
        val raw = prefs.getString(PREFS_KEY_SETTINGS, null) ?: return result
        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.e(LOG_TAG, "Stored settings are not valid JSON; using defaults", e)
            return result
        }
        for (site in json.keys()) {
            val parsed = try {
                parseStoredSite(site, json.getJSONObject(site))
            } catch (e: JSONException) {
                Log.w(LOG_TAG, "Ignoring stored settings for $site: ${e.message}")
                continue
            }
            val error = validationError(site, parsed)
            if (error != null) {
                Log.w(LOG_TAG, "Ignoring stored settings for $site: $error")
                continue
            }
            result[site] = parsed
        }
        return result
    }

    private fun parseStoredSite(site: String, json: JSONObject): SiteSettings {
        val type = if (json.has("type")) {
            SiteType.fromWireValue(json.getString("type")) ?: throw JSONException("unknown type ${json.get("type")}")
        } else {
            expectedTypeFor(site)
        }
        val defaults = DEFAULT_SETTINGS[site]
        val countdowns = mutableMapOf<CapType, Int>()
        json.optJSONObject("countdownMinutes")?.let { stored ->
            for (key in stored.keys()) {
                val cap = CapType.fromWireValue(key) ?: throw JSONException("unknown countdown cap $key")
                countdowns[cap] = stored.requireInt(key)
            }
        }
        return SiteSettings(
            type = type,
            enabled = json.get("enabled") as? Boolean ?: throw JSONException("enabled is not a boolean"),
            mode = FeedLimiterMode.fromWireValue(json.getString("mode"))
                ?: throw JSONException("unknown mode ${json.get("mode")}"),
            postLimit = json.optionalInt("postLimit"),
            timerMinutesLimit = json.optionalInt("timerMinutesLimit"),
            videoLimit = if (json.has("videoLimit")) json.optionalInt("videoLimit") else defaults?.videoLimit,
            desaturate = if (json.has("desaturate")) {
                json.get("desaturate") as? Boolean ?: throw JSONException("desaturate is not a boolean")
            } else {
                false
            },
            countdownMinutes = countdowns,
        )
    }

    private fun loadLowSaturation(): LowSaturationMode {
        val raw = prefs.getString(PREFS_KEY_LOW_SATURATION, null) ?: return LowSaturationMode.OFF
        return LowSaturationMode.fromWireValue(raw) ?: LowSaturationMode.OFF.also {
            Log.w(LOG_TAG, "Unknown stored low-saturation mode '$raw'; using off")
        }
    }

    companion object {
        private const val PREFS_NAME = "feed_limiter_settings"

        // Name kept from the built-in-only version so upgrades keep existing settings.
        private const val PREFS_KEY_SETTINGS = "built_in_site_settings_json"
        private const val PREFS_KEY_LOW_SATURATION = "low_saturation_mode"

        private const val MSG_REQUEST_SETTINGS = "feed-limiter:request-settings"
        private const val MSG_COUNTS_UPDATE = "feed-limiter:counts-update"
        private const val MSG_SETTINGS_SNAPSHOT = "feed-limiter:settings-snapshot"
        private const val MSG_PIP = "feed-limiter:pip"
    }
}

private fun JSONObject.requireInt(name: String): Int {
    val value = get(name)
    if (value !is Number || value.toDouble() != value.toInt().toDouble()) {
        throw JSONException("$name is not an integer: $value")
    }
    return value.toInt()
}

private fun JSONObject.requireCount(name: String): Int =
    requireInt(name).also { if (it < 0) throw JSONException("$name is negative: $it") }

private fun JSONObject.optionalInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else requireInt(name)
