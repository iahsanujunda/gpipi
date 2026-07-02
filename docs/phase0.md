# Household Budget Bot — Iteration 0: Setup & Prerequisites

_Environment scaffolding · Stack: Slack · Ktor · GitHub · Tailscale Funnel (dev tunnel) · Render (prod, later)_

---

## Overview

Iteration 0 produces a **runnable local skeleton reachable by Slack over the internet** — everything Iteration 1 assumes, minus the route itself. No application logic, no database. When this is done, the Ktor default page is serving locally and publicly reachable through a stable Funnel URL, a dev Slack app exists with its credentials in hand, and the repo is safe to commit to.

The one deliberate gap: **Event Subscriptions is left unfinished.** Slack won't accept a Request URL until an endpoint echoes its `url_verification` challenge, and that endpoint is Iteration 1's first task. So Iteration 0 gets the app *created and installed*, and Iteration 1 *completes the wiring*.

### What "consistent local vs prod" actually means

The same jar runs both places — identical Ktor build, identical HTTP-events code path (this is why we use a tunnel, not Socket Mode: one code path, not two). All environment difference lives in **config, never in code**. Nothing in Slack or the code decides "local or prod":

- A Slack app has exactly **one** Event Subscriptions Request URL. That field is the entire routing decision — whatever URL is saved is where every event goes.
- Therefore: **two Slack apps.** A dev app permanently pointed at the Funnel URL, a prod app permanently pointed at Render. Each has its own signing secret + bot token, fed in as env vars per environment.
- Iteration 0 sets up **only the dev app.** The prod app is created when Iteration 1 actually deploys to Render.

---

## 0.1 Git & GitHub — DONE

Repo initialized, `docs/` committed (this file + `phase1.md`). One thing to verify before the first *code* commit:

**`.gitignore` must exclude secrets and build output.** The Ktor generator ships a reasonable default; confirm these are present:

```gitignore
build/
.gradle/
.idea/          # keep .idea/runConfigurations/ only if you want to share run configs (without secrets)
*.log
.env            # if you ever use one
```

Signing secret, bot token, and OpenRouter key **never** land in git. They live in env vars (0.4).

- [x] `git init`, `docs/` committed
- [x] GitHub remote created and pushed
- [x] `.gitignore` verified to exclude `build/`, `.gradle/`, secrets

---

## 0.2 Slack — Dev App

### Create the app

1. If you don't want the bot in your work Slack, create a throwaway workspace at slack.com/create for household/dev use (free plan fine — the 90-day history limit is irrelevant since expenses live in Postgres, not Slack).
2. api.slack.com/apps → **Create New App** → **From scratch** → into that workspace.

### Grab two credentials now

| Credential | Where | Used for |
|-----------|-------|----------|
| **Signing Secret** | Basic Information | Verifying inbound requests (iter 1.2) |
| **Bot User OAuth Token** (`xoxb-…`) | OAuth & Permissions, after Install | Posting replies via `chat.postMessage` |

### Bot Token Scopes (OAuth & Permissions)

Minimum for Iteration 1's echo:

- `app_mentions:read` — receive `@ai` mentions
- `chat:write` — post the echo/confirmation back

Add later, only if you capture beyond direct mentions:

- `channels:history` / `groups:history` — read messages in the channel

Then **Install to Workspace** → copy the `xoxb-…` token.

### Two-app model (why, and what to do now)

- **Now:** this dev app, installed in the test workspace / a `#bot-dev` channel. Its URL will point at Funnel.
- **Later (iter 1 deploy):** a separate prod app in the real household workspace, URL pointed at Render.

Keeping them in separate workspaces/channels means dev test messages never hit the real expense channel, a dev crash never touches prod data, and you avoid the double-reply footgun of two bots in one channel.

### Event Subscriptions — DO NOT complete yet

Leave the Request URL blank. It requires a live endpoint that echoes the `url_verification` challenge (Iteration 1.3). Completing it is the *first proof* that the route works — save it for then.

- [x] Dev app created from scratch in a test workspace
- [x] Signing Secret copied
- [x] `app_mentions:read` + `chat:write` scopes added
- [x] Installed to workspace; `xoxb-…` bot token copied
- [x] Event Subscriptions intentionally left blank

