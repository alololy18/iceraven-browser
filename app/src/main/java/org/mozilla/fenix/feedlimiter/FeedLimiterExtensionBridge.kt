package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - replace "org.mozilla.fenix.feedlimiter" above (and
// in FeedLimiterSettingsModel.kt) with <your fork's actual Kotlin package
// root>.feedlimiter once you've found it. The Kotlin package does NOT have
// to match your applicationId/package name exactly - Fenix's own Kotlin
// source lives under org.mozilla.fenix regardless of which applicationId a
// build variant uses - so just open any existing .kt file under
// app/src/main/java/ in your fork and reuse whatever its `package` line
// says as the root, then append ".feedlimiter". See the grep command in
// the final Phase 2 sync instructions if you want to confirm the
// applicationId itself too.

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.Port
import org.mozilla.geckoview.WebExtension.PortDelegate

/**
 * Native half of the Phase 2 native<->extension bridge. Construct ONE
 * instance of this (it owns persisted state) and register it once, at the
 * same place Phase 0/1 already calls extension.installBuiltIn() for this
 * extension:
 *
 *     val feedLimiterExtensionBridge = FeedLimiterExtensionBridge(context)
 *     extension.setMessageDelegate(feedLimiterExtensionBridge, "feedlimiter")
 *
 * "feedlimiter" MUST exactly match the nativeApp string background.js
 * passes to browser.runtime.connectNative("feedlimiter") - see
 * connectToNative() in feed-limiter-extension/background.js. Keep a
 * reference to this instance wherever your fork wires up GeckoView
 * init (same place/class as installBuiltIn()) and hand it to the
 * Settings screen's ViewModel too - see the wiring instructions for the
 * exact call site once that's written.
 *
 * CAVEAT: unverified against a live build of your fork. This follows
 * GeckoView's public WebExtension.MessageDelegate / Port / PortDelegate API
 * shape as documented, but hasn't been compiled against your fork's actual
 * GeckoView version yet. Two likely failure modes and how to debug them:
 *
 *   1. Doesn't compile - the three `org.mozilla.geckoview.WebExtension.*`
 *      import lines above are the most likely mismatch point. Open
 *      whatever file in your fork already calls installBuiltIn() and check
 *      how it imports WebExtension - Port/PortDelegate have moved between
 *      nested and top-level positions across GeckoView releases, and your
 *      fork may be pinned to an older/newer one than this assumes.
 *   2. Compiles, but the Settings screen never shows live data - add a
 *      `Log.d("FeedLimiter", "onConnect fired")` inside onConnect() below.
 *      If it never logs, setMessageDelegate() isn't actually wired up at
 *      the real installBuiltIn() call site yet (see wiring instructions).
 *      If it logs but the extension never gets a snapshot, check
 *      about:debugging's "Inspect" console on the background page for
 *      errors from connectToNative() in background.js.
 */
class FeedLimiterExtensionBridge(context: Context) : WebExtension.MessageDelegate, PortDelegate {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var port: Port? = null

    private val _settings = MutableLiveData(loadSettings())
    /** Current per-site settings, as a LiveData the Settings screen's
     *  ViewModel can observe directly. This map IS the source of truth on
     *  the native side as of Phase 2 - the extension side only ever
     *  mirrors whatever was last pushed here. */
    val settings: LiveData<Map<String, BuiltInSiteSettings>> get() = _settings

    private val _liveUsage = MutableLiveData<Map<String, SiteUsage>>(emptyMap())
    /** Most recently reported per-site usage for today, pushed up from
     *  background.js whenever a post/timer tick happens. Best-effort/live
     *  only - stays empty for a site until the extension's background page
     *  connects and sends its first counts-update, so the Settings screen
     *  should treat a missing entry as "0 so far", not an error. */
    val liveUsage: LiveData<Map<String, SiteUsage>> get() = _liveUsage

    // --- WebExtension.MessageDelegate ---------------------------------

    override fun onConnect(port: Port) {
        this.port = port
        port.setDelegate(this)
        // Send proactively rather than waiting for the extension's
        // request-settings handshake - covers both orderings (native
        // ready first, or extension ready first) without extra state.
        pushSettingsSnapshot()
    }

    // --- PortDelegate ---------------------------------------------------

    override fun onPortMessage(message: Any, port: Port) {
        val json = message as? JSONObject ?: return
        when (json.optString("type")) {
            "feed-limiter:request-settings" -> pushSettingsSnapshot()
            "feed-limiter:counts-update" -> handleCountsUpdate(json)
        }
    }

    override fun onDisconnect(port: Port) {
        if (this.port === port) this.port = null
    }

    // --- called by the Settings / confirm screens ------------------------

    /** Applies one site's new settings: persists immediately, updates the
     *  LiveData the Settings screen observes, and pushes the full updated
     *  snapshot down to the extension so enforcement picks it up on the
     *  very next post/timer tick - no background-page restart needed.
     *  Both the plain settings screen (for tightening a cap, which needs
     *  no friction) and the confirm/cooldown screen (for loosening one,
     *  once its countdown completes) should call this same method - the
     *  friction gate is a UI-layer concern only, not a bridge concern. */
    fun updateSiteSettings(site: String, newSettings: BuiltInSiteSettings) {
        if (site !in BUILT_IN_SITES) return
        val current = _settings.value.orEmpty().toMutableMap()
        current[site] = newSettings
        persistSettings(current)
        _settings.postValue(current)
        pushSettingsSnapshot()
    }

    // --- friction/cooldown duration preferences (feature list item 22) ---
    // Per-site, per-loosening-action duration, in minutes, defaulting to 15
    // (item 23/24's stated default) until explicitly changed. Stored
    // separately from the site settings themselves since these are a
    // Settings-screen preference about HOW the gate behaves, not part of
    // what gets pushed to the extension - the extension never needs to
    // know these values at all, only native does.
    //
    // CAVEAT: FeedLimiterSettingsFragment doesn't yet expose a UI control
    // to change these (item 22 isn't built out as an actual settings row
    // yet) - getFrictionDurationMinutes()/setFrictionDurationMinutes() are
    // ready for that control to call once it exists; until then, every
    // site/action combination simply uses the 15-minute default.
    fun getFrictionDurationMinutes(site: String, actionWireType: String): Int {
        return prefs.getInt(frictionDurationKey(site, actionWireType), DEFAULT_FRICTION_DURATION_MINUTES)
    }

    fun setFrictionDurationMinutes(site: String, actionWireType: String, minutes: Int) {
        prefs.edit().putInt(frictionDurationKey(site, actionWireType), minutes).apply()
    }

    private fun frictionDurationKey(site: String, actionWireType: String) =
        "friction_duration:$site:$actionWireType"

    // --- internals --------------------------------------------------------

    private fun pushSettingsSnapshot() {
        val currentPort = port ?: return
        val snapshot = JSONObject()
        for ((site, siteSettings) in _settings.value.orEmpty()) {
            snapshot.put(site, siteSettingsToJson(siteSettings))
        }
        val envelope = JSONObject()
        envelope.put("type", "feed-limiter:settings-snapshot")
        envelope.put("settings", snapshot)
        try {
            currentPort.postMessage(envelope)
        } catch (e: Exception) {
            // Port likely disconnected between the null-check above and
            // this call (e.g. background page just got suspended) - safe
            // no-op. onDisconnect() will clear `port`, and the next
            // onConnect() naturally sends a fresh snapshot, so nothing is
            // permanently lost, only delayed.
        }
    }

    private fun handleCountsUpdate(json: JSONObject) {
        val site = json.optString("site").takeIf { it in BUILT_IN_SITES } ?: return
        val entry = json.optJSONObject("entry") ?: return
        val usage = SiteUsage(
            postsSeen = entry.optInt("postsSeen", 0),
            minutesUsed = entry.optInt("minutesUsed", 0),
            blocked = entry.optBoolean("blocked", false)
        )
        val current = _liveUsage.value.orEmpty().toMutableMap()
        current[site] = usage
        _liveUsage.postValue(current)
    }

    private fun siteSettingsToJson(settings: BuiltInSiteSettings): JSONObject {
        val siteJson = JSONObject()
        siteJson.put("enabled", settings.enabled)
        siteJson.put("mode", settings.mode.wireValue)
        siteJson.put("postLimit", settings.postLimit)
        // JSONObject has no implicit "null" for a missing Int? - put
        // JSONObject.NULL explicitly so config.js's `!== undefined` override
        // check still sees a real (null) value instead of the key being
        // absent from the JSON entirely, which `!== undefined` would also
        // treat as "no override" but for the wrong reason.
        siteJson.put("timerMinutesLimit", settings.timerMinutesLimit ?: JSONObject.NULL)
        return siteJson
    }

    private fun loadSettings(): Map<String, BuiltInSiteSettings> {
        val raw = prefs.getString(PREFS_KEY, null) ?: return DEFAULT_BUILT_IN_SETTINGS
        return try {
            val json = JSONObject(raw)
            BUILT_IN_SITES.associateWith { site ->
                val siteJson = json.optJSONObject(site)
                    ?: return@associateWith DEFAULT_BUILT_IN_SETTINGS.getValue(site)
                BuiltInSiteSettings(
                    enabled = siteJson.optBoolean("enabled", true),
                    mode = FeedLimiterMode.fromWireValue(siteJson.optString("mode")),
                    postLimit = siteJson.optInt("postLimit", DEFAULT_BUILT_IN_SETTINGS.getValue(site).postLimit),
                    timerMinutesLimit = if (siteJson.isNull("timerMinutesLimit")) {
                        null
                    } else {
                        siteJson.optInt("timerMinutesLimit")
                    }
                )
            }
        } catch (e: Exception) {
            // Corrupt/unreadable prefs (shouldn't happen since this class is
            // the only writer) - fall back to Phase 1 defaults rather than
            // crashing the Settings screen on launch.
            DEFAULT_BUILT_IN_SETTINGS
        }
    }

    private fun persistSettings(settingsMap: Map<String, BuiltInSiteSettings>) {
        val json = JSONObject()
        for ((site, siteSettings) in settingsMap) {
            json.put(site, siteSettingsToJson(siteSettings))
        }
        prefs.edit().putString(PREFS_KEY, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "feed_limiter_settings"
        private const val PREFS_KEY = "built_in_site_settings_json"
        const val DEFAULT_FRICTION_DURATION_MINUTES = 15
    }
}
