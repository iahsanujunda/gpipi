# Household Budget Bot — Iteration Plan

_Slack-Native Expense Capture · Stack: Slack · Ktor · Supabase (Postgres) via Exposed (JDBC) · Flyway · OpenRouter (Qwen3) · Render (Starter web service + static site)_

---

## Overview

A private Slack workspace for the household. Either spouse posts a natural-language expense — `@ai just paid 1500jpy for ramen` — and the bot records the amount, assigns the correct budget category, and timestamps it. No spreadsheet, no nightly recap.

The core loop: **Slack message → Ktor acks within 3s → single LLM extraction (retrieval-then-generate) → confidence gate → auto-record or editable confirmation card → atomic write + hint upsert → the feedback loop improves the next categorization.**

Categorization is the only part that needs a model. The amount is trivially parseable; the value is in mapping messy JP/EN merchant text (`ito yokado`, `イトーヨーカドー`, `conbini`, `セブン`) to the right envelope, and in disambiguating cases like ¥510 at a konbini (weekly food) vs ¥7,500 at Tokyu Store (monthly groceries).

### Iteration Order Rationale

| # | Scope | Why This Order |
|---|-------|----------------|
| 1 | Walking Skeleton — Slack signing + async ack (Ktor) | Prove the 3s-ack/cold-start reality before anything else depends on it |
| 2 | Thinnest Extraction — one LLM call → Supabase → text reply | Kills the Google Sheet on day one; starts generating real messages |
| 3 | Config-Driven Categories | Move categories to DB, inject descriptions — enables the konbini-vs-Tokyu disambiguation |
| 4 | Inline Confirmation Card + Labeled Events | One-tap correction; every confirm/correct becomes a labeled pair |
| 5 | Merchant Hints Feedback Loop | Only possible once iter 4 has been logging confirmations |
| 6 | Confidence Gate | "High confidence" is meaningless until hints exist and the real distribution is visible |
| 7 | Frontend — Render static site | Deferrable: budgets editable directly in Supabase through iters 3–6 |
| 8 | Query Path (post-MVP) | Read side; deliberately last, deterministic SQL over agent loops |

**Capture-first, not schema-first.** Unlike a seed-driven app, the data this system learns from (merchant→category hints) is *manufactured by dogfooding*. Stand up the loop that produces confirmed labels before building the thing that consumes them — same instinct as the PokeOps silver-label path.

---

## Message Extraction Reference

### Example Inputs → Extracted Record

| Input | `amount` | `currency` | `merchant` | `category` | `confidence` |
|-------|----------|------------|-----------|------------|--------------|
| `just paid 1500jpy for ramen` | 1500 | JPY | null | Eating Out | high |
| `groceries at ito yokado, spent 7000jpy` | 7000 | JPY | Ito Yokado | Monthly Groceries | high |
| `spent 510 in conbini` | 510 | JPY | conbini | Convenience Store | high |
| `7500 tokyu store` | 7500 | JPY | Tokyu Store | Monthly Groceries | medium |
| `2000 for something` | 2000 | JPY | null | (best guess) | low |

The konbini-vs-Tokyu split falls out cleanly when the model has **category descriptions**, not bare labels, plus the amount as a strong prior. `¥510` + konbini and `¥7,500` + supermarket separate on both signals.

### Inbound Message Status Reference

Every `@ai` message is captured to `inbound_message` (see 2.1) — including the ones that fail. The failures are the point: they're the debugging corpus for prompt tuning (iter 5–6) and the replay set for evaluating a new prompt or model against real historical inputs. The `status` field records the outcome.

| Status | Meaning | Links to expense? |
|--------|---------|-------------------|
| `RECEIVED` | Landed, not yet processed | no |
| `RECORDED` | Extracted and written (auto or confirmed) | yes |
| `FAILED_PARSE` | LLM returned invalid/unusable output — raw text kept for debugging | no |
| `NON_EXPENSE` | Parsed as a query or chatter (once iter 8 intent split exists) | no |
| `SKIPPED` | Duplicate retry — row already existed, never reprocessed | — |

`FAILED_PARSE` rows are the goldmine for prompt work; `NON_EXPENSE` rows become the training set for the iter-8 intent classifier.

### Confidence Gate Reference (Iteration 6)

| Condition | Action | Slack surface |
|-----------|--------|---------------|
| Merchant in `merchant_category_hint` | Auto-record | `Recorded ✓ · tap to edit` (passive) |
| No hint, model `confidence >= 0.8` | Auto-record | `Recorded ✓ · tap to edit` (passive) |
| No hint, `confidence < 0.8` | Require confirm | Editable card, `Confirm` required |
| Unknown merchant, low confidence | Require confirm | Editable card, category dropdown open |

