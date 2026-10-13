# Book improvement (Luna) speed

**Date:** 2026-10-13
**Builds on:** `book-production-plan.md` (Phase 2) and `Results-211-page-boo-converted.md` (§1, §4)

## 1. Why

The teacher doesn't wait for Luna: the book is `usable` on Azure's text after ~1–2 min. The Luna
stage still took **~20 min** for the 211-page Schritte 2 book, and while it runs:

- every deck uses Azure's text, which is worse on tables, letter gaps and ticks
- a Render restart or deploy loses the whole run (the book stays on Azure's text)
- only 2 books are improved at once (`book.jobs`), so a third upload waits for its improvement

Where the time goes (211-page run, 8 pages at once):

| | Measured |
|---|---|
| Time per page | median 25–34 s, 90% under ~87 s, slowest 115 s |
| Output tokens | 1.06M ≈ 5k per page, while a page's text is ~1–1.5k tokens, so most of it is reasoning (effort `high`) |
| Retries | 19% of pages run twice; they make the slow tail at the end |
| Swap | once, at the end: the book only improves when the slowest page is done |
| Rate limits | none hit; 8 at once used ~1/4 of the account's limits |

## 2. Options

| # | Option | Expected gain | Status |
|---|---|---|---|
| 1 | More pages at once (8 → 24) | ~20 → ~6–8 min | **Built** (§3) |
| 2 | Lower reasoning effort (`medium` / `low`) | maybe 1.5–3× per page | not done; needs an eval on the 53-page prompt-v4 set (~$0.30 per run) |
| 3 | Skip Luna on plain pages (no tables, gaps, ticks, handwriting) | maybe 20–40% fewer calls | not done |
| 4 | Improve and swap the book in parts | first parts better after ~2 min; less lost on a restart | **Designed** (§4), not built |
| 5 | Shorter tail: cheaper retry, or no retry when the check found a lot | the last 2–4 min | not done |
| 6 | OpenAI priority processing (`service_tier`), if the account has it | faster, steadier answers | not checked; costs more |
| 7 | Smaller page images (scans go as they are, `detail: "high"`) | small | not done |

Not useful for speed: the Batch API and the flex tier are cheaper but slower. gpt-4o was faster but
passed 5/10 pages vs Luna's 8/10 (`book-ocr-investigation.md` §5.1).

## 3. Option 1: built (2026-10-13)

- `config.edn`: `:parallel` 8 → **24** pages at once per book, `BOOK_PARALLEL` overrides it without
  a code change.
- `book/improve.clj`: the global limit across all books (`max-model-calls`) 16 → **24**, i.e. ~3/4 of
  the account's limits if 8 really used ~1/4. It's also the ceiling for `BOOK_PARALLEL`: a higher
  value only adds threads. Two books at once now share 24 calls (fair semaphore).
- `call-with-backoff`: a rate-limit 429 waits 5, 10, 20, 40 s and tries again, and gives its slot
  back while waiting. Before, a 429 used up the page's one check retry (with the feedback "The
  request failed") and a second 429 left the page on Azure's text. A 429 with `insufficient_quota`
  isn't waited out (waiting doesn't help). Tests in `improve_test.clj`.

**Not measured yet.** Before relying on it, run a real book at 24 and log:
- the wall clock (target ~6–8 min for 211 pages), and any "Rate limited, waiting" lines
- RAM on Render: up to 24 page images (base64 JPEG, ~1–3 MB each) plus their request bodies are
  in memory at once, vs 8 before. Page rendering stays one at a time (PDFBox lock).

If 429s show up, lower `BOOK_PARALLEL` on Render (no deploy needed for the env var, only a restart).

## 4. Option 4: improve and swap in parts (design, not built)

### The idea

Today the book is **one file** in the vector store, and Luna's `book.md` replaces Azure's file once
every page is done (`library.clj`, `improve-book!`). Instead, the book becomes **parts of ~20 PDF
pages**, each its own file in the same vector store from the start. When Luna finishes the last
page of a part, only that part is swapped:

