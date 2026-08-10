# Household Assistant — Phase 5: Training Programs and Execution Logging

_Per-person training programs · Google Sheets as the shared interface · Stack: Ktor · Exposed/Postgres (pgen-generated) · Flyway · React · Google Sheets API_

---

## System context

Phases 1–4 built a shared household domain: expenses, budget lines, wallets, a shopping list. Every table so far is **household-scoped** and every authenticated user is identified by their **Slack user ID**.

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

| Prescription | Execution (observed labels: `Eksekusi`, `Realisasi`) |
|---|---|
| Movement, Link, Keterangan, Set, Rest, Reps, Load, RIR, Tempo | per set: Reps, Load, RIR |

**The prescription columns are not stable across documents.** Observed: one sheet has `RIR` and no `Tempo`, another has `Tempo` and no prescribed `RIR`, a third has both. The trainer varies their columns per workout, so every prescription field must be optional.

**Execution is half the document.** The trainer does not write a program and wait to be told how it went — they read the logged numbers and adjust. Any design that treats prescription as the artifact and logging as a private side-effect breaks their workflow.

**Existing execution cells are not import data.** The trainer sometimes copies a previous week or block as a starting point, including populated execution cells. Their presence does not prove that the current week was performed, and the values are not defaults for new logging. Import reads only the prescription side. It retains the execution headers and cell coordinates needed for later write-back, but discards every execution value.

**The execution label and boundary vary.** The observed workbook uses both `Eksekusi` and `Realisasi`; execution begins at column K in some workout tabs and column I in another. The importer must identify the execution column group structurally and semantically, never assume a fixed label or column.

**The prescription columns hold prose, not numbers.** Observed values in numeric-looking columns:

- Set: `3`, `2 each`, `3 each`
- Rest: `45-60sec`, `45-60s`, or blank
- Reps: `10`, `10-12`, `20 total`, `45 sec`, `40-50 sec`
- Load: `45 kg`, `20-25 kg`, `3-4 kg each`, `10 kg di kanan saja`, a tempo prescription (`tempo : turun 5 detik naik 2 detik`), and a **setup instruction where no load exists** — `selutut di squat rack / setinggi bench` for an incline push-up
- RIR: `3`, `15 secs to failure`

These are not data-entry errors. They are a coach using a cell to say a thing the column was not designed for, and they will keep doing it.

**Spreadsheet cell types are not semantic types.** The observed workbook export includes numeric-looking prescription cells stored as dates by the spreadsheet. Import uses the formatted text displayed to the member, not the underlying XLSX/Sheets scalar type, and does not attempt to repair or reinterpret it silently. Any spreadsheet coercion that looks wrong is corrected during human review.

**Movements are grouped, and the group label is authored prose.** Observed headers: `STRAIGHT SET`, `SUPERSET`, `FINISHER SUPERSET (DIKERJAKAN BERGANTIAN)`, `FINISHER (SUPERSET, DIKERJAKAN SELANG SELING TANPA REST)`. "Finisher" is a third concept beyond straight-set/superset, and the parentheticals carry execution instructions. Order is semantic: superset members alternate.

**Execution can exceed prescription.** A movement prescribed `2 each` shows four logged sets. Logged sets are not a mirror of prescribed sets.

**RIR is always recorded, sometimes prescribed.** It appears in every execution block but only in some prescription blocks. It is the intensity dial the program turns on, so it is first-class on execution — and nullable on prescription.

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

**Giving the trainer app access is unnecessary.** If execution lands in their sheet, they never need to open the app. Combined with form videos going over group chat, that removes every reason for guest identity, a server-side authentication-session table, or a share link — see *Deferred*.

---

## Reference: The Workout Dimension

A block is not a flat sequence of weeks. It is a set of recurring **workouts**, each with its own week-by-week progression:

```
program            the block — "current strength block"
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

There is **at most one active program per member**. Deactivation is reversible; activating an older or new program deactivates the current one in the same transaction. There is no expected `week_count`: even a prefilled number in the sheet is only a plan, and the trainer may add or stop weeks in response to progress and life events. The UI may show the authored week numbers currently present under each workout, but no program-level total may imply how many weeks will eventually exist. Once execution exists anywhere under a program, its workout structure is historical and cannot be hard-deleted; a cadence change starts another program.

---

## Reference: Weeks Are Authored, Not Scheduled

A week number is the trainer's sequence position, not elapsed calendar time.

When a session or a week is missed, the trainer does **not** expect it to be made up. They author a *new* entry that accounts for the layoff — lost adaptation, reduced intensity, time away. The missed week is never performed and never rescheduled.

Three consequences, each of which removes work rather than adding it:

**No date arithmetic anywhere.** The gym screen resumes an in-progress session for the selected workout; otherwise it shows *the next week that exists and has no completed session against it* — never "whatever week the calendar says." There is no catch-up state, no drift, no notion of being "behind." `program.starts_on` is decorative and must stay optional.

**Progression comparisons are between authored progressions, not equal time intervals.** "Week 3 versus week 8" is the right coaching comparison, but it is not eight weeks of calendar training. Any future progression view should label by week number and session date, never imply regular spacing.

**A missed week stays in the table forever, unperformed.** This is correct — it is history, and deleting it would erase the fact that a gap happened. Combined with the snapshot rule, the prescription record becomes a legible account of how the program adapted to real life, including the deloads written after time off.

That last point needs one mechanism: the gym screen must move past a skipped week rather than offering it indefinitely. Skipping is **explicit and reversible**: **Skip week** sets `workout_week.skipped_at`, and **Restore week** clears it. It is never inferred from a later completion because doing the workout and entering it may happen out of order. Starting to log a skipped week automatically restores it. A week with a completed session cannot also be skipped.

---

## Reference: Prescription Is Text, Execution Is Typed

This asymmetry is the central modelling decision of the phase.

**Prescription fields are stored as authored strings.** `sets`, `reps`, `load`, `rir`, `rest` are `text`. The trainer writes `10-12`, `2 each`, `15 secs to failure`, and the app stores and renders it verbatim. No parsing, no normalisation, no validation that rejects a coach's phrasing.

**Execution fields are strictly typed.** `reps integer`, `load numeric`, `rir integer`, one row per performed set. This is entered by a household member from a numeric input, in a gym, and it is the only side that will ever be trended, charted, or compared across months.

A logged set contains **exactly one primary measure**: `reps` for counted work or `duration_s` for timed work. Each prescription has a human-confirmed `execution_type`: `REPS`, `REPS_PER_SIDE`, or `DURATION`. It has no inferred or database default. This choice only selects and labels the correct input (`reps`, `reps / side`, or `seconds`); it does not parse or constrain the prescribed prose. Both rep types store the entered integer in `performed_set.reps`, while `DURATION` stores seconds in `performed_set.duration_s`.

`load`, `rir`, and `note` are optional and remain null when blank. Logging does not validate execution against the prescription, require RIR, or require load; the app records what the member supplies rather than deciding whether it was a valid performance.

The reasoning: **you control one side of this and not the other.** Typing the prescription means either rejecting valid coaching language or building a parser against a format that changes whenever the trainer restructures their sheet. Typing the execution costs nothing, because the app owns that input.

A consequence worth stating plainly: **the app cannot compute "did you hit the prescription."** `10-12` versus a logged `11` is not machine-comparable without parsing. That comparison happens in a human's head, during review, which is where it happens today.

---

## Reference: Editable History, Honest Records

Unlike money, a training program is **freely editable**. A coach adjusting week 5 after seeing week 3 is normal practice, not data corruption. There is no append-only ledger here, no immutability rule, no confirmation gate on prescription edits.

One narrow exception needs handling: editing a week that has already been performed rewrites what the member was measured against. If the sheet said `3 × 5 @ 60kg`, the member hit it, and the prescription is later revised to `65kg`, the logged history silently reads as a miss.

**The fix is the snapshot pattern already used for `expense.account_id`.** When the first set for a prescribed movement is logged, `performed_exercise` snapshots the complete display prescription: group label/kind, canonical exercise name, execution type, sets, rest, reps, load, RIR, tempo, note, and demo URL. Each performed set also carries the set-relevant target fields copied at log time. The live prescription may then be edited or deactivated freely, with no locking, versioning, or frozen-week rule, because history renders from the snapshot rather than silently adopting later edits.

This is the same principle phase 2 states for wallets: *"changing a budget line's wallet later affects future expenses, not history."*

---

## Reference: Exercise Is an Entity, Not a String

Movements must be a first-class table with canonical identity, referenced by ID from every prescription.

The driver is comparison across time. "How has my RDL load moved over this block" is an **exercise-first** question, and it underlies every progression view worth building. Import makes it urgent: movement names arrive as strings from a document whose author has no reason to be consistent.

Free-text movement names break that silently. The source document already contains `high incline shoulder press, kakinya udah boleh di bawah aja` — a movement name with a coaching cue appended. If week 3 says `Barbell RDL` and week 7 says `Barbell RDL (tempo)`, string matching produces two timelines for one movement and the comparison quietly fails.

So:

- `exercise` holds a member-owned canonical name and an optional demo link.
- `exercise_alias` stores only human-confirmed source spellings for that exercise. A later import can propose the known match, but the member still confirms it in review.
- Per-week coaching text (`Keterangan`) is a **note on the prescription**, never part of identity.
- Authoring includes a match step: is this movement one we already have, or new?

There is no substitution execution model. If the trainer changes a movement, that adjustment appears as an ordinary prescription in the next authored week and is logged exactly like every other prescribed movement.

Cheap now. Painful to retrofit once months of logged sets are keyed to strings.

---

## Reference: Why There Is No Guest Access

An earlier draft of this phase gave the trainer a login. It is worth recording why that was cut, because the reasoning is what keeps it cut.

Adding a non-workspace user breaks three assumptions at once. **Identity stops being Slack-brokered** — the auth design rests on the workspace being private, so membership *is* identity and `auth_nonce.user_id` can be a Slack user ID; a trainer has no Slack identity, so the system would need a generalized identity model. **Authorization becomes real, and fails silently** — today a valid session reaches expenses, wallets, budgets, and shopping, so every existing route would need a fail-closed guard. And **stateless authentication sessions cannot be revoked**, which is exactly what durable third-party access demands, so a server-side authentication-session table becomes mandatory.

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

There is deliberately **no coach access, no guest identity, no server-side authentication-session table, and no share link.** Each was designed for a problem iteration 3 removes: if execution lands in the trainer's own document, they have no reason to open this app. See *Deferred* for the condition that would revive them.

**Do not build a *deterministic* spreadsheet parser.** The layout is repeated table blocks with prose in numeric columns, per-document column variation, and section headers as rows — authored by someone who will restructure it whenever it suits them. A hand-written parser is brittle against exactly that drift.

LLM extraction (iteration 2) has the opposite failure profile: tolerant of layout change, imprecise on detail. That is the right trade here, because a human reviews every import before it is saved. It is deliberately placed *after* manual entry — extraction needs a known target shape, and iteration 1 is what establishes it.

The trainer will keep using spreadsheets — they run dozens of clients that way and have their own reconciliation on top. So import is not a stopgap until they adopt the app; it is how the app reads its input, permanently.

**Enter one week of *each* program before writing any authoring UI.** The two members' programs differ in kind, and the observed column sets already differ across three documents. Entering only one program will make the model look correct when it is merely narrow.

---

# Iteration 1 — Private Programs and Gym Execution

The member-facing loop: see this workout's session for the current week, log sets against it. Private to the owning member, exactly like every budgeting surface. No sharing, no coach access.

The goal is as much **schema validation as feature delivery** — get two real programs in and find out where the model bends.

### 1.1 The gym screen

Design target: **empty means not yet recorded.** Every new execution input starts blank. The prescription remains visible immediately above it for reference, but no prescribed or previously populated sheet value is copied into execution. A set becomes evidence only after the member enters the actual values and presses **Log**.

```
Full Body WO 1 · week 2              ⌄ workout  ⌄ week