The routine ¥510 conbini spend just records. Only genuinely ambiguous ones stop you.

---

# Iteration 1 — Walking Skeleton: Slack Signing + Async Ack

No DB, no AI. Verify the signing secret, ack within 3s, echo the text back via `chat.postMessage`. The entire point is to hit the cold-start reality immediately and make the hosting decision with real feel.

### 1.1 Architecture

```
Slack Events API
  → POST /slack/events (Ktor)
    → verify X-Slack-Signature (HMAC-SHA256, replay window)
    → handle url_verification challenge (setup only)
    → dedup on event_id (Slack RETRIES on timeout)
    → respond 200 IMMEDIATELY (<3s)
    → applicationScope.launch { handleEvent(...) }   // async, survives response
        → chat.postMessage(echo)
```

### 1.2 Signature Verification

Slack signs every request. Base string is `v0:{timestamp}:{rawBody}`, signed with the signing secret, compared against `X-Slack-Signature`.

```kotlin
fun verifySlackSignature(headers: Headers, rawBody: String, signingSecret: String): Boolean {
    val timestamp = headers["X-Slack-Request-Timestamp"]?.toLongOrNull() ?: return false
    // Replay guard — reject anything older than 5 minutes
    if (abs(Instant.now().epochSecond - timestamp) > 60 * 5) return false

    val basestring = "v0:$timestamp:$rawBody"
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(signingSecret.toByteArray(), "HmacSHA256"))
    }
    val computed = "v0=" + mac.doFinal(basestring.toByteArray()).toHexString()
    val provided = headers["X-Slack-Signature"] ?: return false
    return MessageDigest.isEqual(computed.toByteArray(), provided.toByteArray()) // constant-time
}
```

> Verify against the **raw** request body, before any deserialization. Read the body as text once, verify, then parse.

### 1.3 The Ack Pattern

```kotlin
post("/slack/events") {
    val raw = call.receiveText()
    if (!verifySlackSignature(call.request.headers, raw, signingSecret)) {
        call.respond(HttpStatusCode.Unauthorized); return@post
    }
    val payload = json.decodeFromString<SlackEnvelope>(raw)

    if (payload.type == "url_verification") {          // one-time setup handshake
        call.respondText(payload.challenge!!); return@post
    }
    // Slack retries on any non-200 within 3s — early-return on retries
    if (call.request.headers["X-Slack-Retry-Num"] != null) {
        call.respond(HttpStatusCode.OK); return@post
    }

    call.respond(HttpStatusCode.OK)                    // ACK within 3s — nothing heavy before this line
    applicationScope.launch { handleEvent(payload) }   // app-scoped, NOT the call scope
}
```

> Use an application-scoped `CoroutineScope`, not the request's scope — otherwise the work is cancelled when the response returns.

### 1.4 Hosting Decision (make it here)

Slack disables an event subscription after repeated ack failures. Render **free** web services spin down after 15 min idle with a 30–60s cold start — a household bot used a few times a day is almost always cold, so the `@ai` mention times out and Slack retries or gives up.

| Option | Cost | Verdict |
|--------|------|---------|
| Render Starter (always-on) | $7/mo | Recommended. No cold starts, done. |
| Render Free + UptimeRobot 5-min ping | $0 | Consumes ~744 of 750 free instance-hours/mo; unsupported, flaky. Prototype only. |

> Supabase free Postgres does not pause with daily use (only after ~7 days idle), so it's fine. Render's 30-day free-DB deletion is irrelevant — the DB is on Supabase.

### 1.5 Socket Mode Note

Socket Mode (WebSocket initiated from the server) avoids needing a public URL, but still requires a non-sleeping process — it does **not** solve cold starts. HTTP Events + Starter is simpler here. Revisit Socket Mode only if you want to drop the public endpoint.

### Definition of Done

- [ ] Slack app created, event subscription pointed at `/slack/events`
- [ ] `url_verification` challenge passes in Slack app config
- [ ] Signature verification rejects bad signatures and stale timestamps
- [ ] Endpoint acks 200 within 3s under a cold-start test
- [ ] Async echo posts back via `chat.postMessage`
- [ ] Retry requests (`X-Slack-Retry-Num`) short-circuit to 200
- [ ] Hosting tier chosen and deployed (Starter recommended)

---

# Iteration 2 — Thinnest Extraction: One LLM Call → Supabase → Text Reply

The moment this ships, the nightly recap dies. Categories are hardcoded in the prompt for now — DB-driven categories come in iteration 3.

### 2.0 Persistence Setup (Exposed + Flyway)

Dependencies (add to `build.gradle.kts` — confirm current version + the `v1` coordinates on the Exposed docs, which moved to a `v1` package namespace):

