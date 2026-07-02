package me

import io.ktor.server.engine.*
import io.ktor.server.application.*

fun main(args: Array<String>) {
    dotenv {
        ignoreIfMissing = true
        systemProperties = true
    }

    io.ktor.server.netty.EngineMain.main(args)
}