STRAIGHT SET

Barbell RDL                                   ▶ demo
  3 × 10 @ 45kg · RIR 3
  tempo: turun 5 detik, naik 2 detik
  cues ⌄

  ①  10 reps · 45kg · RIR 3                        ✓
  ②  [  ]    [  ]    [  ]                      log ›

Pronated grip lat pull down                   ▶ demo
  3 × 12 @ 33kg · RIR 3
  ①  12 · 33kg · RIR 0                             ✓
  ②  11 · 33kg · RIR 0                             ✓
  ③  [  ]    [  ]    [  ]                      log ›

SUPERSET                                      ⇄ alternate
DB alternating hooklying skullcrushers · 3 each × 15 @ 4kg
Bear hold pull through · 3 × 20 total · 15s to failure
```

Workout is the primary selector and week the secondary, per the workout-dimension reference — the same authored week number appears independently under several workouts.

**"Current" first means an in-progress session for the selected workout; otherwise it means the next uncompleted, unskipped week.** Never a calendar derivation: weeks are authored, missed sessions are not made up, and any date-based rule breaks the first time one is skipped.

**Sets commit immediately.** No session-level save button. A member is between sets, sweaty, and may close the app at any point — the same immediate-durability argument as the shopping list card, for the same reason. An explicit **Finish workout** action marks the session complete; it does not save the sets, which are already durable.

Blank fields are never interpreted as matching the prescription. They remain null until the member supplies an actual value, and merely opening or revisiting a session creates no performed set. This matters especially for delayed entry: the app must not turn a remembered target, or a trainer's copied execution cells, into claimed performance.

Each exercise renders its already logged sets followed by **one blank set editor**. The editor targets the lowest positive set number that is not currently logged; when there are no gaps, that is the next sequential number. When a gap exists, the correction slot is the default, but the member may instead choose the next new number — for example, **Correct Set 1** or **Log new Set 4** — without filling or renumbering the gap. Pressing **Log** fills the chosen stable slot, then clears the editor for another set. The UI does not parse the prescription's `sets` prose to decide how many rows to create, and no empty placeholder is persisted. This makes both corrections and additional sets ordinary.

**Set numbers are stable slots and never compact automatically.** If Set 1 is deleted, Sets 2 and 3 remain Sets 2 and 3; the blank editor becomes Set 1 so the member can enter the corrected first set without changing already-correct data. **Delete set** is a direct action inside that set's editor with no extra confirmation modal. Deletion is soft: the row is hidden from active execution by `deleted_at`, but retained so it can be restored, so replacing the same slot preserves its target snapshot, and so a later sheet preview can clear previously written cells. Logging into a deleted slot restores that row with the corrected execution values rather than creating a different set. Delete and restore both update the session's execution timestamps.

**Doing the workout and entering it are different events.** `performed_on` is the member-supplied date the workout happened in real life and defaults to today. `started_at`, `updated_at`, and `completed_at` describe interaction with the app. A member may therefore enter a workout days after performing it without falsifying the training date.

There is **one attempt per workout week**. A partially entered attempt remains `IN_PROGRESS` and is resumed when the member returns. **Finish workout** is allowed even when some or all prescribed movements have no logged sets; completion is a workflow marker, not a completeness validation. Finishing changes the status to `COMPLETED`, advances the default "current" week, and makes the session eligible for sheet write-back.

Completion is not a lock. A completed session remains available from the week selector and may be reviewed or corrected days later; ordinary edits leave it `COMPLETED`. An explicit **Resume workout** reverses an accidental finish by returning it to `IN_PROGRESS` and clearing `completed_at`. Editing reps, duration, load, or RIR updates both `updated_at` and `execution_updated_at`, making a previously written session unsynced again. Editing metadata that is not written to the sheet, such as `performed_on`, the session note, or a set note, updates `updated_at` only and does not by itself require another sheet write. The set mutation and both timestamp updates happen in the same database transaction.

**Another blank set editor is always available after logging.** Execution exceeding prescription is normal, not an error state.

Prescription prose renders verbatim. Long `Keterangan` text collapses behind a `cues` disclosure so it does not push the inputs below the fold.

### 1.2 Schema

```sql
create table exercise (
    id          uuid primary key default gen_random_uuid(),
    owner_user_id text not null,
    name        text not null,
    demo_url    text,
    created_at  timestamptz not null default now(),
    unique (id, owner_user_id),
    check (btrim(name) <> '')
);

create unique index exercise_owner_name_ci
    on exercise (owner_user_id, lower(btrim(name)));