```
org.jetbrains.exposed:exposed-core
org.jetbrains.exposed:exposed-jdbc          # blocking JDBC path (not R2DBC)
org.jetbrains.exposed:exposed-java-time     # timestamp column support
org.postgresql:postgresql                   # JDBC driver
com.zaxxer:HikariCP                          # connection pool
org.flywaydb:flyway-core                     # migrations
org.flywaydb:flyway-database-postgresql
```

**Schema source of truth = Flyway SQL migrations.** Exposed `Table` objects *mirror* the migrations by hand — they're the query mapping, not the schema authority. This is the one ergonomic cost vs jOOQ (which generated the Kotlin from the schema); at this schema size it's trivial and gives full control. Do **not** use `SchemaUtils.create`/`createMissingTablesAndColumns` as your prod schema mechanism — it's convenient for a throwaway local DB but not migration-safe. Flyway owns DDL; Exposed owns queries.

Connection, once at startup:

```kotlin
val ds = HikariDataSource(HikariConfig().apply {
    jdbcUrl = config.property("db.url").getString()   // from env: DATABASE_URL
    maximumPoolSize = 3
    // Supabase pooler (Supavisor, transaction mode) — disable JDBC prepared-statement
    // caching or use the session-mode port, same as the janken-elo setup.
})
Flyway.configure().dataSource(ds).load().migrate()     // migrations run before first query
Database.connect(ds)                                    // Exposed binds to the pool
```

### 2.1 Schema (initial)

Flyway migration `V1__inbound_and_expense.sql`:

```sql
-- Capture EVERY @ai message here, including failures. This is the debugging
-- corpus, the replay set, and the dedup key — all in one table.
create table inbound_message (
    id          uuid primary key default gen_random_uuid(),
    event_id    text        not null unique,   -- Slack event_id; also the dedup key
    user_id     text        not null,
    channel_id  text        not null,
    text        text,                           -- raw @ai message; nullable so it can be TTL'd (appendix)
    slack_ts    text        not null,           -- Slack's message timestamp
    status      text        not null default 'RECEIVED',  -- see Inbound Message Status Reference
    fail_reason text,
    received_at timestamptz not null default now()
);

create table expense (
    id                  uuid primary key default gen_random_uuid(),
    inbound_message_id  uuid        not null references inbound_message(id),  -- source of raw text
    user_id             text        not null,          -- Slack user id
    amount              bigint      not null,          -- integer JPY (no minor unit); multi-currency later
    currency            text        not null default 'JPY',
    category            text        not null,          -- free text for now; FK in iter 3
    merchant            text,
    note                text,
    spent_at            timestamptz not null default now(),
    source              text        not null default 'SLACK',
    created_at          timestamptz not null default now()
);
```

Exposed `Table` objects mirroring the above (query mapping):

```kotlin
object InboundMessages : UUIDTable("inbound_message") {
    val eventId     = text("event_id").uniqueIndex()
    val userId      = text("user_id")
    val channelId   = text("channel_id")
    val text        = text("text").nullable()
    val slackTs     = text("slack_ts")
    val status      = text("status").default("RECEIVED")
    val failReason  = text("fail_reason").nullable()
    val receivedAt  = timestamp("received_at").defaultExpression(CurrentTimestamp)
}

object Expenses : UUIDTable("expense") {
    val inboundMessageId = reference("inbound_message_id", InboundMessages)
    val userId    = text("user_id")
    val amount    = long("amount")
    val currency  = text("currency").default("JPY")
    val category  = text("category")
    val merchant  = text("merchant").nullable()
    val note      = text("note").nullable()
    val spentAt   = timestamp("spent_at").defaultExpression(CurrentTimestamp)
    val source    = text("source").default("SLACK")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
```

> Amount stored as integer JPY. If multi-currency is ever needed, migrate to `amount_minor bigint` + per-currency minor-unit handling. Not now.
>
> **`inbound_message` is the single source of truth for raw text** — `expense` (and later `categorization_event`) reference it by FK rather than copying the string around. Storing every message, not just successful ones, is what makes prompt tuning and model swaps measurable later: re-run the stored inputs against a new pipeline and compare.
>
> **Dedup and capture are the same write**, and Exposed makes this a one-liner: `insertIgnoreAndGetId { }` returns `null` when the unique `event_id` already exists (a Slack retry), so a null return *is* the skip signal — no separate existence check.
>
> ```kotlin
> // status effectively SKIPPED on a retry (null id → return early)
> val id: EntityID<UUID>? = InboundMessages.insertIgnoreAndGetId {
>     it[eventId]   = e.eventId
>     it[userId]    = e.user
>     it[channelId] = e.channel
>     it[text]      = e.text
>     it[slackTs]   = e.ts
> }
> ```
> Same idempotency discipline as PokeOps `processed_mutation_ids`, now doing double duty as the audit log.

