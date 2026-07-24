# gpipi testing guide

The repository has two test harnesses:

- Ktor: JUnit 5, Ktor `testApplication`, MockK, Exposed, Flyway, and Testcontainers Postgres.
- Web: Vitest with Testing Library for component behavior, plus Playwright Firefox for browser behavior.

Flyway is the schema authority in production and tests. Backend persistence tests always run against the real migrations and Postgres; there is no H2 or test-only schema.

## Quick verification

Run backend tests from the Ktor project:

```bash
cd ktor
./gradlew test
```

This requires:

- Java 21, or a Gradle installation able to provision the configured Java 21 toolchain.
- A running Docker-compatible engine for Testcontainers.

Run the frontend checks from the web project:

```bash
cd web-app
npm ci
npm run lint
npm test
npm run build
```

Install Playwright Firefox once, then run the browser suite:

```bash
cd web-app
npx playwright install firefox
npm run test:e2e
```

The Playwright suite starts its own fake API on `127.0.0.1:18080` and Vite on `127.0.0.1:4173`. It does not need Ktor, Docker, Postgres, Slack, or OpenRouter.

## Test layers

| Layer | Scope | Harness | Real Postgres? |
|---|---|---|---|
| Backend unit | Parsing, validation, prompts, signatures, cache generations, services | JUnit 5 + MockK | No |
| Backend persistence | Repositories, constraints, idempotency, atomic updates and transactions | `PersistenceTest` + Testcontainers | Yes |
| Backend routes | HTTP paths, serialization, statuses, cookies, guards | Ktor `testApplication` | Only when booting the full app |
| Frontend component | Rendering, queries, mutations, drawers, navigation, accessibility | Vitest + jsdom + Testing Library | No |
| Frontend browser | Responsive layout, animation timing, geometry, focus, reduced motion | Playwright + Firefox + fake API | No |

Use the narrowest layer that proves the behavior. For example:

- Test input validation and result mapping at the service layer.
- Test unique constraints and `UPDATE ... RETURNING` concurrency against Postgres.
- Test exact URLs and HTTP statuses at the route layer.
- Test layout geometry and animation timing in Playwright, not jsdom.

There is currently one Gradle `test` source set rather than separate `unitTest` and `integrationTest` tasks. Running the complete backend suite therefore starts Docker even though many individual classes are pure unit tests.

## Backend harness

### Shared Postgres

[`TestPostgres.kt`](../ktor/src/test/kotlin/me/gpipi/support/TestPostgres.kt) lazily starts one `postgres:17-alpine` container per test JVM. Its `database` property calls the production `connectDatabase` function, so it:

1. creates the Hikari pool;
2. runs every Flyway migration under `src/main/resources/db/migration`;
3. connects Exposed to the migrated database.

This means every persistence-suite run also proves that the production migrations apply cleanly to a fresh Postgres instance.

`withReuse(true)` permits reuse between Gradle runs only when Testcontainers reuse is enabled on the developer machine. Reuse is optional; the singleton already prevents multiple containers inside one test JVM.

### Database isolation

Repository and transaction tests extend [`PersistenceTest.kt`](../ktor/src/test/kotlin/me/gpipi/support/PersistenceTest.kt):

```kotlin
class CategoryRepositoryTest : PersistenceTest() {
    private val repo = CategoryRepository()

    @Test
    fun `create persists a new budget line`() = runBlocking {
        val id = dbQuery(db) {
            repo.create(
                name = "Monthly Groceries",
                description = "Supermarket and pantry spending",
                period = "MONTHLY",
                amount = 75_000L,
                active = true,
                slackLoggable = true,
            )
        }

        // Assert the committed database state.
    }
}
```

Before each test, `cleanDatabase()` truncates every table in `public` except `flyway_schema_history` using `RESTART IDENTITY CASCADE`. Do not add a hard-coded table list when a migration introduces a table.

The suite deliberately uses truncate-between-tests rather than wrapping tests in a rollback transaction. Production repository calls open their own Exposed transactions through `dbQuery`; allowing them to commit exercises the real transaction and concurrency boundaries.

### Full application tests

[`TestApp.kt`](../ktor/src/test/kotlin/me/gpipi/support/TestApp.kt) provides `configureWithTestDb()`. It loads the real `application.conf` module chain while replacing:

- database properties with the shared Testcontainers connection;
- Slack, OpenRouter, and session secrets with non-sensitive test values;
- the web and CORS origins with test origins.

Use it when the behavior depends on the production composition root:

```kotlin
@Test
fun `readiness answers when the database is available`() = testApplication {
    configureWithTestDb()

    assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
}
```

This style starts Postgres. For an isolated route contract, install only the required plugins and route, then inject mocked collaborators:

```kotlin
@Test
fun `GET budgets returns the service result`() = testApplication {
    val service = mockk<BudgetService>()
    coEvery { service.listBudgets() } returns emptyList()

    application {
        configureSerialization()
        routing { budgetApiRoutes(service) }
    }

    assertEquals(HttpStatusCode.OK, client.get("/api/budgets").status)
}
```

An isolated `testApplication` normally does not need Docker.

### External boundaries

Automated tests must not call Slack or OpenRouter over the network.

- Mock `OpenRouterClient`, `SlackClient`, or the next internal collaborator when testing orchestration.
- Use Ktor's test server/client when testing HTTP client serialization.
- Keep Slack request-signature fixtures local and sign the exact raw request body.
- Inject `Clock`, `SecureRandom`, or another deterministic source when behavior depends on time or randomness.

