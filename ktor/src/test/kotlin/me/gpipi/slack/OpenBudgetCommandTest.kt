package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.auth.AuthService

class OpenBudgetCommandTest {
    private val authService = mockk<AuthService>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val command = OpenBudgetCommand(
        authService = authService,
        slack = slack,
        webBaseUrl = "https://budget.test/",
    )
    private val message = SlackMessage(
        eventId = "Ev001",
        userId = "U1",
        channelId = "C1",
        ts = "1751700000.000100",
        text = "<@BOT> open",
        body = "open",
    )

    @Test
    fun `matches only open commands`() {
        assertTrue(command.matches("open"))
        assertTrue(command.matches("open budget"))
        assertFalse(command.matches("1500 ramen"))
        assertFalse(command.matches("opening the fridge"))
    }

    @Test
    fun `handle mints a nonce and sends the link only to the requester`() = runBlocking {
        coEvery { authService.mint("U1") } returns "raw-nonce"

        command.handle(message, UUID.randomUUID())

        coVerify(exactly = 1) { authService.mint("U1") }
        coVerify(exactly = 1) {
            slack.postEphemeral(
                channel = "C1",
                user = "U1",
                text = match { it.contains("raw-nonce") },
            )
        }
        coVerify(exactly = 0) { slack.postMessage(any(), any()) }
    }

    @Test
    fun `handle sends private failure feedback when minting fails`() = runBlocking {
        coEvery { authService.mint(any()) } throws RuntimeException()

        command.handle(message, UUID.randomUUID())

        coVerify(exactly = 1) {
            slack.postEphemeral(
                channel = "C1",
                user = "U1",
                text = "Couldn't open your budget right now — try again shortly.",
            )
        }
        coVerify(exactly = 0) {
            slack.postEphemeral(
                channel = "C1",
                user = "U1",
                text = match { it.startsWith("Open your budget:") },
            )
        }
        coVerify(exactly = 0) { slack.postMessage(any(), any()) }
    }
}
