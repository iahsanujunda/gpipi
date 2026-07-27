# Household Budget Bot — Phase 3: Shared Shopping List

_Slack-native shared list · Stack: Slack (Events + Interactivity) · Ktor · Exposed/Postgres (pgen-generated) · Flyway · OpenRouter_

---

## System context

Phases 1 and 2 built expense capture and a web frontend. Phase 3 adds a **second domain** — a shared household shopping list.

The list is **shared, not per-person**: one household list, either member adds to it, either member marks items bought, and either member can undo a change. There is no per-user scoping anywhere in this phase.

**Deployment boundary.** One application deployment and database represent one household. The bot is not intended to serve multiple households or workspaces, so `shopping_item` deliberately has no household, workspace, or channel scope.

---

## Reference: The Command Dispatcher (already built)

Phase 3 does **not** introduce routing. It already exists, built alongside the web frontend's magic-link command:

```kotlin
interface SlackCommand {
    fun matches(body: String): Boolean            // sync — no I/O
    suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome
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

**Tier 1 — deterministic.** Keyword matching is zero latency, zero cost, exact, and unit-testable with string literals. A matched command may still perform its own domain work — `list add`, for example, uses one model call for item extraction — but routing itself does not. Anything with a recognisable trigger word belongs here.

**Tier 2 — classified.** Only messages that match no command reach the default. If the default must distinguish between domains, *that* is where a model call belongs — paid for once, only by genuinely ambiguous natural language.

An earlier draft put an LLM classifier in front of *every* message. That was wrong: `open` will never need a model to be recognised, and paying a round-trip to discover so is waste. **Push everything that can be deterministic into tier 1, and let tier 2 handle only what's left.**

The practical consequence for this phase: the shopping list ships in tier 1 first (iteration 1), and tier-2 classification is a separate, *optional* ergonomic upgrade (iteration 2) whose necessity is discovered through use rather than assumed.

---

## Reference: `COMMAND` Status

Deterministic commands that complete successfully mark their `inbound_message` row `COMMAND`; handled failures become `FAILED_COMMAND` with a diagnostic reason.

Before this, a handled `open` sat at `RECEIVED` forever — but `RECEIVED` means *not yet processed*, and these messages were processed successfully. Without a terminal state, any query for stuck or in-flight messages is polluted by every command ever run, and phase 4's outcome-based label heuristic cannot distinguish "handled a command" from "died mid-processing."

`COMMAND` rather than `NON_EXPENSE` because it is forward-looking: more deterministic commands are expected, and they are a category of their own, not merely an absence of expense-ness.

For `list add`, the deterministic command is complete when it has persisted an extraction draft and successfully delivered the confirmation card. The later Add, Cancel, and Undo button presses have their own durable draft/mutation lifecycle. They do not leave the original inbound message at `RECEIVED`, and cancelling a draft does not turn a successfully handled command into a failure.

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

## Reference: Card Semantics (immediate, reversible, re-rendered)

The list card contains **pending items only**. Checking one or more fires `block_actions`; the handler immediately marks exactly the eligible rows `BOUGHT` and re-renders in place via `response_url`. Bought items disappear from Slack rather than accumulating in a struck-through section. Their durable history belongs in Postgres and, later, the web UI.

The refreshed card includes brief feedback — for example, `Milk marked bought` — and an **Undo** button for the mutation that just completed. Marking bought does not open a second confirmation dialog: a modal on every supermarket tap would make the primary workflow cumbersome. Immediate durability plus a guarded Undo provides correction without that friction.

Undo is an explicit inverse mutation, not ordinary two-way checkbox state:

- Every Add or Mark Bought operation gets a server-generated mutation UUID.
- A shopping item records the UUID of its latest mutation.
- An Undo applies only while its target mutation is still the item's latest mutation and the item is in the expected state.
- An old card therefore cannot undo a later action by either household member.
- Re-sending the same interaction is an idempotent no-op.

**Concurrency.** Slack sends the full set of currently-checked options on every interaction, so a second interaction re-sends items already bought. Marking must be **idempotent**: a conditional update on `status = 'PENDING'` that no-ops otherwise — the same pattern as `expense_draft.consumeIfPending`.

**Staleness.** A card posted before an item was added won't show it. Accepted, not solved; asking again renders fresh.

**Slack limits.** One checkbox element accepts at most 10 options, and option text and description are each limited to 75 characters. Split a longer pending list into checkbox groups of at most 10. Preserve full item text in Postgres; truncate only its Slack presentation.

> **Unvalidated design.** The re-render-per-tick model is untested. Ticking five items on mobile over supermarket wifi means five round trips and five redraws, and rapid interactions may finish out of order. **Prototype three items and twelve items, then test rapid taps, weak connectivity, Undo, and two members interacting with separate cards before building the schema around this.** Every redraw must query canonical database state rather than build the next card from the interaction payload.

---

## Iteration Order Rationale

| # | Scope | Why This Order |
|---|-------|----------------|
| 1 | Shopping list as a deterministic command | Ships the entire feature with no classifier dependency. Explicit routing is unambiguous and free; only item extraction uses a model |
| 2 | Natural-language routing (**contingent**) | Removes the need to remember syntax. Built only if iteration 1's ergonomics prove annoying in real use |
| 3 | Refinements | Only what real use proves necessary |

Iteration 2 is genuinely optional. If explicit commands turn out to be comfortable, it should never be built — and that is a successful outcome, not a shortfall.

---

# Iteration 1 — Shopping List as a Deterministic Command

The complete list loop, routed entirely by keyword. There is no classifier and no added latency on the existing expense path. `list add` makes one model call to extract its item payload.

### 1.1 Command syntax

The explicit syntax is:

| Input | Action |
|-------|--------|
| `list` | Show pending items as an interactive card |
| `list add <items>` | Extract one or more items and show a confirmation card |

`ShoppingShowCommand.matches()` matches `list` exactly. `ShoppingAddCommand.matches()` matches the `list add ` prefix with a non-blank payload. It is never containment-based. Inputs such as `add 500 for lunch`, `need to log 500`, and `listening to music` must continue to fall through to expense extraction.

The two commands intentionally share a namespace but have non-overlapping matchers. Do not add bare `add` or `need` aliases during iteration 1; daily use will tell us whether natural-language routing is worth building.

### 1.2 Architecture

```
"@bot list add milk and eggs"
  → capture → ShoppingAddCommand.matches("list add milk and eggs") = true
  → extract item list (LLM, structured output)
  → persist PENDING add draft + extracted draft items
  → post confirmation card: [Add items] [Cancel]
  → command returns Completed → dispatcher marks inbound COMMAND

