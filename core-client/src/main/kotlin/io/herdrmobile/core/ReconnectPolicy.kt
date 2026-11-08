package io.herdrmobile.core

import io.herdrmobile.core.rpc.HcError
import kotlin.random.Random

internal val HcError.retryable: Boolean get() = kind !in setOf("auth", "host_key", "unsupported", "unsupported_generation")

internal fun connectionError(error: Exception): HcError = error as? HcError ?: HcError(
    if (error is java.io.FileNotFoundException || error is java.net.ConnectException) "not_running" else "io",
    error.message ?: "I/O error",
).also { it.initCause(error) }

/** ±20% jitter, with both the exponential base and the actual delay capped at 30s. */
internal fun reconnectDelayMs(attempt: Int, random: Random = Random.Default): Long {
    require(attempt > 0)
    val base = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)[minOf(attempt - 1, 5)]
    return random.nextLong(base * 80 / 100, minOf(base * 120 / 100, 30_000) + 1)
}