### 2.2 Extraction Schema (OpenRouter `json_schema`, strict)

```json
{
  "type": "object",
  "properties": {
    "amount":     { "type": "integer" },
    "currency":   { "type": "string", "enum": ["JPY"] },
    "merchant":   { "type": ["string", "null"] },
    "category":   { "type": "string" },
    "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
    "note":       { "type": ["string", "null"] }
  },
  "required": ["amount", "currency", "category", "confidence"],
  "additionalProperties": false
}
```

### 2.3 OpenRouter Call (Ktor client)

```kotlin
val body = buildJsonObject {
    put("model", "qwen/qwen3-instruct")        // pick exact current slug from openrouter.ai/models
    putJsonArray("messages") {
        addJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT) }
        addJsonObject { put("role", "user"); put("content", slackText) }
    }
    putJsonObject("response_format") {
        put("type", "json_schema")
        putJsonObject("json_schema") { put("strict", true); put("schema", EXTRACTION_SCHEMA) }
    }
    putJsonArray("plugins") {                  // JSON repair safety net
        addJsonObject { put("id", "response-healing") }
    }
    put("temperature", 0)
}
```

> **Model:** Qwen3 leads for CJK among the cheap OpenRouter options and matches your local harness conventions. Use `temperature: 0` and, if the slug is a reasoning variant, disable thinking — extraction gains nothing from chain-of-thought. Prefer a **paid** variant over `:free` for a service you rely on daily (`:free` is rate-limited and can be pulled without notice; your volume is trivial either way). Validate the parsed JSON server-side regardless of strict mode.

### 2.4 System Prompt (categories hardcoded — temporary)

```
You extract a single household expense from a short casual message (English, Japanese, or mixed).

Return JSON matching the schema. Rules:
- amount: integer yen. "1500jpy", "¥1,500", "1500円" all → 1500.
- merchant: the shop/place if named (keep original form: "Ito Yokado", "セブン"), else null.
- category: choose exactly one from the list below by best fit.
- confidence: 0-1. Lower it when the merchant is unknown or the category is a guess.
- note: anything the user added that isn't amount/merchant/category, else null.

Categories:
- Eating Out — restaurants, cafes, ramen, izakaya, takeout meals
- Convenience Store — konbini, small quick purchases (Seven, Lawson, FamilyMart)
- Monthly Groceries — supermarket runs, bulk shopping (Ito Yokado, Tokyu Store, OK)
- Transport — trains, buses, taxi, IC top-ups
- Household — daily goods, drugstore, home supplies
- Other — anything that fits nothing above
```

### 2.5 Reply

All DB access goes through one helper so the transaction style is uniform — this matters because Exposed forbids mixing blocking `transaction {}` and suspended transactions in the same path:

```kotlin
// The single DB entry point. newSuspendedTransaction dispatches to IO and manages
// Exposed's thread-local correctly across suspension. Repos do raw table ops INSIDE this.
suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
```

```kotlin
suspend fun handleEvent(e: SlackEnvelope) {
    // Capture + dedup in one write. Null id back = retry of an already-seen event.
    val msgId = dbQuery { inboundRepo.captureOrSkip(e) } ?: return   // insertIgnoreAndGetId → null on conflict

    val x = try {
        openRouter.extract(e.text)                                    // suspend, non-blocking — outside the tx
    } catch (ex: ExtractionException) {
        dbQuery { inboundRepo.markFailed(msgId, reason = ex.message) } // status = FAILED_PARSE, text kept
        slack.postMessage(e.channel, "Couldn't read that one — mind rephrasing?")
        return
    }

    dbQuery {                                                         // one transaction for the write pair
        expenseRepo.insert(x, inboundMessageId = msgId, userId = e.user)
        inboundRepo.markRecorded(msgId)                               // status = RECORDED
    }
    slack.postMessage(e.channel, "Recorded ✓  ¥${x.amount} · ${x.category}")
}
```

> Note the LLM call sits **outside** any `dbQuery` block — never hold a DB transaction open across a network call to OpenRouter. Open the transaction only around the actual writes.

> The `catch` is why capture-everything pays off immediately: a `FAILED_PARSE` row keeps the exact input that broke, so you can inspect it while tuning the prompt instead of guessing what the user typed.

### Definition of Done

