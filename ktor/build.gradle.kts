import org.testcontainers.postgresql.PostgreSQLContainer

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.testcontainers:testcontainers-postgresql:2.0.5")
        classpath("org.postgresql:postgresql:42.7.12")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    id("de.quati.pgen") version "0.49.0"
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
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.sessions)
    implementation(ktorLibs.server.cors)
    implementation("org.jetbrains.exposed:exposed-core:1.3.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.3.1")
    implementation("org.jetbrains.exposed:exposed-java-time:1.3.1")
    implementation("de.quati.pgen:jdbc:0.49.0")
    implementation("de.quati:kotlin-util:2.0.0")   // generated code needs this
    implementation("org.flywaydb:flyway-core:12.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.10.0")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.sksamuel.aedile:aedile-core:3.0.4")

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

// Deployment artifact is the `application` distribution (installDist), NOT the Ktor fat jar.
// Flyway registers its plugins via ServiceLoader files under META-INF/services, and packing
// flyway-core + flyway-database-postgresql into one shaded jar clobbers those same-named files
// (shadow's ServiceFileTransformer drops flyway-core's 37 entries), leaving PluginRegister empty
// → NPE on boot. The distribution keeps every jar separate so both service files coexist — the
// same classpath shape `run` uses, which is why `run` never hit this.

val pgenDbPort = 55432
var pgenContainer: PostgreSQLContainer? = null

tasks.register("startPgenDb") {
    doLast {
        pgenContainer = PostgreSQLContainer("postgres:17-alpine").apply {
            withUsername("postgres"); withPassword("postgres"); withDatabaseName("postgres")
            portBindings = listOf("$pgenDbPort:5432")
            start()
        }
    }
}
tasks.register("stopPgenDb") { doLast { pgenContainer?.stop() } }

pgen {
    addDb("base") {
        connection {
            url("jdbc:postgresql://localhost:$pgenDbPort/postgres")
            user("postgres"); password("postgres")
        }
        flyway { migrationDirectory("$projectDir/src/main/resources/db/migration") }
        tableFilter {
            addTable("public", "inbound_message")
            addTable("public", "expense")
            addTable("public", "category")
            addTable("public", "categorization_event")
            addTable("public", "expense_draft")
            addTable("public", "auth_nonce")
        }
        columnTypeMappings {
            add(
                sqlType = "pg_catalog.timestamptz",
                columnTypeClass = "org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType",
                valueClass = "java.time.OffsetDateTime",
            )
        }
    }
    packageName("me.gpipi.generated")
    specFilePath("$projectDir/src/main/resources/pgen-spec.json")
    setConnectionTypeJdbc()
    useJavaUuidType()
}

tasks.named("pgenGenerateSpec") {
    dependsOn("startPgenDb"); finalizedBy("stopPgenDb")
}

sourceSets.main { kotlin.srcDir("build/generated/sources/pgen/src/main/kotlin") }
