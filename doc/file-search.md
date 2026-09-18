# File search / "Teacher Library" — full reference plan

This is the complete design for letting a teacher upload a reference PDF and have slide/quiz
generation grounded in it via OpenAI's `file_search` tool. It's split into slices that were
built incrementally; the sections below mark what's **done** vs **not yet built**.

## Status

**Done (backend-only, no auth/persistence/frontend):**
- Conversation pipeline can ground answers in one or more vector stores (`:book-ids`).
- `POST /library/upload` + `GET /library/book-status/:id` — upload a PDF, get a vector-store-id,
  poll until indexed. No email/token/conversation scoping — anyone can upload, gets back a raw id.
- See `src/teachers_center_backend/library.clj`'s trailing `(comment ...)` block for a REPL
  walkthrough of the whole path (upload → poll status → ground a real conversation request).

**Not yet built:**
- Email verification (6-digit code) for a persistent personal library.
- Multi-book listing per teacher, cross-session persistence.
- `web-compose` changes (verification-code email sending).
- All PowerPoint add-in UI (attach button, library modal, upload/poll/attach flow).

---

## Context

`teachers-center-backend` had an unwired POC (`openapi/file_search.clj`) for OpenAI vector
stores + the Responses API's `file_search` tool. The goal: let a teacher upload a reference PDF
in the PowerPoint add-in and have slide/quiz generation optionally grounded in it. Originally:
the frontend had zero file-upload UI, the backend had no route for it, and `responses-api`
didn't even support the `:tools` param the whole feature depends on.

## Agreed product shape

- Teacher can optionally verify their email (6-digit code sent by mail) to get a **persistent
  personal library** of uploaded PDFs, reusable across chat sessions. Skipping verification
  still allows attaching a PDF, but it's **ephemeral** — scoped to that one conversation only.
- **One vector store per book/PDF**, not one merged store per teacher. A verified teacher can
  attach one or more of their books to a given chat.
- **PDF only** for v1.
- **No new database.** OpenAI vector stores are the source of truth — each store is tagged with
  `:metadata {:email ... :book-name ...}` at creation, and "a teacher's library" is reconstructed
  by listing vector stores and filtering by metadata. A short-lived in-memory atom holds
  email-verification codes only (losing it on restart just means "request a new code" —
  acceptable).
- **Expiration** via OpenAI's `expires_after: {anchor: "last_active_at", days: N}`, surfaced to
  the user in the UI as a free-tier limitation. (The basic slice that's actually built today
  hardcodes 3 days for every upload, since there's no verified/ephemeral distinction yet —
  tune per-tier once auth lands.)
- Verification-code email is sent by **web-compose** (the existing shared email microservice
  already used for contact-form logging), not by adding new SMTP creds directly to
  teachers-center-backend — but this needs a genuinely **new** endpoint there (arbitrary
  recipient), since the existing `/api/contact` always mails a fixed `contact-to` address.

## Two fixes/hardening items (done)

1. `response-output-text` (`openapi/core.clj`) assumed `(:output response)`'s **first** item was
   the assistant message. Once `:tools` is used, OpenAI puts a `file_search_call` item in
   `output` *before* the final `message` item:
   ```json
   "output": [
     { "type": "file_search_call", "id": "fs_...", "queries": [...], "results": [...] },
     { "type": "message", "content": [{ "type": "output_text", "text": "..." }] }
   ]
   ```
   This silently broke response parsing (returned `nil`) the moment file_search was attached.
   Fixed to find the `"message"`-typed item instead of assuming index 0, with a `log/warn` if
   none is found. Confirmed via OpenAI's own docs: *"it is not safe to assume that the model's
   text output is present at `output[0].content[0].text`"* — the official SDKs' `output_text`
   convenience property exists specifically to paper over this.
2. Keying "the teacher's library" by a bare email (as originally sketched) means anyone who
   knows/guesses a teacher's email could list their books. Planned fix (not yet built, since
   auth isn't built yet): `verify-code!` should return a **stateless signed token** (HMAC of
   email+expiry, server secret) instead of just confirming the email; all `/library/*` calls
   after verification should take the token, not a raw email.

---

## 1. `teachers-center-backend`

