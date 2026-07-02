# Household Budget Bot — Testing Harness

_Test facility · Stack: Ktor `testApplication` · Exposed (JDBC) · Testcontainers (Postgres) · Flyway · JUnit 5 · MockK_

> **Schema authority for this repo = Flyway.** The sibling `jepangpg` repo lets **Supabase** own the schema (its tests apply `supabase/migrations/*.sql` directly via JDBC, no Flyway). This repo has no Supabase — Flyway owns migrations for both prod and tests. Keep that distinction in mind whenever you copy an idea across from `jepangpg`; some of its machinery is Supabase/Spring-specific and does **not** port (see *Divergences* below).

---

## Current state (2026-07-02) — what already exists

Ground the plan in what's actually in the repo, not what the draft assumed:

| Piece | State |
|-------|-------|
| Ktor `testApplication` wiring | ✅ **Working.** `src/test/kotlin/ServerTest.kt` boots the real app via Ktor's `configure()` (loads `application.conf` → all three modules) and asserts `GET /` returns 200. This *is* the app-layer smoke test. |
| Test dependencies | ✅ **Present.** `ktor-server-test-host`, `kotlin("test")`, `org.testcontainers:postgresql:1.20.4`, `org.testcontainers:junit-jupiter:1.20.4`, `io.mockk:mockk:1.13.13`. |
| Shared container | 🟡 **Half.** `src/test/kotlin/support/TestPostgres.kt` is a lazy singleton `PostgreSQLContainer("postgres:17-alpine")` that only `start()`s. No Flyway, no Exposed `Database` binding yet, and **nothing exercises it** (no test references it → the container never actually boots in CI). |
| DB main deps (Exposed, Flyway, Postgres driver) | ❌ **Not added.** There are zero database deps in `main`. These are the gate for everything persistence-related. |
| `PersistenceTest` base / `cleanDatabase()` | ❌ Not written. |
| Route DI (`budgetModule(collaborators…)`) | ❌ Routes are still the scaffold's top-level `configureRouting()` with no injected collaborators. |

So the honest headline: **the app-layer smoke test is done; the persistence harness is not even scaffolded, because its main deps aren't in the build yet.**

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
| Testcontainers `@Container` per class | **Singleton** container started once (your `TestPostgres`) | Reused across whole suite |

Two properties fall out for free and are worth naming: because Flyway runs against the container, **every test run validates the migrations apply cleanly to a fresh Postgres** — migration tests you didn't write. And because Ktor DI is constructor-passing, **test doubles are just parameters** — no `@MockBean`, no context restart, no bean-override ceremony.

---

## Divergences from `jepangpg` that do NOT port

You asked for a setup *similar* to `jepangpg`. The conceptual pyramid ports; three implementation details do not — don't cargo-cult them:

1. **No Flyway there, Flyway here.** `jepangpg`'s `SharedPostgres` drops/recreates `public` and replays `../supabase/migrations/*.sql` by hand, because Supabase is the schema authority. Here, Flyway is — put migrations under `src/main/resources/db/migration/` and let `Flyway.migrate()` run them.
2. **`max_connections=500` bump — not needed.** `jepangpg` raises it because each Spring test *context* opens its own Hikari pool against the shared container, and many contexts exhaust the default 100. Ktor has no context-per-test explosion: one `Database.connect` for the entire suite. Default `max_connections` is fine.
3. **`stop()`-override — barely relevant.** `jepangpg` suppresses `stop()` so one context shutdown doesn't kill a container other contexts still use. With a single suite-wide connection there are no competing contexts; let Ryuk reap the container at JVM exit.

What *does* port: the singleton-container-per-JVM idea, truncate-between-tests, and the "real Postgres, no H2" stance.

---

## Test Layers (the pyramid)

