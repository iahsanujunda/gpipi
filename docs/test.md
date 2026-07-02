# Household Budget Bot — Testing Harness

_Test facility · Stack: Ktor `testApplication` · Exposed (JDBC) · Testcontainers (Postgres) · Flyway · JUnit 5 · MockK_

---

## Overview — the Paul Bakker model, translated

The Spring model (layered tests, one shared real-Postgres container reused across the suite, no H2, fast feedback) carries over wholesale. What does **not** carry over is the machinery that provides it — Ktor has no test slices, no context bootstrapping, no annotation-driven wiring. Each Spring convenience maps to an explicit Ktor equivalent, and a couple of them are actually *simpler* here because there's no application context to override.

| Spring Boot Test | Ktor + Exposed equivalent | Note |
|------------------|---------------------------|------|
| `@SpringBootTest(webEnvironment=MOCK)` + MockMvc | `testApplication { }` (from `ktor-server-test-host`) | In-memory, no real port |
| `@DataJpaTest` (persistence slice) | Base class: shared container + Flyway + Exposed `Database.connect` | Tests real migrated schema |
| `@ServiceConnection` / `@DynamicPropertySource` | Manual: wire `container.jdbcUrl` into the test module | One helper, no magic |
| `@Transactional` rollback between tests | **Truncate** all tables in `@BeforeEach` | Rollback doesn't translate cleanly — see below |
| `@MockBean` (context bean replacement) | Constructor-inject a MockK double into the module | No context caching pain — a Ktor win |
| Testcontainers `@Container` per class | **Singleton** container started once (your `SharedPostgres`) | Reused across whole suite |

Two properties fall out for free and are worth naming: because Flyway runs against the container, **every test run validates the migrations apply cleanly to a fresh Postgres** — migration tests you didn't write. And because Ktor DI is constructor-passing, **test doubles are just parameters** — no `@MockBean`, no context restart, no bean-override ceremony.

---

## Test Layers (the pyramid)

| Layer | Scope | Tooling | Container? | Speed |
|-------|-------|---------|-----------|-------|
| **Unit** | Pure logic — signature verification, amount parsing, prompt assembly, JSON parsing | plain JUnit + MockK | no | ms |
| **Persistence** | Exposed repos against real Postgres; dedup/idempotency; atomic writes | container base class | yes | ~seconds |
| **Application** | Full route flow — `/slack/events` ack, signature reject, confirm handler — with external clients mocked | `testApplication` + container + MockK | yes | ~seconds |

Keep the base as wide as possible: signature verification, the extraction-JSON decode, the amount/currency normalization, and prompt building are all *unit*-testable with no container — fast, and where most of the logic bugs live.

---

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // ... existing ...
    testImplementation(ktorLibs.server.testHost)                 // already present from scaffold
    testImplementation("org.testcontainers:postgresql:1.20.4")   // confirm current version
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("io.mockk:mockk:1.13.13")                 // confirm current version
    testImplementation(kotlin("test"))                            // already present
    // exposed-jdbc, flyway-core, postgresql driver are already main deps (iter 2)
}

tasks.test { useJUnitPlatform() }
```

> Match the container's Postgres **major** version to Supabase's (currently PG 16/17) — test against what you deploy on. `postgres:16-alpine` if Supabase is on 16.

---

## The Shared Container (singleton)

Started once, reused for the entire suite, never explicitly stopped — Testcontainers' Ryuk reaps it, or it dies with the JVM. This is your `SharedPostgres`, Ktor-side.

```kotlin
// src/test/kotlin/support/TestPostgres.kt
object TestPostgres {
    val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withReuse(true)            // optional: keep alive across runs for faster local loops
            .apply { start() }
    }

    // Run migrations + bind Exposed exactly once, the first time any test needs the DB.
    val database: Database by lazy {
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .load()
            .migrate()
        Database.connect(
            url = container.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password,
        )
    }
}
```

---

## Base Class — Persistence Tests

```kotlin
// src/test/kotlin/support/PersistenceTest.kt
abstract class PersistenceTest {
    protected val db: Database = TestPostgres.database

