package me.gpipi.training.google

import java.security.MessageDigest
import java.time.OffsetDateTime
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

data class StoredGoogleCredential(
    val encryptedRefreshToken: String,
    val scope: String,
    val connectedAt: OffsetDateTime,
)

data class ConsumedGoogleOAuthState(
    val userId: String,
    val returnPath: String,
)

class GoogleCredentialRepository {
    fun credential(userId: String): StoredGoogleCredential? = rows(
        """
        select encrypted_refresh_token, scope, connected_at
        from google_credential
        where user_id = ? and revoked_at is null
        """.trimIndent(),
        listOf(text(userId)),
    ) { rs ->
        StoredGoogleCredential(
            encryptedRefreshToken = rs.getString("encrypted_refresh_token"),
            scope = rs.getString("scope"),
            connectedAt = rs.getObject("connected_at", OffsetDateTime::class.java),
        )
    }.singleOrNull()

    fun saveCredential(
        userId: String,
        encryptedRefreshToken: String,
        scope: String,
        now: OffsetDateTime,
    ) {
        execute(
            """
            insert into google_credential (
                user_id, encrypted_refresh_token, scope, connected_at, revoked_at
            ) values (?, ?, ?, ?, null)
            on conflict (user_id) do update set
                encrypted_refresh_token = excluded.encrypted_refresh_token,
                scope = excluded.scope,
                connected_at = excluded.connected_at,
                revoked_at = null
            """.trimIndent(),
            listOf(text(userId), text(encryptedRefreshToken), text(scope), timestamp(now)),
        )
    }

    fun revoke(userId: String, now: OffsetDateTime): String? = rows(
        """
        update google_credential
        set revoked_at = ?
        where user_id = ? and revoked_at is null
        returning encrypted_refresh_token
        """.trimIndent(),
        listOf(timestamp(now), text(userId)),
        StatementType.UPDATE,
    ) { it.getString("encrypted_refresh_token") }.singleOrNull()

    fun saveState(
        rawState: String,
        userId: String,
        returnPath: String,
        expiresAt: OffsetDateTime,
        now: OffsetDateTime,
    ) {
        execute("delete from google_oauth_state where expires_at <= ?", listOf(timestamp(now)))
        execute(
            """
            insert into google_oauth_state (state_hash, user_id, return_path, expires_at, created_at)
            values (?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(text(hash(rawState)), text(userId), text(returnPath), timestamp(expiresAt), timestamp(now)),
        )
    }

    fun consumeState(rawState: String, now: OffsetDateTime): ConsumedGoogleOAuthState? = rows(
        """
        delete from google_oauth_state
        where state_hash = ? and expires_at > ?
        returning user_id, return_path
        """.trimIndent(),
        listOf(text(hash(rawState)), timestamp(now)),
        StatementType.UPDATE,
    ) { rs ->
        ConsumedGoogleOAuthState(
            userId = rs.getString("user_id"),
            returnPath = rs.getString("return_path"),
        )
    }.singleOrNull()

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun execute(statement: String, args: List<Pair<IColumnType<*>, Any?>>) {
        transaction().exec(statement, args, StatementType.UPDATE)
    }

    private fun <T> rows(
        statement: String,
        args: List<Pair<IColumnType<*>, Any?>>,
        type: StatementType = StatementType.SELECT,
        transform: (java.sql.ResultSet) -> T,
    ): List<T> = transaction().exec(statement, args, type) { rs ->
        buildList { while (rs.next()) add(transform(rs)) }
    }.orEmpty()

    private fun transaction() = checkNotNull(TransactionManager.currentOrNull())
    private fun text(value: String) = TextColumnType() to value
    private fun timestamp(value: OffsetDateTime) = JavaOffsetDateTimeColumnType() to value
}