| Layer | Scope | Tooling | Container? | Speed |
|-------|-------|---------|-----------|-------|
| **Unit** | Pure logic — signature verification, amount parsing, prompt assembly, JSON parsing | plain JUnit + MockK | no | ms |
| **Persistence** | Exposed repos against real Postgres; dedup/idempotency; atomic writes | container base class | yes | ~seconds |
| **Application** | Full route flow — `/slack/events` ack, signature reject, confirm handler — with external clients mocked | `testApplication` + container + MockK | yes | ~seconds |

Keep the base as wide as possible: signature verification, the extraction-JSON decode, the amount/currency normalization, and prompt building are all *unit*-testable with no container — fast, and where most of the logic bugs live.

---

# Roadmap — three stages

The work splits cleanly into **what you can do now** (unblocked), **what you can prepare now** (scaffold that compiles and runs green, no-op until schema exists), and **what waits for later** (needs iteration-2 schema or iteration-1 route code). Do them in order.

## Stage A — Do now (unblocked, no new deps, no schema)

Everything here works against the repo exactly as it stands today.

- [ ] **Container-boots smoke test.** The one genuinely valuable thing available today: assert `TestPostgres.container.isRunning`. This forces the singleton to actually `start()`, proving the whole Testcontainers→Docker path works on your machine and in CI — the flakiest infra dependency, validated before any schema exists. Nothing references `TestPostgres` yet, so today it's dead code; this is what lights it up.

  ```kotlin
  // src/test/kotlin/support/ContainerSmokeTest.kt
  class ContainerSmokeTest {
      @Test fun `postgres container boots`() {
          assertTrue(TestPostgres.container.isRunning)
      }
  }
  ```

- [x] **App-layer smoke test** — already done by `ServerTest` (`GET /` through the real config). Leave it; it's your proof the test-host wiring is sound.
- [ ] *(Optional)* a trivial pure-unit test, only if you want explicit proof the unit path runs — `ServerTest` already proves the task runs, so this is low-value.

## Stage B — Prepare now (scaffold that no-ops until schema arrives)

This is the "expected later but stood up now" tier. Each piece **compiles and runs green today** even with zero migrations — Flyway on an empty `db/migration/` is a successful no-op that just creates `flyway_schema_history`, `Database.connect` binds fine, and `cleanDatabase()` truncates an empty set. Standing it up now means iteration 2 only writes *tests*, not *plumbing*.

- [ ] **Add the DB main deps** (these are the real gate — they do **not** exist yet):
  ```kotlin
  // build.gradle.kts — main (versions via your libs catalog once picked)
  implementation("org.jetbrains.exposed:exposed-core:<v>")
  implementation("org.jetbrains.exposed:exposed-jdbc:<v>")
  implementation("org.flywaydb:flyway-core:<v>")
  implementation("org.flywaydb:flyway-database-postgresql:<v>") // Flyway 10+ needs the PG module
  implementation("org.postgresql:postgresql:<v>")
  ```
- [ ] **Confirm JUnit 5 is the engine.** `@BeforeEach` (used below) is JUnit Jupiter. `testcontainers:junit-jupiter` pulls the engine onto the classpath, but the `Test` task still needs the platform selected explicitly:
  ```kotlin
  tasks.test { useJUnitPlatform() }
  ```
  Verify this once `@BeforeEach` appears — the current `ServerTest` uses only `kotlin.test` annotations, so the gap is latent, not yet visible.
