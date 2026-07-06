
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "me"
version = "1.0.0-SNAPSHOT"

application {
    // Our own entry point (main.kt) — loads .env into system properties, THEN delegates to
    // EngineMain. Pointing this straight at io.ktor.server.netty.EngineMain would skip dotenv.
    mainClass = "me.gpipi.MainKt"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(libs.logback.classic)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation("org.jetbrains.exposed:exposed-core:1.3.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.3.1")
    implementation("org.jetbrains.exposed:exposed-java-time:1.3.1")
    implementation("org.flywaydb:flyway-core:12.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.10.0")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("com.zaxxer:HikariCP:7.1.0")

    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.test {
    useJUnitPlatform()
}