-- A source spelling becomes reusable only after a member confirms the match.
create table exercise_alias (
    id            uuid primary key default gen_random_uuid(),
    exercise_id   uuid not null,
    owner_user_id text not null,
    alias         text not null,
    created_at    timestamptz not null default now(),
    foreign key (exercise_id, owner_user_id)
        references exercise(id, owner_user_id) on delete cascade,
    check (btrim(alias) <> '')
);

create unique index exercise_alias_owner_alias_ci
    on exercise_alias (owner_user_id, lower(btrim(alias)));

-- An open-ended training block owned by one member.
create table program (
    id           uuid primary key default gen_random_uuid(),
    owner_user_id text not null,                            -- authenticated Slack user ID
    name         text not null,
    note         text,                                     -- document header text
    starts_on    date,                                     -- decorative; weeks are authored, not scheduled
    active       boolean not null default true,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    check (btrim(name) <> '')
);

create unique index program_one_active_per_owner
    on program (owner_user_id) where active;

create index program_owner_idx on program (owner_user_id);

-- A recurring session within the block: "Full Body WO 1" (day 1), "Full Body WO 2" (day 2).
-- Count varies per block and per member (observed: 1, 2, and 3 per week).
create table workout (
    id         uuid primary key default gen_random_uuid(),
    program_id uuid not null references program(id) on delete cascade,
    name       text not null,
    note       text,                       -- per-document instruction, e.g. the video request
    position   integer not null,
    unique (program_id, position),
    check (position >= 1),
    check (btrim(name) <> '')
);

-- One week of one workout.
create table workout_week (
    id          uuid primary key default gen_random_uuid(),
    workout_id  uuid not null references workout(id) on delete cascade,
    week_number integer not null,       -- the trainer's sequence, not elapsed time
    skipped_at  timestamptz,            -- set when the trainer moves past an unperformed week
    unique (workout_id, week_number),
    check (week_number >= 1)
);

-- Group headers within a week. Label is authored prose; kind drives UI only.
create table workout_group (
    id       uuid primary key default gen_random_uuid(),
    week_id  uuid not null references workout_week(id) on delete cascade,
    label    text not null,                -- "FINISHER SUPERSET (DIKERJAKAN BERGANTIAN)"
    kind     text not null,                -- STRAIGHT_SET | SUPERSET
    position integer not null,
    unique (week_id, position),
    check (kind in ('STRAIGHT_SET', 'SUPERSET')),
    check (position >= 1),
    check (btrim(label) <> '')
);

-- The prescription. Every quantity is authored text, and every field is optional
-- because the trainer varies their columns per workout document.
create table prescription (
    id          uuid primary key default gen_random_uuid(),
    group_id    uuid not null references workout_group(id) on delete cascade,
    exercise_id uuid not null references exercise(id) on delete restrict,
    position    integer not null,
    execution_type text not null, -- REPS | REPS_PER_SIDE | DURATION; human-confirmed
    sets        text,        -- "3", "2 each", "3 each"
    rest        text,        -- "45-60sec"
    reps        text,        -- "10", "10-12", "20 total", "45 sec"
    load        text,        -- "20-25 kg", "3-4 kg each", "selutut di squat rack"
    rir         text,        -- "3", "15 secs to failure" — absent in some documents
    tempo       text,        -- "turun 5 detik naik 2 detik"
    note        text,        -- Keterangan: setup + execution cues
    archived_at timestamptz, -- removed from future training, retained for history
    unique (group_id, position),
    check (position >= 1),
    check (execution_type in ('REPS', 'REPS_PER_SIDE', 'DURATION'))
);

-- The single attempt for one (workout, week). Ownership is inherited from program.
-- performed_on is when the workout happened; the timestamps below describe app activity.
create table training_session (
    id           uuid primary key default gen_random_uuid(),
    week_id      uuid not null references workout_week(id) on delete restrict,
    performed_on date not null,
    status       text not null default 'IN_PROGRESS',
    note         text,
    started_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    execution_updated_at timestamptz,
    completed_at timestamptz,
    unique (week_id),
    check (status in ('IN_PROGRESS', 'COMPLETED')),
    check (
        (status = 'IN_PROGRESS' and completed_at is null)
            or (status = 'COMPLETED' and completed_at is not null)
    )
);

-- A prescribed movement as actually performed. There is no separate substitution type.
create table performed_exercise (
    id              uuid primary key default gen_random_uuid(),
    session_id      uuid not null references training_session(id) on delete cascade,
    exercise_id     uuid not null references exercise(id) on delete restrict,
    prescription_id uuid not null references prescription(id) on delete restrict,
    position        integer not null,
    note            text,
    -- Complete historical display snapshot, captured when the first set is logged.
    target_group_label   text not null,
    target_group_kind    text not null,
    target_exercise_name text not null,
    target_demo_url      text,
    target_execution_type text not null,
    target_sets          text,
    target_rest          text,
    target_reps          text,
    target_load          text,
    target_rir           text,
    target_tempo         text,
    target_note          text,
    unique (session_id, prescription_id),
    unique (session_id, position),
    check (position >= 1),
    check (target_group_kind in ('STRAIGHT_SET', 'SUPERSET')),
    check (target_execution_type in ('REPS', 'REPS_PER_SIDE', 'DURATION'))
);

-- One logged set. Typed, and carrying its own target snapshot.
create table performed_set (
    id                    uuid primary key default gen_random_uuid(),
    performed_exercise_id uuid not null references performed_exercise(id) on delete cascade,
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
    updated_at            timestamptz not null default now(),
    deleted_at            timestamptz,
    unique (performed_exercise_id, set_number),
    check (set_number >= 1),
    check (reps is null or reps >= 0),
    check (duration_s is null or duration_s >= 0),
    check (load is null or load >= 0),
    check (num_nonnulls(reps, duration_s) = 1)
);

create index workout_program_idx on workout (program_id);
create index exercise_alias_exercise_idx on exercise_alias (exercise_id);
create index workout_week_workout_idx on workout_week (workout_id);
create index workout_group_week_idx on workout_group (week_id);
create index prescription_group_idx on prescription (group_id);
create index prescription_exercise_idx on prescription (exercise_id);
create index training_session_week_idx on training_session (week_id);
create index performed_exercise_session_idx on performed_exercise (session_id);
create index performed_set_exercise_active_idx
    on performed_set (performed_exercise_id, set_number) where deleted_at is null;