### `openapi/core.clj` — done
- `response-output-text` fixed (see above).
- `responses-api` accepts `:vector-store-ids`; only adds `:tools` when non-empty:
  ```clojure
  (seq vector-store-ids)
  (assoc :tools [{:type "file_search" :vector_store_ids vector-store-ids}])
  ```

### `conversation/core.clj` / `conversation/ws.clj` — done
- `ask-responses-api` takes a `book-ids` arg, threaded into `responses-api`'s
  `:vector-store-ids`. `edit-slide`, `generate-conversation`, `generate-interactivity` all read
  `(:book-ids req)` and pass it through. When books are attached, a fixed instruction sentence
  is appended to the prompt (`file-search-instruction` in `conversation/core.clj`) rather than
  editing all 6 prompt EDN files.
- `conversation/ws.clj`'s `on-request-callback` parses an optional `:book-ids` field (default
  `[]`) from inbound WS messages.

### `library.clj` — partially done
**Built:** `upload-book!`, `get-book-status`, `upload-handler`, `status-handler` — no
scoping/auth. See its trailing `(comment ...)` block for the REPL walkthrough.

**Not built — the auth/persistence layer:**
- `db.clj` needs a `verification-code-store` atom (`email -> {:code :expires-at}`), following
  the exact pattern already used for `conversation-store`/`interactivity-store`
  (atom + `future`-based writes): `save-verification-code!`, `get-verification-code`,
  `valid-code?`.
- `request-code! [email]` — generates a 6-digit code, `db/save-verification-code!`, calls the
  new web-compose endpoint (§2) via `clj-http.client/post`, same call shape as
  `email_logger.clj` (`app-id "teacher-assistant"`, new `service-id "verification-code"`).
- `verify-code! [email code]` — checks `db/valid-code?`; on success mints an HMAC-signed token
  `(email, expiry)` using a server secret env var (`LIBRARY_TOKEN_SECRET`), returns `{:token
  ...}`.
- `upload-book!` needs extending to accept a verified `token`/`email` and tag the vector store's
  `:metadata` accordingly (currently it doesn't tag metadata at all, since there's nothing to
  scope by yet), and to pick `expires-after-days` based on verified (e.g. 30) vs ephemeral
  (e.g. 3) rather than always 3.
- `list-books [openai-client token]` — verify token → email, paginate `list-vector-stores`,
  filter by `metadata.email`. **Before writing this**: confirm empirically (REPL) that
  `list-vector-stores` items actually include `:metadata` — the current implementation only
  supports `limit/order/after/before`, no server-side metadata filter, so this must be
  client-side filtering after paginating. If metadata isn't present on list items, fall back to
  one `retrieve-vector-store` call per id.

### `handler.clj` / `system.clj` — partially done
**Built:** `wrap-multipart-params` middleware, `POST /library/upload`,
`GET /library/book-status/:id`, `LIBRARY`-adjacent http-kit `:max-body` bump to 25MB.

**Not built:**
```
POST /library/request-code   {email}
POST /library/verify-code    {email code}   -> {token}
GET  /library/books?token=…
```
No new Integrant component needed for any of this (atoms are plain vars like the existing
ones). Add `LIBRARY_TOKEN_SECRET` env var, read directly like `OPENAI_API_KEY`.

---

## 2. `web-compose` — not built

- `config/services.edn` (gitignored, manual edit): add a `:verification-code` service-id under
  the existing `teacher-assistant` app-id, reusing the same Gmail creds already configured
  under `:support`.
- `email/rate_limit.clj`: parameterize `allow?` to take an optional
  `{:max-requests :window-ms}` map instead of hardcoded constants, so it can be reused for both
  `"ip:<ip>"` (existing behavior) and `"email:<to>"` (new, e.g. 3/hour) keys — no parallel file
  needed.
- `email/core.clj`: add `send-verification-code!` — fixed subject/body template, `code` is
  always server-generated digits (never caller-supplied free text), sent to the caller-supplied
  `to` address.
- New `verification_code.clj`, mirroring `contact.clj`: client-ip lookup, both rate-limit
  checks, `cfg/lookup services app-id service-id` for caller auth (same pattern
  `email_logger.clj` already relies on), then `email/send-verification-code!`.
