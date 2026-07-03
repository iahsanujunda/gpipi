package me.gpipi.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int)

fun ApplicationConfig.dbConfig() = DbConfig(
    url = property("db.url").getString(),
    user = property("db.user").getString(),
    password = property("db.password").getString(),
    maxPoolSize = property("db.maxPoolSize").getString().toInt(),
)

fun connectDatabase(cfg: DbConfig): Database {
    val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.url
        username = cfg.user
        password = cfg.password
        maximumPoolSize = cfg.maxPoolSize
    })
    Flyway.configure().dataSource(pool).load().migrate()  // migrate on boot
    return Database.connect(pool)
}
