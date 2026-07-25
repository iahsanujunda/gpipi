# Household Budget Bot — Phase 3: Shared Shopping List

_Slack-native shared list · Stack: Slack (Events + Interactivity) · Ktor · Exposed/Postgres (pgen-generated) · Flyway · OpenRouter_

---

## System context

Phases 1 and 2 built expense capture and a web frontend. Phase 3 adds a **second domain** — a shared household shopping list.

The list is **shared, not per-person**: one household list, either member adds to it, either member marks items bought. There is no per-user scoping anywhere in this phase.

---

## Reference: The Command Dispatcher (already built)

Phase 3 does **not** introduce routing. It already exists, built alongside the web frontend's magic-link command:

```kotlin
interface SlackCommand {
    fun matches(body: String): Boolean            // sync — no I/O
    suspend fun handle(msg: SlackMessage, inboundMessageId: UUID)
}

// SlackEventHandler:
//   capture to inbound_message  →  commands.firstOrNull { it.matches(msg.body) } ?: default
```

`SlackMessage.from()` strips the mention prefix, so `matches()` sees `open web` rather than `<@U0BGP…> open web`. `OpenBudgetCommand` matches on an `open` prefix; `LogExpenseCommand` is the explicit default (`matches` returns `false`, so it is only ever reached by fall-through).

**The architectural constraint that shapes this phase:** `matches` is **not** a suspend function, so it cannot perform I/O. An LLM classifier can never be a `matches()` implementation. Classification, if it happens at all, must live inside a command's `handle()`.

This is the right shape and should be preserved. Keep `matches` synchronous: it makes dispatch a cheap, deterministic, fully-testable string operation, and it means the dispatcher never fans out into a sequence of speculative network calls.

---

## Reference: Two-Tier Routing

The dispatcher establishes a hierarchy worth making explicit, because it inverts the design of an earlier draft of this document:

**Tier 1 — deterministic.** Keyword-matched `SlackCommand`s. Zero latency, zero cost, exact, unit-testable with string literals. Anything with a recognisable trigger word belongs here.

**Tier 2 — classified.** Only messages that match no command reach the default. If the default must distinguish between domains, *that* is where a model call belongs — paid for once, only by genuinely ambiguous natural language.

An earlier draft put an LLM classifier in front of *every* message. That was wrong: `open` will never need a model to be recognised, and paying a round-trip to discover so is waste. **Push everything that can be deterministic into tier 1, and let tier 2 handle only what's left.**

The practical consequence for this phase: the shopping list ships in tier 1 first (iteration 1), and tier-2 classification is a separate, *optional* ergonomic upgrade (iteration 2) whose necessity is discovered through use rather than assumed.

---

## Reference: `COMMAND` Status

Deterministic commands that complete successfully mark their `inbound_message` row `COMMAND`; handled failures become `FAILED_COMMAND` with a diagnostic reason.

Before this, a handled `open` sat at `RECEIVED` forever — but `RECEIVED` means *not yet processed*, and these messages were processed successfully. Without a terminal state, any query for stuck or in-flight messages is polluted by every command ever run, and phase 4's outcome-based label heuristic cannot distinguish "handled a command" from "died mid-processing."

`COMMAND` rather than `NON_EXPENSE` because it is forward-looking: more deterministic commands are expected, and they are a category of their own, not merely an absence of expense-ness.

Updated status vocabulary:

| Status | Meaning |
|--------|---------|
| `RECEIVED` | Landed, not yet processed (genuinely in-flight) |
| `RECORDED` | Extracted and written as an expense |
| `COMMAND` | Handled by a deterministic command (`open`, list operations) |
| `FAILED_COMMAND` | Deterministic command failed — reason retained for diagnosis |
| `FAILED_PARSE` | LLM returned unusable output — raw text kept |
| `NON_EXPENSE` | Classified as neither expense nor a known command |
| `SKIPPED` | Duplicate retry |

---

## Reference: Why Checking a Box, Not Reporting Text

The obvious flow — "we bought milk and the beef" — requires matching free text against existing rows. "The beef" must resolve to "ground beef 1kg"; "the diapers" to "diapers size L for night." That is fuzzy text-to-text matching, and its failure mode is the worst kind: **silently marking the wrong item bought**, with no signal anything went wrong.