- `handler.clj`: add `POST /api/verification-code` next to the existing `/api/contact` route.

---

## 3. `teachers-center-powerpoint` — not built

### `taskpane.html`
- New `libraryModal`, structurally copied from the existing `settingsModal` pattern, with three
  internal steps toggled by CSS class (not separate modals): email entry → code entry → book
  list (checkboxes + "Upload PDF" button + hidden
  `<input type="file" accept="application/pdf">`).
- A new attach icon-button near the existing `interactivityChip`, plus an `attachedBookChip`
  container using the same show/hide chip pattern already used for `interactivityChip`.

### `taskpane.js`
- `state.elements`: add the new modal/step/chip element refs.
- `openLibraryModal`/`closeLibraryModal` (mirrors `openSettingsModal`/`closeSettingsModal`).
- `requestVerificationCode()`, `verifyCode()` — plain `fetch()` POSTs, same pattern as
  `sendFeedback()` (i.e. **not** sent over the WebSocket).
- `getLibraryStorageKey()` → fixed global string `'teachersCenterLibraryIdentity'` —
  **deliberately not** derived from `Office.context.document.url` like
  `getSettingsStorageKey()`, since a teacher's identity shouldn't depend on which pptx happens
  to be open. Stores `{email, token}` after successful verification.
- `uploadBook(file)` — multipart `FormData` fetch to `/library/upload`.
- `pollBookStatus(vectorStoreId)` — polls `/library/book-status/:id` until it leaves
  `"in_progress"`.
- `attachBook(id, name)` / `detachBook(id)` — maintain `state.attachedBookIds`, update the chip,
  mirroring `enterInteractivityMode`/`exitInteractivityMode`'s show/hide logic.
- `sendWebSocketMessage()`: add
  `...(state.attachedBookIds?.length && { 'book-ids': state.attachedBookIds })` to the outgoing
  WS payload. No changes needed in `handleSend`/`handleEditSend` themselves.
