package me.gpipi.training.google

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleSheetSelectionCodecTest {
    private val cipher = GoogleCredentialCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    )
    private val issuedAt = Instant.parse("2026-08-11T00:00:00Z")

    @Test
    fun `selection round trips for the member it was issued to`() {
        val codec = codecAt(issuedAt)

        val selected = codec.resolve("member-1", codec.issue("member-1", "sheet-id-12345"))

        assertEquals("sheet-id-12345", selected.spreadsheetId)
    }

    @Test
    fun `selection cannot be used by another member`() {
        val codec = codecAt(issuedAt)
        val token = codec.issue("member-1", "sheet-id-12345")

        assertFailsWith<GoogleIntegrationException> { codec.resolve("member-2", token) }
    }

    @Test
    fun `selection expires after ten minutes`() {
        val token = codecAt(issuedAt).issue("member-1", "sheet-id-12345")

        assertFailsWith<GoogleIntegrationException> {
            codecAt(issuedAt.plusSeconds(601)).resolve("member-1", token)
        }
    }

    private fun codecAt(instant: Instant) = GoogleSheetSelectionCodec(
        cipher,
        Clock.fixed(instant, ZoneOffset.UTC),
    )
}
