# Household Budget Bot — Phase 2: Frontend & Magic-Link Auth

_Slack-brokered web UI · Stack: React (Render static site) · Ktor (shared backend) · Exposed/Postgres · magic-link → HttpOnly session_

---

## System context

The household budget system lets the members of one household track shared spending. In phase 1, users log expenses through a Slack bot (`@ai just paid 1500 for ramen`); a Ktor backend extracts, categorizes, and stores each expense in Postgres (accessed via Exposed). The Slack workspace is private to the household — only its members are in it — so workspace membership is a trustworthy identity signal.

Phase 2 adds a **React web frontend** for the parts of budget management that don't fit a chat command: reviewing and editing budgets, wallets, and — the core monthly task — the amounts each member must transfer into each wallet on their payday.

This introduces a second way to reach the backend. The phase 1 backend authenticates Slack requests by verifying Slack's request signature; that mechanism does not cover a browser client, which needs its own scheme. This document specifies that scheme and the frontend's minimal surface.

---

## Status of this document

**Intentionally minimal.** Only the authentication mechanism and the request-routing architecture are specified, because those can be designed correctly up front. The frontend's actual functionality — which screens exist, how the payday-transfer calculation works, the exact wallet data model — is **left open on purpose**, to be determined once the system is in real daily use. Implementers should not pre-build the domain UI. This document locks the plumbing so that, once the functionality is understood, there is a secure surface ready to build on.

---

## What the frontend replaces (domain intent)

The information currently lives in a spreadsheet: budget values, budget items, the wallet each item is funded into, and the monthly transfer ritual — on each member's payday, the amount that member must transfer into each wallet from their income. Each member funds a defined portion of the household budget; on their payday they check their portion and make the transfers.

That is the *intent*. The exact data model — how wallets relate to the phase 1 `budget_envelope`/`category` tables, how the per-member funding split is expressed, how transfer amounts are computed — is **provisional and expected to be refined through use**, so this document does not fix those schemas. What is known: the frontend edits the existing budget tables and adds a wallet-and-funding layer on top. The shape of that layer is to be discovered.

---

## Authentication: magic link → session

Identity is brokered by Slack. Because the workspace contains only household members, a request initiated from Slack is already attributable to a known user; the frontend can therefore be authorized off the back of a Slack action rather than a separate login. This is the standard **magic-link** pattern.

### The failure mode to avoid

A credential placed *in a URL* leaks — through browser history, server and proxy access logs, and the `Referer` header sent to any third-party resource the page loads. A token that stays valid for, say, 30 minutes and sits in a shared log is a live credential for that whole window. The URL must therefore never carry the session credential itself.

### Design: two tokens

The link carries a **single-use nonce**, not the session. On click, the nonce is immediately exchanged for a **session** delivered as an `HttpOnly` cookie, which never appears in a URL.

```
User issues a budget-edit command in Slack (e.g. "@ai edit budget")
  → backend mints a nonce: { nonce, user_id, expires_at (~5 min), consumed=false }  → Postgres
  → posts a link back to the user:  https://<frontend>/enter#<nonce>   (fragment — see note)

User clicks the link
  → frontend reads the nonce from location.hash
  → POST /api/auth/redeem { nonce }
      → backend validates: unexpired AND unconsumed
      → flips consumed=true IN THE SAME TRANSACTION (a leaked or double-clicked nonce cannot be reused)
      → sets a session cookie: HttpOnly; Secure; SameSite=Lax; short lifetime
  ← the nonce is now dead; the URL left in history is worthless

Subsequent /api/** calls
  → carry the session cookie; endpoints check the SESSION, never the nonce
```

### Three non-negotiables

1. **The nonce is single-use**, enforced transactionally — validation and the `consumed` flip occur in one transaction, so concurrent or repeated redemptions cannot both succeed.
2. **The session lives in an `HttpOnly` cookie** — never in the URL, never in `localStorage` (which any injected script can read). Set `Secure` (HTTPS only) and `SameSite=Lax` (CSRF mitigation).
3. **Verify before trust** — `/api/auth/redeem` is the only public endpoint (it runs before any session exists); every other `/api/**` route requires a valid session.

