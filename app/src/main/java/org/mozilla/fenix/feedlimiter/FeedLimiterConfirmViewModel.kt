package org.mozilla.fenix.feedlimiter

// PLACEHOLDER PACKAGE - see the comment at the top of
// FeedLimiterExtensionBridge.kt.

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The friction/cooldown gate itself - feature list items 23-27. One of
 * these is created fresh per confirm-screen visit (scoped to
 * FeedLimiterConfirmFragment, NOT shared/retained beyond it), which is what
 * gives it the required "any exit resets to zero" behavior for free: if the
 * Fragment is removed (back press, navigating away, the confirm screen
 * being replaced), this ViewModel is cleared and its countdown simply
 * ceases to exist - there is nothing to resume, by construction, matching
 * item 26 ("countdown state lives only in-memory in that screen's
 * ViewModel - never persisted, never survives leaving the screen").
 *
 * Backgrounding/screen-off while the screen stays on the back stack is
 * handled by the Fragment, not here (see FeedLimiterConfirmFragment's
 * repeatOnLifecycle(RESUMED) usage) - this ViewModel only runs the actual
 * countdown coroutine, and the Fragment controls start/stop/reset calls
 * based on its own lifecycle state (item 25: "countdown only progresses
 * while the confirmation screen stays open and foregrounded; any
 * interruption resets it to zero").
 *
 * Device rotation is the one interruption that must NOT reset the
 * countdown (item 26 implies this by scoping state to the ViewModel rather
 * than the Fragment/View - a plain ViewModel already survives
 * configuration changes by default, so rotation needs no special-casing
 * here; it only breaks if you reach for a View-scoped CountDownTimer
 * instead, which is why this uses a viewModelScope coroutine rather than
 * that).
 */
class FeedLimiterConfirmViewModel(
    private val action: FeedLimiterLoosenAction,
    private val durationMinutes: Int,
    private val bridge: FeedLimiterExtensionBridge
) : ViewModel() {

    enum class State { RUNNING, COMPLETED, RESET }

    // var, not val: setDuration() below allows the Fragment to apply a
    // different duration before the countdown has started (e.g. once the
    // item-22 per-site/per-cap duration selector exists in the Settings
    // screen and the Fragment reads a different value than the default
    // passed in here).
    private var totalSeconds = durationMinutes * 60

    private val _secondsRemaining = MutableLiveData(totalSeconds)
    val secondsRemaining: LiveData<Int> get() = _secondsRemaining

    private val _state = MutableLiveData(State.RESET)
    val state: LiveData<State> get() = _state

    private var tickJob: Job? = null

    val description: String get() = action.describe()

    /** Called by the Fragment when the screen becomes RESUMED. Starting an
     *  already-running countdown again is a no-op (guarded by tickJob !=
     *  null) so repeated onResume calls within one truly-continuous
     *  foreground session don't restart the clock. */
    fun startOrResume() {
        if (tickJob?.isActive == true) return
        _state.value = State.RUNNING
        tickJob = viewModelScope.launch {
            while ((_secondsRemaining.value ?: 0) > 0) {
                delay(1000)
                _secondsRemaining.value = (_secondsRemaining.value ?: 1) - 1
            }
            _state.value = State.COMPLETED
        }
    }

    /** Called by the Fragment on ANY pause/exit - per item 25, every
     *  interruption hard-resets the countdown to zero, there is no partial
     *  credit for an interrupted wait. Rotation is the only exception, and
     *  it's handled by never calling this for a rotation in the first
     *  place (the Fragment's repeatOnLifecycle block only sees a real
     *  pause, not a config-change teardown/recreate, because the ViewModel
     *  - not the lifecycle callback - is what survives rotation). */
    fun pauseAndReset() {
        tickJob?.cancel()
        tickJob = null
        _secondsRemaining.value = totalSeconds
        _state.value = State.RESET
    }

    /** Applies a different duration than the one passed in at construction
     *  - only while the countdown hasn't started yet (item 22's
     *  per-site/per-cap duration selector is expected to live on the
     *  Settings screen as a persisted preference the confirm screen reads
     *  on open, not as a live control on this screen once waiting has
     *  begun - letting someone shorten an in-progress wait would defeat
     *  the point of the gate). */
    fun setDuration(minutes: Int) {
        if (_state.value == State.RUNNING) return
        totalSeconds = minutes * 60
        _secondsRemaining.value = totalSeconds
    }

    /** Called once the countdown reaches zero AND the user explicitly
     *  confirms (two separate gates - reaching zero alone shouldn't
     *  silently apply the change with no final tap, in case they walked
     *  away from an unattended device during the wait). */
    fun confirmAndApply() {
        if (_state.value != State.COMPLETED) return
        val current = bridge.settings.value.orEmpty()[action.site] ?: return
        bridge.updateSiteSettings(action.site, current.applying(action))
    }

    override fun onCleared() {
        tickJob?.cancel()
    }

    class Factory(
        private val action: FeedLimiterLoosenAction,
        private val durationMinutes: Int,
        private val bridge: FeedLimiterExtensionBridge
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(FeedLimiterConfirmViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
            }
            @Suppress("UNCHECKED_CAST")
            return FeedLimiterConfirmViewModel(action, durationMinutes, bridge) as T
        }
    }
}