```

Note `performed_set` carries **both** `reps` and `duration_s`, nullable. `execution_type` tells the UI which one to require. A plank prescribed `45 sec` logs seconds held; putting 45 into `reps` would be semantically wrong and would corrupt any future trend on repetitions. `REPS_PER_SIDE` remains distinct from `REPS` for labels and future comparison, without adding another numeric column.

Hard deletes cascade only through unperformed authoring structure. The `RESTRICT` links from sessions and performed exercises stop those cascades once history exists. The application exposes deactivation/archive for historical programs and prescriptions, not hard delete. Activating a program, skipping/restoring a week, and finishing/resuming a session each happen in one flat `dbQuery` transaction.

Register every new table in the pgen `tableFilter` allowlist before regenerating. Generated tables are plain `Table`, so ids are client-side `UUID.randomUUID()`.

Training ownership uses the Slack user ID already carried by `UserSession`. There is no generalized user table in this phase. Every program, workout, week, session, and performed-set query must join back to `program.owner_user_id` and require it to equal the authenticated session's `userId`; a foreign ID is reported as not found. Prescription writes also verify that `exercise.owner_user_id` equals the program owner, so an ID from another member cannot be attached indirectly.

### 1.3 Authoring (manual)

A web form for entering a block: program name, workouts, then an open-ended list of authored weeks, groups, and prescriptions. It never asks for an expected week count. **Copy-forward is essential** — weeks in a block repeat the same movements with adjusted numbers, so "duplicate week 1 into week 2" turns repeated entry into one week plus edits.

Exercise selection is a combobox over the member's existing `exercise` rows and confirmed aliases, with an inline create. This is where canonical identity is enforced. Every prescription requires an explicit `REPS`, `REPS_PER_SIDE`, or `DURATION` execution type; there is no default to silently misclassify a movement.

Removing a prescription deactivates it immediately rather than destroying it. It disappears from future/current training but stays visible through performed-exercise snapshots in execution history. No extra confirmation modal is required. A new trainer-side substitution is simply another ordinary prescription in the affected authored week.

### Definition of Done

- [ ] All tables created, registered in the pgen allowlist, tables regenerated
- [ ] A block with **multiple workouts per week** can be hand-entered
- [ ] Groups, finisher supersets, and prose prescriptions all round-trip verbatim
- [ ] Copy-forward duplicates a week's structure for editing
- [ ] At most one program is active per member; activating one deactivates the previous program atomically and can be reversed
- [ ] No expected week count is requested or stored; authored week labels/ranges are derived per workout
- [ ] A program/workout with execution history cannot be hard-deleted
- [ ] Exercise names and aliases are case-insensitively unique per member
- [ ] Exercise selection reuses existing rows and confirmed aliases; new names are created deliberately, not by typo
- [ ] Every prescription has a human-confirmed `REPS`, `REPS_PER_SIDE`, or `DURATION` execution type, with no default
- [ ] This week's session renders on mobile with one-handed reach and no horizontal scroll
- [ ] Every new execution input starts blank; targets are visible for reference but never copied into the inputs
- [ ] Opening or revisiting a session does not create a performed set
- [ ] Each exercise shows logged sets plus one blank next-set editor; logging clears it for the next set
- [ ] The number of editors is never derived by parsing prescribed `sets` prose
- [ ] Set numbers are stable: deleting an earlier set never renumbers later sets
- [ ] The blank editor targets the lowest missing set number, or the next sequential number when there is no gap
- [ ] When a gap exists, the member can correct the missing slot or explicitly log the next new set without renumbering
- [ ] Deleting a set soft-deletes it; logging the same slot restores it with corrected values and its original target snapshot
- [ ] A logged set has exactly one of reps or duration; load, RIR, and note remain optional
- [ ] Execution is never validated against the prescription
- [ ] Each set commits immediately; no session-level save
- [ ] There is one `training_session` per workout week
- [ ] A partially entered session remains `IN_PROGRESS` and is resumed on return
- [ ] **Finish workout** allows partial execution and marks the session `COMPLETED` without performing a second save of its sets
- [ ] **Resume workout** reverses an accidental finish; ordinary history edits do not reopen a completed session
- [ ] `performed_on` records when training happened, independently of when the session was entered or edited
- [ ] A completed session remains editable and stays `COMPLETED`
- [ ] Reps/load/RIR/duration edits atomically update the set's `updated_at` and the session's `updated_at` and `execution_updated_at`
- [ ] Metadata-only edits update the relevant row and session `updated_at`, but not `execution_updated_at`
- [ ] Editing execution after a successful sheet write makes the session visibly unsynced again
- [ ] Logging reveals another blank editor, allowing execution beyond prescription
- [ ] The first logged set creates a performed-exercise snapshot containing the complete prescription display
- [ ] Every logged set stores its set-relevant target snapshot
- [ ] Editing a prescription after logging does not alter logged targets
- [ ] Deactivating a prescription removes it from future training without removing its execution or prescribed-target history
- [ ] Prescription prose renders verbatim, including tempo and per-side instructions
- [ ] Timed movements log `duration_s` rather than `reps`
- [ ] Group labels render as authored, including finisher/superset parentheticals
- [ ] A workout with no prescribed RIR column renders without an empty RIR affordance
- [ ] The gym screen resolves "current" as an in-progress session first, then the next uncompleted, unskipped week — no calendar arithmetic
- [ ] Skip and restore are explicit, reversible actions; skip is never inferred from later sessions
- [ ] Logging a skipped week restores it automatically; a completed week cannot also be skipped
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

**Encryption at rest is net-new — the session cookie only signs, it does not encrypt.** `configureSecurity` uses `SessionTransportTransformerMessageAuthentication` (sign, don't encrypt), which proves *not tampered* but hides nothing. A refresh token is a long-lived key to a member's Drive, so plaintext in the DB turns any backup, `pg_dump`, or read-replica leak into third-party account takeover. Add a small symmetric utility (AES-GCM: confidentiality *and* an auth tag) with the key sourced from config the same way `session.signKey` is (`Security.kt`), key held outside the repo and rotatable. Encrypt on write into `google_credential.refresh_token`; decrypt only at the moment of a token refresh; never log plaintext or ciphertext. The key-location decision (env var, matching the current sign-key pattern, versus a KMS) is deliberate, not a default.

**Build the Google integration read-path-first, so failures surface cheap.** This is the first external integration beyond Slack and OpenRouter, and the entire write-back safety model (iteration 3) rests on reading *formatted display values plus A1 addresses* from the grid API — a flattened export would make provenance wrong from day one. Order: (1) confirm live scope names and Picker behaviour *before* writing client code, keeping `drive.file` least-privilege intact; (2) build and prove the read path (OAuth connect → Picker file id → Sheets grid read returning display values **with** addresses) against a real fixture sheet; (3) only then build write-back. Put the Sheets calls behind an interface with a fake boundary so drift/conflict/verify logic is testable without the network (see Cross-Cutting).

```sql
create table google_credential (
    user_id        text primary key,       -- authenticated Slack user ID
    refresh_token text not null,          -- encrypted at rest
    scope         text not null,
    connected_at  timestamptz not null default now(),
    revoked_at    timestamptz
);
```

### 2.3 Two entry points, both manual

| Action | What it does |
|---|---|
| **Select file** | Opens the Picker and starts a persisted import for the active program |
| **Sync** | Re-extracts from the already-linked spreadsheet |

Nothing else triggers a read. There is no background poll, no refresh on page load, and no sync on a timer. The trainer edits their sheet on their own schedule; a member decides when to pull those edits in, having usually just been told about them.

`sheet_link` is unique per program. **Select file** on an already-linked program replaces the link only after a warning and a new review; it does not silently retarget existing provenance.

### 2.4 Reading the sheet

Read formatted display values **and their addresses** via the Sheets API — a grid of rows and columns per tab, not a flattened export. Position carries meaning here: week headers, group headers, and the prescription/execution split are all spatial. Do not derive prescription text from the underlying numeric/date cell type.

**Tab mapping is confirmed before extraction.** The app lists every tab and may propose that `Full Body 1`, `Full Body 2`, and `Full Body 3` map to workouts while `Warming Up` and `Macro Check In` are excluded. The member must confirm, correct, or exclude every tab. A workout tab may map to an existing workout or deliberately create a new one; the model cannot decide this on its own.

For each confirmed workout tab, the app then proposes the row range of every authored week. The member can change week numbers, move start/end rows, add a missed range, or exclude a proposed range. Only confirmed week ranges proceed. Extraction never scans an entire tab and guesses where one week's output should stop.

Before prescription extraction, establish the execution boundary from headers and layout. Known labels include `Eksekusi` and `Realisasi`, but neither the word nor the starting column is fixed. Boundary detection receives header structure only, not execution data values. If the boundary is missing or ambiguous, stop and ask the member to identify the first execution column; never guess and never send the complete populated grid onward.

After the boundary is known, redact every non-header cell in the execution region **before** building the LLM request or import draft. Keep only the execution header, set/field header coordinates, and target-cell addresses required for provenance. Execution values are not sent to the model, persisted, logged, compared during sync, or used to create `training_session`, `performed_exercise`, or `performed_set` rows.

Extraction runs **one confirmed week range at a time**, not one document at a time. Weeks are the repeating unit, output stays bounded, and a bad week is re-run without discarding the rest.

### 2.5 Persisted import lifecycle

An import is durable across API calls and browser refreshes. Its state moves forward as follows:

```
READING → NEEDS_MAPPING → EXTRACTING → REVIEW → APPLIED
    └──────────────→ FAILED          └──────→ CANCELLED
```

`READING` fetches spreadsheet metadata and formatted grids. `NEEDS_MAPPING` waits for tab, workout, week-range, and ambiguous execution-boundary decisions. `EXTRACTING` processes only confirmed ranges. `REVIEW` holds editable proposals and exercise decisions. `APPLIED` is terminal and records the one explicit draft-to-domain transition. A failed import may be retried into the appropriate non-terminal state; cancellation saves the audit trail but creates nothing.

```sql
create table training_import (
    id             uuid primary key default gen_random_uuid(),
    program_id     uuid not null references program(id) on delete cascade,
    spreadsheet_id text not null,
    state          text not null,
    error_detail   text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    applied_at     timestamptz,
    check (state in (
        'READING', 'NEEDS_MAPPING', 'EXTRACTING', 'REVIEW',
        'APPLIED', 'FAILED', 'CANCELLED'
    ))
);

