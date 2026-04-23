package com.pirorin215.btclockmob.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Extension functions for Flow operations.
 * Reduces boilerplate code in ViewModels and DI modules.
 */

/**
 * Convert a Flow to StateFlow with default configuration.
 * Uses WhileSubscribed(5000ms) as the sharing strategy.
 *
 * @param scope The coroutine scope
 * @param initialValue The initial value before any emission
 * @param timeoutMs Time to keep the flow active after last subscriber (default: 5000ms)
 */
fun <T> Flow<T>.stateInWithDefault(
    scope: CoroutineScope,
    initialValue: T,
    timeoutMs: Long = 5000L
): StateFlow<T> = this.stateIn(
    scope,
    SharingStarted.WhileSubscribed(timeoutMs),
    initialValue
)

/**
 * Convert a Flow to StateFlow with lazy initialization.
 * The flow starts only when the first subscriber appears.
 */
fun <T> Flow<T>.stateInLazy(
    scope: CoroutineScope,
    initialValue: T
): StateFlow<T> = this.stateIn(
    scope,
    SharingStarted.Lazily,
    initialValue
)

/**
 * Convert a Flow to StateFlow with eager initialization.
 * The flow starts immediately in the given scope.
 */
fun <T> Flow<T>.stateInEager(
    scope: CoroutineScope,
    initialValue: T
): StateFlow<T> = this.stateIn(
    scope,
    SharingStarted.Eagerly,
    initialValue
)
