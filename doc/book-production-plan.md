# Book OCR in production: implementation plan

**Date:** 2026-10-13
**Builds on:** `book-ocr-investigation.md` (§6) and `Results-211-page-boo-converted.md` (§6–8)

## Today vs. target

**Today:** `POST /library/upload` sends the PDF to OpenAI as-is → vector store → the add-in polls
`/library/book-status/:vector-store-id` for ~2 min. A scanned book gives an empty index, with no error.

**Target:**
```
upload PDF ─► job (in memory) ─► ① Azure Layout (~25 s for 200 pages)
                                 ② clean-up, Azure text ─► vector store      status: usable  (book works)
                                 ③ Luna per page + check + fallback (~10–20 min, background)
                                 ④ book.md replaces the Azure text in the same vector store
                                                                              status: ready
```
The teacher can use the book after ~1–2 minutes. Luna only makes it better later.

## Phases

Each phase ends deployable. Phase 1 alone already fixes scanned books.

### Phase 0: Prerequisites (small)
- Language-neutral changes from TODO.md (`\p{L}`, language in the prompt, `(Example: …)`). Needed before
  real teachers upload non-German books, but not before Phase 1 (Azure text is language-neutral).
- Decide on Azure after the trial (~2026-11-02): S0 billing, ~$10 per 1,000 pages.

### Phase 1: Azure text only (the big win)
Backend:
- `azure/layout.clj`: analyze + poll (move from `dev/book_convert_eval.clj`), timeouts, error handling.
- `book/convert.clj`: step ② as pure functions (`split-pages`, `mark-examples`, `tag-audio`, `clean`,
  page markers, join). Tests with small saved Azure JSON snippets (no book content in the repo).
- `book/jobs.clj`: in-memory job store (atom) + a small fixed thread pool. A job has
  `{:id :status :pages-total :pages-done :vector-store-id :error :created-at}`.
- `POST /library/upload` → validates, starts a job, returns `{job-id}` at once.
- `GET /library/book-status/:job-id` → `{status, pages-done, pages-total, vector-store-id?, error?}`.
  Status: `ocr` → `indexing` → `usable` (→ later `improving` → `ready`) or `failed`.
- Upload Azure's `book.md` (text, not the PDF) to OpenAI → vector store with the same expiry as today.
- Limits: size 20 → 60 MB (also http-kit `:max-body`), page cap (e.g. 300), PDF only.
- If Azure fails: fall back to today's path (send the PDF to OpenAI), so digital PDFs keep working.

Add-in (`taskpane.js`):
- Upload → `job-id`; poll `book-status/:job-id`; book usable at `usable`.
- Chip: "Reading your book…" → "Ready". Timeout ~2 → ~5 min for Phase 1.
- Keep the old response shape working for one deploy, or deploy backend + add-in together.

### Phase 2: Luna in the background
- After `usable`, the same job runs ③ with a parallelism setting (start 8 per book) and a
  **global** limit across books (e.g. 16 calls at once), so two uploads don't hit rate limits.
- Status `improving` with `pages-done/pages-total`; the add-in can show it but doesn't wait for it.
- ④ Swap: add `book.md` as a new file to the same vector store, wait until it's indexed, then remove
  the Azure file. `vector-store-id` doesn't change, so running conversations keep working.
- Log per book: pages, ok / retried-ok / fallback counts, tokens, cost, time.

### Phase 3: Cache by file hash
- SHA-256 of the PDF → if a converted `book.md` exists, reuse it (no Azure, no Luna).
- No database: store `book.md` at OpenAI with the hash in its filename and look it up with the
  files list. It lives as long as the OpenAI file (7 days); later move to real storage if books repeat.
- Popular textbooks are where this pays off: the second teacher with Schritte 2 pays nothing.

### Phase 4: Guardrails
- No accounts, so anyone can upload: a daily budget (pages/day for the whole service) and a per-IP
  limit (e.g. 3 books/day). Over the limit → Phase 1 only (Azure text), or a clear error.
- Kill switch: config flag to turn Phase 2 off without a deploy.

### Phase 5: Release
- Privacy policy: Microsoft Azure (EU) as a processor; "your PDF is sent to Azure for text
  recognition; only the extracted text is stored at OpenAI (7 days)".
- `CERTIFICATION_NOTES.md`; web-only change, so no Partner Center resubmission (manifest unchanged).
- Test in PowerPoint desktop + web: scanned book, digital book, 46 MB book, expired book, two uploads at once.

## Risks and open questions
| Risk | Why it matters | Mitigation |
|---|---|---|
| **Render restarts / spin-down** | jobs live in memory; a 20-min Luna run dies with the instance | Phase 1 is short (~1–2 min); Phase 2 loss only means "stays on Azure text". Check the Render plan (free instances sleep after 15 min). |
| Memory on Render | a 46 MB PDF + page images | render one page at a time, never hold all images |
| Cost abuse | no accounts, ~$2.75 per 200-page book | Phase 4 limits; Phase 2 is the expensive part and can be switched off |
| Fallback rate on other books | measured only on Schritte 2 (~10%) | eval set from TODO.md before relying on Phase 2 quality |
| Expired books | vector store expires after 3 days unused | already open in TODO.md (case 9) |

