

Book OCR Phases 1–4 — release checklist (2026-10-13)
====================================================

- [ ] Test in PowerPoint (desktop + web), backend running locally on :2000,
      `npm run start:dev`: scanned PDF (chip: Reading your book… → Indexing… →
      Ready, deck uses the book), digital PDF, >60 MB or broken PDF (error
      message), remove the book while it's processing. Also: the same PDF a
      second time (ready in seconds, cache), a deck right after "Ready" while
      the backend is still "improving" (must not block), a 429 message (start
      the backend with BOOK_IP_DAILY_BOOKS=1, upload two new PDFs).
- [ ] Privacy policy (teacher-assistant): name Microsoft Azure (EU) as a
      processor for text recognition; on the Azure path only the extracted text
      is stored at OpenAI, kept 29 days (was 7) and reused when someone uploads
      the exact same PDF (matched by file fingerprint, not name).
- [ ] Deploy: set AZURE_DI_ENDPOINT / AZURE_DI_KEY on Render (without them
      uploads silently use the old PDF path). Optional: BOOK_IMPROVE=false (Luna
      off), BOOK_DAILY_PAGES (default 3000), BOOK_IP_DAILY_BOOKS (default 10).
      Deploy backend + add-in together
      (the live add-in polls by vector-store id and breaks against the new
      endpoint); optional version 2.1.0 (web-only, no Partner Center
      resubmission); check /health and one real upload on production.
- [ ] After deploy: check what Render puts in X-Forwarded-For (log one upload's
      headers). library/client-ip takes the FIRST entry, which a client can fake
      to dodge the per-IP limit (the daily page budget still caps cost). Take
      the last entry only if Render appends the real client IP there.


Book OCR — what's left after Phases 1–4 (2026-10-13)
====================================================

Code for Phases 1–4 is built and reviewed (doc/book-production-plan.md), not
committed. In order:

Before release
- [ ] Commit both repos (backend: library, azure/, book/, openapi additions,
      config, deps (jbig2-imageio), tests, prompt moved to resources/book/;
      add-in: job polling, chip stages, 60 MB, error messages).
- [ ] Luna only for German books until Phase 0 is done: the add-in sends the
      teacher's language with the upload, the backend skips `improving` for
      other languages (they stay on Azure text). Or start with
      BOOK_IMPROVE=false and turn it on later.
- [ ] One full-size run before release (e.g. Schritte 2, 211 pages, 46 MB) on
      the paid Render instance: RAM use (PDFBox + page images), time to usable /
      ready, Azure + Luna cost. Only measured locally so far (2-page PDFs).
- [ ] doc/CERTIFICATION_NOTES.md + What's New text: 60 MB, 300 pages, scanned
      books work.
- [ ] README "Testing" section is still a TODO: `clojure -X:test` (unit tests);
      `book-convert-eval/recheck-book` re-checks saved answers for free;
      `-M:dev:repl` starts nREPL and never exits, so scripts use `-M:dev`.

Azure / OpenAI accounts
- [ ] Azure trial credit ends ~2026-11-02: set up billing for the S0 resource
      (~$10 per 1,000 pages) or uploads fall back to the PDF path.
- [ ] Clean up test data at OpenAI: the ~10 old test PDFs (no expiry) and the
      files / vector stores from today's local tests (they expire on their own:
      files 29 days, stores 3 days unused).

Speed (Phase 2, doc/book-improve-speed.md)
- [x] Option 1: 24 pages at once (BOOK_PARALLEL), global limit 24, 429s waited out.
- [ ] Measure option 1 on a real book: wall clock (target ~6–8 min / 211 pages), "Rate limited,
      waiting" lines, RAM on Render. Lower BOOK_PARALLEL if 429s show up.
- [ ] Option 2: effort medium/low eval on the 53-page prompt-v4 set.
- [ ] Option 4 (improve + swap in parts): designed, build only if a book still takes ≥10 min.

Digital PDFs: skip Azure, keep Luna (2026-10-13, not built)
- Today every PDF goes through Azure + Luna, even one with a good text layer (manual test:
  deep_research_blog.pdf, 38 pages, ~$0.40 Azure for text the PDF already had).
- Decision: skip **Azure** (~77% of the cost, $10 / 1,000 pages), keep **Luna** (~$0.003/page):
  - The text layer is exact, so it's a better reference for the check than OCR.
  - Luna is what fixes layout (tables, gaps, columns). Digital textbooks need that as much as
    scans; PDFBox text loses it. BOOK_IMPROVE still turns Luna off.
  - Skipping both (PDFBox text only) is the fallback when Luna is off. Skipping only Luna saves
    little.
