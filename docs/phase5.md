# Household Assistant — Phase 5: Training Programs and Execution Logging

_Per-person training programs · Google Sheets as the shared interface · Stack: Ktor · Exposed/Postgres (pgen-generated) · Flyway · React · Google Sheets API_

---

## System context

Phases 1–4 built a shared household domain: expenses, budget lines, wallets, a shopping list. Every table so far is **household-scoped** and every principal is a **Slack workspace member**.

Phase 5 breaks both assumptions.

Both members follow individually-tailored training programs written by a shared personal trainer. The programs differ in kind — one weighted toward running and mobility, the other toward strength and hypertrophy — and they are currently authored as spreadsheets, followed on a phone in the gym, and discussed with form videos shared out-of-band.

This phase moves the **logging** half into the app while leaving the spreadsheet as the shared interface: members import the trainer's Google Sheet, log execution in the gym instead of on a phone-held spreadsheet, and write that execution back into the sheet the trainer already reads. Form videos continue to go over group chat.

Two structural firsts:

- **Per-person data.** A program belongs to one member. The other member does not see it. Every prior table is household-scoped.
- **A third-party system of record.** The trainer's Google Sheet is authoritative for prescriptions and is edited by someone outside the app, so every read assumes it has moved and every write must refuse rather than guess.

---

## Reference: The Source Document

The trainer's spreadsheet is the specification. It is worth reading precisely, because several of its properties drive the schema.

A block is a set of **workout documents** — `Full Body WO 1`, `Full Body WO 2`, `Full Body 2 Prenatal` — and each document contains its own weekly tables. Both members' programs have several workouts per week. Each week repeats that workout's movement list with two column groups:

| Prescription | Execution (`Eksekusi`) |
|---|---|
| Movement, Link, Keterangan, Set, Rest, Reps, Load, RIR, Tempo | per set: Reps, Load, RIR |

**The prescription columns are not stable across documents.** Observed: one sheet has `RIR` and no `Tempo`, another has `Tempo` and no prescribed `RIR`, a third has both. The trainer varies their columns per workout, so every prescription field must be optional.

**Execution is half the document.** The trainer does not write a program and wait to be told how it went — they read the logged numbers and adjust. Any design that treats prescription as the artifact and logging as a private side-effect breaks their workflow.

**The prescription columns hold prose, not numbers.** Observed values in numeric-looking columns:

- Set: `3`, `2 each`, `3 each`
- Rest: `45-60sec`, `45-60s`, or blank
- Reps: `10`, `10-12`, `20 total`, `45 sec`, `40-50 sec`
- Load: `45 kg`, `20-25 kg`, `3-4 kg each`, `10 kg di kanan saja`, a tempo prescription (`tempo : turun 5 detik naik 2 detik`), and a **setup instruction where no load exists** — `selutut di squat rack / setinggi bench` for an incline push-up
- RIR: `3`, `15 secs to failure`

These are not data-entry errors. They are a coach using a cell to say a thing the column was not designed for, and they will keep doing it.

**Movements are grouped, and the group label is authored prose.** Observed headers: `STRAIGHT SET`, `SUPERSET`, `FINISHER SUPERSET (DIKERJAKAN BERGANTIAN)`, `FINISHER (SUPERSET, DIKERJAKAN SELANG SELING TANPA REST)`. "Finisher" is a third concept beyond straight-set/superset, and the parentheticals carry execution instructions. Order is semantic: superset members alternate.

**Execution can exceed prescription.** A movement prescribed `2 each` shows four logged sets. Logged sets are not a mirror of prescribed sets.

**RIR is always recorded, sometimes prescribed.** It appears in every `Eksekusi` block but only in some prescription blocks. It is the intensity dial the program turns on, so it is first-class on execution — and nullable on prescription.

**Some movements are timed, not counted.** `Full plank · 45 sec`, `Hollow hold · 40-50 sec`. What gets logged for these is seconds held, not repetitions.

**Filming is part of the method, and stays outside this phase.** The document header instructs: *"Mohon untuk videokan 1 round setiap gerakan untuk dievaluasi bersama."* One round per movement, filmed, evaluated together. Those clips go to group chat and continue to; nothing here changes that.

---

## Reference: The Spreadsheet Is the Interface

The trainer runs dozens of clients through Google Sheets and has their own reconciliation built on top. They are not going to adopt a household app, and they should not have to.

So the sheet stays the **communication interface**, and this app is a better input surface for a document someone else owns:

```
trainer authors  →  Google Sheet  →  import       →  app: prescriptions
member trains    →  app: logs sets, typed, in the gym
                                   →  write-back   →  Google Sheet Eksekusi cells
trainer reads    →  Google Sheet  (their existing workflow, untouched)
```

Three consequences that shape everything downstream:

**The app is never the sole writer.** The trainer edits the sheet whenever they like — inserting rows, adding weeks, restructuring. Any write-back must assume the document has moved since it was read, and must refuse rather than guess.

**The file is native Google Sheets, which makes write-back safe.** Cell-range updates touch only the `Eksekusi` cells and leave everything else intact. (Had it been an `.xlsx` in Drive, there is no cell-level write — the only option is download-modify-reupload, which silently discards anything the trainer changed meanwhile. That would not be safe to build.)

