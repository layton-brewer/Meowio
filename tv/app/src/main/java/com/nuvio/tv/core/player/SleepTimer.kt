package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sleep timer for the player. It lives outside any single playback session so it keeps
 * counting across episodes and source switches.
 *
 * The phone and TV apps keep identical copies of this file's logic.
 */
object SleepTimer {

    sealed interface State {
        /** No timer set. */
        data object Off : State

        /** Pause playback once [deadlineMs] (wall clock) is reached. */
        data class Until(val minutes: Int, val deadlineMs: Long) : State

        /** Pause when the current episode ends instead of starting the next one. */
        data object EndOfEpisode : State
    }

    /** The choices offered, in the order the button cycles through them. */
    val MINUTE_OPTIONS: List<Int> = listOf(15, 30, 45, 60, 90)

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(minutes: Int, nowMs: Long = System.currentTimeMillis()) {
        _state.value = if (minutes <= 0) {
            State.Off
        } else {
            State.Until(minutes = minutes, deadlineMs = nowMs + minutes * 60_000L)
        }
    }

    fun endOfEpisode() {
        _state.value = State.EndOfEpisode
    }

    fun cancel() {
        _state.value = State.Off
    }

    /** Off, 15, 30, 45, 60, 90 minutes, end of episode, then Off again. */
    fun cycle(nowMs: Long = System.currentTimeMillis()): State {
        when (val current = _state.value) {
            State.Off -> start(MINUTE_OPTIONS.first(), nowMs)
            is State.Until -> {
                val next = MINUTE_OPTIONS.firstOrNull { it > current.minutes }
                if (next != null) start(next, nowMs) else endOfEpisode()
            }
            State.EndOfEpisode -> cancel()
        }
        return _state.value
    }

    /** Milliseconds left on a timed sleep, or null when no timed sleep is running. */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long? =
        (_state.value as? State.Until)?.let { (it.deadlineMs - nowMs).coerceAtLeast(0L) }

    /**
     * Called when an episode is about to hand over to the next one. Returns true when playback
     * should stop here; the timer is cleared so the next press of play behaves normally.
     */
    fun consumeEndOfEpisode(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = _state.value
        val stop = current == State.EndOfEpisode ||
            (current is State.Until && current.deadlineMs <= nowMs)
        if (stop) cancel()
        return stop
    }
}