- PDFBox probe (free, scratchpad text_layer_probe.clj): English Today 3/3 pages with text,
  deep_research_blog 27/38 (the rest short or image pages), Penguin reader 32/34; the Schritte
  scans 0 text and a full-page image on every page. No broken characters in any of them.
- Build:
  - [ ] Detect a good text layer: most non-blank pages with ≥100 letters, mostly letters
        (\p{L} ratio ≥ 0.7), no U+FFFD / private-use characters. A page with a full-page
        image AND text is a scan with the scanner's own OCR → treat as a scan (Azure).
  - [ ] Good → per-page text from PDFTextStripper (sortByPosition) in the split-pages shape
        (+ tag-audio, clean, page markers; no footers → marker is just "PDF p. N"),
        `:method "text-layer"`. Otherwise Azure as today.
  - [ ] Check how check.clj behaves with plain text (no Azure comments / handwriting) and
        that the prompt's "OCR TEXT" wording still fits.
  - [ ] Later: mixed PDFs (some pages scanned) → Azure only for those pages (`pages` parameter).
  - [ ] Daily page budget (book.limits) counts Azure pages; decide if text-layer pages count
        less.

Deck prompt: book language ≠ class language, clarification answers (2026-10-13, manual test)
- English blog for a German B1 class, "2 slides on the vocabulary from the book": gpt-4o searched
  the request's words (against the prompt), asked for the topic (against the prompt) in German
  although the teacher wrote English; the answer "Deep research" gave 2 English bullet slides
  about deep research — "vocabulary" and German were lost.
- [ ] conversation-content.edn: slide content always in {{language}} / {{level}}; a document in
      another language only gives the topic.
- [ ] Combine a clarification answer with the original request.
- [ ] Ask the clarification question in the teacher's language.
- [ ] Same language rule in the interactivity-*.edn prompts.
- [ ] Eval (dev/slide_eval.clj): English doc + German class, German doc + English class,
      clarification follow-up; several runs each (temperature 0.7).

Chat model: gpt-4o (temperature 0.7) → Luna? (2026-10-13, not started)
- Every chat template (deck, edit, 4 games) still uses gpt-4o; Luna only repairs book pages.
- For: reasoning models keep many rules better (the deck-prompt bug above broke 2 rules at
  once; book pages: Luna 8/10 vs gpt-4o 5/10). Probably cheaper: the 211-page run was $0.64 for
  ~2.2M tokens; check the real Luna price.
- Against / unknown: speed (deck ~6 s today; Luna `high` was 25–115 s per book page);
  max-output-tokens includes reasoning (decks have 3000 → JSON could be cut off); no temperature;
  Luna + strict json_schema + file_search + previous_response_id untested together.
- Code is ready: openapi/core.clj sends :reasoning-effort, each template's :config goes through.
- [ ] Do the deck-prompt fix above first, so the comparison runs on the new prompt.
- [ ] dev/slide_eval.clj: option to override the model / effort / max-output-tokens.
- [ ] Compare gpt-4o vs Luna none / low / medium on requests.edn + the 3 new language cases:
      response time (median, slowest), schema errors, soft-limit violations, unneeded
      clarification questions, content in the class language. Expected cost < $1.
- [ ] Decide per template: edit = fastest good one (teacher waits); deck = rules matter most;
      games = correctness matters most (answers reach students' phones).

Quality (Phase 2 output)
- [ ] Full-book run with prompt v4 to confirm the ~10% fallback estimate
      (doc/Results-211-page-boo-converted.md §7, skipped so far, ~$0.70).
- [ ] The 6 unsettled fallback pages (103, 112, 155, 104, 119, 153 — §8) and
      the real errors still left (30: example twice + dropped grammar box; 73:
      example letters turned into ticks; 122: repeated line).
- [ ] Phase 0, the multi-language section above.

Known risks (not fixed on purpose — decide if they matter)
- [ ] Azure fails on a scanned book → the job sends the PDF itself → empty
      index, but the teacher sees "ready". Could fail with a message instead
      when the PDF has no text layer.
- [ ] JPEG 2000 page images: no decoder (license of jai-imageio-jpeg2000), those
      pages keep Azure text.
- [ ] Jobs, limits and the cache index are in memory: a deploy loses running
      improvements (the book stays on Azure text) and resets the daily counters.
- [ ] A cache hit at upload isn't counted against the limits; if that cached
      file then turns out to be gone, the full conversion runs uncounted.

Cleanup
- [ ] Stray untracked files in the backend repo: `#README.md#`, `TODO.md~`,
      `nul`, `.clj-kondo/`.
- [ ] Docs still point at the old prompt path dev/eval/book-page-prompt.txt
      (now resources/book/page-prompt.txt) — fine as history, or update.
