package com.farelock.reservation.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/**
 * Runs blocking code (JDBC, blocking HTTP) on the IO thread pool from inside a coroutine.
 *
 * Rule used throughout this service: database transactions live entirely inside one
 * `io { }` block. A transaction never spans a suspension point, so it is always bound
 * to a single thread, which Spring's thread-local transaction handling requires.
 */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/**
 * Wraps a suspend controller body so the logging MDC (the request id) follows the coroutine
 * onto whichever threads it resumes on. Every `io { }` and `async { }` inside inherits it.
 */
suspend fun <T> withRequestContext(block: suspend () -> T): T = withContext(MDCContext()) { block() }
