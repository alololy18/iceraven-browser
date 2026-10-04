package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

/**
 * Process-wide access point for the single FeedLimiterExtensionBridge
 * instance, so the Settings screen and the confirm/cooldown screen can both
 * reach the same bridge (and therefore the same in-memory LiveData/prefs)
 * without either one owning its lifecycle.
 *
 * CAVEAT - this is a plain singleton holder, not real dependency injection,
 * because this fork's actual DI setup (Fenix normally exposes shared
 * objects off an Application subclass, often called something like
 * FenixApplication.components) isn't available to inspect from here. If
 * your fork already has such a components/service-locator object, it's
 * worth moving `bridge` there instead and deleting this file - this holder
 * is only a drop-in that needs nothing else from your codebase to compile.
 *
 * `initialize()` must be called exactly once, at the same call site where
 * extension.setMessageDelegate(bridge, "feedlimiter") is wired up (see the
 * class doc on FeedLimiterExtensionBridge and the Phase 2 wiring
 * instructions) - before either screen tries to read `instance`.
 */
object FeedLimiterBridgeHolder {
    private var _instance: FeedLimiterExtensionBridge? = null

    val instance: FeedLimiterExtensionBridge
        get() = _instance
            ?: throw IllegalStateException(
                "FeedLimiterBridgeHolder.initialize() hasn't been called yet - " +
                    "it must run at the same GeckoView/extension init call site " +
                    "that calls extension.setMessageDelegate(bridge, \"feedlimiter\")."
            )

    fun initialize(bridge: FeedLimiterExtensionBridge) {
        _instance = bridge
    }
}