---

## 0.3 Ktor Scaffold (start.ktor.io)

### Generator settings

| Setting | Choice |
|---------|--------|
| Build system | Gradle (Kotlin DSL) |
| Engine | Netty |
| Configuration | HOCON file (`application.conf`) |
| Ktor version | latest **3.x** |

> Most Ktor tutorials online are 2.x. The request-pipeline *concepts* are the same, but some import paths and signatures moved in 3.x — trust the 3.x docs and your compiler over blog posts.

### Plugins to add in the generator

- **Routing**
- **Content Negotiation** + **kotlinx.serialization (JSON)**
- **Call Logging**
- **Status Pages**

> Do **not** add the Authentication plugin. Slack signature verification is your own middleware, not Ktor auth.

### Client dependencies (add manually, not generator plugins)

For calling Slack's API (and later OpenRouter). Add when wiring the echo in iter 1.3:

```
ktor-client-core
ktor-client-cio
ktor-client-content-negotiation
ktor-serialization-kotlinx-json
```

### After generating

Unzip into the project root (alongside `docs/`), open in IntelliJ, confirm `./gradlew run` serves the default page at `http://localhost:8080`.

- [x] Project scaffolded with the settings above
- [x] Unzipped into repo root; opens cleanly in IntelliJ
- [x] `./gradlew run` serves the default page on :8080
- [x] Committed (with verified `.gitignore`)

---

## 0.4 Secrets via Env Vars (dotenv + `.env.example`)

Reference the environment in `application.conf` — never hardcode. Note the key name matches your `.env` (`SLACK_BOT_OAUTH_TOKEN`):

```hocon
slack {
    signingSecret = ${?SLACK_SIGNING_SECRET}
    botToken = ${?SLACK_BOT_OAUTH_TOKEN}
}
```

**Locally:** Ktor does **not** read `.env` natively, so load it into JVM system properties at the very top of `main()`, *before* Ktor reads config — then HOCON's `${?VAR}` resolves normally with zero call-site coupling:

```kotlin
// build.gradle.kts
implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")  // confirm current version
```

```kotlin
fun main() {
    dotenv {
        ignoreIfMissing = true     // prod (Render) has no .env — real env vars are already set
        systemProperties = true    // populate System properties so HOCON ${?VAR} picks them up
    }
    io.ktor.server.netty.EngineMain.main(arrayOf())
}
```

`ignoreIfMissing = true` is load-bearing: on Render there's no `.env`, the platform injects real env vars, and dotenv quietly does nothing. Do **not** sprinkle `dotenv.get(...)` through the code — that couples every call site to `.env` existing and breaks on Render. Load once, into system properties, then read through Ktor's normal config everywhere.

**Reproducibility — commit `.env.example`, never `.env`:**

```bash
# .env.example — copy to .env and fill in. Never commit .env.
SLACK_SIGNING_SECRET=        # Slack app → Basic Information → App Credentials
SLACK_BOT_OAUTH_TOKEN=       # Slack app → OAuth & Permissions → Bot User OAuth Token (xoxb-)
# Not needed unless distributing the app to other workspaces:
# SLACK_CLIENT_ID=           # public identifier, not secret
# SLACK_CLIENT_SECRET=       # secret — treat like signing secret
# SLACK_APP_ID=              # public identifier, not secret
```

**Later on Render:** same variable names, populated with the **prod app's** credentials. The code never branches on environment — it verifies against whatever secret it was given and replies with whatever token it was given.

- [x] `application.conf` references `SLACK_SIGNING_SECRET` and `SLACK_BOT_OAUTH_TOKEN` via env
- [x] dotenv loader wired at top of `main()` (`systemProperties = true`, `ignoreIfMissing = true`)
- [x] `.env` populated with dev app credentials; `.env` in `.gitignore`
- [x] `.env.example` committed (keys + source comments, no values); **not** gitignored
- [x] App reads the values at startup (log a boolean "secret present", never the value)


---

## 0.5 Tailscale Funnel (Dev Tunnel)