    @BeforeEach
    fun clean() = cleanDatabase()   // fresh state per test — see next section
}
```

```kotlin
// Truncate every table except Flyway's history. Robust across iterations — no hardcoded table list.
fun cleanDatabase() = transaction(TestPostgres.database) {
    val tables = exec(
        """select tablename from pg_tables
           where schemaname = 'public' and tablename <> 'flyway_schema_history'"""
    ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } ?: emptyList()
    if (tables.isNotEmpty()) {
        exec("TRUNCATE TABLE ${tables.joinToString(", ")} RESTART IDENTITY CASCADE")
    }
}
```

---

## Data Cleanup — Truncate, Not Rollback (and why)

Spring's `@Transactional`-per-test wraps each test in a transaction and rolls it back. **Do not port that here** — it's precisely the Exposed-meets-transaction-management friction that soured you on Exposed under Spring. In a Ktor+Exposed test, the code under test opens its *own* `newSuspendedTransaction`s (via `dbQuery`), and trying to wrap those in an outer rollback transaction reproduces the nesting/"connection is closed" problems. Truncate sidesteps all of it: let the code commit normally, wipe between tests. Deterministic, and it exercises the real commit path (which is what you actually want to test for the idempotency and atomic-write behavior).

---

## External Doubles — Constructor-Injected MockK

For application tests, the OpenRouter and Slack clients must not make real network calls. In Ktor there's no `@MockBean` — you pass a MockK double into the module. This requires (and rewards) writing the module to take its collaborators as parameters:

```kotlin
// Production module takes its collaborators — makes it test-injectable (design this in iter 1).
fun Application.budgetModule(
    openRouter: OpenRouterClient,
    slack: SlackClient,
    db: Database,
) { /* install plugins, wire routes */ }
```

```kotlin
// src/test/kotlin/SlackEventsTest.kt
class SlackEventsTest : PersistenceTest() {

    @Test
    fun `unsigned request is rejected`() = testApplication {
        val openRouter = mockk<OpenRouterClient>()
        val slack = mockk<SlackClient>(relaxed = true)
        application { budgetModule(openRouter, slack, db) }

        val res = client.post("/slack/events") { setBody("""{"type":"event_callback"}""") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)   // no valid signature
    }

    @Test
    fun `url_verification challenge is echoed`() = testApplication {
        application { budgetModule(mockk(), mockk(relaxed = true), db) }
        // sign the body, POST, assert challenge echoed back
    }
}
```

> This is the payoff of Ktor's explicit DI: no context, no bean override, no restart — the double is just an argument. It also nudges iteration 1's `module` into a testable shape from the start.

---

## What's Testable Now vs Iteration 2

**Now (pre-iteration-1, only Hello World exists):**
- A trivial **unit** test (sanity that the test task runs).
- A **`testApplication`** smoke test hitting the current Hello World route — this validates the Ktor test-host wiring end to end.
- A **container-boots** smoke test (`TestPostgres.container.isRunning`) — validates Testcontainers + Docker on your machine.

Flyway-against-container and repo tests are scaffolded now but have nothing to migrate until **iteration 2** introduces `V1__inbound_and_expense.sql`. That's expected — stand up the *structure* now, and it lights up the moment schema exists.

**Iteration 2 onward, the harness is built to verify:**
- Migrations apply cleanly to fresh Postgres (implicit, every run).
- `insertIgnoreAndGetId` dedup — same `event_id` twice → one row, second returns null (the Slack-retry idempotency guard).
- The atomic confirm write (iter 4) — all-or-nothing: force a failure mid-block, assert nothing committed.
- Extraction JSON decode + normalization against the reference inputs (`イトーヨーカドー`, `conbini`, `¥1,500`) — unit level, no container.

---

## Definition of Done

- [ ] Testcontainers + MockK deps added; `useJUnitPlatform()` set
- [ ] `TestPostgres` singleton boots one Postgres container for the suite
- [ ] `PersistenceTest` base class connects Exposed + truncates per test
- [ ] `cleanDatabase()` truncates all non-Flyway tables (no hardcoded list)
- [ ] Hello World route verified via `testApplication`
- [ ] Container-boots smoke test green (confirms Docker available locally)
- [ ] `budgetModule` designed to take collaborators as params (test-injectable)
- [ ] Unit test for signature verification passes with a known-good/known-bad pair
- [ ] `./gradlew test` runs green end to end

---

## Handoff

The signature-verification unit test is the ideal first real test — it needs no container, exercises iteration 1.2's security-critical code, and gives a known-good/known-bad fixture pair you'll trust. Write the harness, prove it with the Hello World `testApplication` test + container smoke, then iteration 1's route is written test-first against it.