**Every sheet operation is human-triggered.** No background polling, no scheduled sync, no write on save. Both documents are edited by two parties who are not coordinating, so an automated read could silently overwrite a member's correction and an automated write could land in a restructured sheet. A person presses a button, sees what will happen, and confirms — for reads and for writes alike.

**Giving the trainer app access is unnecessary.** If execution lands in their sheet, they never need to open the app. Combined with form videos going over group chat, that removes every reason for a guest principal, a session table, or a share link — see *Deferred*.

---

## Reference: The Workout Dimension

A block is not a flat sequence of weeks. It is a set of recurring **workouts**, each with its own week-by-week progression:

```
program            the block — "8 weeks, starting July"
  └─ workout       "Full Body WO 1", "Full Body WO 2"      ← a recurring session
      └─ week      week 1 … week N for that workout
          └─ group  STRAIGHT SET / FINISHER SUPERSET / …
              └─ prescription
```

Both members' programs have several workouts per week, so a model that goes `program → week → group` cannot represent them — week 1 contains more than one session.

Two consequences:

- The gym screen opens **"week 2 · WO 1"**, not "week 2". Workout is the primary selector, week the secondary.
- `training_session` references the (workout, week) pair. This is what makes *"my WO 1 across the whole block"* a natural query, which is the comparison any progression view wants.

**Workout count varies per block and per member.** Observed across the household: three days a week, then one day a week postpartum, currently two. Nothing may assume a fixed cadence — not the schema, not the workout selector, not any future progression view.

This also makes the **block** the unit that changes when circumstances do. A one-day postpartum block is not a smaller three-day block; it is a different program. So a change in cadence means deactivating the current `program` and creating a new one, never editing a block's workout list mid-flight. It also gives any future "recent blocks" view a boundary that follows real transitions rather than a calendar.

---

## Reference: Weeks Are Authored, Not Scheduled

A week number is the trainer's sequence position, not elapsed calendar time.

When a session or a week is missed, the trainer does **not** expect it to be made up. They author a *new* entry that accounts for the layoff — lost adaptation, reduced intensity, time away. The missed week is never performed and never rescheduled.

Three consequences, each of which removes work rather than adding it:

**No date arithmetic anywhere.** The gym screen shows *the next week that exists and has no session logged against it* — never "whatever week the calendar says." There is no catch-up state, no drift, no notion of being "behind." `program.starts_on` is decorative and must stay optional.

**Progression comparisons are between authored progressions, not equal time intervals.** "Week 3 versus week 8" is the right coaching comparison, but it is not eight weeks of calendar training. Any future progression view should label by week number and session date, never imply regular spacing.

**A missed week stays in the table forever, unperformed.** This is correct — it is history, and deleting it would erase the fact that a gap happened. Combined with the snapshot rule, the prescription record becomes a legible account of how the program adapted to real life, including the deloads written after time off.

That last point needs one mechanism: the gym screen must move past a skipped week rather than offering it indefinitely. `workout_week.skipped_at` (nullable) marks a week the trainer has moved on from.

> **Open decision.** Is skipping explicit — the trainer marks it — or implicit, inferred from a later week having a session? The implicit rule is free but breaks the first time a session is logged out of order, which is plausible precisely because weeks are authored rather than scheduled. Settle this while entering the first real block.

---

## Reference: Prescription Is Text, Execution Is Typed

This asymmetry is the central modelling decision of the phase.

**Prescription fields are stored as authored strings.** `sets`, `reps`, `load`, `rir`, `rest` are `text`. The trainer writes `10-12`, `2 each`, `15 secs to failure`, and the app stores and renders it verbatim. No parsing, no normalisation, no validation that rejects a coach's phrasing.

**Execution fields are strictly typed.** `reps integer`, `load numeric`, `rir integer`, one row per performed set. This is entered by a household member from a numeric input, in a gym, and it is the only side that will ever be trended, charted, or compared across months.

The reasoning: **you control one side of this and not the other.** Typing the prescription means either rejecting valid coaching language or building a parser against a format that changes whenever the trainer restructures their sheet. Typing the execution costs nothing, because the app owns that input.

A consequence worth stating plainly: **the app cannot compute "did you hit the prescription."** `10-12` versus a logged `11` is not machine-comparable without parsing. That comparison happens in a human's head, during review, which is where it happens today.

---

## Reference: Editable History, Honest Records

Unlike money, a training program is **freely editable**. A coach adjusting week 5 after seeing week 3 is normal practice, not data corruption. There is no append-only ledger here, no immutability rule, no confirmation gate on prescription edits.

One narrow exception needs handling: editing a week that has already been performed rewrites what the member was measured against. If the sheet said `3 × 5 @ 60kg`, the member hit it, and the prescription is later revised to `65kg`, the logged history silently reads as a miss.

**The fix is the snapshot pattern already used for `expense.account_id`.** A performed set carries its own `target_reps`, `target_load`, `target_rir` — copied from the prescription at the moment it is logged. The prescription may then be edited freely, with no locking, no versioning, and no frozen-week rule, because every logged set remembers the target it was actually performed against.