- [ ] **Extend `TestPostgres`** with the Flyway-migrate-then-connect singleton (pin the image to your prod Postgres major — `17-alpine` today, matching `jepangpg`'s PG 17):
  ```kotlin
  // src/test/kotlin/support/TestPostgres.kt
  object TestPostgres {
      val container: PostgreSQLContainer<*> by lazy {
          PostgreSQLContainer("postgres:17-alpine")
              // .withReuse(true)  // only if you enable reuse in ~/.testcontainers.properties
              .apply { start() }
      }

      // Migrate + bind Exposed exactly once, the first time any test needs the DB.
      // With no migration files yet, migrate() is a green no-op.
      val database: Database by lazy {
          Flyway.configure()
              .dataSource(container.jdbcUrl, container.username, container.password)
              .load()
              .migrate()                       // reads src/main/resources/db/migration/
          Database.connect(
              url = container.jdbcUrl,
              driver = "org.postgresql.Driver",
              user = container.username,
              password = container.password,
          )
      }
  }
  ```
- [ ] **`PersistenceTest` base + `cleanDatabase()`** — truncates every non-Flyway table, no hardcoded list, so it survives every future migration untouched:
  ```kotlin
  // src/test/kotlin/support/PersistenceTest.kt
  abstract class PersistenceTest {
      protected val db: Database = TestPostgres.database
      @BeforeEach fun clean() = cleanDatabase()
  }

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
- [ ] **Design routes for injection** (iteration-1 work, but decide the shape now). A module that takes its collaborators as parameters is what makes the application layer testable with MockK doubles — see below.

## Stage C — Later (needs iteration-2 schema or iteration-1 route code)

Blocked until the schema and route handlers actually exist. The Stage-B harness is built precisely so these become *just tests*:

- [ ] `V1__inbound_and_expense.sql` under `src/main/resources/db/migration/` (iteration 2) — the moment this lands, Flyway-against-container starts validating migrations on **every** run, for free.
- [ ] `insertIgnoreAndGetId` dedup — same `event_id` twice → one row, second returns null (the Slack-retry idempotency guard).
- [ ] Atomic confirm write (iter 4) — all-or-nothing: force a failure mid-block, assert nothing committed.
- [ ] Signature-verification **unit** test (iter 1.2) — no container, security-critical, known-good/known-bad fixture pair. **This is the ideal first *real* test.**
- [ ] `/slack/events` application tests — unsigned → 401, `url_verification` challenge echoed — with Slack/OpenRouter mocked.
- [ ] Extraction JSON decode + normalization against reference inputs (`イトーヨーカドー`, `conbini`, `¥1,500`) — unit level, no container.

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

> This is the payoff of Ktor's explicit DI: no context, no bean override, no restart — the double is just an argument. It also nudges iteration 1's module into a testable shape from the start.
>
> **Note:** the current scaffold wires modules by fully-qualified reference in `application.conf` (`me.RoutingKt.configureRouting`). Switching to a parameterized `budgetModule(...)` means dropping the config-declared modules and calling the module explicitly (`application { budgetModule(...) }` in tests; a thin `fun Application.module()` that constructs real collaborators in prod). Plan that refactor as part of iteration 1.

---

## Definition of Done

**Done now:**
- [x] Testcontainers + MockK deps added
- [x] App-layer `testApplication` smoke test green (`ServerTest`, `GET /`)

**Stage A (do now):**
- [ ] Container-boots smoke test green (confirms Docker available locally + `TestPostgres` actually starts)

**Stage B (prepare now — green as no-ops):**
- [ ] Exposed + Flyway + Postgres driver added to `main`
- [ ] `useJUnitPlatform()` set / JUnit 5 engine confirmed
- [ ] `TestPostgres` runs Flyway then binds Exposed `Database`
- [ ] `PersistenceTest` base connects + truncates per test; `cleanDatabase()` covers all non-Flyway tables
- [ ] Route module designed to take collaborators as params (test-injectable)

**Stage C (later):**
- [ ] `V1__inbound_and_expense.sql` present; migrations validated every run
- [ ] Signature-verification unit test passes with a known-good/known-bad pair
- [ ] `/slack/events` reject + challenge application tests
- [ ] `./gradlew test` runs green end to end

---

## Handoff

Right now, do **Stage A** — the container smoke test — and you've proven every piece of infra the harness depends on (Docker, Testcontainers, the test task) with zero schema. Then **Stage B** stands up the persistence plumbing as green no-ops, so iteration 2 spends its budget on behavior, not wiring. The signature-verification unit test (Stage C) is the ideal first *real* test — no container, security-critical, and it gives you a fixture pair you'll trust before the Slack route is written test-first against this harness.