[member clicks Add items]
  → consume draft exactly once
  → create ADD mutation
  → insert non-duplicate PENDING rows (database-enforced)
  → replace card: "Added: milk, eggs ✓" [Undo]

[member clicks Cancel]
  → consume draft as CANCELLED
  → replace card: "Nothing added"

"@bot list"
  → capture → ShoppingShowCommand → query PENDING
  → post Block Kit card → mark inbound COMMAND

[member ticks two boxes]
  → create MARK_BOUGHT mutation
  → conditionally mark PENDING rows BOUGHT
  → re-render pending-only card + "Marked bought" [Undo]

[member clicks Undo]
  → verify target mutation is still current for each item
  → apply inverse transition idempotently
  → re-render canonical pending list
```

`list add <items>` makes **one** LLM call — for *extraction*, not classification. The keyword told us the domain; the model only structures the payload. That is the same division of labour as expense capture.

The add draft is required because extraction is probabilistic. The user sees item, quantity, and note before they become canonical shopping rows. The draft and its items are persisted before the Slack card is posted; the original inbound message becomes `COMMAND` after the command successfully delivers that card. The later interaction transaction consumes the draft and creates shopping rows atomically.

### 1.3 Schema

```sql
create table shopping_add_draft (
    id                 uuid        primary key,
    inbound_message_id uuid        not null unique references inbound_message(id),
    channel_id         text        not null,
    user_id            text        not null,
    status             text        not null default 'PENDING',
    created_at         timestamptz not null default now(),
    completed_at       timestamptz,
    check (status in ('PENDING', 'CONFIRMED', 'CANCELLED')),
    check (
        (status = 'PENDING' and completed_at is null)
        or (status in ('CONFIRMED', 'CANCELLED') and completed_at is not null)
    )
);