This is the same principle phase 2 states for wallets: *"changing a budget line's wallet later affects future expenses, not history."*

---

## Reference: Exercise Is an Entity, Not a String

Movements must be a first-class table with canonical identity, referenced by ID from every prescription.

The driver is comparison across time. "How has my RDL load moved over this block" is an **exercise-first** question, and it underlies every progression view worth building. Import makes it urgent: movement names arrive as strings from a document whose author has no reason to be consistent.

Free-text movement names break that silently. The source document already contains `high incline shoulder press, kakinya udah boleh di bawah aja` — a movement name with a coaching cue appended. If week 3 says `Barbell RDL` and week 7 says `Barbell RDL (tempo)`, string matching produces two timelines for one movement and the comparison quietly fails.

So:

- `exercise` holds the canonical name and an optional demo link.
- Per-week coaching text (`Keterangan`) is a **note on the prescription**, never part of identity.
- Authoring includes a match step: is this movement one we already have, or new?

Cheap now. Painful to retrofit once months of logged sets are keyed to strings.

---

## Reference: Why There Is No Guest Access

An earlier draft of this phase gave the trainer a login. It is worth recording why that was cut, because the reasoning is what keeps it cut.

Adding a non-workspace principal breaks three assumptions at once. **Identity stops being Slack-brokered** — the auth design rests on the workspace being private, so membership *is* identity and `auth_nonce.user_id` can be a Slack user ID; a trainer has no Slack identity, so the system needs a `principal` concept. **Authorization becomes real, and fails silently** — today a valid session reaches expenses, wallets, budgets, and shopping, so every existing route would need a fail-closed guard. And **stateless sessions cannot be revoked**, which is exactly what durable third-party access demands, so a server-side `session` table becomes mandatory.

That is a large, security-sensitive body of work. It was justified by two needs: the trainer reading execution data, and reviewing form video.

Iteration 3 satisfies the first by writing execution into their own sheet. Group chat satisfies the second. **Neither need survives, so neither does the feature.**

The condition to revive it is specific: a concrete requirement that a spreadsheet cannot carry — in-app video review, or the trainer asking to author programs directly rather than in Sheets. Not before.

---

## Iteration Order Rationale

Ordering here is driven by **validating the model before building on it**. Everything in this phase rests on assumptions about a document format authored by someone else, so the first goal is to get real data in and have the trainer confirm the app reproduces their program faithfully.

| # | Scope | Why This Order |
|---|-------|----------------|
| 1 | Private programs + gym execution logging | Replaces the phone-held spreadsheet, and forces the schema to meet two real programs. Zero auth novelty |
| 2 | Drive-connected block import | Removes transcription. Only tractable after iteration 1, because extraction needs a known target shape |
| 3 | Write execution back to the sheet | Closes the loop with the trainer in the tool they already use. Depends on the cell provenance captured in iteration 2 |

**Three iterations close the loop completely**, which is the whole phase. Prescriptions come in from the trainer's sheet, execution is logged in the gym, execution goes back to the sheet, and the trainer reads it where they already work. Form videos continue to go over group chat, which already works.

There is deliberately **no coach access, no guest principal, no session table, and no share link.** Each was designed for a problem iteration 3 removes: if execution lands in the trainer's own document, they have no reason to open this app. See *Deferred* for the condition that would revive them.

**Do not build a *deterministic* spreadsheet parser.** The layout is repeated table blocks with prose in numeric columns, per-document column variation, and section headers as rows — authored by someone who will restructure it whenever it suits them. A hand-written parser is brittle against exactly that drift.

LLM extraction (iteration 2) has the opposite failure profile: tolerant of layout change, imprecise on detail. That is the right trade here, because a human reviews every import before it is saved. It is deliberately placed *after* manual entry — extraction needs a known target shape, and iteration 1 is what establishes it.

The trainer will keep using spreadsheets — they run dozens of clients that way and have their own reconciliation on top. So import is not a stopgap until they adopt the app; it is how the app reads its input, permanently.

**Enter one week of *each* program before writing any authoring UI.** The two members' programs differ in kind, and the observed column sets already differ across three documents. Entering only one program will make the model look correct when it is merely narrow.

---

# Iteration 1 — Private Programs and Gym Execution

The member-facing loop: see this workout's session for the current week, log sets against it. Private to the owning member, exactly like every budgeting surface. No sharing, no coach access.

The goal is as much **schema validation as feature delivery** — get two real programs in and find out where the model bends.

### 1.1 The gym screen

Design target: **a clean set is one tap.** Every execution input is prefilled from its target, so performing exactly as prescribed requires confirmation rather than entry, and divergence is an edit rather than a form.

```
Full Body WO 1 · week 2              ⌄ workout  ⌄ week

STRAIGHT SET

Barbell RDL                                   ▶ demo
  3 × 10 @ 45kg · RIR 3
  tempo: turun 5 detik, naik 2 detik
  cues ⌄

  ①  10 reps · 45kg · RIR 3                        ✓
  ②  [10]    [45]    [3]                       log ›
  ③  10       45      3

Pronated grip lat pull down                   ▶ demo
  3 × 12 @ 33kg · RIR 3
  ①  12 · 33kg · RIR 0                             ✓
  ②  11 · 33kg · RIR 0                             ✓
  ③  [ 9]    [33]    [ 0]                      log ›
  + add set

SUPERSET                                      ⇄ alternate
DB alternating hooklying skullcrushers · 3 each × 15 @ 4kg
Bear hold pull through · 3 × 20 total · 15s to failure
```

