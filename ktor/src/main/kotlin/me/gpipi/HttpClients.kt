package me.gpipi

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal const val SLACK_REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val OPENROUTER_REQUEST_TIMEOUT_MILLIS = 90_000L

internal fun HttpClientConfig<*>.configureSlackHttpClient() {
    install(ContentNegotiation) { json() }
    install(HttpTimeout) { requestTimeoutMillis = SLACK_REQUEST_TIMEOUT_MILLIS }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        retryOnException(maxRetries = 2, retryOnTimeout = true)
        exponentialDelay()
    }
}

internal fun HttpClientConfig<*>.configureOpenRouterHttpClient(
    timeoutMillis: Long = OPENROUTER_REQUEST_TIMEOUT_MILLIS,
) {
    require(timeoutMillis > 0) { "OpenRouter timeout must be positive" }

    install(ContentNegotiation) { json() }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        retryOnException(maxRetries = 2, retryOnTimeout = false)
        exponentialDelay()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        socketTimeoutMillis = timeoutMillis
    }
}
