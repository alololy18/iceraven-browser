package org.mozilla.fenix.feedlimiter

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The countdown gate. Scoped to one confirm-screen visit and never persisted,
 * so leaving the screen discards the wait. Living in a ViewModel is what lets
 * a rotation keep the countdown while a real pause resets it (the Fragment
 * calls [pauseAndReset] on ON_PAUSE).
 */
class FeedLimiterConfirmViewModel(
    private val action: FeedLimiterLoosenAction,
    durationMinutes: Int,
    private val bridge: FeedLimiterExtensionBridge,
) : ViewModel() {

    enum class State { RUNNING, COMPLETED, RESET }

    val totalSeconds = durationMinutes * 60

    private val _secondsRemaining = MutableLiveData(totalSeconds)
    val secondsRemaining: LiveData<Int> get() = _secondsRemaining

    private val _state = MutableLiveData(State.RESET)
    val state: LiveData<State> get() = _state

    private var tickJob: Job? = null

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

    /** Any interruption restarts the wait from the full duration; there is no partial credit. */
    fun pauseAndReset() {
        tickJob?.cancel()
        tickJob = null
        _secondsRemaining.value = totalSeconds
        _state.value = State.RESET
    }

    /** Requires a final tap after reaching zero, in case the phone was left unattended. */
    fun confirmAndApply(): Boolean {
        if (_state.value != State.COMPLETED) return false
        return bridge.applyConfirmedLoosen(action)
    }

    override fun onCleared() {
        tickJob?.cancel()
    }

    class Factory(
        private val action: FeedLimiterLoosenAction,
        private val durationMinutes: Int,
        private val bridge: FeedLimiterExtensionBridge,
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