Workout is the primary selector and week the secondary, per the workout-dimension reference — a week contains several sessions.

**"Current" means the next unlogged, unskipped week of the selected workout.** Never a calendar derivation: weeks are authored, missed sessions are not made up, and any date-based rule breaks the first time one is skipped.

**Sets commit immediately.** No session-level save button. A member is between sets, sweaty, and may close the app at any point — the same immediate-durability argument as the shopping list card, for the same reason.

**`+ add set` is always available.** Execution exceeding prescription is normal, not an error state.

Prescription prose renders verbatim. Long `Keterangan` text collapses behind a `cues` disclosure so it does not push the inputs below the fold.

### 1.2 Schema

```sql
create table exercise (
    id          uuid primary key default gen_random_uuid(),
    name        text not null unique,
    demo_url    text,
    created_at  timestamptz not null default now()
);

-- A block: "8 weeks from July", owned by one member.
create table program (
    id           uuid primary key default gen_random_uuid(),
    principal_id uuid not null references principal(id),   -- iteration 3; member id until then
    name         text not null,
    note         text,                                     -- document header text
    starts_on    date,                                     -- decorative; weeks are authored, not scheduled
    week_count   integer,                                  -- may grow as the trainer writes further weeks
    active       boolean not null default true,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- A recurring session within the block: "Full Body WO 1" (day 1), "Full Body WO 2" (day 2).
-- Count varies per block and per member (observed: 1, 2, and 3 per week).
create table workout (
    id         uuid primary key default gen_random_uuid(),
    program_id uuid not null references program(id),
    name       text not null,
    note       text,                       -- per-document instruction, e.g. the video request
    position   integer not null,
    unique (program_id, position)
);

-- One week of one workout.
create table workout_week (
    id          uuid primary key default gen_random_uuid(),
    workout_id  uuid not null references workout(id),
    week_number integer not null,       -- the trainer's sequence, not elapsed time
    skipped_at  timestamptz,            -- set when the trainer moves past an unperformed week
    unique (workout_id, week_number),
    check (week_number >= 1)
);

-- Group headers within a week. Label is authored prose; kind drives UI only.
create table workout_group (
    id       uuid primary key default gen_random_uuid(),
    week_id  uuid not null references workout_week(id),
    label    text not null,                -- "FINISHER SUPERSET (DIKERJAKAN BERGANTIAN)"
    kind     text not null,                -- STRAIGHT_SET | SUPERSET
    position integer not null,
    unique (week_id, position),
    check (kind in ('STRAIGHT_SET', 'SUPERSET'))
);

-- The prescription. Every quantity is authored text, and every field is optional
-- because the trainer varies their columns per workout document.
create table prescription (
    id          uuid primary key default gen_random_uuid(),
    group_id    uuid not null references workout_group(id),
    exercise_id uuid not null references exercise(id),
    position    integer not null,
    sets        text,        -- "3", "2 each", "3 each"
    rest        text,        -- "45-60sec"
    reps        text,        -- "10", "10-12", "20 total", "45 sec"
    load        text,        -- "20-25 kg", "3-4 kg each", "selutut di squat rack"
    rir         text,        -- "3", "15 secs to failure" — absent in some documents
    tempo       text,        -- "turun 5 detik naik 2 detik"
    note        text,        -- Keterangan: setup + execution cues
    unique (group_id, position)
);

-- One member's attempt at one (workout, week) session.
create table training_session (
    id           uuid primary key default gen_random_uuid(),
    principal_id uuid not null references principal(id),
    week_id      uuid not null references workout_week(id),
    performed_on date not null,
    note         text,
    created_at   timestamptz not null default now()
);

-- A movement as actually performed. Nullable prescription link allows substitutions.
create table performed_exercise (
    id              uuid primary key default gen_random_uuid(),
    session_id      uuid not null references training_session(id),
    exercise_id     uuid not null references exercise(id),
    prescription_id uuid references prescription(id),
    position        integer not null,
    note            text,
    unique (session_id, position)
);

-- One logged set. Typed, and carrying its own target snapshot.
create table performed_set (
    id                    uuid primary key default gen_random_uuid(),
    performed_exercise_id uuid not null references performed_exercise(id),
    set_number            integer not null,
    reps                  integer,
    duration_s            integer,          -- timed holds: plank, hollow hold
    load                  numeric(8,2),
    rir                   integer,
    note                  text,
    -- Snapshot: what this set was performed against, at log time.
    target_reps           text,
    target_load           text,
    target_rir            text,
    target_tempo          text,
    logged_at             timestamptz not null default now(),
    unique (performed_exercise_id, set_number),
    check (set_number >= 1),
    check (reps is null or reps >= 0),
    check (duration_s is null or duration_s >= 0),
    check (load is null or load >= 0)
);
```