create table shopping_add_draft_item (
    id          uuid primary key,
    draft_id    uuid not null references shopping_add_draft(id),
    position    integer not null,
    item        text not null,
    quantity    text,
    note        text,
    unique (draft_id, position)
);

create table shopping_mutation (
    id                    uuid        primary key,
    kind                  text        not null,
    actor_id              text        not null,
    reverses_mutation_id  uuid        references shopping_mutation(id),
    created_at            timestamptz not null default now(),
    check (kind in ('ADD', 'MARK_BOUGHT', 'UNDO_ADD', 'UNDO_BOUGHT')),
    check (
        (kind in ('ADD', 'MARK_BOUGHT') and reverses_mutation_id is null)
        or (kind in ('UNDO_ADD', 'UNDO_BOUGHT') and reverses_mutation_id is not null)
    )
);

create table shopping_item (
    id                  uuid        primary key,
    inbound_message_id uuid        not null references inbound_message(id),  -- provenance, as everywhere
    item                text        not null,          -- "milk", "ground beef"
    quantity            text,                          -- "1kg", "2 packs" — free text, not parsed
    note                text,                          -- "size L, for night"
    status              text        not null default 'PENDING',
    added_by            text        not null,
    added_at            timestamptz not null default now(),
    bought_by           text,
    bought_at           timestamptz,
    removed_by          text,
    removed_at          timestamptz,
    current_mutation_id uuid        not null references shopping_mutation(id),
    check (status in ('PENDING', 'BOUGHT', 'REMOVED')),
    check (
        (
            status = 'PENDING'
            and bought_by is null and bought_at is null
            and removed_by is null and removed_at is null
        )
        or (
            status = 'BOUGHT'
            and bought_by is not null and bought_at is not null
            and removed_by is null and removed_at is null
        )
        or (
            status = 'REMOVED'
            and bought_by is null and bought_at is null
            and removed_by is not null and removed_at is not null
        )
    )
);

create table shopping_mutation_item (
    mutation_id uuid not null references shopping_mutation(id),
    item_id     uuid not null references shopping_item(id),
    primary key (mutation_id, item_id)
);

create unique index shopping_item_pending_identity
    on shopping_item (
        lower(btrim(item)),
        lower(btrim(coalesce(quantity, ''))),
        lower(btrim(coalesce(note, '')))
    )
    where status = 'PENDING';