```
today:     [ Azure text, whole book ──────────── ~20 min ──────────── ] → [ Luna, whole book ]

in parts:  part 1   [Azure ~2 min] → [Luna]
           part 2   [Azure ~4 min  ] → [Luna]
           ...
           part 11  [Azure ──────────── ~20 min ────────────] → [Luna]
```

The total time is the same; the book gets better part by part (at 8 at once, about one part every
~2 min; at 24, about one a minute).

### Why parts of fixed size, not lessons

- Lesson numbers come from the "LEKTION" footer, which is Schritte-only (Phase 0 in TODO.md); a page
  count works for every book.
- Retrieval doesn't care: file_search cuts every file into small chunks anyway, and every page already
  starts with its marker (`<!-- Schritte 2 · Lektion 8 · S. 92 · PDF p. 14 -->`). A part boundary only
  touches the one passage that would have crossed it.
- ~20 pages: a 300-page book (the cap) is 15 parts → ~60 extra API calls per book, each a few seconds.
  Single pages (211 files) would be too many swaps for little gain.

### Changes

| Where | Today | In parts |
|---|---|---|
| `run-book-job` (usable) | 1 Azure `.md` → new vector store | split `pages` into parts → N files → **one** vector store created with all N file ids (`create-vector-store` already takes a list) |
| `improve/improve-pages` | returns when every page is done | same pool and page order (parts finish roughly in order); a new `:on-part` callback fires when a part's last page is done |
| `improve-book!` (swap) | once: add Luna file → wait until indexed → remove Azure file → delete it | the same 4 steps **per part**, on its own thread so page workers aren't blocked; the job keeps a map part → Azure file id |
| `book/cache.clj` | 1 file per sha: `<title> [sha256-…].md` | N files per sha: `<title> [sha256-…] part 03 of 11.md`; `load-index!` groups by sha; a hit needs **all** N parts, the newest complete set wins |
| job status | `improving` + `pages-done` → `ready` | plus `parts-done` / `parts-total`; `ready` when every part is swapped |
| add-in | treats `improving` as ready | no change needed; optional chip label "Improving 4/11" |

### What it changes besides speed

- **Failures stay in their part.** Today one failed swap leaves the whole book on Azure's text; in
  parts only that part does, and the others still improve. The job ends `ready` only if every part
  was swapped; otherwise `usable` with `:improve-error`, as today.
- **A restart loses less.** Swapped parts stay improved (they're in the vector store). The book isn't
  cached (incomplete), so the next upload of the same PDF converts again.
- **Double passages during a swap.** Between "new file indexed" and "old file removed" both versions
  are in the store and a search can return the same passage twice. Today that's one window over the
  whole book; in parts it's N windows of ~20 pages each. Add-then-remove stays the right order: the
  other way leaves a gap where the part is missing.
- **Mixed text during the run.** A deck at minute 8 can get Luna's text from early parts and Azure's
  from late ones. Harmless: each part is complete in either version.

### Edge cases to test

- a part whose pages are all blank (no file for it, or an empty one: skip it)
- a failed swap of one part (the others go on; the job ends `usable`)
- a cache hit with one part missing or expired (forget the sha, convert again)
- two uploads of the same PDF at once (two complete sets: the newer wins, as today)
- a book shorter than one part (one part = today's behaviour)

### Effort and when to build it

Medium: `library.clj`, `improve.clj` (`:on-part`), `cache.clj`, plus tests for the split, a failed part
swap and a partial cache hit. About half a day to a day with one live 2-part run.

Build it if, after option 1 (and maybe 2), a full book still takes **≥10 min**, or if 300-page books
or 2 books at once turn out to be common. At ~5 min the extra cache complexity isn't worth it.

Later idea parts make possible: improve the part a teacher is using first (file_search results say
which pages were found, so that part could jump the queue).
