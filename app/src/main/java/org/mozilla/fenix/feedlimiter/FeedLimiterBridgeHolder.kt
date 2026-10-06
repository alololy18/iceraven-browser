package org.mozilla.fenix.feedlimiter

import android.util.Log

/**
 * Process-wide access to the bridge. It only exists once GeckoProvider's
 * ensureBuiltIn() has completed, so screens must handle [instance] being null.
 */
object FeedLimiterBridgeHolder {
    @Volatile
    var instance: FeedLimiterExtensionBridge? = null
        private set

    @Volatile
    private var installFailed = false

    @Volatile
    private var installFailure: Throwable? = null

    fun initialize(bridge: FeedLimiterExtensionBridge) {
        instance = bridge
        installFailed = false
        installFailure = null
    }

    // Nullable because GeckoResult hands its exception consumer a @Nullable Throwable.
    fun recordInstallFailure(cause: Throwable?) {
        installFailure = cause
        installFailed = true
    }

    fun logUnavailable(screen: String) {
        if (installFailed) {
            Log.e(LOG_TAG, "$screen opened, but the Feed Limiter extension failed to install", installFailure)
        } else {
            Log.w(LOG_TAG, "$screen opened before the Feed Limiter extension finished installing")
        }
    }
}
