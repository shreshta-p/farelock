package com.farelock.reservation.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs blocking code (JDBC, blocking HTTP) on the IO thread pool from inside a coroutine.
 *
 * Rule used throughout this service: database transactions live entirely inside one
 * `io { }` block. A transaction never spans a suspension point, so it is always bound
 * to a single thread, which Spring's thread-local transaction handling requires.
 */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