Note `performed_set` carries **both** `reps` and `duration_s`, nullable. A plank prescribed `45 sec` logs seconds held; putting 45 into `reps` would be semantically wrong and would corrupt any future trend on repetitions.

Register every new table in the pgen `tableFilter` allowlist before regenerating. Generated tables are plain `Table`, so ids are client-side `UUID.randomUUID()`.

`principal_id` is introduced here but the `principal` table arrives in iteration 5. Until then it references the household member identity in use.

### 1.3 Authoring (manual)

A web form for entering a block: program name, week count, then per week a list of groups and prescriptions. **Copy-forward is essential** — weeks in a block repeat the same movements with adjusted numbers, so "duplicate week 1 into week 2" turns eight weeks of entry into one week plus edits.

Exercise selection is a combobox over existing `exercise` rows with an inline create. This is where canonical identity is enforced.

### Definition of Done

- [ ] All tables created, registered in the pgen allowlist, tables regenerated
- [ ] A block with **multiple workouts per week** can be hand-entered
- [ ] Groups, finisher supersets, and prose prescriptions all round-trip verbatim
- [ ] Copy-forward duplicates a week's structure for editing
- [ ] Exercise selection reuses existing rows; new names are created deliberately, not by typo
- [ ] This week's session renders on mobile with one-handed reach and no horizontal scroll
- [ ] Execution inputs are prefilled from targets; a matching set logs in one tap
- [ ] Each set commits immediately; no session-level save
- [ ] `+ add set` allows execution beyond prescription
- [ ] Every logged set stores its target snapshot
- [ ] Editing a prescription after logging does not alter logged targets
- [ ] Prescription prose renders verbatim, including tempo and per-side instructions
- [ ] Timed movements log `duration_s` rather than `reps`
- [ ] Group labels render as authored, including finisher/superset parentheticals
- [ ] A workout with no prescribed RIR column renders without an empty RIR affordance
- [ ] The gym screen resolves "current" as the next unlogged, unskipped week — no calendar arithmetic
- [ ] A skipped week is passed over rather than offered indefinitely, and is retained as history
- [ ] A block with one workout and a block with three both render correctly (no fixed-cadence assumption)
- [ ] A member cannot see another member's program
- [ ] **One week of each member's program is entered**, and the model held without a schema change

---

# Iteration 2 — Drive-Connected Block Import

Select the trainer's Google Sheet, extract a week into a reviewable draft, and record where every cell came from.

### 2.1 Why this is tractable now, and was not before

**The target shape is known.** Iteration 1 established it against two real programs. Extraction has a fixed destination — workout → weeks → groups → prescriptions — rather than an open modelling question.

**Most fields are text, so extraction is mostly *placement*, not interpretation.** `sets`, `reps`, `load`, `rir`, `tempo`, `rest`, and `note` are stored as authored strings. The model works out which cell belongs to which field; it never parses `10-12` into a range or decides what `selutut di squat rack` means. That is far more reliable than typed extraction, and it fails *visibly* — a value in the wrong column — rather than silently.

### 2.2 Drive access

The trainer shares a native Google Sheet. Members connect Google once, then pick the file with the **Google Picker**.

**Use the `drive.file` scope**, not full Drive read. `drive.file` grants access only to files the user explicitly selects through the Picker, so the app never holds broad access to either member's Drive. This is the same least-privilege instinct as scoping the Slack app to `app_mentions:read`.

Refresh tokens are credentials: stored encrypted, per member, revocable from the app, and never logged. Confirm current Google API scope names and Picker behaviour at implementation time.

```sql
create table google_credential (
    principal_id  uuid primary key references principal(id),
    refresh_token text not null,          -- encrypted at rest
    scope         text not null,
    connected_at  timestamptz not null default now(),
    revoked_at    timestamptz
);
```

### 2.3 Two entry points, both manual

| Action | What it does |
|---|---|
| **Select file** | Opens the Picker, links a spreadsheet to a workout (`sheet_link`), then extracts |
| **Sync** | Re-extracts from the already-linked spreadsheet |

Nothing else triggers a read. There is no background poll, no refresh on page load, and no sync on a timer. The trainer edits their sheet on their own schedule; a member decides when to pull those edits in, having usually just been told about them.

`sheet_link` is unique per workout, so **Select file** on an already-linked workout replaces the link and warns first.

### 2.4 Reading the sheet

Read cell values **and their addresses** via the Sheets API — a grid of rows and columns per tab, not a flattened export. Position carries meaning here: week headers, group headers, and the prescription/`Eksekusi` split are all spatial.

Extraction runs **one week at a time**, not one document at a time. Weeks are the repeating unit, output stays bounded, and a bad week is re-run without discarding the rest.

### 2.5 Extraction target

Strict structured output, nested to match the schema, with **cell addresses carried alongside every extracted movement** (see 2.5):