```

Register all five tables in the pgen `tableFilter` allowlist, then regenerate and commit `pgen-spec.json`. Generated tables are plain `Table`, so all ids are client-side `UUID.randomUUID()`.

- **`quantity` is free text, deliberately.** "1kg", "2 packs", "a few" — parsing into number+unit buys nothing here and loses information. Displayed, never computed on.
- **`BOUGHT` and `REMOVED` rows are retained forever.** Bought history is for a future receipt matcher and web view; removed rows preserve corrections and provenance.
- **The mutation tables are durable undo receipts.** `shopping_mutation_item` preserves which rows an operation affected; `current_mutation_id` prevents a stale Undo button from reversing a newer transition.
- **Undo Bought clears `bought_by` and `bought_at`; Undo Add sets `REMOVED` with `removed_by` and `removed_at`.** The mutation log retains the history that the current-state columns intentionally no longer express.
- **Identity columns have distinct meanings.** `added_by` is the member who sent `list add`; `shopping_mutation.actor_id` is the member who clicked Add, Mark Bought, or Undo. Either household member may operate a shared confirmation or list card.

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

Duplicate identity is the normalized tuple `(item, quantity, note)`, not the item name alone:

- Comparison is case-insensitive and trims surrounding whitespace.
- `null` and blank quantity/note are equivalent.
- `milk · 1 carton` and `milk · 2 cartons` are distinct.
- Only `PENDING` rows participate; identical historical `BOUGHT` or `REMOVED` rows are allowed.

The partial unique index is the authority. The confirmation handler may pre-read pending identities to produce friendly `already on the list` feedback, but correctness must not depend on that read: concurrent confirmations by both members still converge through conflict-safe inserts.

One extraction can itself contain duplicate items. Apply the same normalization within the batch before attempting inserts so the result card can clearly separate added and already-present items.

Create mutation-item links only for rows actually inserted or transitioned. If confirmation finds every extracted item already pending, show `Already on the list` without an Undo button. Likewise, a stale Mark Bought payload that changes no rows gets a canonical refresh but no empty mutation to undo.

### 1.6 The card

```
*Shopping list*
☐ milk
☐ ground beef · 1kg
☐ diapers · size L, for night
```

Checkbox `options` are pending items only; each `value` is the row UUID. Split more than 10 pending items across multiple checkbox elements. Bought and removed items never render in the Slack list.

After marking:

```
*Shopping list*
☐ ground beef · 1kg
☐ diapers · size L, for night

Milk marked bought ✓
[Undo]
```

The feedback and Undo affordance describe only the mutation just handled. A later `list` command renders a clean canonical card with pending items only.

The add flow has separate cards:

```
*Add to shopping list?*
• milk
• eggs · 2 packs