**Questions to decide:**
1. Which Render plan is the backend on (does it sleep, how much RAM)?
2. Page cap and daily budget numbers.
3. Ship Phase 1 alone first? (Recommended: it fixes scanned books with little risk.)

## Status

**Phase 1 built (2026-10-13, not deployed):**
- Backend: `azure/layout.clj`, `book/convert.clj` (moved from `dev/`, blank pages handled), `book/jobs.clj`
  (in-memory jobs, 4 at once, daemon threads, kept 1 day), `library.clj` (upload → job id; status by job id;
  404 for an unknown job), config: handler gets `:azure-di`, http-kit `:max-body` 65 MB.
- Limits: 60 MB, 300 pages, PDFs PDFBox can't open are rejected with a clear message.
- Fallback: no Azure config or an Azure error → the PDF goes to OpenAI as before (`:method "pdf"` in the log).
- Add-in: polls by job id, chip shows "Reading your book…" / "Indexing…", 5-min timeout, server's 400
  messages shown to the teacher, help text says 60 MB / 300 pages / scans work.
- Tests: `convert_test.clj`, `jobs_test.clj` (54 tests pass).
- Live check: the 2-page Schritte scan → `usable` in 8 s (Azure 4.8 s); a vector-store search returns the
  scanned page's text with its page marker.

**Before deploying Phase 1:** the privacy policy must name Azure — the PDF now goes to Microsoft for OCR
(Phase 5 item, but Phase 1 already changes the data flow). Backend and add-in deploy together: the old
add-in polls by vector-store id and would break against the new status endpoint.

**Phase 2 built (2026-10-13, not deployed):**
- `book/improve.clj`: steps ③–⑤ moved from `dev/` (page image, Luna repair, check + one retry + fallback);
  per book 8 pages at once, a global semaphore of 16 model calls across books; page images rendered one at a
  time under a lock (PDFBox isn't thread-safe); blank pages skipped.
- `library.clj`: after `usable` the job continues on its own pool (`jobs/continue-later!`, 2 books at once)
  so a long improvement never delays a new upload's OCR. Status `improving` + `pages-done` → `ready`. The
  new `book.md` is added to the **same** vector store, indexed, then the Azure file is removed and
  deleted. Any failure → back to `usable` with `:improve-error`; the book keeps working on Azure's text.
- Config `:teachers-center-backend/book`: `BOOK_IMPROVE=false` turns Phase 2 off without a deploy; model
  `gpt-6-luna`, effort `high`, 8 pages at once. Prompt moved to `resources/book/page-prompt.txt`.
- OpenAI client: `delete-request`, `add-file-to-vector-store`, `remove-file-from-vector-store`, `delete-file`.
- Add-in: `usable`, `improving` and `ready` all mean "book ready" (the teacher doesn't wait for Phase 2).
- Tests: `improve_test.clj` (ok / retry with the check's reasons / fallback / request error). 58 tests pass.
- Live check: 2-page scan → usable in 7 s, improved in 80 s (2 × ok, 7.8k tokens in / 9.7k out), the store
  then holds one file — Luna's text — under the same id.

**Speed (2026-10-13):** 8 → 24 pages at once (`BOOK_PARALLEL`), 429s waited out; other options, incl.
swapping the book in parts, in `book-improve-speed.md`.

**Not done in Phase 2:** the prompt is still German/Schritte-specific (TODO.md, Phase 0) — for other books
Phase 2 mostly falls back to Azure text, which is safe but adds cost. Consider `BOOK_IMPROVE` per language
until Phase 0 is done.

**Phase 3 built (2026-10-13, not deployed):**
- `book/cache.clj`: key = SHA-256 of the PDF bytes (not the name: names are shared by different files, and a
  name match could hand one teacher's file to another). The finished book's OpenAI file is named
  `<title> [sha256-<hash>].md`; an in-memory index is rebuilt from OpenAI's file list on first use.
- Only `ready` (improved) books are cached. A hit → new vector store with the cached file → `ready`
  (`:method "cache"`). A cached file that's gone → forgotten, converted again.
- Retention: book files now expire after **29 days** (was 7; OpenAI max 30). Privacy policy must say so.
- Upload log has `:sha` (first 12 chars) — shows how often the exact same file comes back.
- Tests: `cache_test.clj` (61 tests pass).
- Live check: 1st upload 94 s (full conversion), same file again 2 s, after a server restart 10 s
  (index rebuilt from OpenAI: 1 book).

**Phase 4 built (2026-10-13, not deployed):**
- `book/limits.clj`: daily page budget for the whole service (`BOOK_DAILY_PAGES`, default 3000 ≈ $40/day) and
  books per IP per day (`BOOK_IP_DAILY_BOOKS`, default 10 — a school often shares one IP). IP from
  `X-Forwarded-For` (Render's proxy). Cache hits never count. In memory, reset at midnight UTC and on deploy.
- Over a limit: 429 with a message for the teacher ("…please try again tomorrow"); the add-in shows it.
  Not a silent fallback to the PDF path — that would give scans an empty index again.
- Kill switch: `BOOK_IMPROVE=false` (from Phase 2).
- Tests: `limits_test.clj` (63 tests pass). Live check with a limit of 1: new PDF 200, second new PDF 429,
  cached PDF 200; `BOOK_IMPROVE=false` stopped the book at `usable`.