```json
{
  "type": "object",
  "properties": {
    "week_number": { "type": "integer" },
    "groups": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "label": { "type": "string" },
          "kind":  { "type": "string", "enum": ["STRAIGHT_SET", "SUPERSET"] },
          "prescriptions": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "movement":  { "type": "string" },
                "row":       { "type": "integer" },
                "demo_url":  { "type": ["string", "null"] },
                "sets":      { "type": ["string", "null"] },
                "rest":      { "type": ["string", "null"] },
                "reps":      { "type": ["string", "null"] },
                "load":      { "type": ["string", "null"] },
                "rir":       { "type": ["string", "null"] },
                "tempo":     { "type": ["string", "null"] },
                "note":      { "type": ["string", "null"] }
              },
              "required": ["movement", "row"],
              "additionalProperties": false
            }
          }
        },
        "required": ["label", "kind", "prescriptions"],
        "additionalProperties": false
      }
    },
    "eksekusi_columns": {
      "type": "array",
      "description": "Per set index, the column letters for reps/load/rir under Eksekusi.",
      "items": {
        "type": "object",
        "properties": {
          "set_number": { "type": "integer" },
          "reps_col":   { "type": "string" },
          "load_col":   { "type": ["string", "null"] },
          "rir_col":    { "type": ["string", "null"] }
        },
        "required": ["set_number", "reps_col"],
        "additionalProperties": false
      }
    }
  },
  "required": ["week_number", "groups", "eksekusi_columns"],
  "additionalProperties": false
}
```

Prompt guidance worth encoding, all drawn from observed documents:

- **Copy cells verbatim.** Do not normalise `45-60sec` to `45-60s`, do not convert `10-12` to a number, do not translate Indonesian cues.
- **Extract only prescription values.** Everything right of the `Eksekusi` header is execution data, entered by the member — importing it would fabricate history. Its *column positions* are captured, its *values* are not.
- **Column sets vary per document.** A missing `RIR` or `Tempo` column means null, not an inferred value.
- **Group headers are rows**, not columns: `STRAIGHT SET`, `FINISHER SUPERSET (…)`. Capture the label verbatim; set `kind` from whether it describes alternating work.
- **A load cell may not describe load.** `selutut di squat rack / setinggi bench` is a setup instruction. Keep it in `load` as written.

### 2.6 Cell provenance — the load-bearing part

Write-back (iteration 3) needs a mapping from (prescription, set number, field) to a real cell address. **The only moment that mapping can be built reliably is while reading the grid**, so import must capture it even though nothing consumes it yet.

```sql
create table sheet_link (
    id             uuid primary key default gen_random_uuid(),
    workout_id     uuid not null references workout(id),
    spreadsheet_id text not null,
    connected_by   uuid not null references principal(id),
    created_at     timestamptz not null default now(),
    unique (workout_id)
);

-- Where this week lives in the sheet, and where its Eksekusi cells are.
create table sheet_week_link (
    week_id           uuid primary key references workout_week(id),
    sheet_link_id     uuid not null references sheet_link(id),
    tab_name          text not null,
    eksekusi_columns  jsonb not null,     -- [{set:1, reps:"K", load:"L", rir:"M"}, ...]
    imported_at       timestamptz not null default now()
);

-- Row anchor per movement, plus the value used to detect drift.
create table sheet_prescription_link (
    prescription_id uuid primary key references prescription(id),
    week_id         uuid not null references workout_week(id),
    row_number      integer not null,
    movement_text   text not null       -- the exact cell text at import, for drift detection
);
```

`movement_text` is the drift anchor: before any write, the app re-reads that row and confirms the movement cell still matches. If it does not, the sheet has been restructured and the write is refused.

### 2.7 Confirmation — choose which weeks to apply

Extraction produces a **draft**, never a saved week. Nothing reaches `prescription` until a member confirms, and confirmation is **per week**, not all-or-nothing.

This matters because most syncs are small. The common case is a conversation with the trainer followed by one adjusted exercise in one workout of the current week — the other seven weeks are unchanged and re-applying them is pure risk, since it would discard any correction a member made to a bad extraction.

So the confirmation page lists every extracted week with its state:

```
Full Body WO 1 — synced from "Junda – Full Body" · 8 weeks found

  ☑ Week 1     unchanged
  ☑ Week 2     unchanged
  ☑ Week 3     changed · DB romanian deadlift: load 6-7 kg → 7-8 kg
  ☑ Week 4     changed · 1 movement added
  ☑ Week 5     new
  ☑ Week 6-8   unchanged
                                        [ Apply 8 weeks ]
```

**All weeks are selected by default**, so a first import is one click. Deselecting is how a member scopes a sync down to the week that actually moved.

The per-week annotation is what makes that choice informed rather than blind: comparing extracted values against what is already stored costs nothing and turns "which of these eight weeks do I want" into a decision someone can actually make. A week whose stored values were locally edited and now differ from the sheet is worth flagging distinctly — applying it discards the edit.

Selected weeks then render in the iteration-1 authoring UI, fully editable, before the final save. Same discipline as the expense confirmation card: the model proposes, a human disposes.

### 2.8 Exercise matching is the risky step

Movement names arrive as strings and must resolve to canonical `exercise` rows. This is where silent damage happens: auto-creating `DB hooklying skullcrusher` alongside an existing `DB alternating hooklying skullcrushers` splits one movement's history into two, and it will not be noticed for months.