create table training_import_tab (
    id                 uuid primary key default gen_random_uuid(),
    import_id          uuid not null references training_import(id) on delete cascade,
    google_sheet_id    bigint not null, -- stable numeric tab ID; title may change
    tab_title          text not null,
    decision           text,            -- WORKOUT | EXCLUDE; null until human-reviewed
    target_workout_id  uuid references workout(id) on delete restrict,
    new_workout_name   text,
    position           integer not null,
    unique (import_id, google_sheet_id),
    check (decision is null or decision in ('WORKOUT', 'EXCLUDE')),
    check (position >= 1)
);

create table training_import_week (
    id                   uuid primary key default gen_random_uuid(),
    import_tab_id        uuid not null references training_import_tab(id) on delete cascade,
    target_week_id       uuid references workout_week(id) on delete restrict,
    week_number          integer not null,
    start_row            integer not null,
    end_row              integer not null,
    decision             text,          -- KEEP | EXCLUDE; null until human-reviewed
    extracted_draft      jsonb,
    base_source_snapshot jsonb,         -- last applied source for three-way comparison
    source_snapshot      jsonb,         -- newly extracted, redacted prescription source
    source_hash          text,
    unique (import_tab_id, week_number),
    check (week_number >= 1),
    check (start_row >= 1 and end_row >= start_row),
    check (decision is null or decision in ('KEEP', 'EXCLUDE'))
);

create table training_import_exercise_match (
    id                 uuid primary key default gen_random_uuid(),
    import_week_id     uuid not null references training_import_week(id) on delete cascade,
    source_movement_key text not null, -- stable within the draft, e.g. source cell address
    source_text        text not null,
    decision           text,           -- MATCH | CREATE | EXCLUDE; null until human-reviewed
    exercise_id        uuid references exercise(id) on delete restrict,
    new_exercise_name  text,
    remember_as_alias  boolean not null default true,
    unique (import_week_id, source_movement_key),
    check (decision is null or decision in ('MATCH', 'CREATE', 'EXCLUDE'))
);
```

Draft rows may contain model proposals, but nullable `decision` columns distinguish a proposal from a human decision. The final **Apply** transaction refuses any included tab, week, movement, or execution type that has not been explicitly resolved.

### 2.6 Extraction target

Strict structured output, nested to match the schema, with **cell addresses carried alongside every extracted movement** (see 2.7):

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
                "execution_type": {
                  "type": "string",
                  "enum": ["REPS", "REPS_PER_SIDE", "DURATION"]
                },
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
    "execution_columns": {
      "type": "array",
      "description": "Per set index, the column letters for reps/load/rir under the execution group.",
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
  "required": ["week_number", "groups", "execution_columns"],
  "additionalProperties": false
}
```

Prompt guidance worth encoding, all drawn from observed documents:

- **Copy cells verbatim.** Do not normalise `45-60sec` to `45-60s`, do not convert `10-12` to a number, do not translate Indonesian cues.
- **Extract only prescription values.** Everything in the detected execution column group is excluded, even when populated. Those values may have been copied from an earlier week and do not prove current execution. Its *header and column positions* are captured; its *values* are redacted before extraction.
- **Column sets vary per document.** A missing `RIR` or `Tempo` column means null, not an inferred value.
- **Group headers are rows**, not columns: `STRAIGHT SET`, `FINISHER SUPERSET (…)`. Capture the label verbatim; set `kind` from whether it describes alternating work.
- **A load cell may not describe load.** `selutut di squat rack / setinggi bench` is a setup instruction. Keep it in `load` as written.
- **Execution type is only a proposal.** `2 each` may suggest `REPS_PER_SIDE` and `45 sec` may suggest `DURATION`, but the member must explicitly confirm or correct it before Apply. It is a plain non-nullable string enum and is **omitted from `required`** — absence means "no confident proposal," which the member must then set. This matches the existing extraction schemas (`ExtractionSchema.kt`: nullable free-text via `["string","null"]`, constrained values via a plain string `enum`); a `null` literal inside an `enum` is a construct the codebase does not use and the structured-output validator may reject.

### 2.7 Cell provenance — the load-bearing part

Write-back (iteration 3) needs a mapping from (prescription, set number, field) to a real cell address. **The only moment that mapping can be built reliably is while reading the grid**, so import must capture it even though nothing consumes it yet.

```sql
create table sheet_link (
    id             uuid primary key default gen_random_uuid(),
    program_id     uuid not null references program(id) on delete cascade,
    spreadsheet_id text not null,
    connected_by_user_id text not null,   -- authenticated Slack user ID
    created_at     timestamptz not null default now(),
    unique (program_id)
);

-- Where this week lives in the sheet, including stable tab identity and range anchors.
create table sheet_week_link (
    week_id                  uuid primary key references workout_week(id) on delete cascade,
    sheet_link_id            uuid not null references sheet_link(id) on delete cascade,
    google_sheet_id          bigint not null, -- stable numeric tab ID
    tab_title                text not null,   -- display/audit only; safe to update on rename
    week_start_row           integer not null,
    week_end_row             integer not null,
    execution_boundary_col   integer not null,
    execution_header_address text not null,
    execution_header_value   text not null,   -- e.g. Eksekusi or Realisasi
    source_snapshot          jsonb not null,  -- redacted prescription-side source at Apply
    source_hash              text not null,
    imported_at              timestamptz not null default now(),
    check (week_start_row >= 1 and week_end_row >= week_start_row),
    check (execution_boundary_col >= 1)
);

-- Exact source and destination cells for one movement.
create table sheet_prescription_link (
    prescription_id   uuid primary key references prescription(id) on delete cascade,
    week_id           uuid not null references workout_week(id) on delete cascade,
    movement_address  text not null, -- e.g. B12; row and movement column are both explicit
    movement_text     text not null, -- exact formatted text used as the drift anchor
    prescription_cells jsonb not null, -- field → A1 address for source provenance
    execution_cells   jsonb not null, -- set number → {reps, load, rir} exact A1 addresses
    source_snapshot   jsonb not null,
    source_hash       text not null
);
```

The spreadsheet ID plus numeric `google_sheet_id` form the stable remote identity; `tab_title` is retained for human-readable previews but is never used to locate the tab. Week ranges, the execution boundary/header address/value, every prescription source cell, and every per-set destination address are stored exactly. `movement_text` is the drift anchor: before any write, the app re-reads `movement_address` and confirms the formatted value still matches. If it does not, the sheet has been restructured and the write is refused.

### 2.8 Confirmation — choose which weeks to apply

Extraction produces a **draft**, never a saved week. Nothing reaches `prescription` until a member confirms, and confirmation is **per week**, not all-or-nothing.

**Every import insert is human-reviewed.** LLM output cannot directly create a program, workout, week, group, prescription, or exercise. For each proposed week, the member can keep it, exclude it, or edit any extracted field. For each movement, they must match an existing exercise, deliberately create a new exercise, or exclude the movement. The final **Apply** action is the only transition from import draft to domain tables; direct member logging is already an explicit human action and does not require a second confirmation screen.

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

**All weeks are included by default** so the member does not have to select every row. This does not bypass review: Apply remains disabled until each included movement, execution type, and conflict is resolved. Deselecting is how a member scopes a sync down to the week that actually moved.

The per-week annotation is what makes that choice informed rather than blind: comparing extracted values against what is already stored costs nothing and turns "which of these eight weeks do I want" into a decision someone can actually make. A week whose stored values were locally edited and now differ from the sheet is flagged distinctly and resolved through the three-way rules below rather than silently overwritten.

Selected weeks then render in the iteration-1 authoring UI, fully editable, before the final save. Each included movement must also have its execution type explicitly confirmed. Same discipline as the expense confirmation card: the model proposes, a human disposes.

### 2.9 Exercise matching is the risky step

Movement names arrive as strings and must resolve to canonical `exercise` rows. This is where silent damage happens: auto-creating `DB hooklying skullcrusher` alongside an existing `DB alternating hooklying skullcrushers` splits one movement's history into two, and it will not be noticed for months.

Propose matches, never auto-create. For each extracted movement show the best existing candidates and require an explicit choice between *"this is that exercise"*, *"this is new"*, and *"exclude"*. Fuzzy matching and confirmed aliases narrow the list; a human makes the call.

