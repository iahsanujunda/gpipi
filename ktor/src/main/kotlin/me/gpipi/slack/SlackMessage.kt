package me.gpipi.slack

data class SlackMessage(
    val eventId: String,
    val userId: String,
    val channelId: String,
    val ts: String,
    val text: String,
    val body: String,
) {
    companion object {
        fun from(payload: SlackEnvelope): SlackMessage? {
            val event = payload.event ?: return null
            val eventId = payload.eventId ?: return null

            if (
                event.type != "app_mention" ||
                event.user == null ||
                event.channel == null ||
                event.ts == null ||
                event.text.isNullOrBlank()
            ) {
                return null
            }

            return SlackMessage(
                eventId = eventId,
                userId = event.user,
                channelId = event.channel,
                ts = event.ts,
                text = event.text,
                body = event.text.substringAfter('>').trim(),
            )
        }
    }
}