- [ ] `inbound_message` + `expense` tables created in Supabase
- [ ] Every `@ai` message captured to `inbound_message` before processing
- [ ] Capture + dedup is a single `insertIgnoreAndGetId` write (null id on conflict → skip); retries skip
- [ ] OpenRouter call returns schema-valid JSON for the reference inputs
- [ ] JP, EN, and mixed input all extract correctly (test `イトーヨーカドー`, `conbini`, `¥1,500`)
- [ ] Expense row written referencing `inbound_message_id`, with amount/category/merchant/spent_at
- [ ] Failed extractions mark `status = FAILED_PARSE` with `fail_reason` and keep the raw text
- [ ] Successful extractions mark `status = RECORDED`
- [ ] Plain-text `Recorded ✓` confirmation posted
- [ ] Both spouses can log an expense end-to-end from mobile Slack

---

# Iteration 3 — Config-Driven Categories

Categories and their budget envelopes move to the DB. Descriptions are injected into the prompt so categorization is config-driven, not baked into a string — this is what makes konbini-vs-Tokyu disambiguation tunable.

### 3.1 Schema

```sql
create table budget_envelope (
    id          uuid primary key default gen_random_uuid(),
    name        text        not null,          -- "Weekly Food", "Monthly Groceries"
    period      text        not null,          -- WEEKLY | MONTHLY
    amount      bigint      not null,          -- budget cap, integer JPY
    created_at  timestamptz not null default now()
);

create table category (
    id          uuid primary key default gen_random_uuid(),
    envelope_id uuid        not null references budget_envelope(id),
    name        text        not null unique,   -- "Eating Out", "Convenience Store"
    description text        not null,          -- LLM disambiguation hint
    active      boolean     not null default true,
    created_at  timestamptz not null default now()
);

-- expense.category becomes a FK
alter table expense add column category_id uuid references category(id);
-- backfill from the old text column, then drop it once verified
```

### 3.2 Retrieval-then-Generate (NOT agentic)

One cheap deterministic query fetches active categories + descriptions, injected into the prompt before a single extraction call. No loop, no model-driven tool selection.

```kotlin
suspend fun buildSystemPrompt(): String {
    val cats = categoryRepo.findActive()   // one query, cached ~a few minutes
    val list = cats.joinToString("\n") { "- ${it.name} — ${it.description}" }
    return SYSTEM_PROMPT_TEMPLATE.replace("{{CATEGORIES}}", list)
}
```

The `category` field in the schema can be tightened to an `enum` built from the active category names, so the model can only return a valid category.

### 3.3 Envelope Periods

Two envelopes can hold overlapping intent but different periods — that's the point. "Weekly Food" (konbini, quick meals) resets weekly; "Monthly Groceries" (supermarket) resets monthly. The category description carries the signal; the amount reinforces it.

### Definition of Done

- [ ] `budget_envelope` + `category` tables created and seeded
- [ ] `expense.category_id` FK populated; old text column dropped after backfill
- [ ] Active categories + descriptions injected into the prompt at request time
- [ ] Category list cached, invalidated on change
- [ ] Adding/editing a category in Supabase changes categorization with no redeploy
- [ ] Konbini ¥510 → Convenience Store, Tokyu Store ¥7,500 → Monthly Groceries, verified

---

# Iteration 4 — Inline Confirmation Card + Labeled Events

Replace the passive text reply with an editable Block Kit card. This is where correction becomes one tap and — critically — where every confirm/correct becomes a labeled pair that later iterations depend on.

### 4.1 Schema — the labeled dataset

```sql
create table categorization_event (
    id                    uuid primary key default gen_random_uuid(),
    inbound_message_id    uuid        not null references inbound_message(id),  -- raw text lives here
    expense_id            uuid        references expense(id),
    predicted_category_id uuid        references category(id),
    final_category_id     uuid        references category(id),
    was_corrected         boolean     not null,
    confidence            numeric,
    model                 text,
    created_at            timestamptz not null default now()
);
```

Every confirm writes one row. `was_corrected = predicted != final`. Raw text isn't copied here — join through `inbound_message_id` when you need it. This is the silver-label set — it feeds hints (iter 5), the confidence gate (iter 6), and any future eval or fine-tune.

### 4.2 Block Kit Card (inline `static_select` + Confirm)

```
¥510 · [ Convenience Store ▼ ]   [ Confirm ]
```

```json
{
  "blocks": [
    { "type": "section", "text": { "type": "mrkdwn", "text": "*¥510* · conbini" } },
    { "type": "actions", "block_id": "expense_confirm", "elements": [
      { "type": "static_select", "action_id": "category_select",
        "initial_option": { "text": { "type": "plain_text", "text": "Convenience Store" }, "value": "<cat_uuid>" },
        "options": [ /* active categories */ ] },
      { "type": "button", "action_id": "confirm_expense",
        "text": { "type": "plain_text", "text": "Confirm" }, "style": "primary",
        "value": "<draft_id>" }
    ]}
  ]
}
```

