package me.gpipi.auth

import io.mockk.confirmVerified
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.AuthNonce
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.Database

class AuthServiceTest : PersistenceTest() {
    private val repository = AuthNonceRepository()
    private val mintTime = Instant.parse("2026-07-24T03:00:00Z")

    private fun service(at: Instant = mintTime) = AuthService(
        db = db,
        nonceRepo = repository,
        clock = Clock.fixed(at, ZoneOffset.UTC),
    )

    @Test
    fun `minted nonce redeems to the same user id`() = runBlocking {
        val auth = service()

        val nonce = auth.mint("U123")

        assertEquals("U123", auth.redeem(nonce))
    }

    @Test
    fun `mint persists the nonce hash instead of the raw nonce`() = runBlocking {
        val rawNonce = service().mint("U123")

        val storedHash = dbQuery(db) {
            AuthNonce.selectAll().single()[AuthNonce.nonceHash]
        }

        assertNotEquals(rawNonce, storedHash)
    }

    @Test
    fun `redeem returns null after the minted nonce expires`() = runBlocking {
        val rawNonce = service(at = mintTime).mint("U123")

        val result = service(at = mintTime.plusSeconds(11 * 60)).redeem(rawNonce)

        assertNull(result)
    }

    @Test
    fun `redeem returns null for a nonce that was never minted`() = runBlocking {
        val result = service().redeem("not-a-minted-nonce")

        assertNull(result)
    }

    @Test
    fun `minted nonce can only be redeemed once`() = runBlocking {
        val auth = service()
        val rawNonce = auth.mint("U123")

        assertEquals("U123", auth.redeem(rawNonce))
        assertNull(auth.redeem(rawNonce))
    }
}

class AuthServiceGuardTest {
    @Test
    fun `blank nonce returns null without accessing persistence`() = runBlocking {
        val database = mockk<Database>()
        val repository = mockk<AuthNonceRepository>()
        val auth = AuthService(database, repository)

        assertNull(auth.redeem(""))
        confirmVerified(database, repository)
    }
}