### Two independent lifetimes

- **Nonce expiry: ~5–10 minutes** — it only needs to survive from the moment the link is posted to the moment it is clicked.
- **Session expiry: the working window** (e.g. 30 minutes, or an idle timeout) — it covers the editing session.

These are separate clocks and should not be collapsed into one value.

### Identity is carried through

The nonce record stores the `user_id` of the member who issued the command, so the resulting session knows which member is acting. This supports per-member views (each member's payday transfers differ) without a separate identity system.

> **Fragment vs query string:** placing the nonce after `#` keeps it out of the HTTP request line (so it is absent from server access logs) and out of the `Referer` header. Because the nonce is single-use and short-lived, this is minor additional hardening rather than a load-bearing control.

---

## Architecture: two doors, one authorization model

A web UI plus a Slack bot can appear to require two separate authentication systems. They do not. There are two **authentication front-doors** that resolve to a single authorization model.

```
Slack request      ──▶  Slack signature verification   ──┐
                                                         ├──▶  validated user_id  ──▶  service logic
Frontend request   ──▶  session-cookie check           ──┘
```

Each door is middleware that produces an authenticated `user_id`. Everything behind the doors — the budget, expense, and wallet logic — is identical and door-agnostic. There is one notion of an authenticated user once inside.

- **Frontend hosting:** React on a Render static site, calling the shared Ktor backend.
- **New backend surface:** `/api/**` routes behind a session-cookie guard, plus the single public `/api/auth/redeem`.
- **CORS:** the backend must permit the static-site origin with credentials, so the session cookie is sent on cross-origin API calls.

---

## Minimal schema

```sql
-- The only table phase 2 defines up front.
create table auth_nonce (
    nonce       text primary key,          -- random, single-use
    user_id     text        not null,      -- the member the link was minted for
    expires_at  timestamptz not null,      -- ~5-10 minutes out
    consumed    boolean     not null default false,
    created_at  timestamptz not null default now()
);
```

Session storage may be a signed stateless cookie (no table) or a `session` table if server-side revocation is wanted; either is adequate for a small, fixed set of users. Decide when implementing. Expired and consumed nonces are removed by a periodic cleanup job.

---

## Deferred until real usage

The following depend on how the system is actually used and should not be designed in advance:

- The wallet and per-member-funding data model, and its relationship to `budget_envelope`/`category`
- The payday-transfer calculation — the central feature, but its precise inputs and outputs are expected to emerge from use
- The set of screens and their layout
- Read/write permission granularity between members (beyond "all members are trusted")
- Whether budget edits should also be possible via chat commands — a longer-term direction that could reduce the frontend's scope rather than expand it

Each should be captured as it becomes clear, then added to this document.

---

## Definition of Done (auth and plumbing only)

This is the full scope phase 2 commits to initially. Definition of Done for domain features is added later, per feature.

- [ ] `auth_nonce` table created
- [ ] A budget-edit chat command mints a nonce and posts a magic link to the requesting user
- [ ] `/api/auth/redeem` validates and consumes the nonce in one transaction, then sets an `HttpOnly; Secure; SameSite` session cookie
- [ ] A consumed or expired nonce is rejected (single-use proven by test)
- [ ] All `/api/**` routes reject requests without a valid session; `/api/auth/redeem` is the only public route
- [ ] The session carries the acting member's `user_id`
- [ ] React app deployed on a Render static site; CORS permits its origin with credentials
- [ ] Nonce cleanup runs on a schedule
- [ ] End-to-end: a Slack-signed request and a cookie-session request both reach the same authorized service path

---

## Getting started

Phase 2 depends on the phase 1 bot being in use, so that the frontend's functionality can be shaped by real needs. Until then, implement the authentication surface above; it is independent of the eventual UI. Build the first domain screen against it once the requirements are understood, and grow the functional Definition of Done one feature at a time.