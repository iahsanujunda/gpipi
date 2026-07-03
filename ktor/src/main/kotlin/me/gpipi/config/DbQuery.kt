package me.gpipi.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * The single entry point for request-path DB access. Every repo call in a coroutine goes
 * through here; repos do raw table ops INSIDE the block and never open their own transaction.
 *
 * Two disciplines this enforces by construction:
 *  - suspendTransaction carries Exposed's thread-local transaction correctly across coroutine
 *    suspension (a plain blocking `transaction {}` in a suspend path breaks when it resumes on
 *    another thread — "connection is closed").
 *  - withContext(Dispatchers.IO) keeps blocking JDBC off Netty's event loop, so the 3s Slack
 *    ack is never stalled. suspendTransaction runs on the current context, so this is on us.
 *
 * A multi-step atomic write is ONE flat dbQuery block — never nest these (a nested suspended
 * transaction may not roll back when the outer throws). Keep network calls (OpenRouter, Slack)
 * OUTSIDE the block: open a transaction only around the actual writes.
 */
suspend fun <T> dbQuery(db: Database, block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        suspendTransaction(db = db) { block() }
    }
