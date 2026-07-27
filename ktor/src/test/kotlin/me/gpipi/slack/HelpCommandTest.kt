package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HelpCommandTest {
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val command = HelpCommand(slack)
    private val message = SlackMessage(
        eventId = "Ev001",
        userId = "U1",
        channelId = "C1",
        ts = "1751700000.000100",
        text = "<@BOT> help",
        body = "help",
    )

    @Test
    fun `matches greetings and help words but not commands or expenses`() {
        assertTrue(command.matches("help"))
        assertTrue(command.matches("HELP"))
        assertTrue(command.matches(" hi "))
        assertTrue(command.matches("?"))
        assertTrue(command.matches("commands"))
        assertFalse(command.matches("open"))
        assertFalse(command.matches("list add milk"))
        assertFalse(command.matches("510 conbini"))
    }

    @Test
    fun `handle posts a private cheat sheet listing every command`() = runBlocking {
        val outcome = command.handle(message, UUID.randomUUID())

        assertEquals(SlackCommandOutcome.Completed, outcome)
        coVerify(exactly = 1) {
            slack.postEphemeralCard(
                channel = "C1",
                user = "U1",
                text = "What I can do",
                blocks = match {
                    val rendered = it.toString()
                    "open" in rendered &&
                        "list add" in rendered &&
                        "Not an expense" in rendered
                },
            )
        }
        coVerify(exactly = 0) { slack.postMessage(any(), any()) }
    }

    @Test
    fun `handle reports Slack delivery rejection as a command failure`() = runBlocking {
        coEvery {
            slack.postEphemeralCard(any(), any(), any(), any())
        } throws SlackApiException("chat.postEphemeral", "channel_not_found")

        val outcome = command.handle(message, UUID.randomUUID())

        assertEquals(
            SlackCommandOutcome.Failed("chat.postEphemeral failed: channel_not_found"),
            outcome,
        )
    }
}
