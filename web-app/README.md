# gpipi web app

Minimal React frontend for Phase 2. It mirrors the useful foundation from
`jepangpg/ops-app`: Vite, Material UI, React Router, TanStack Query, cookie-aware
API calls, and Vitest with Testing Library.

## Commands

```bash
npm install
npm run dev
npm test
npm run lint
npm run build
```

During local development, Vite proxies `/api` to Ktor on `http://localhost:8080`.
For a separately hosted production build, set `VITE_API_URL` to the Ktor origin.
Set `VITE_SLACK_RETURN_URL` to the Slack deep link that the app-bar back button
should open. Without it, the app falls back to browser history and then the
generic Slack application link.

## Frontend API contract

The scaffold expects these authenticated-session endpoints:

- `POST /api/auth/redeem` with `{ "nonce": "..." }`
- `GET /api/auth/session`
- `POST /api/auth/logout`
- `GET /api/budgets`

Auth responses use this initial shape:

```json
{
  "userId": "U123",
  "expiresAt": "2026-07-22T13:00:00Z"
}
```

`GET /api/auth/session` returns `401` when the browser has no valid session.