An interactive checkbox carries the row's UUID in its `value`. There is nothing to match and nothing to get wrong, and it is faster than typing at a supermarket.

Consequence: there is **no "bought" text command**. Marking bought is exclusively a `block_actions` interaction on the existing `/slack/interactions` route.

---

## Reference: Card Semantics (one-way, re-rendered)

The card's checkbox element contains **pending items only**. Checking one or more fires `block_actions`; the handler marks those rows `BOUGHT` and re-renders in place via `response_url`, moving bought items into a struck-through section below.

- **Marking is one-way by construction.** A bought item is no longer a checkbox option, so it cannot be unchecked. The rule is visible in the card, not hidden in handler logic.
- **The list doesn't shift under a finger.** Items move rather than disappear, so ticking several in a row doesn't cause jumping layout.
- **Progress is legible mid-shop.**

If mis-taps prove annoying, the fix is an explicit undo action — *not* two-way checkbox semantics, which would let a stale card un-buy an item the other member already bought.

**Concurrency.** Slack sends the full set of currently-checked options on every interaction, so a second interaction re-sends items already bought. Marking must be **idempotent**: a conditional update on `status = 'PENDING'` that no-ops otherwise — the same pattern as `expense_draft.consumeIfPending`.

**Staleness.** A card posted before an item was added won't show it. Accepted, not solved; asking again renders fresh.

> **Unvalidated design.** The re-render-per-tick model is untested. Ticking five items on mobile over supermarket wifi means five round trips and five redraws, and it may feel laggy or flicker. The alternative — tick freely, commit with a Done button — is one round trip but loses durability if Slack is closed mid-shop. **Prototype three hardcoded items and tick them in a real store before building the schema around this.** It is half a day and it de-risks the central interaction of the phase.

---

## Iteration Order Rationale

| # | Scope | Why This Order |
|---|-------|----------------|
| 1 | Shopping list as a deterministic command | Ships the entire feature with zero model dependency. Explicit syntax is unambiguous and free |
| 2 | Natural-language routing (**contingent**) | Removes the need to remember syntax. Built only if iteration 1's ergonomics prove annoying in real use |
| 3 | Refinements | Only what real use proves necessary |

Iteration 2 is genuinely optional. If explicit commands turn out to be comfortable, it should never be built — and that is a successful outcome, not a shortfall.

---

# Iteration 1 — Shopping List as a Deterministic Command

The complete list loop, routed entirely by keyword. No classifier, no second model call, no new latency on any path.

### 1.1 Command syntax

A starting point, to be adjusted after a week of use:

| Input | Action |
|-------|--------|
| `list` | Show pending items as an interactive card |
| `need <items>` | Add one or more items |
| `add <items>` | Add (synonym — pick one, or support both) |

`matches()` is prefix-based, not containment-based. Containment would false-positive on `I need to log 500 for lunch`, which must fall through to expense extraction. Prefix matching is the price of determinism, and it is exactly the friction iteration 2 would remove.

Two commands, or one with a sub-verb, is a style choice; two keeps each `matches()` trivially readable.

### 1.2 Architecture

```
"@bot need milk and eggs"
  → capture → ShoppingAddCommand.matches("need milk and eggs") = true
  → extract item list (LLM, structured output)
  → insert N PENDING rows → mark inbound COMMAND
  → reply: "Added: milk, eggs ✓"

"@bot list"
  → capture → ShoppingShowCommand → query PENDING
  → post Block Kit card → mark inbound COMMAND

[member ticks two boxes]
  → block_actions → mark those rows BOUGHT (idempotent) → re-render via response_url
```

Note that `need <items>` still makes **one** LLM call — for *extraction*, not classification. The keyword told us the domain; the model only structures the payload. That is the same division of labour as expense capture.

### 1.3 Schema

```sql
create table shopping_item (
    id                 uuid        primary key default gen_random_uuid(),
    inbound_message_id uuid        not null references inbound_message(id),  -- provenance, as everywhere
    item               text        not null,          -- "milk", "ground beef"
    quantity           text,                          -- "1kg", "2 packs" — free text, not parsed
    note               text,                          -- "size L, for night"
    status             text        not null default 'PENDING',  -- PENDING | BOUGHT
    added_by           text        not null,
    added_at           timestamptz not null default now(),
    bought_by          text,
    bought_at          timestamptz
);
```

