package me.gpipi.auth

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.AuthNonce
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll

class AuthNonceRepositoryTest : PersistenceTest() {
    private val repository = AuthNonceRepository()

    private val now: OffsetDateTime = OffsetDateTime.of(2026, 7, 23, 12, 0, 0, 0, ZoneOffset.UTC)

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun givenNonce(hash: String, userId: String, expiresAt: OffsetDateTime) = query {
        repository.insert(hash, userId, expiresAt)
    }

    private fun row(hash: String) = query {
        AuthNonce.selectAll().where { AuthNonce.nonceHash eq hash }.single()
    }

    @Test
    fun `insert persists an unconsumed nonce`() {
        val expiresAt = now.plusHours(1)
        val id = givenNonce("hash-1", "U1", expiresAt)

        val row = query { AuthNonce.selectAll().single() }

        assertEquals(id, row[AuthNonce.id])
        assertEquals("hash-1", row[AuthNonce.nonceHash])
        assertEquals("U1", row[AuthNonce.userId])
        assertEquals(expiresAt, row[AuthNonce.expiresAt])
        assertNull(row[AuthNonce.consumedAt])
        assertNotNull(row[AuthNonce.createdAt])
    }

    @Test
    fun `consume returns the user id for a valid nonce`() {
        givenNonce("hash-1", "U1", now.plusHours(1))

        val consumed = query { repository.consume("hash-1", now) }

        assertEquals("U1", consumed)
        assertEquals(now, row("hash-1")[AuthNonce.consumedAt])
    }

    @Test
    fun `consume is single-use`() {
        givenNonce("hash-1", "U1", now.plusHours(1))

        assertEquals("U1", query { repository.consume("hash-1", now) })
        assertNull(query { repository.consume("hash-1", now.plusMinutes(5)) })

        // the second attempt must not overwrite consumedAt
        assertEquals(now, row("hash-1")[AuthNonce.consumedAt])
    }

    @Test
    fun `expired nonce cannot be consumed`() {
        givenNonce("hash-expired", "U1", now.minusSeconds(1))

        assertNull(query { repository.consume("hash-expired", now) })
        assertNull(row("hash-expired")[AuthNonce.consumedAt])
    }

    @Test
    fun `nonce expiring exactly now is treated as expired`() {
        givenNonce("hash-edge", "U1", now)

        assertNull(query { repository.consume("hash-edge", now) })
        assertNull(row("hash-edge")[AuthNonce.consumedAt])
    }

    @Test
    fun `unknown nonce returns null and leaves other rows untouched`() {
        givenNonce("hash-real", "U1", now.plusHours(1))

        assertNull(query { repository.consume("hash-nope", now) })

        assertNull(row("hash-real")[AuthNonce.consumedAt])
    }

    @Test
    fun `consume affects only the matching nonce`() {
        givenNonce("hash-a", "U-A", now.plusHours(1))
        givenNonce("hash-b", "U-B", now.plusHours(1))

        val consumed = query { repository.consume("hash-a", now) }

        assertEquals("U-A", consumed)
        assertEquals(now, row("hash-a")[AuthNonce.consumedAt])
        assertNull(row("hash-b")[AuthNonce.consumedAt])
    }

    @Test
    fun `duplicate nonce hash is rejected by the unique constraint`() {
        givenNonce("hash-dup", "U1", now.plusHours(1))

        assertFailsWith<ExposedSQLException> {
            query { repository.insert("hash-dup", "U2", now.plusHours(1)) }
        }

        // the original row is untouched
        assertEquals("U1", row("hash-dup")[AuthNonce.userId])
        assertNull(row("hash-dup")[AuthNonce.consumedAt])
    }

    @Test
    fun `concurrent consumers produce exactly one winner`() = runBlocking {
        givenNonce("hash-race", "U-race", now.plusHours(1))

        val results = coroutineScope {
            val a = async(Dispatchers.IO) { dbQuery(db) { repository.consume("hash-race", now) } }
            val b = async(Dispatchers.IO) { dbQuery(db) { repository.consume("hash-race", now) } }
            listOf(a.await(), b.await())
        }

        assertEquals(1, results.count { it == "U-race" })
        assertEquals(1, results.count { it == null })

        // the row was consumed exactly once
        assertEquals(now, row("hash-race")[AuthNonce.consumedAt])
    }
}