Slack's servers aren't on your tailnet, so plain Tailscale / `tailscale serve` (tailnet-only) won't work — you need **Funnel**, which exposes a local port to the public internet over HTTPS.

### One-time tailnet setup (admin console)

1. **DNS page** → enable **MagicDNS** if not already.
2. **DNS page → HTTPS Certificates** → **Enable HTTPS**.
3. **Funnel** — enabled per-tailnet in Access Controls. If it's not configured, running the funnel command presents a login URL to turn it on; `autogroup:member` gets Funnel access by default.

### Command

```bash
tailscale funnel --bg 8080
```

Serves `http://localhost:8080` publicly at `https://<your-machine>.<tailnet>.ts.net`. `--bg` runs it in the background so it survives closing the terminal. Check with `tailscale funnel status`; turn off by appending `off` to the command.

Your Slack Request URL (used in iter 1) will be: `https://<your-machine>.<tailnet>.ts.net/slack/events`

### Why Funnel over ngrok/cloudflared here

The `ts.net` hostname is **stable** — tied to the machine, not rotated on restart. Free ngrok/cloudflared quick tunnels hand you a new random URL every restart, forcing a re-paste into Slack Event Subscriptions and a re-run of the `url_verification` handshake each time. With Funnel you paste it into Slack **once**.

### Caveats (neither a blocker)

- **Public ports are 443 / 8443 / 10000 only** — but that's the *public* side; `tailscale funnel 8080` maps local 8080 → public 443 automatically. No need to change your Ktor port.
- **No auth layer** — the endpoint is genuinely public; anyone can POST to it. That's fine and intended: iter 1.2's signature verification *is* the auth layer. Unsigned requests get 401. Same exposure any tunnel gives.
- **Relay-limited throughput / possible rate limits** — irrelevant at household-Slack volume. Funnel is the *dev* tunnel only; Render is prod.

- [x] MagicDNS + HTTPS certificates enabled on tailnet
- [x] Funnel enabled for the tailnet
- [x] `tailscale funnel --bg 8080` running; `tailscale funnel status` shows the public URL
- [x] `ts.net` URL noted for iter 1's Event Subscriptions

---

## 0.6 Verify the Loop

With `./gradlew run` and `tailscale funnel --bg 8080` both up, open `https://<your-machine>.<tailnet>.ts.net` in a browser (or from your phone off wifi). You should see the Ktor default page served **through** Funnel — proving the full public path works before any route exists.

- [x] Ktor default page reachable at the public `ts.net` URL from an external device

---

## Definition of Done — Artifacts Iteration 1 Assumes

- [x] Repo committed with a `.gitignore` that excludes secrets and build output
- [x] Dev Slack app created and installed; **Signing Secret** and **bot token** in hand
- [x] Slack scopes `app_mentions:read` + `chat:write` granted
- [x] Ktor 3.x project scaffolded, `./gradlew run` serves :8080 locally
- [x] Secrets wired via dotenv + `.env` (gitignored); `.env.example` committed
- [x] Tailscale Funnel running with a stable public `ts.net` URL
- [x] Default Ktor page verified reachable through Funnel from an external device

---

## Explicitly Deferred

Not part of Iteration 0 — adding any of these now is just surface area to debug alongside concepts still forming:

| Item | Arrives in |
|------|-----------|
| Slack Event Subscriptions Request URL + `app_mention` subscription | Iteration 1 (needs the route to echo the challenge) |
| `/slack/events` route, signature verification, async ack | Iteration 1 |
| Supabase / Postgres, Exposed tables + Flyway migrations | Iteration 2 |
| Prod Slack app (real workspace, Render URL) | Iteration 1 deploy step |
| Render deployment | End of Iteration 1 (after the skeleton runs clean locally) |
| OpenRouter key + client wiring | Iteration 2 |

---

## Handoff to Iteration 1

When the DoD above is green, Iteration 1 begins with the Ktor request pipeline + coroutine-scoping walkthrough, then: stub `/slack/events` → handle `url_verification` → run locally → paste the Funnel URL into Slack Event Subscriptions → Slack verifies → subscribe to `app_mention` → async echo via `chat.postMessage`.