The current suite covers, among other things:

- Slack signature verification, route acknowledgement, retry handling, and interaction dispatch;
- inbound deduplication and atomic expense confirmation;
- extraction schema, prompt construction, and category-catalog invalidation;
- auth nonce hashing, expiry, single use, and concurrent redemption;
- signed session cookies, idle renewal, absolute expiry, and tamper rejection;
- budget CRUD, Tokyo date defaults, spend-versus-cap bucketing, and auth guards.

## Frontend component tests

Vitest finds `src/**/*.test.{js,jsx}` using the jsdom environment configured in [`vite.config.js`](../web-app/vite.config.js). [`src/test-setup.js`](../web-app/src/test-setup.js) installs jest-dom matchers and performs Testing Library cleanup after every test.

Prefer queries that match how a user or assistive technology finds the control:

```jsx
expect(screen.getByRole('button', { name: 'Review budget line' })).toBeEnabled()
```

Use:

- `renderWithProviders` for the application theme, router, and query client;
- `userEvent` for complete interactions;
- mocked API modules or query responses for network states;
- fake or controlled timers only when the behavior itself is time-based.

Component tests should cover loading, error, empty, successful, and destructive-confirmation states where the component supports them.

Do not use jsdom to prove pixel geometry, CSS transition progress, focus after a real browser animation, or responsive breakpoint layout. Those belong in Playwright.

## Playwright browser tests

[`playwright.config.js`](../web-app/playwright.config.js) runs headless Firefox with the Pixel 7 device profile by default. Individual tests resize the viewport when they need to prove tablet or desktop behavior.

The suite automatically starts:

- [`fake-api.mjs`](../web-app/tests/e2e/fake-api.mjs), which implements the authenticated budget and activity API contract;
- the Vite development server with `VITE_API_URL=http://127.0.0.1:18080`.

Browser tests currently cover:

- mobile drawers and wider-screen dialogs/tables;
- budget creation, editing, deactivation, and spend-versus-cap presentation;
- navigation and contextual page actions;
- animation duration and intermediate motion;
- tactile pressed states;
- focus restoration;
- reduced-motion behavior.

When the frontend API contract changes, update the fake API in the same change. Keep the fake deterministic and limited to behavior needed by browser tests.

Playwright retains screenshots only on failure and traces for failed tests under `web-app/test-results`.

## Focused commands

Run one backend class:

```bash
cd ktor
./gradlew test --tests '*AuthServiceTest*'
```

Run backend tests matching a method name:

```bash
cd ktor
./gradlew test --tests '*AuthServiceTest*minted nonce can only be redeemed once*'
```

Run one frontend test file:

```bash
cd web-app
npm test -- src/budgets/__tests__/BudgetsPage.test.jsx
```

Run Vitest in watch mode:

```bash
cd web-app
npx vitest
```

Run one Playwright file:

```bash
cd web-app
npm run test:e2e -- tests/e2e/budget-management.spec.js
```

Run matching Playwright tests or show the browser:

```bash
npm run test:e2e -- --grep "launcher"
npm run test:e2e -- --headed
```

Inspect a retained trace:

```bash
npx playwright show-trace test-results/<test-directory>/trace.zip
```

## Adding or changing behavior

For backend changes:

1. Put pure rules in a unit test first.
2. Use `PersistenceTest` when correctness depends on SQL, a constraint, locking, transaction rollback, or concurrency.
3. Add a route test for the public HTTP contract and authentication guard.
4. Use a fixed `Clock` for boundary dates and expiration.
5. Assert both the returned result and committed state for atomic operations.

For frontend changes:

1. Add or update component tests for state and interaction behavior.
2. Add Playwright coverage when the acceptance criterion concerns responsive presentation, animation, geometry, or browser focus.
3. Keep selectors accessible; add test-only selectors only for otherwise-unobservable animation internals.
4. Update `fake-api.mjs` when the API response or mutation contract changes.

Do not weaken a production constraint or add a test-only production branch merely to make a test easier.

## Troubleshooting

### Backend cannot start Postgres

Confirm Docker is running:

```bash
docker info
```

The full backend suite will fail early if Testcontainers cannot reach the Docker daemon. A focused pure-unit class can still run without Docker if it does not extend `PersistenceTest` or boot the full application.

### A persistence test passes alone but fails in the suite

Check for:

- coroutines still running after the test returns;
- state stored outside Postgres in a singleton or cache;
- a test that bypasses `PersistenceTest`;
- assumptions about database row order without an explicit `ORDER BY`.

Database rows are truncated before every `PersistenceTest`, but in-memory state must be reset by the owning test.

### Playwright cannot launch Firefox

Install the configured browser:

```bash
cd web-app
npx playwright install firefox
```

On Linux, missing system libraries may require:

```bash
npx playwright install --with-deps firefox
```

### Playwright reports an occupied port

The suite requires ports `4173` and `18080`, with `reuseExistingServer` disabled. Stop the process using either port before retrying.

### Browser and component tests disagree

Treat Playwright as the authority for layout, CSS motion, media queries, and focus timing. Treat Vitest as the faster authority for application state, rendered semantics, and interaction branching.

## Completion checklist

Before considering a change complete, run the checks relevant to its scope:

- Backend behavior: `cd ktor && ./gradlew test`
- Frontend behavior: `cd web-app && npm test`
- Frontend static quality: `cd web-app && npm run lint && npm run build`
- Responsive or animated UI: `cd web-app && npm run test:e2e`

Before deployment, run all four groups.
