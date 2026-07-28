package me.gpipi.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import me.gpipi.observability.AppObservabilityKey
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int)

fun ApplicationConfig.dbConfig() = DbConfig(
    url = property("db.url").getString(),
    user = property("db.user").getString(),
    password = property("db.password").getString(),
    maxPoolSize = property("db.maxPoolSize").getString().toInt(),
)

data class Db(val database: Database, val dataSource: HikariDataSource)

fun connectDatabase(cfg: DbConfig, meterRegistry: MeterRegistry? = null): Db {
    val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.url
        username = cfg.user
        password = cfg.password
        maximumPoolSize = cfg.maxPoolSize
        meterRegistry?.let(::setMetricRegistry)
    })
    Flyway.configure().dataSource(pool).load().migrate()  // migrate on boot
    return Db(Database.connect(pool), pool)
}

val DbKey = AttributeKey<Db>("Db")

fun Application.configureDatabase() {
    val meterRegistry = attributes.getOrNull(AppObservabilityKey)?.meterRegistry
    val db = connectDatabase(environment.config.dbConfig(), meterRegistry)
    monitor.subscribe(ApplicationStopped) { db.dataSource.close() }
    attributes.put(DbKey, db)
}