When the member confirms that a new source spelling is an existing exercise, store it in `exercise_alias` unless they uncheck **Remember this name**. An alias improves the next proposal but never bypasses review or creates a cross-member match.

This is the one part of the import that should feel slow.

### 2.10 Re-import and three-way reconciliation

Re-import replaces a prior **draft**, never saved data. For an applied week, reconciliation compares three versions:

```
base       last sheet source explicitly applied by the member
local      the prescription currently stored in the app
incoming   the newly extracted sheet prescription
```

The base is the redacted `source_snapshot` saved with provenance at the previous Apply. Comparison is field-aware but the review remains movement-oriented:

| Comparison | Proposed result |
|---|---|
| local = base, incoming changed | **Use sheet** |
| incoming = base, local changed | **Keep local** |
| local = incoming | already resolved |
| local and incoming changed differently | require **Use sheet**, **Keep local**, or a manual edit |
| incoming movement is new | require include + exercise match/create, or exclude |
| movement disappeared from incoming | require **Keep local** or **Remove/deactivate** |

No conflict has a silent winner. **Remove/deactivate** archives the prescription; it never deletes a performed-exercise snapshot or logged set. Applying incoming changes updates existing domain rows in place where identities still match, preserving their IDs and execution links. Re-import cannot create, update, or delete `training_session`, `performed_exercise`, or `performed_set` rows.

Only the final Apply transaction changes domain tables, confirmed aliases, and provenance. It rechecks that the import is still in `REVIEW`, applies all resolved choices atomically, records the new base snapshots/hashes, and moves the import to `APPLIED`. A stale/double Apply becomes a harmless conflict response rather than applying twice.

### Definition of Done

- [ ] Google connected with `drive.file` scope; file chosen via the Picker
- [ ] Refresh tokens encrypted at rest, revocable in-app, never logged
- [ ] Sheet read through the Sheets API preserving cell addresses
- [ ] Every tab is explicitly mapped to an existing/new workout or excluded before extraction
- [ ] Non-workout tabs such as warming and check-in tabs can be excluded during mapping
- [ ] Proposed week numbers and row ranges are human-correctable and confirmed before extraction
- [ ] Prescription values use the sheet's formatted display text, without scalar-type reinterpretation
- [ ] Execution boundary supports observed `Eksekusi` and `Realisasi` layouts without assuming a fixed starting column
- [ ] Missing or ambiguous execution boundaries stop for human selection rather than guessing
- [ ] Extraction runs per confirmed week range and returns schema-valid structured output
- [ ] Values copied verbatim — ranges, units, Indonesian cues, tempo prose unchanged
- [ ] Populated execution values are redacted before the LLM request and are never persisted or logged by import
- [ ] Only execution headers and column positions are captured; values never create or prefill logged sets
- [ ] An import fixture with populated execution cells produces prescriptions but zero sessions, performed exercises, or performed sets
- [ ] Absent columns yield null, not inferred values
- [ ] Group labels captured verbatim; `kind` correct for supersets and finishers
- [ ] Import lifecycle persists `READING → NEEDS_MAPPING → EXTRACTING → REVIEW → APPLIED`, with recoverable `FAILED` and terminal `CANCELLED`
- [ ] A browser refresh loses no completed mapping, extraction, or review decisions
- [ ] `sheet_link`, `sheet_week_link`, and `sheet_prescription_link` capture stable numeric tab ID, display title, week range, execution boundary/header, exact movement/source cells, per-set destination cells, and redacted source snapshot/hash
- [ ] **Select file** and **Sync** are the only ways a read happens — no poll, no timer, no read on page load
- [ ] Re-linking a program to a different spreadsheet warns first and does not silently retarget old provenance
- [ ] Confirmation lists every extracted week, all selected by default, individually deselectable
- [ ] Each week is annotated unchanged / changed / new, with changed weeks naming what differs
- [ ] A week whose stored values were locally edited is flagged distinctly before overwrite
- [ ] Deselected weeks are not written at all
- [ ] Nothing saved without explicit confirmation in the authoring UI
- [ ] LLM output cannot insert any domain row before the member's final **Apply** action
- [ ] Review supports keeping, editing, or excluding each proposed week and movement
- [ ] Each movement is explicitly matched, deliberately created as new, or excluded
- [ ] Every included movement has an explicitly confirmed execution type; an LLM proposal is never accepted as a default
- [ ] Exercise matching proposes candidates and requires a choice; no silent creation
- [ ] A confirmed source spelling can be stored as a member-scoped alias and improves later proposals without bypassing review
- [ ] Re-import performs a base/local/incoming comparison and exposes Use sheet, Keep local, manual edit, exclude, and remove/deactivate where applicable
- [ ] Concurrent local and sheet changes have no silent winner
- [ ] Applying a source removal archives the prescription and preserves all execution history
- [ ] Re-import replaces a draft, updates matched rows in place, and cannot reach logged sets
- [ ] Final Apply is one transaction and cannot be applied twice
- [ ] A full real block imports faster than hand entry, with corrections needed recorded
- [ ] Extraction failure degrades to manual entry rather than blocking the block

---

# Iteration 3 — Write Execution Back to the Sheet

A button that fills the sheet's execution cells (`Eksekusi` or `Realisasi`) for a logged session, so the trainer reads execution where they already work.

### 3.1 What is written, and what is never touched

**Written:** for each active performed set, the primary value plus every available optional destination in that movement's matching execution set columns. `REPS` and `REPS_PER_SIDE` write the entered integer to the reps cell. `DURATION` writes `duration_s` as display text such as `45 sec`. A numeric load is sent as a numeric Sheet value without adding `kg`; RIR is an integer. Notes are not written.

For an active logged set, a null optional value is authoritative and means **clear that destination cell**. This deliberately removes a trainer-copied load or RIR rather than preserving it as if the member performed it. A completely missing performed-set slot means **do not write that set at all**; absence is not converted into a row of clears.

**Cleared:** null optional fields on active sets, and execution cells for a set that was successfully written and later deleted. A deletion never shifts later sets into earlier sheet columns. The preview shows each proposed clear exactly like any other cell change, and confirmation remains required before the sheet is modified.

**Never written:** prescription cells, group headers, week headers, formatting, or any other tab. The app writes only to the designated execution cells and nowhere else. Those cells may already contain copied or manually entered values; iteration 3's conflict preview handles that explicitly rather than treating them as import data.

If any active set number has no corresponding destination set columns in provenance, the whole write is blocked before confirmation. The app never inserts columns, silently omits the extra set, or writes the other movements while truncating this one. The member must expand the sheet and re-import its mapping first.

### 3.2 Preview before write — a dry run, not a warning

Pressing **Write to sheet** does not write. It produces a preview of exactly what would change, and the write happens only on a second, explicit confirmation.

```
Write to "Junda – Full Body" · tab "Full Body WO 1" · week 3

  DB zercher bench squat        row 12
    Set 1   K12 = 10    L12 = 5      M12 = 3
    Set 2   N12 = 10    O12 = 5      P12 = 3
    Set 3   Q12 =  8    R12 = 5      S12 = 2

  Incline push up               row 13
    Set 1   K13 = 8     L13 = clear  M13 = 3
    …

  18 cells across 4 movements. 2 target cells already contain values ⚠
                        [ Cancel ]   [ Write 18 cells ]
```

Two things earn this step:

**Drift detection runs here, so a mismatch is information rather than a failed action.** The anchor check (3.3) happens while building the preview. If the sheet has been restructured, the member is told *before* committing to anything: "the sheet has changed since import — re-import week 3 first." That is a much better experience than pressing a button and having it abort.

**Writing into someone else's working document deserves a look first.** The trainer runs dozens of clients through these sheets. Showing the target range, the values, and the count converts a leap of faith into a glance.

The preview is not a promise. Its exact observed and proposed cell values are persisted as a `PREPARED` write attempt. Confirmation re-runs the anchor check and re-reads every target cell immediately before executing, because the sheet can move between preview and confirmation. A late drift or newly conflicting value aborts the attempt and requires a fresh preview.

### 3.3 Drift detection gates every write

The trainer will insert rows, add weeks, and restructure between import and write-back. Writing to stale coordinates corrupts **their** working document, which is the worst failure available here — it is someone else's file, with other clients' expectations attached.

So, before every write:

Both when building the preview and immediately before executing:

1. Re-read the anchor cells for the target week using `sheet_prescription_link`.
2. Confirm each `movement_text` still matches its `movement_address`.
3. Confirm the stored execution header value still matches `execution_header_address` on the numeric `google_sheet_id` tab.
4. **Any mismatch aborts the whole write** and tells the member to re-import the week.

Never write optimistically, never write partially past a mismatch, and never "find the row again" heuristically — a fuzzy re-match that guesses wrong writes a member's numbers onto the wrong movement.

### 3.4 Non-empty cells

A target cell that already has a different value is a conflict, not an automatic overwrite. Either the trainer entered something, copied a prior execution, or a previous write-back ran. A cell that already equals the proposed value is not a conflict.

Conflicts surface **in the preview**, with existing and proposed values shown per cell but resolved **per movement**. For each affected movement, the member chooses **Replace movement with app data** or **Skip movement this write**. Replace applies all proposed values and clears for that movement together; there is no per-cell mixture that could leave one set half app-owned and half copied. Skip leaves that movement unsynced. No overwrite choice is remembered for the next write.

### 3.5 Exact-cell persistence

Every preview creates a write attempt whose projection, movement decisions, and proposed cell payload are immutable; only lifecycle status and post-write verification fields change. A count and free-text detail are not enough: deleted-set clearing needs to know which stable performed set previously owned which remote cells, and an ambiguous retry needs the exact typed values it may already have sent.

```sql
create table sheet_write (
    id                        uuid primary key default gen_random_uuid(),
    session_id                uuid not null references training_session(id) on delete restrict,
    spreadsheet_id            text not null,
    google_sheet_id           bigint not null,
    written_by_user_id        text not null, -- authenticated Slack user ID
    idempotency_key           uuid not null unique,
    execution_projection_hash text not null, -- complete app-authoritative session projection
    payload_hash              text not null, -- canonical confirmed remote cell payload
    status                    text not null default 'PREPARED',
    fully_synced              boolean not null default false,
    api_called                boolean not null default false,
    created_at                timestamptz not null default now(),
    status_updated_at         timestamptz not null default now(),
    finished_at               timestamptz,
    detail                    text,
    check (status in (
        'PREPARED', 'VALIDATING', 'SENDING', 'SUCCEEDED',
        'DRIFT_ABORTED', 'CONFLICT_ABORTED', 'VERIFY_CONFLICT',
        'UNKNOWN', 'FAILED'
    ))
);

create table sheet_write_movement (
    id                    uuid primary key default gen_random_uuid(),
    sheet_write_id        uuid not null references sheet_write(id) on delete cascade,
    performed_exercise_id uuid not null references performed_exercise(id) on delete restrict,
    prescription_id       uuid not null references prescription(id) on delete restrict,
    decision              text not null, -- APPLY | SKIP
    position              integer not null,
    unique (sheet_write_id, prescription_id),
    check (decision in ('APPLY', 'SKIP')),
    check (position >= 1)
);

create table sheet_write_cell (
    id                          uuid primary key default gen_random_uuid(),
    sheet_write_movement_id     uuid not null references sheet_write_movement(id) on delete cascade,
    performed_set_id            uuid not null references performed_set(id) on delete restrict,
    set_number                  integer not null,
    field                       text not null, -- REPS | LOAD | RIR destination field
    row_index                   integer not null, -- zero-based GridRange coordinate
    column_index                integer not null,
    cell_address                text not null, -- human-readable A1 address for preview/audit
    observed_user_entered_value jsonb, -- exact typed value before confirmation; SQL null means empty
    observed_formatted_value    text,
    action                      text not null, -- WRITE | CLEAR
    proposed_user_entered_value jsonb, -- SQL null only when action = CLEAR
    verified_user_entered_value jsonb,
    verified_formatted_value    text,
    verified_at                 timestamptz,
    unique (sheet_write_movement_id, set_number, field),
    unique (sheet_write_movement_id, row_index, column_index),
    check (set_number >= 1),
    check (field in ('REPS', 'LOAD', 'RIR')),
    check (row_index >= 0 and column_index >= 0),
    check (action in ('WRITE', 'CLEAR')),
    check (
        (action = 'WRITE' and proposed_user_entered_value is not null)
            or (action = 'CLEAR' and proposed_user_entered_value is null)
    )
);

create index sheet_write_session_created_idx
    on sheet_write (session_id, created_at desc);

create index sheet_write_cell_performed_set_idx
    on sheet_write_cell (performed_set_id);
```

`observed_user_entered_value`, `proposed_user_entered_value`, and `verified_user_entered_value` preserve Sheets value type: number, string, boolean, formula, or empty. The formatted value exists only for a readable preview. A clear is an explicit action rather than an ambiguous JSON null.

The desired projection contains:

- Every mapped field for every active performed set. Optional nulls become explicit clears.
- Every mapped field for a soft-deleted set that was previously synchronized successfully to the same `(spreadsheet_id, google_sheet_id, session)` remote target.
- Nothing for a missing slot or a set deleted before it was ever synchronized.

Cell rows are retained even when the remote value already equals the proposal and no API update is needed. This records that the member reviewed that remote state and makes later deletion deterministic.

Rows are also retained for movements marked `SKIP` so the decision is auditable, but only `APPLY` rows enter the remote payload or count as successfully synchronized.

For example, after Set 1 is synchronized, its `performed_set.id` is attached to its reps/load/RIR cell rows. Deleting it preserves that ID and slot. The next preview finds the prior successful synchronization and proposes `CLEAR` for Set 1 using the **current** provenance addresses; Sets 2 and 3 have different performed-set IDs and remain untouched. Once those clears succeed, an already-empty remote state becomes a no-op. Restoring Set 1 reuses the same row and produces ordinary writes again.

Raw spreadsheet and numeric tab IDs are copied onto each attempt so replacing a `sheet_link` cannot make old history clear a different file. Re-imported coordinates within the same remote tab remain usable because clearing resolves the stable performed-set identity through current provenance, not the old A1 address.

### 3.6 Safe confirmation and retry

Preview creation reads Google first, then stores `sheet_write`, its movement decisions, and its cells in one `dbQuery` transaction with status `PREPARED`. `execution_projection_hash` covers the complete app-authoritative execution plus its current provenance. `payload_hash` covers the sorted, typed remote cell payload for movements marked `APPLY`; it is an integrity check, not a uniqueness constraint.

Confirmation uses this sequence:

1. In a short database transaction, lock the attempt, require `PREPARED`, verify the authenticated owner and unchanged execution projection, and move it to `VALIDATING`.
2. Outside the transaction, re-read all anchors and exact target cells.
3. An anchor mismatch records `DRIFT_ABORTED`. A target that is neither its previewed observed value nor its proposed value records `CONFLICT_ABORTED`. Either result requires a fresh preview. A target already equal to the proposal is safe and may become a no-op.
4. In another short transaction, move the claimed attempt to `SENDING`. Then issue one `spreadsheets.batchUpdate` outside the transaction, using `UpdateCellsRequest` operations restricted to the `userEnteredValue` field. This changes values only and preserves formatting.
5. Re-read every target cell. Store the verified typed/display values. If all applied cells match, record `SUCCEEDED`; `api_called` says whether a batch was necessary. Set `fully_synced = true` only when no movement was skipped and the current complete projection was verified.

The unique `idempotency_key` represents one confirmation action. Repeating the same request returns its stored attempt instead of starting another. A new press after a completed attempt creates a new preview, because the trainer may have edited the remote cells since the last success; database success alone never substitutes for a new remote read.

A definite API rejection that applied nothing becomes `FAILED`. A timeout, lost connection, or process failure after entering `SENDING` becomes `UNKNOWN`, because the batch may have landed. Retrying an `UNKNOWN` attempt first reads every proposed cell:

- If all cells equal the persisted proposal, mark the attempt `SUCCEEDED` without another write.
- If any differ, mark `VERIFY_CONFLICT` and require a new preview.

Never blindly replay an ambiguous write. A stale `VALIDATING` attempt is safe to validate again because it had not entered `SENDING`; a stale `SENDING` attempt is always reconciled as `UNKNOWN`.

### 3.7 The accepted final read/write race