Propose matches, never auto-create. For each extracted movement show the best existing candidates and require an explicit choice between *"this is that exercise"* and *"this is new."* Fuzzy matching narrows the list; a human makes the call.

This is the one part of the import that should feel slow.

### 2.9 Re-import

Import targets a specific (workout, week), so a repeat run replaces the **draft**, never saved data. A week that already has prescriptions warns before overwrite, and never touches `training_session` or `performed_set` — logged history is not import-reachable. Re-import refreshes the provenance rows, which is the supported recovery when the sheet has drifted.

### Definition of Done

- [ ] Google connected with `drive.file` scope; file chosen via the Picker
- [ ] Refresh tokens encrypted at rest, revocable in-app, never logged
- [ ] Sheet read through the Sheets API preserving cell addresses
- [ ] Extraction runs per week and returns schema-valid structured output
- [ ] Values copied verbatim — ranges, units, Indonesian cues, tempo prose unchanged
- [ ] `Eksekusi` values are ignored; only their column positions are captured
- [ ] Absent columns yield null, not inferred values
- [ ] Group labels captured verbatim; `kind` correct for supersets and finishers
- [ ] `sheet_link`, `sheet_week_link`, `sheet_prescription_link` populated on every import
- [ ] **Select file** and **Sync** are the only ways a read happens — no poll, no timer, no read on page load
- [ ] Re-linking a workout to a different spreadsheet warns first
- [ ] Confirmation lists every extracted week, all selected by default, individually deselectable
- [ ] Each week is annotated unchanged / changed / new, with changed weeks naming what differs
- [ ] A week whose stored values were locally edited is flagged distinctly before overwrite
- [ ] Deselected weeks are not written at all
- [ ] Nothing saved without explicit confirmation in the authoring UI
- [ ] Exercise matching proposes candidates and requires a choice; no silent creation
- [ ] Re-import replaces a draft, warns before overwriting a saved week, cannot reach logged sets
- [ ] A full real block imports faster than hand entry, with corrections needed recorded
- [ ] Extraction failure degrades to manual entry rather than blocking the block

---

# Iteration 3 — Write Execution Back to the Sheet

A button that fills the `Eksekusi` cells for a logged session, so the trainer reads execution where they already work.

### 3.1 What is written, and what is never touched

**Written:** for each performed set, the `reps`, `load`, and `rir` cells in that movement's row, under the matching set column group.

**Never written:** prescription cells, group headers, week headers, formatting, or any other tab. The app writes into cells the trainer left blank for exactly this purpose and nowhere else.

Timed movements are the one conversion: `duration_s` is written into the `reps` cell as the trainer writes it — `45 sec` — because that is what their column contains for a plank.

### 3.2 Preview before write — a dry run, not a warning

Pressing **Write to sheet** does not write. It produces a preview of exactly what would change, and the write happens only on a second, explicit confirmation.

```
Write to "Junda – Full Body" · tab "Full Body WO 1" · week 3

  DB zercher bench squat        row 12
    Set 1   K12 = 10    L12 = 5      M12 = 3
    Set 2   N12 = 10    O12 = 5      P12 = 3
    Set 3   Q12 =  8    R12 = 5      S12 = 2

  Incline push up               row 13
    Set 1   K13 = 8     L13 = —      M13 = 3
    …

  18 cells across 4 movements. 2 target cells already contain values ⚠
                        [ Cancel ]   [ Write 18 cells ]
```

Two things earn this step:

**Drift detection runs here, so a mismatch is information rather than a failed action.** The anchor check (3.3) happens while building the preview. If the sheet has been restructured, the member is told *before* committing to anything: "the sheet has changed since import — re-import week 3 first." That is a much better experience than pressing a button and having it abort.

**Writing into someone else's working document deserves a look first.** The trainer runs dozens of clients through these sheets. Showing the target range, the values, and the count converts a leap of faith into a glance.

The preview is not a promise. The write re-runs the anchor check immediately before executing, because the sheet can move between preview and confirmation. A second-check failure aborts with the same message.

### 3.3 Drift detection gates every write

The trainer will insert rows, add weeks, and restructure between import and write-back. Writing to stale coordinates corrupts **their** working document, which is the worst failure available here — it is someone else's file, with other clients' expectations attached.

So, before every write:

Both when building the preview and immediately before executing:

1. Re-read the anchor cells for the target week using `sheet_prescription_link`.
2. Confirm each `movement_text` still matches the cell at `row_number`.
3. Confirm the `Eksekusi` header still sits where `eksekusi_columns` says.
4. **Any mismatch aborts the whole write** and tells the member to re-import the week.

Never write optimistically, never write partially past a mismatch, and never "find the row again" heuristically — a fuzzy re-match that guesses wrong writes a member's numbers onto the wrong movement.

### 3.4 Non-empty cells

A target cell that already has a value is a conflict, not an overwrite. Either the trainer entered something or a previous write-back ran.

Conflicts surface **in the preview**, flagged per cell with the existing value alongside the proposed one, so the member decides with the comparison in front of them. Default to refusing; overriding is explicit and per-write, never a remembered preference.

### 3.5 Batching and idempotency

