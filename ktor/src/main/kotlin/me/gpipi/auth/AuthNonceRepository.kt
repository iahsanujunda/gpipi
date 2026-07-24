package me.gpipi.auth

import java.time.OffsetDateTime
import java.util.UUID
import me.gpipi.generated.db.base.public1.AuthNonce
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.updateReturning

class AuthNonceRepository {
    fun insert(
        nonceHash: String,
        userId: String,
        expiresAt: OffsetDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        AuthNonce.insert {
            it[AuthNonce.id] = id
            it[AuthNonce.userId] = userId
            it[AuthNonce.expiresAt] = expiresAt
            it[AuthNonce.nonceHash] = nonceHash
        }
        return id
    }

    fun consume(
        nonceHash: String,
        now: OffsetDateTime,
    ): String? {
        return AuthNonce.updateReturning(
            returning = listOf(AuthNonce.userId),
            where = {
                (AuthNonce.nonceHash eq nonceHash) and
                    (AuthNonce.expiresAt greater now) and
                    AuthNonce.consumedAt.isNull()
            },
        ) {
            it[AuthNonce.consumedAt] = now
        }.singleOrNull()?.get(AuthNonce.userId)
    }
}