[Add items] [Cancel]
```

After confirmation:

```
Added milk and eggs ✓
[Undo]
```

There is no inline editor in iteration 1. If extraction is wrong, Cancel and resend the command. Once confirmed, Undo Add removes only items whose Add mutation is still their latest transition.

### 1.7 Interaction handling

Extend the existing `/slack/interactions` route and `SlackInteractionHandler`, not a new route. Dispatch these action ids alongside `confirm_expense`:

| Action id | Payload identity | Effect |
|-----------|------------------|--------|
| `confirm_shopping_add` | Draft UUID | Consume draft, insert items, create `ADD` mutation |
| `cancel_shopping_add` | Draft UUID | Consume draft as `CANCELLED` |
| `shopping_mark_bought` | Selected item UUIDs | Conditionally mark pending items and create `MARK_BOUGHT` mutation |
| `undo_shopping_mutation` | Mutation UUID | Apply the guarded inverse transition |

The interaction payload model must include the acting Slack user and checkbox `selected_options`. Capture one real checkbox payload from the prototype and retain it as a route-test fixture rather than relying only on a hand-written approximation.

All state transitions happen in one flat database transaction. Slack acknowledgement remains immediate, while card replacement happens after commit. After every Mark Bought or Undo action, query current pending rows and render from canonical database state.

### 1.8 Undo rules

Undo is available to either household member and has no arbitrary time limit. Safety comes from state/version guards rather than clock time:

- **Undo Add:** transition `PENDING → REMOVED` only when `current_mutation_id` is the target `ADD`.
- **Undo Bought:** transition `BOUGHT → PENDING` only when `current_mutation_id` is the target `MARK_BOUGHT`.
- **Repeated Undo:** no-op and re-render current state.
- **Old Undo after a newer action:** no-op; explain that the item changed after the action.
- **Undo Bought when an identical pending item was added later:** do not violate the pending unique index or resurrect a second row. Treat the list as already restored and report that the item is already present.
- **Partial eligibility:** for a multi-item mutation, reverse eligible rows and report any rows skipped because their state changed.

Every successful inverse is itself recorded as `UNDO_ADD` or `UNDO_BOUGHT`, links to the mutation it reverses, becomes the affected item's `current_mutation_id`, and receives `shopping_mutation_item` rows.

### Definition of Done

- [x] `COMMAND` / `FAILED_COMMAND` added; the dispatcher terminalizes `OpenBudgetCommand`
- [x] Exact `list` and `list add <items>` matchers do not capture expense-like messages
- [x] Both list commands return terminal outcomes through the same dispatcher path
- [x] All shopping tables and the pending-identity unique index are migrated
- [x] All shopping tables are registered in the pgen allowlist and generated specs are committed
- [ ] Add command extracts one or many items in ID/EN/JP/mixed
- [ ] Item, quantity, and note separated correctly ("diapers size L for night" → item `diapers`)
- [x] Add command persists a draft and renders an Add/Cancel confirmation card before creating items
- [x] Confirm and Cancel consume the draft exactly once
- [x] Database enforcement prevents concurrent exact duplicates and the result card says what was skipped
- [x] Different quantity or note qualifiers remain distinct pending items
- [x] Confirmed additions render an Undo action; Undo Add removes only unchanged pending rows
- [x] Show command renders pending items only; empty list gets a friendly empty state
- [x] More than 10 pending items render in checkbox groups of at most 10
- [x] Ticking marks exactly those rows `BOUGHT` with `bought_by`/`bought_at`
- [x] Marking is idempotent — a re-sent bought id changes nothing and does not error
- [x] Card re-renders in place from canonical state; bought history is absent from Slack
- [x] Mark Bought renders feedback and a guarded Undo action
- [x] Undo Bought restores only rows for which that Mark Bought mutation is still current
- [x] Mutation and inverse-mutation history is durable and attributable to the acting member
- [x] A stale second card cannot un-buy an item
- [x] Stale and repeated Undo actions cannot reverse newer state
- [x] `BOUGHT` and `REMOVED` rows are never deleted
- [ ] A genuine checkbox payload is retained as an interaction-route fixture
- [x] Expense capture is bit-for-bit unchanged — no new command matches an expense message
- [ ] **Prototype validated:** 3-item and 12-item cards, rapid taps, weak connectivity, two members, and Undo feel acceptable

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
          LIST_ADD  → item extraction + add confirmation draft   (reuses iteration 1)
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

- **Deliberate removal after the Add Undo is no longer current** — a `list remove` command would reintroduce fuzzy matching, so prefer a UUID-backed per-item action if real use proves it necessary.
- **Inline correction of an extracted add draft** — only if Cancel-and-resend is materially annoying.
- **Pagination or a different Slack surface** — only if chunked checkbox groups become unwieldy with real list sizes.
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
- **Every deterministic command returns a terminal outcome.** Shared dispatcher orchestration writes `COMMAND` or `FAILED_COMMAND`; an accidental pending outcome is a failure rather than a row left at `RECEIVED`. `list add` is complete when its durable draft and confirmation card have been produced; draft confirmation is a later interaction lifecycle.
- **Mutation correctness is database-owned.** Exact duplicate prevention, draft consumption, conditional state changes, mutation receipts, and Undo guards must remain correct under concurrent requests without depending on Slack timing.
- **Migrations + codegen:** Flyway is the source of truth; register each new table in the pgen allowlist before regenerating.
- **Testing:** Testcontainers with real migrations. `cleanDatabase()` truncates seed data, so tests needing FK targets must create them in setup.
- **Slack side-effects stay outside transactions** — write, commit, then post or re-render.

---

## Getting started

Prototype the checkbox card before anything else; it is the only design decision here that cannot be reasoned about. Exercise 3-item and 12-item cards, rapid taps, weak connectivity, separate stale cards, and the post-action Undo affordance. Capture a genuine checkbox payload while doing so.

Then build iteration 1 end to end in this order:

1. `list` with hardcoded rows, checkbox chunking, re-render, and fake Undo.
2. Migrations, generated tables, repositories, database-enforced duplicates, and guarded mutations.
3. `list add` extraction draft and Add/Cancel confirmation card.
4. Real Mark Bought and both inverse mutations.
5. Concurrency and stale-action integration tests.

Live with the explicit commands for at least two weeks before deciding whether iteration 2 is needed — and be willing to conclude that it isn't.
