package me

import io.github.cdimascio.dotenv.dotenv

fun main(args: Array<String>) {
    dotenv {
        ignoreIfMissing = true
        systemProperties = true
    }

    io.ktor.server.netty.EngineMain.main(args)
}