- UI copy in the modal/chip: explicit free-tier disclaimer ("uploaded documents are removed
  after N days of inactivity").

### Why POST + GET polling, not WebSocket push (decided, applies to future frontend work too)
A WS-push design was considered for status updates (a registry of `client-id -> channel`,
populated on WS connect and cleaned up on close, with a background `future` pushing a
`{:type "book-status"}` message once indexing completes) — rejected for now:
- **Fragile across reconnects.** If the socket reconnects (it already does, with backoff) or the
  tab closes while waiting, the push has nowhere to land — there's no server-side state to query
  afterward, only a live channel to push into. Close and reopen the tab and the notification is
  lost even though the PDF finished indexing.
- Needs new infrastructure: channel registry, `on-close` cleanup, a `client-id` plumbed through
  both the WS connect URL and the upload POST body, and would be the *first* place in this
  codebase where the server pushes something outside of a direct request/response tick.
- Harder to test (needs an actual WS client, not just `curl`).

Polling is stateless and resilient by construction (survives reconnects/reloads/tab-close as
long as the vector-store-id is remembered client-side, e.g. localStorage), trivially
`curl`-testable, and needs far less new code. It's the better default for this product's current
scale; WS push could be layered on later purely as a UX nicety, with polling kept as the
resync/fallback path.

Upload itself is POST (not WS) for a related but separate reason: WS messages here are JSON text,
so a multi-MB PDF would need base64 encoding first (~33% size bloat — the test PDF is 8.5MB →
~11MB as base64), risking frame/message-size limits somewhere in the stack that a normal HTTP
multipart body (streamed to a temp file by Ring, not held in memory as one string) doesn't hit.

---

## Build order

1. ~~`response-output-text` fix~~ — done.
2. ~~`:tools` plumbing (`responses-api` → `ask-responses-api` → `generate-conversation`)~~ —
   done, REPL-verified.
3. ~~Basic upload: `library.clj`'s `upload-book!`/`get-book-status`, `handler.clj` routes,
   multipart middleware~~ — done, curl- and WS-verified end to end.
4. **Next:** `db.clj` verification-code atom + `library.clj`'s `request-code!`/`verify-code!` —
   REPL-testable in isolation, no web-compose dependency needed yet for the code-generation and
   storage part.
5. `web-compose`: `verification_code.clj` + rate-limit change + `services.edn` entry — testable
   standalone via curl, no dependency on teachers-center-backend.
6. Wire `library.clj`'s `request-code!` to the new web-compose endpoint, add the
   `list-books`/token-scoped upload changes.
7. `handler.clj` routes for request-code/verify-code/books.
8. Frontend UI last — `npm run start:dev`, exercise the full flow against the already-verified
   backend.

---

## Verification

**REPL walkthrough for what's built today:** see the `(comment ...)` block at the end of
`src/teachers_center_backend/library.clj` — upload a PDF, poll status, ground a real
`generate-conversation` call in it, all from the REPL.

**curl, for what's built today:**
```bash
curl -X POST localhost:2000/library/upload \
  -F "file=@resources/deep_research_blog.pdf" \
  -F "book-name=Deep Research Blog"
# => {"vector-store-id":"vs_...","status":"in_progress","book-name":"Deep Research Blog"}

curl localhost:2000/library/book-status/vs_...
# poll every few seconds until "status":"completed"
```

**curl, once the auth layer is built:**
```bash
curl -X POST localhost:2000/library/request-code -H "Content-Type: application/json" -d '{"email":"you@example.com"}'
curl -X POST localhost:2000/library/verify-code -H "Content-Type: application/json" -d '{"email":"you@example.com","code":"123456"}'
curl "localhost:2000/library/books?token=<token>"

curl -X POST localhost:3000/api/verification-code -H "Content-Type: application/json" \
  -d '{"app-id":"teacher-assistant","service-id":"verification-code","to":"you@example.com"}'
```

**Manual add-in test**, once frontend work lands: `npm run start:dev` — open the Library modal,
request a code, confirm it arrives by email, verify, upload a PDF, watch it move from
"processing" to "ready" via polling, attach it, send a chat message referencing content only in
that PDF, confirm the generated slides reflect it.

---

## Real bugs found only by running the code (not visible from reading it)

These surfaced during implementation of the basic upload slice and are worth knowing about
before touching this code again:

1. `ring.middleware.multipart-params` needs `javax.servlet-api` on the classpath at compile
   time, even though nothing here runs in a servlet container. Added to `deps.edn`.
2. `openapi/file_search.clj` originally required the dev-only `dev` namespace unconditionally at
   the top of the file — harmless while the namespace was REPL-only, but broke production boot
   once `library.clj` pulled it into the real load path. Moved to an inline `require` inside its
   `(comment ...)` block, matching the convention already used in `conversation/core.clj`'s
   comment block.
3. http-kit defaults to an 8MB max request body — below the app's own 20MB PDF-upload limit.
   Raised via `:max-body` in `system.clj`'s `run-server` call.
4. The actual root cause of an early "Invalid extension tmp" upload failure: clj-http's
   multipart support for `File` content only honors a custom filename when `:mime-type` is
   *also* present in the part map (checked directly in `clj-http.multipart`'s source), and it
   reads the filename from `:name`, not a `:filename` key (which clj-http doesn't recognize at
   all). The original POC only "worked" via the REPL by coincidence — the source file already
   had a `.pdf` extension on disk, so it fell through to a constructor that ignores any name
   override and just used the `File` object's own name. An HTTP-uploaded file is a Ring tempfile
   named like `ring-multipart-....tmp`, which exposed the real gap. Fixed in `upload-file` by
   using `:part-name` (the actual form field name) separately from `:name`+`:mime-type` (which
   together control the displayed filename).

## Open items / risks

- Vector-store-id ownership isn't re-validated server-side when a chat request attaches
  `book-ids` (IDs are opaque/unguessable `vs_...` strings) — acceptable low risk for a free
  anonymous product, flagged rather than silently skipped.
- `expires-after-days` defaults (currently a flat 3 for every upload) will need a
  verified/ephemeral split once auth lands — not a firm requirement yet, adjust once real usage
  is observed.
- `list_vector_stores`'s metadata round-trip is unconfirmed — verify empirically in the REPL
  before writing `list-books`'s filtering logic (see §1 above).
