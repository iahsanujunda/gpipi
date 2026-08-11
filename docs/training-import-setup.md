# Training Sheet import setup

This is the operator checklist for Phase 5 Iteration 2. It covers the changes that must be made outside the repository: selecting an extraction model, creating the Google Cloud integration, installing secrets, and proving the connection against a disposable Sheet.

The import is intentionally narrow:

- Google Picker gives the app one spreadsheet ID selected by the member.
- Import always targets the member's existing active program; program creation stays manual and is never part of import.
- The member chooses exactly one week and confirms its workout ranges.
- Ktor reads only those confirmed ranges for extraction.
- Execution values under `Eksekusi` or `Realisasi` are removed before the model request and are never used to prefill training execution.
- Model output remains a draft until every movement and execution type is reviewed and **Apply** is pressed.
- Apply writes prescriptions and provenance for the reviewed week into the existing active program. Apply never creates, activates, or deactivates a program, and never creates a training session, performed exercise, or performed set.

The detailed data and safety contract remains in [phase5.md](phase5.md#iteration-2--drive-connected-block-import).

## 1. Choose the training extraction model

Training import has its own model setting:

```dotenv
OPENROUTER_TRAINING_EXTRACTION_MODEL=provider/model-slug
```

`OPENROUTER_MODEL` continues to control expense and shopping extraction. Changing the training setting affects only future training extractions. Each extracted workout stores the model name returned by OpenRouter, so historical drafts retain an audit record after the setting changes.

Do not use `openrouter/auto` or a `:free` variant for this workflow. Use an explicit, stable model slug whose OpenRouter model page lists `structured_outputs`, `response_format`, and `reasoning` support, including the `high` effort level. The training client always sends `reasoning: { "effort": "high" }`; this is intentionally part of the extraction contract rather than another deployment setting. Expense and shopping extraction do not receive this parameter.

The client also sends strict JSON Schema, `require_parameters: true`, and temperature `0`; a provider that cannot honor those parameters will be rejected rather than silently receiving a weaker request. High reasoning can increase latency and output-token cost, but import makes only one reviewed extraction call per included workout tab and prioritizes transcription accuracy. See OpenRouter's [reasoning guide](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens), [structured output guide](https://openrouter.ai/docs/guides/features/structured-outputs), and [model metadata guide](https://openrouter.ai/docs/guides/overview/models).

The model needs these capabilities:

| Capability | Why it is required |
| --- | --- |
| `high` reasoning effort | The extraction must resolve irregular spatial layouts and source-cell provenance without relaxing verbatim transcription rules. |
| Strict JSON Schema output | The server deserializes a fixed group → prescription → source-cell contract. |
| Exact transcription | Returned values must equal the cited formatted Sheet cell byte-for-byte; normalization is rejected. |
| Indonesian and English text fidelity | Real prescriptions mix both languages, abbreviations, units, and trainer notes. |
| At least the largest one-week context | One request contains one sanitized workout range, with coordinates and merged-range metadata. Start with at least 32k context unless fixture measurements prove less is safe. |
| Reliable nullable fields | Missing columns must stay null instead of being inferred. |
| Low hallucination at temperature `0` | Invented text or an incorrect A1 citation rejects the entire workout draft. |

Vision, image understanding, Google Drive access, and XLSX upload support are not needed. The model receives coordinate-preserving JSON containing only the selected prescription-side cells. It never receives the file, a Google URL, OAuth token, spreadsheet ID, member identity, or execution values.

Before changing the production model:

1. Make sanitized fixtures from several real one-week ranges, including mixed Indonesian/English text, merged group headings, absent columns, and copied execution values.
2. Run each candidate with the production prompt and schema.
3. Reject a candidate that changes punctuation, spacing, ranges, units, URLs, or A1 citations—even if the changed text looks cleaner.
4. Confirm that populated execution cells occur nowhere in the captured request or persisted redacted snapshot.
5. Compare correction rate first, then latency and cost. This workflow makes only one call per included workout tab and always has human review.

The repository currently provides a fallback model in `application.conf`, but production should set `OPENROUTER_TRAINING_EXTRACTION_MODEL` explicitly so a deployment does not depend on that fallback.

## 2. Create the Google Cloud project

Use a dedicated project for gpipi. Google recommends separate development/testing and production projects; that also prevents a local redirect URI, test users, or API-key referrer from leaking into the production setup.

In [Google Cloud Console](https://console.cloud.google.com/):

1. Create or select the project.
2. Record the numeric **project number**, not the project ID. It becomes `GOOGLE_CLOUD_PROJECT_NUMBER` and is passed to Picker as its app ID.
3. Enable **Google Picker API**, **Google Drive API**, and **Google Sheets API** in APIs & Services → Library.
4. Configure the Google Auth Platform consent screen.
5. Request only this scope:

   ```text
   https://www.googleapis.com/auth/drive.file
   ```

`drive.file` is deliberate: Picker grants the app access to files the member explicitly selects, instead of broad Drive read access. The web Picker requires an OAuth access token and returns selected file metadata to its JavaScript callback; Google documents that flow in the [Picker overview](https://developers.google.com/workspace/drive/picker/guides/overview).

For a household-only test deployment, keep the app in **Testing** and add each Google account under Test users. Google currently expires authorizations for testing users after seven days, so periodic reconnection is expected in that mode. Move to **In production** when persistent household authorization is required, and follow any consent-screen or brand verification instructions shown for the configured scope. See Google's [app audience documentation](https://support.google.com/cloud/answer/15549945).

## 3. Create the web OAuth client

Create an OAuth client of type **Web application**. Google requires the redirect URI to exactly match one of the client’s authorized redirect URIs.

Local development:

```text
http://localhost:8080/api/training/google/callback
```

Production:

```text
https://YOUR-KTOR-ORIGIN/api/training/google/callback
```

Use the public Ktor origin, not the frontend origin, because Ktor exchanges the authorization code and encrypts the refresh token. Add the corresponding frontend origins under authorized JavaScript origins if the console requests them:

```text
http://localhost:5173
https://YOUR-WEB-ORIGIN
```

Use `localhost` consistently during local testing. Switching between `localhost` and `127.0.0.1` changes the cookie and origin boundary and can make the authenticated callback appear signed out.

Copy the client ID and client secret into the environment variables described below. Never commit the downloaded client-secret JSON. The implementation uses Google’s web-server authorization flow with a one-use, ten-minute state value, offline access, and explicit consent so Ktor can receive and securely retain a refresh token. Google’s current requirements are documented in the [OAuth web-server guide](https://developers.google.com/identity/protocols/oauth2/web-server).

## 4. Create and restrict the Picker API key

Create an API key for Google Picker. This key is intentionally sent to the browser, so it is not protected by secrecy; it is protected by restrictions.

Configure:

- Application restriction: **Websites / HTTP referrers**.
- Local referrer: `http://localhost:5173/*`.
- Production referrer: `https://YOUR-WEB-ORIGIN/*`.
- API restriction: restrict the key to the **Google Picker API**.

Store its value as `GOOGLE_PICKER_API_KEY`. The OAuth client, API key, and numeric project number must belong to the same Google Cloud project.

## 5. Configure local environment variables

Copy `ktor/.env.example` to `ktor/.env` if needed, then set:

```dotenv
OPENROUTER_API_KEY=...
OPENROUTER_TRAINING_EXTRACTION_MODEL=provider/model-slug

GOOGLE_OAUTH_CLIENT_ID=....apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=...
GOOGLE_OAUTH_REDIRECT_URI=http://localhost:8080/api/training/google/callback
GOOGLE_PICKER_API_KEY=...
GOOGLE_CLOUD_PROJECT_NUMBER=123456789012
GOOGLE_CREDENTIAL_ENCRYPTION_KEY=...
```

Generate the credential-encryption key once:

```bash
openssl rand -base64 32
```

The decoded value must be exactly 32 bytes. It encrypts Google refresh tokens using AES-256-GCM before they enter PostgreSQL.

Treat this key as durable production data, not as a disposable deployment secret. Replacing it immediately makes existing refresh tokens unreadable. The current implementation does not keep an old-key ring: before an intentional rotation, disconnect the Google connection, rotate the key, deploy, and reconnect. A lost key requires reconnecting affected Google accounts.

The Picker API key and project number are delivered to the authenticated browser by the backend. The OAuth client secret, refresh token, and encryption key never leave Ktor.

## 6. Configure production secrets

For the current Fly.io backend, install the values from the `ktor` directory:

```bash
fly secrets set \
  OPENROUTER_TRAINING_EXTRACTION_MODEL='provider/model-slug' \
  GOOGLE_OAUTH_CLIENT_ID='....apps.googleusercontent.com' \
  GOOGLE_OAUTH_CLIENT_SECRET='...' \
  GOOGLE_OAUTH_REDIRECT_URI='https://YOUR-KTOR-ORIGIN/api/training/google/callback' \
  GOOGLE_PICKER_API_KEY='...' \
  GOOGLE_CLOUD_PROJECT_NUMBER='123456789012' \
  GOOGLE_CREDENTIAL_ENCRYPTION_KEY='...'
```

`OPENROUTER_API_KEY` must also already be present. A secrets update restarts the Fly Machine. Flyway applies the training-import migrations (`V15__training_sheet_import.sql` through `V17__training_import_existing_program_only.sql`) during backend startup.

No Iteration 2 values belong in `web-app/.env`. This avoids maintaining a second copy of Picker configuration and lets the backend report exactly which server-side values are missing.

## 7. First connection and smoke test

Use a copy of the trainer Sheet first:

1. Start Ktor and Vite, authenticate through Slack, and open **Training → Program settings → Import one week**.
2. Confirm the page reports Google as configured. If not, it lists the exact missing variable names.
3. Press **Connect Google**, approve only the selected-file permission, and return to the import page.
4. Press **Choose Google Sheet** and choose the disposable native Google Sheet.
5. Choose one week that has both prescriptions and copied execution values.
6. Confirm or exclude every tab. For each included workout, verify the row range, target workout, first execution column, execution-header cell, and execution-header text.
7. Press **Extract Week N**. Confirm that only this week appears in review.
8. Verify copied execution numbers do not appear as execution defaults—or anywhere in the review as extracted execution.
9. Explicitly match/create/exclude every movement and confirm every included movement’s execution type.
10. Save the reviewed week, then press **Apply Week N**.
11. Open the resulting week. Prescription targets should be present and every execution input should remain empty.
12. Return to an older authored week and back to the current week to prove normal Iteration 1 navigation is unchanged.

For a database-level check, the selected import should have `training_import.state = 'APPLIED'`, one selected-week row per included workout tab, and provenance in `sheet_week_link` and `sheet_prescription_link`. Applying an import must not add rows to `training_session`, `performed_exercise`, or `performed_set`, and must not change any `program` row (import never creates, activates, or deactivates a program).

## 8. Troubleshooting

| Symptom | Check |
| --- | --- |
| Google says `response_type` is missing | The deployed backend must generate a URL containing `response_type=code`. This is produced by Ktor, not configured in Google Cloud. Deploy a version containing the OAuth URL-builder fix, then press **Connect Google** again to create a fresh one-use state. |
| `redirect_uri_mismatch` | `GOOGLE_OAUTH_REDIRECT_URI` must exactly match an authorized redirect URI, including scheme, host, port, path, and trailing-slash absence. |
| Callback returns signed out | Use one hostname consistently; confirm the browser retained the Ktor session cookie and production uses HTTPS. |
| Google returns no refresh token | Disconnect/revoke the app from the Google account, then connect again. The authorization request uses offline access and explicit consent. |
| Picker says the developer key is invalid | Confirm the Picker API is enabled, HTTP-referrer restrictions include the exact frontend origin, and the key belongs to the OAuth client’s project. |
| Sheet read returns 403 | The OAuth account must be able to open the file, and the file must have been selected through this app’s Picker under `drive.file`. Reconnect and select it again. |
| No weeks are found | Week discovery recognizes visible labels containing `Week N` or `Minggu N`. Correct the visible label or continue with manual authoring. |
| Execution boundary is ambiguous | In Step 2, supply the first execution column and the exact execution-header cell/value. Extraction does not guess. |
| OpenRouter rejects `response_format` | The configured model/provider does not support strict structured output. Choose a model whose metadata lists `structured_outputs` and `response_format`. |
| OpenRouter rejects `reasoning` | Choose a model/provider whose metadata lists `reasoning` and supports `high` effort. The training extraction contract does not fall back to a non-reasoning request. |
| Extraction rejects apparently good output | Inspect the reviewed source fixture for punctuation, whitespace, unit, URL, or A1-citation differences. The server intentionally rejects “helpful” normalization. |
| Server fails while constructing the cipher | Regenerate `GOOGLE_CREDENTIAL_ENCRYPTION_KEY` as standard Base64 for exactly 32 bytes. |
| A testing connection stops working after several days | Google testing-mode authorizations currently expire after seven days. Reconnect or move the correctly configured app to production status. |

## 9. Operational boundaries

- There is no background polling, timer, or read on page load.
- **Choose Google Sheet**, **Load Week details**, and **Extract Week N** are explicit reads.
- Week discovery is transient. If the browser is refreshed before scope confirmation, press **Load Week details** to perform that read again.
- Confirmed mappings, completed extraction, review decisions, source hashes, and model names survive refreshes.
- Disconnect marks the stored credential revoked and makes a best-effort call to Google’s revoke endpoint.
- Logs must contain import IDs, numeric tab IDs, chosen week, range, contract version, returned model, timing, and outcome only. Do not add spreadsheet IDs, OAuth tokens, cell values, model bodies, or source snapshots to logs.