Changing the dropdown fires `block_actions`; clicking Confirm fires `block_actions` again with the (possibly changed) selection. One tap for the common case. Use a **modal** (`views.open` → `view_submission`) only later if you want to edit amount + merchant + note together — overkill for fixing just a category.

> Draft state (the extracted-but-unconfirmed expense) can live in a short-lived `expense_draft` row or be encoded in the button `value` — a draft row is cleaner and survives restarts.

### 4.3 Atomic Confirm Write

The expense insert, the `categorization_event` insert, the `inbound_message` status transition to `RECORDED`, and (iter 5) the hint upsert are **one flat transaction**. Only after it returns do you call `chat.postMessage` to confirm. (Capture + dedup already happened up front in iter 2 — the confirm step just flips status and writes the labeled event.)

```kotlin
suspend fun onConfirm(draft: ExpenseDraft, finalCategoryId: UUID) {
    dbQuery {                                          // single flat newSuspendedTransaction — do NOT nest
        val expenseId = expenseRepo.insert(draft, finalCategoryId)
        categorizationEventRepo.insert(draft, expenseId,
                                       predicted = draft.predictedCategoryId,
                                       final = finalCategoryId)
        inboundRepo.markRecorded(draft.inboundMessageId)   // status = RECORDED
    }
    // Post to Slack AFTER the transaction block returns.
    slack.postMessage(draft.channel, "Recorded ✓  ¥${draft.amount} · ${categoryName(finalCategoryId)}")
}
```