Register `shopping_item` in the pgen `tableFilter` allowlist, then regenerate and commit `pgen-spec.json`. Generated tables are plain `Table`, so ids are client-side `UUID.randomUUID()`.

- **`quantity` is free text, deliberately.** "1kg", "2 packs", "a few" — parsing into number+unit buys nothing here and loses information. Displayed, never computed on.
- **`BOUGHT` rows are retained forever.** They are what a future receipt-matcher matches against, and the basis of any "what do we usually buy" view.

### 1.4 Item extraction

One message may add several items, so extraction returns an **array** — the one structural difference from expense extraction, which always returns a single record.

```json
{
  "type": "object",
  "properties": {
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "item":     { "type": "string" },
          "quantity": { "type": ["string", "null"] },
          "note":     { "type": ["string", "null"] }
        },
        "required": ["item"],
        "additionalProperties": false
      }
    }
  },
  "required": ["items"],
  "additionalProperties": false
}
```

Prompt guidance worth encoding: separate the *item* from its *qualifiers*. "diapers size L for night" → item `diapers`, note `size L, for night`. A clean item name is what makes duplicate detection and future receipt matching tractable.

**Language.** Real messages are Indonesian, English, and mixed (`bayar`, `jajan`, `pasir kucing`), with Japanese merchant names likely. The prompt should name all three explicitly — the current expense prompt says "English, Japanese, or mixed" and omits Indonesian despite Indonesian being the dominant language in practice.

### 1.5 Duplicate handling

Before inserting, check for an existing `PENDING` row with a matching item name (case-insensitive, trimmed). If found, don't insert — reply that it's already on the list. Cheap guard against "did we already add milk?", and it keeps the card short.

### 1.6 The card

```
*Shopping list*
☐ milk
☐ ground beef · 1kg
☐ diapers · size L, for night

~eggs~  ~bread~          ← bought, struck through
```

Checkbox `options` are pending items only; each `value` is the row UUID. Bought items render in a separate section below.

### 1.7 Interaction handling

Extends the existing `/slack/interactions` route and `SlackInteractionHandler` — a new `action_id` alongside `confirm_expense`, not a new route.

### Definition of Done

- [x] `COMMAND` / `FAILED_COMMAND` added; the dispatcher terminalizes `OpenBudgetCommand`
- [ ] Both list commands return terminal outcomes through the same dispatcher path
- [ ] `shopping_item` created, registered in the pgen allowlist, tables regenerated
- [ ] Add command extracts one or many items in ID/EN/JP/mixed
- [ ] Item, quantity, and note separated correctly ("diapers size L for night" → item `diapers`)
- [ ] Adding an already-`PENDING` item creates no duplicate and says so
- [ ] Show command renders pending items as a checkbox card; empty list gets a friendly empty state
- [ ] Ticking marks exactly those rows `BOUGHT` with `bought_by`/`bought_at`
- [ ] Marking is idempotent — a re-sent bought id changes nothing and does not error
- [ ] Card re-renders in place; bought items move to the struck-through section
- [ ] A stale second card cannot un-buy an item
- [ ] `BOUGHT` rows are never deleted
- [ ] Expense capture is bit-for-bit unchanged — no new command matches an expense message
- [ ] **Prototype validated:** ticking multiple items in a real store feels acceptable

---

# Iteration 2 — Natural-Language Routing (contingent)

**Build this only if iteration 1's explicit syntax proves annoying in daily use.** The trigger is real friction — forgetting the prefix, or a message like "we ran out of diapers" silently becoming an expense — not a preference for elegance.

### 2.1 What changes

The default command stops being "extract an expense" and becomes "classify, then delegate":

```
message matches no command
  → default.handle()
      → CLASSIFY (one LLM call, tiny schema)
          EXPENSE   → existing extraction + confirmation card   (unchanged)
          LIST_ADD  → item extraction + insert                  (reuses iteration 1)
          OTHER     → mark NON_EXPENSE, brief acknowledgement
```