Sheets v4 guarantees that requests within `spreadsheets.batchUpdate` are applied together atomically, but its documented value-write contract has no compare-and-swap condition against values read immediately beforehand. Drive's `headRevisionId` is available only for blob files, not native Google Sheets. Therefore the final read and the subsequent batch cannot be made one conditional remote operation. See the official [Sheets batch update contract](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/batchUpdate) and [Drive revisions overview](https://developers.google.com/workspace/drive/api/guides/change-overview).

This phase accepts the narrow residual race with these controls:

- Re-read anchors and every target immediately before the batch.
- Abort instead of guessing when that read finds drift or a new conflict.
- Send one atomic value-only batch immediately afterward.
- Re-read and verify every target immediately after the batch.
- Record `VERIFY_CONFLICT` and show the affected movements if post-write values differ; never automatically retry or restore them.

A trainer edit made in the milliseconds after the final read but before the batch can still be overwritten, and post-verification cannot prove that such an overwritten value once existed. This is an explicit accepted limitation. The write is manual, previewed, per-movement confirmed, and targets only app-authoritative execution cells; adding an Apps Script lock or another coordination service is not justified for this risk.

### 3.8 When to offer it

Write-back is **explicit, not automatic** — a button on a completed session, not a trigger on every logged set, and never a background job. Per-set writes would mean dozens of API calls, a partially-filled row while a member is mid-session, and drift checks on every tap.

Surfacing a persistent "unsynced sessions" indicator is worth more than automation: the member decides when the session is done.

### Definition of Done

- [ ] Pressing write produces a preview and writes nothing
- [ ] Preview shows spreadsheet, tab, week, target cell addresses, values, and total count
- [ ] Preview persists one `PREPARED` attempt with per-movement decisions and exact typed observed/proposed cell values
- [ ] Drift is detected while building the preview and reported as guidance, not as a failed write
- [ ] Anchors and every applied target cell are re-read immediately before execution; late drift or conflict aborts cleanly
- [ ] Occupied target cells are flagged in the preview with existing vs proposed values
- [ ] Conflicts are resolved per movement as Replace with app data or Skip this write; the choice is never remembered
- [ ] Write-back writes only execution reps/load/rir cells for the target session
- [ ] Prescription cells, headers, formatting, and other tabs are never modified
- [ ] Reps and reps-per-side write an integer; timed sets write duration as `45 sec`; load is numeric without a unit suffix; RIR is an integer
- [ ] A null optional field on a logged set previews a clear, including when the sheet contains a copied value
- [ ] A missing performed-set slot makes no proposal unless it was previously written and then deleted
- [ ] Deleting a previously written set previews clearing that set's cells without renumbering later sets
- [ ] A successfully reviewed no-op cell counts as synchronized; a skipped movement does not
- [ ] Deleted-set clearing is scoped to the same spreadsheet, numeric tab, session, and stable performed-set ID
- [ ] A set number beyond the mapped sheet columns blocks the whole write; columns are never inserted and sets are never omitted
- [ ] Every write re-reads anchors and aborts entirely on any drift
- [ ] Drift aborts tell the member to re-import, and never attempt a fuzzy re-match
- [ ] Non-empty unequal target cells raise a per-movement conflict, resolvable only by explicit confirmation
- [ ] When any update is needed, one atomic `spreadsheets.batchUpdate` carries every movement confirmed for this write and changes only `userEnteredValue`; an all-equal payload performs no API write
- [ ] Database status transitions use short transactions; Google reads and writes never run inside `dbQuery`
- [ ] An unchanged execution projection is required at confirmation; a stale preview cannot write
- [ ] Repeating an idempotency key returns the same attempt rather than sending again
- [ ] Every applied cell is re-read after the batch and its verified typed/display value is persisted
- [ ] An ambiguous `SENDING` outcome becomes `UNKNOWN` and is reconciled by read-back, never blind replay
- [ ] Matching read-back marks an unknown attempt successful without another write; differing read-back becomes `VERIFY_CONFLICT`
- [ ] `fully_synced` is true only after the entire current projection is verified with no skipped movements
- [ ] The documented final read/write race is accepted, minimized by immediate pre-read/batch/post-read, and never handled by automatic restore
- [ ] Write-back is manual, with unsynced sessions visible
- [ ] Verified: the trainer read a written-back week in their own sheet without noticing anything amiss

---

# Iteration 4 — Refinements

Unspecified until iterations 1–3 are in real use. Candidates:

- **Progression views** — logged load and reps for one exercise over a block. Cheap once execution is typed.
- **Session reminders** — a Slack nudge when a workout's next week is unlogged for a while.

---

## Deferred until real usage

- **Coach access to the app** — a generalized identity model, a `coach_client` link, a server-side authentication-session table for revocation, and a fail-closed second auth provider. Designed, then cut: iteration 3 gives the trainer execution data in their own sheet, and form video goes over group chat. **Revive only if a concrete need appears that a spreadsheet cannot carry** — most likely in-app video review, or the trainer asking to author directly. It is a substantial piece of engineering and should not be built speculatively.
- **Public share links for a single session** — same reasoning. Also the system's first unauthenticated read path and a durable URL-borne credential, which phase 2's single-use-nonce design deliberately avoids. Not worth introducing without a need.
- **In-app form video.** Filming stays part of the method — the trainer's instruction to film one round per movement is unchanged — but the clips go to group chat, which works today. Bringing them in-app means object storage, presigned uploads, signed playback, retention rules, and a review surface, and it is the main thing that would drag coach access back into scope.
- **Offline logging.** Everything here assumes connectivity. If gym signal proves unreliable, offline support means optimistic local writes, a queue, and reconciliation — a large addition, worth its own phase.
- **Running and mobility prescriptions with distance/duration semantics.** Currently expressible as prose in the same text fields. Typed fields only if trending demands them.
- **Multiple sheets per program, or a second trainer.** Iteration 2 links one spreadsheet to one program; federation and reconciliation across several source files have not been designed.

## Cross-Cutting

- **Per-person by default.** Training data is scoped to the authenticated Slack user through `program.owner_user_id`. This is the first domain where cross-member visibility is a deliberate exception rather than the norm.
- **Persistence discipline** unchanged: all access through `dbQuery(db)`, one flat transaction per atomic write, no network calls inside a transaction, client-side UUIDs.
- **Migrations + codegen:** Flyway is the source of truth; register each new table in the pgen allowlist before regenerating, or it silently will not be generated.
- **`jsonb` is net-new and needs a pgen `columnTypeMapping` before any jsonb table is registered.** Iterations 2–3 depend heavily on jsonb (`extracted_draft`, `source_snapshot`, `prescription_cells`, `execution_cells`, `observed_/proposed_/verified_user_entered_value`, …), but no existing table uses it and `columnTypeMappings` in `build.gradle.kts` maps only `timestamptz`. Unmapped, pgen falls back to text or emits a type Exposed cannot bind — degrading provenance maps into stringly-typed blobs whose corruption would not surface until a write-back targets the wrong cell in the trainer's live sheet. Add a jsonb mapping alongside the `timestamptz` one, keep (de)serialization at the repo boundary using the existing `kotlinx.serialization` `Json`, and verify with a throwaway jsonb table that the generated Kotlin compiles and round-trips before building on it.
- **Testing:** per the testing guide, narrowest layer that proves the behaviour. Authorization boundaries belong in route tests with two authenticated member sessions, proving member B receives not found for member A's IDs. Session lifecycle, target snapshots, stable set slots, exact-cell history, deleted-set clearing, and write-state/idempotency transitions belong in persistence tests. A fake Sheets boundary covers drift, late conflict, atomic payload construction, timeout/read-back reconciliation, and post-write verification. The Drive picker, import review, and write preview/conflict choices belong in Playwright.
- **Design system** applies unchanged: mobile-first, one-handed reach, no reliance on colour alone, and loading/empty/error/pending states visible — an unsynced session is a state the UI must show, not hide.

---

## Getting started

Enter one week of **each** program by hand before writing the authoring UI, and before touching iteration 2 — extraction needs a target shape, and hand entry is what establishes it. The two programs differ in kind, and the three observed documents already differ in their column sets; entering only one will make a narrow model look correct.

Then connect Drive and import a real block. The measure of iteration 2 is whether importing plus correcting beats typing; record what needed fixing.

Iteration 3 is the one that changes the trainer's experience, and its acceptance test is behavioural rather than technical: they open their own sheet, read a written-back week, and notice nothing amiss.