> **Exposed transaction discipline (replaces the old Spring `@JooqTest`/`AFTER_COMMIT` concern — that machinery doesn't exist here).** Two footguns to avoid, both of which would bite exactly at this multi-step write:
> - **Keep it one flat transaction.** Don't nest suspended transactions — an inner `newSuspendedTransaction` may *not* roll back when the outer block throws, so a nested design can silently half-commit. All four steps live directly inside the single `dbQuery` block.
> - **Don't mix styles.** Every DB call in a request path goes through `dbQuery` (suspended). Mixing a blocking `transaction {}` with suspended ones causes "connection is closed" errors.
>
> The Slack side-effect stays *outside* the transaction: post only after `dbQuery { }` returns successfully. There's no event bus or commit-phase listener to route through — the boundary is just "DB work inside, network side-effect after."

### Definition of Done

- [ ] `categorization_event` table created, referencing `inbound_message_id`
- [ ] Extraction posts an editable card (category dropdown pre-filled with the prediction)
- [ ] Changing the dropdown + Confirm records the corrected category
- [ ] Confirm with no change records the prediction as-is
- [ ] Expense + categorization_event + `inbound_message` status flip written in one transaction
- [ ] Slack confirmation posted only after commit returns
- [ ] `was_corrected` correctly reflects prediction vs final
- [ ] Card updates in place (or is replaced) on confirm — no dangling draft

---

# Iteration 5 — Merchant Hints Feedback Loop

Now that confirmations are flowing, learn from them. A household-shared `merchant_category_hint` table upserts on every confirm; recent hints inject as few-shot examples so frequented merchants stop being guessed.

### 5.1 Schema

```sql
create table merchant_category_hint (
    id                  uuid primary key default gen_random_uuid(),
    normalized_merchant text        not null unique,   -- lowercased, trimmed
    category_id         uuid        not null references category(id),
    confirm_count       int         not null default 1,
    last_confirmed_at   timestamptz not null default now()
);
```

### 5.2 Upsert on Confirm (inside the same `dbQuery` block as iter 4)

Exposed has a native `upsert` with an `onUpdate` clause — no raw SQL needed:

```kotlin
// Called inside onConfirm's dbQuery { } — same transaction as the expense + event writes.
fun upsertHint(merchant: String?, categoryId: UUID) {
    val norm = merchant?.lowercase()?.trim() ?: return
    MerchantCategoryHints.upsert(
        MerchantCategoryHints.normalizedMerchant,      // conflict key
        onUpdate = {
            it[MerchantCategoryHints.categoryId] = categoryId
            it[MerchantCategoryHints.confirmCount] = MerchantCategoryHints.confirmCount + 1
            it[MerchantCategoryHints.lastConfirmedAt] = CurrentTimestamp
        }
    ) {
        it[normalizedMerchant] = norm
        it[MerchantCategoryHints.categoryId] = categoryId
    }
}
```

Since it runs inside the iter-4 `dbQuery` block, the hint upsert commits atomically with the expense and the labeled event — a confirmed categorization and its learned mapping are never out of sync.

### 5.3 Injection

Fetch the most-confirmed / most-recent hints and prepend as few-shot lines:

```
Known merchant → category mappings (from past confirmations):
- ito yokado → Monthly Groceries
- tokyu store → Monthly Groceries
- seven → Convenience Store
```

Over time "Tokyu Store → Monthly Groceries" is a learned mapping, not a guess. Same pattern as PokeOps human-confirmed labels reducing future model error — still one call, still deterministic assembly.

### Definition of Done

- [ ] `merchant_category_hint` table created
- [ ] Hint upserted on every confirm, inside the confirm transaction
- [ ] `confirm_count` increments; `last_confirmed_at` updated on repeat merchants
- [ ] Top/recent hints injected as few-shot into the extraction prompt
- [ ] A previously-corrected merchant is predicted correctly on next mention
- [ ] Verified: correct Tokyu Store once → next Tokyu Store message predicts Monthly Groceries

---

# Iteration 6 — Confidence Gate

Remove the tap from cases that don't need it. Deliberately last of the bot work: "high confidence" only becomes meaningful once hints exist and the real distribution of confidently-correct predictions is visible.

### 6.1 Gate Logic

```kotlin
suspend fun route(x: Extraction, merchant: String?): Disposition {
    val hint = merchant?.let { hintRepo.find(it.lowercase().trim()) }
    return when {
        hint != null            -> Disposition.AutoRecord(hint.categoryId)  // known merchant
        x.confidence >= 0.80    -> Disposition.AutoRecord(x.categoryId)      // model is sure
        else                    -> Disposition.Confirm(x)                    // ask
    }
}
```

- **AutoRecord** → write immediately, post passive `Recorded ✓ ¥510 · Convenience Store · tap to edit` (an overflow/button that opens the same category dropdown if wrong).
- **Confirm** → the iter-4 card with the dropdown open.

### 6.2 Tuning

Threshold starts at `0.80`; adjust against `categorization_event` after real usage — check where `was_corrected = true` clusters by confidence bucket, and set the gate just above the band where auto-records were reliably right. Auto-records that later get edited (via the passive "tap to edit") are also labeled events, so the loop keeps improving even for the silent path.

### Definition of Done

- [ ] Known-merchant and high-confidence paths auto-record with a passive, editable confirmation
- [ ] Low-confidence / unknown-merchant path shows the mandatory card
- [ ] "Tap to edit" on an auto-recorded expense reopens the dropdown and writes a correction
- [ ] Corrections from the silent path still write `categorization_event` + upsert hints
- [ ] Threshold tuned against real confidence-vs-correction data
- [ ] Routine konbini spend records with zero taps; ambiguous ones still prompt

---

# Iteration 7 — Frontend (Render Static Site)

Deferrable — budgets and categories are editable directly in Supabase through iters 3–6. Build this once the household wants to manage things without touching the DB.

### 7.1 Scope

- **Budget config:** create/edit envelopes and categories (name, description, period, cap)
- **Tracking:** spend vs cap per envelope, current period, category breakdown
- **Manual ops:** add/edit/delete an expense, correct a category, view recent
- **Hint review:** see learned merchant→category mappings, override a bad one

### 7.2 Stack Notes

- React on Render static site (free, unlimited)
- Talks to the same Ktor API (add `/api/budgets`, `/api/expenses`, `/api/hints` read/write routes)
- Auth: simplest is a shared household passphrase or Supabase Auth (2 users); don't over-build

### Definition of Done

- [ ] Static site deployed on Render, CORS configured for the Ktor API
- [ ] Envelope + category CRUD wired to the API
- [ ] Spend-vs-cap view per envelope for the current period
- [ ] Manual expense add/edit/delete
- [ ] Merchant hint list with override
- [ ] Category description edits reflected in the next categorization (no redeploy)

---

# Iteration 8 — Query Path (Post-MVP)

The read side. Kept last and deliberately **not** agentic.

### 8.1 Intent Split

A `@ai` message is either a **log** (default, everything so far) or a **query** ("how much on dining this month?"). A cheap intent classifier (or a lightweight keyword/regex pre-check) routes it.

### 8.2 Deterministic SQL Over Agent Loops

The common questions — spend by category this period, remaining in an envelope, biggest expenses this week — are a handful of **predefined parameterized queries**, not a text-to-SQL agent. The LLM's only job is mapping the question to one of the known query templates + extracting parameters (category name, period). Deterministic, safe, cheap.

Reserve genuine text-to-SQL (read-only role, LIMIT-capped) for the long tail, if ever. No agentic execution loop is warranted for a two-person budget.

### Definition of Done

- [ ] Intent classifier splits log vs query (noise gate unnecessary for a 2-person workspace)
- [ ] Predefined query templates for the top ~5 questions
- [ ] LLM maps question → template + params; Ktor runs parameterized SQL
- [ ] Results posted as a readable Slack message (Block Kit for tables if useful)
- [ ] No write path exposed to the query role

---

## Appendix — Cross-Cutting Notes

### Idempotency
Slack retries on any non-200 within 3s and includes `X-Slack-Retry-Num`. Dedup on `inbound_message.event_id` via Exposed's `insertIgnoreAndGetId { }` at capture time — a `null` return means the unique `event_id` already exists (a retry), so skip. The header check in iter 1 is a fast early-out; the DB unique constraint is the real guarantee. Same discipline as PokeOps `processed_mutation_ids` / `SELECT FOR UPDATE`.

### Message capture (store everything)
Every `@ai` message is persisted to `inbound_message`, including failures. Rationale: `FAILED_PARSE` rows are the debugging corpus for prompt tuning; the full set is a replay corpus for evaluating a new prompt or model against real historical inputs; `NON_EXPENSE` rows (iter 8) become intent-classifier training data. `inbound_message` is the single source of truth for raw text — `expense` and `categorization_event` reference it by FK rather than duplicating the string.

### Privacy & scope
This is defensible where the corporate equivalent isn't, and it's worth being precise why: the only data subjects are the two consenting account holders, on infrastructure they control, for personal/household use (which Japan's APPI explicitly carves out). Three residual considerations, all cheap:
- **Supabase is a third-party processor** — raw text leaves your control. Acceptable for household budget notes; it's why capture isn't literally free.
- **Scope capture to a dedicated expense channel.** "All `@ai` mentions" only stays clean if the channel is expense-only; a general household channel would persist conversation you didn't mean to keep.
- **The consent property is temporary.** When the child eventually joins the household, they're a data subject who can't consent — keep this scoped to the two adults, or revisit capture at that point.

### Retention (TTL on raw text)
Keep structured `expense` rows forever — that's the value. Null out `inbound_message.text` after ~6 months: long enough for prompt tuning and replay, short enough to avoid sitting on a years-long transcript of household chatter. A nightly Supabase scheduled job (`update inbound_message set text = null, fail_reason = null where received_at < now() - interval '6 months' and text is not null`). Status rows and structure survive; only the free text ages out.

### Persistence & concurrency (Exposed on JDBC)
All DB access goes through one `dbQuery` helper wrapping `newSuspendedTransaction(Dispatchers.IO)`. Two disciplines, both because Exposed's transaction model has sharp edges the Spring version hid:
- **One style everywhere.** Never mix a blocking `transaction {}` with suspended transactions in a request path — it causes "connection is closed" errors. `dbQuery` is the only entry point.
- **Flat, not nested.** A multi-step atomic write (iter 4 confirm) is a *single* `dbQuery` block with all steps inside. Don't nest suspended transactions — an inner one may fail to roll back when the outer throws.
- **No network calls inside a transaction.** LLM/Slack calls happen outside the `dbQuery` block; open the transaction only around the writes. Exposed-JDBC is blocking under the hood, so `Dispatchers.IO` keeps it off the Netty event-loop threads — but at two-person volume this is correctness hygiene, not a performance concern.

### Schema management (Flyway owns DDL, Exposed owns queries)
Flyway SQL migrations are the source of truth; Exposed `Table` objects are hand-written mirrors used only for the query DSL. This is the one ergonomic cost vs jOOQ (which generated the Kotlin from the schema) — trivial at this schema size, and it keeps you in full control of migrations for the append-only ledger. Do not use `SchemaUtils.create*` as the prod schema mechanism; it isn't migration-safe. Test persistence against real Postgres with **Testcontainers** — the Ktor-world replacement for Spring's `@JooqTest` slice, and better, since you're testing real Postgres semantics rather than a mocked slice.

### Transaction / Slack side-effect boundary
DB writes inside a single `dbQuery { }`; `chat.postMessage` only after it returns. There is no event bus, transaction manager, or commit-phase listener in this stack — the Spring `@Transactional`/`AFTER_COMMIT` machinery that made this fiddly simply isn't present. The boundary is just: writes inside the block, network side-effect after it returns.

### Model config (OpenRouter)
`qwen/qwen3-instruct` class (confirm exact slug on openrouter.ai/models — version slugs drift). `temperature: 0`, `response_format: json_schema` strict, `response-healing` plugin as a fallback, thinking disabled if a reasoning variant. Paid variant over `:free` for reliability. Volume (~300–600 calls/mo) makes cost negligible — optimize for CJK handling and JSON reliability, not price.

### Hosting floor
Render Starter $7/mo (always-on) is the realistic floor for clean 3s acks. Supabase free tier is sufficient; it won't pause under daily use.