Tier 1 is untouched. `open` and the explicit list commands never reach the classifier and never pay for it.

### 2.2 Classification schema

```json
{
  "type": "object",
  "properties": {
    "intent": { "type": "string", "enum": ["EXPENSE", "LIST_ADD", "OTHER"] }
  },
  "required": ["intent"],
  "additionalProperties": false
}
```

**Three classes, not five.** `open` and list-show are keyword-matched and never reach here. Fewer classes means a sharper decision boundary and — relevantly for phase 4 — a much easier learning problem.

### 2.3 Cost

This adds a second sequential LLM call to the expense path, roughly doubling time-to-card. If each call is ~1s, that is ~2s before the confirmation card appears. Measure the current single-call latency before committing; if the classifier is run on a smaller, faster model (it is an easy task on short text), the increment is smaller.

`openrouter.model` should gain a sibling key rather than being reused — the classifier and the extractor need not be the same model.

### 2.4 Instrumentation (required, see phase 4)

Add `intent` and `intent_confidence` columns to `inbound_message` and log the classifier's prediction on every message it sees. Every week without this is a week of unrecoverable training data.

Note that only tier-2 messages produce rows with an intent — keyword-matched commands generate no training data, by design. Phase 4's volume projections must be computed against the *ambiguous subset*, not total message count.

### Definition of Done

- [ ] Classifier returns a valid intent for ID/EN/JP/mixed input
- [ ] Expense messages behave exactly as before (existing handler tests pass unchanged)
- [ ] `OTHER` marks `NON_EXPENSE`, writes no expense, and acknowledges rather than apologising
- [ ] A classifier failure degrades to the expense path rather than dropping the message
- [ ] Classifier model configurable independently of the extraction model
- [ ] `intent` and `intent_confidence` logged on every classified message
- [ ] Added latency measured and recorded

---

# Iteration 3 — Refinements

Deliberately unspecified until iteration 1 is in real use. Candidates:

- **Remove/cancel an item** — a `ShoppingRemoveCommand`, or a per-item overflow action on the card.
- **Undo a mis-tap** — only if mis-taps actually happen; an explicit action, never two-way checkboxes.
- **Scope the struck-through section** to "bought since this card was posted," if it grows unwieldy.
- **Near-duplicate expense guard** — real data already shows one bike rental entered twice, fifteen seconds apart, with distinct `event_id`s. Dedup on `event_id` correctly did not fire. A soft warning on a same-amount, same-category expense within a short window would have caught it.

Do not build any of these speculatively.

---

## Deferred until real usage

- **Receipt attachment.** Photographing a receipt and auto-marking items is a vision problem — OCR/line-item extraction, then matching against pending rows — a different capability class entirely. The only concession made now: `shopping_item` stores enough structure that a future matcher has something to match against.
- **Linking a purchase to an expense.** "bought milk 500" is arguably both. Routing sends it to one domain. Deferred until it proves common.
- **Live-updating every open card.** Staleness is accepted.
- **Recurring/staple items.** Not until the manual flow is habitual.

---

## Cross-Cutting

- **Persistence discipline** unchanged: all access through `dbQuery(db)`, one flat transaction per atomic write, no network calls inside a transaction, client-side UUIDs.
- **Capture-everything still holds.** Every mention lands in `inbound_message` before dispatch, including commands. `shopping_item.inbound_message_id` preserves the same provenance chain expenses have.
- **Every deterministic command returns a terminal outcome.** Shared dispatcher orchestration writes `COMMAND` or `FAILED_COMMAND`; an accidental pending outcome is a failure rather than a row left at `RECEIVED`.
- **Migrations + codegen:** Flyway is the source of truth; register each new table in the pgen allowlist before regenerating.
- **Testing:** Testcontainers with real migrations. `cleanDatabase()` truncates seed data, so tests needing FK targets must create them in setup.
- **Slack side-effects stay outside transactions** — write, commit, then post or re-render.

---

## Getting started

Prototype the checkbox card before anything else; it is the only design decision here that cannot be reasoned about. Then build iteration 1 end to end. Live with explicit commands for at least two weeks before deciding whether iteration 2 is needed — and be willing to conclude that it isn't.