One batched cell-update call per session, so a session lands atomically from the sheet's perspective rather than as a trickle of edits.

Record the outcome so a repeat press is safe:

```sql
create table sheet_write (
    id           uuid primary key default gen_random_uuid(),
    session_id   uuid not null references training_session(id),
    written_by   uuid not null references principal(id),
    written_at   timestamptz not null default now(),
    cell_count   integer not null,
    status       text not null,        -- OK | DRIFT_ABORTED | CONFLICT | FAILED
    detail       text
);
```

### 3.6 When to offer it

Write-back is **explicit, not automatic** — a button on a completed session, not a trigger on every logged set, and never a background job. Per-set writes would mean dozens of API calls, a partially-filled row while a member is mid-session, and drift checks on every tap.

Surfacing a persistent "unsynced sessions" indicator is worth more than automation: the member decides when the session is done.

### Definition of Done

- [ ] Pressing write produces a preview and writes nothing
- [ ] Preview shows spreadsheet, tab, week, target cell addresses, values, and total count
- [ ] Drift is detected while building the preview and reported as guidance, not as a failed write
- [ ] The anchor check re-runs immediately before execution; a late mismatch aborts cleanly
- [ ] Occupied target cells are flagged in the preview with existing vs proposed values
- [ ] Overriding a conflict is explicit and per-write, never remembered
- [ ] Write-back writes only `Eksekusi` reps/load/rir cells for the target session
- [ ] Prescription cells, headers, formatting, and other tabs are never modified
- [ ] Timed sets write duration into the reps cell as authored (`45 sec`)
- [ ] Every write re-reads anchors and aborts entirely on any drift
- [ ] Drift aborts tell the member to re-import, and never attempt a fuzzy re-match
- [ ] Non-empty target cells raise a conflict, resolvable only by explicit confirmation
- [ ] One batched update per session
- [ ] `sheet_write` records outcome; repeating a successful write is safe
- [ ] Write-back is manual, with unsynced sessions visible
- [ ] Verified: the trainer read a written-back week in their own sheet without noticing anything amiss

---

# Iteration 4 — Refinements

Unspecified until iterations 1–3 are in real use. Candidates:

- **Progression views** — logged load and reps for one exercise over a block. Cheap once execution is typed.
- **Session reminders** — a Slack nudge when a workout's next week is unlogged for a while.
- **Substitution capture** — recording *why* a movement was swapped, if it happens often.

---

## Deferred until real usage

- **Coach access to the app** — a guest principal, a `coach_client` link, a server-side `session` table for revocation, and a fail-closed second auth provider. Designed, then cut: iteration 3 gives the trainer execution data in their own sheet, and form video goes over group chat. **Revive only if a concrete need appears that a spreadsheet cannot carry** — most likely in-app video review, or the trainer asking to author directly. It is a substantial piece of engineering and should not be built speculatively.
- **Public share links for a single session** — same reasoning. Also the system's first unauthenticated read path and a durable URL-borne credential, which phase 2's single-use-nonce design deliberately avoids. Not worth introducing without a need.
- **In-app form video.** Filming stays part of the method — the trainer's instruction to film one round per movement is unchanged — but the clips go to group chat, which works today. Bringing them in-app means object storage, presigned uploads, signed playback, retention rules, and a review surface, and it is the main thing that would drag coach access back into scope.
- **Offline logging.** Everything here assumes connectivity. If gym signal proves unreliable, offline support means optimistic local writes, a queue, and reconciliation — a large addition, worth its own phase.
- **Running and mobility prescriptions with distance/duration semantics.** Currently expressible as prose in the same text fields. Typed fields only if trending demands them.
- **Multiple sheets per member, or a second trainer.** `sheet_link` is keyed per workout, so it can already span documents; nothing else has been designed for it.

## Cross-Cutting

- **Per-person by default.** Training data is scoped to a principal. This is the first domain where cross-member visibility is a deliberate exception rather than the norm.
- **Persistence discipline** unchanged: all access through `dbQuery(db)`, one flat transaction per atomic write, no network calls inside a transaction, client-side UUIDs.
- **Migrations + codegen:** Flyway is the source of truth; register each new table in the pgen allowlist before regenerating, or it silently will not be generated.
- **Testing:** per the testing guide, narrowest layer that proves the behaviour. Authorization boundaries belong in route tests with a guest session fixture; the target-snapshot rule belongs in a persistence test; the Drive picker and import review flow belong in Playwright.
- **Design system** applies unchanged: mobile-first, one-handed reach, no reliance on colour alone, and loading/empty/error/pending states visible — an unsynced session is a state the UI must show, not hide.

---

## Getting started

Enter one week of **each** program by hand before writing the authoring UI, and before touching iteration 2 — extraction needs a target shape, and hand entry is what establishes it. The two programs differ in kind, and the three observed documents already differ in their column sets; entering only one will make a narrow model look correct.

Then connect Drive and import a real block. The measure of iteration 2 is whether importing plus correcting beats typing; record what needed fixing.

Iteration 3 is the one that changes the trainer's experience, and its acceptance test is behavioural rather than technical: they open their own sheet, read a written-back week, and notice nothing amiss.