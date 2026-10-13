;; This buffer is for text that is not saved, and for Lisp evaluation.
;; To create a file, visit it with ‘C-x C-f’ and enter text in its buffer.

1. Single requests, one per book state

  #: 1
  Case: Deck, no book
  What happens now: Normal flow: strict deck schema, may ask a clarification
  question
  Problem?: OK
  ────────────────────────────────────────
  #: 2
  Case: Deck + ready book
  What happens now: file_search runs, plus the instruction "treat the
  document
  as the topic"
  Problem?: Mostly OK. Citations always come back as 0 (strict output), so
  there's no proof the book was actually used
  ────────────────────────────────────────
  #: 3
  Case: Deck while the book is still processing
  What happens now: No book-ids are sent. The message has no "📄 Using"
  label.
  "Slides from the book" gets a clarification question asking for the topic
  Problem?: ⚠️ Nothing tells the teacher the book was skipped, and sending
  isn't blocked
  ────────────────────────────────────────
  #: 4
  Case: Game, no book
  What happens now: JSON as plain text (no strict schema)
  Problem?: OK, same as today
  ────────────────────────────────────────
  #: 5
  Case: Game + ready book
  What happens now: file_search is attached and the file-search instruction
  is
  added
  Problem?: ⚠️ See the issues list: the instruction talks about slides, and
  the JSON is more likely to break
  ────────────────────────────────────────
  #: 6
  Case: Game while the book is still processing
  What happens now: Sent without the book. Game prompts have no clarification

  path, so the game is made up
  Problem?: ⚠️ Worse than case 3: you silently get a generic game instead of
  a
  question
  ────────────────────────────────────────
  #: 7
  Case: Edit, no book
  What happens now: One-off request with the original request and the current

  slide as context
  Problem?: OK
  ────────────────────────────────────────
  #: 8
  Case: Edit + ready book
  What happens now: The book is attached to the edit too
  Problem?: ⚠️ See the issues list
  ────────────────────────────────────────
  #: 9
  Case: Any request, book expired (3 days) or deleted at OpenAI
  What happens now: OpenAI returns an error, which becomes a bad-request
  error
  with the text "try rephrasing"
  Problem?: ⚠️ Misleading message. The chip still says ready, so every later
  request fails until the teacher removes the book
  ────────────────────────────────────────
  #: 10
  Case: Any request, backend restarted
  What happens now: Books live at OpenAI, so they survive. Follow-up history
  and saved games are lost
  Problem?: Games: old QR codes return 404 (this happens without a book too)

  2. Sequences inside one conversation

  #: 11
  Sequence: Deck → clarification question → answer (no book)
  What happens now: Continues the conversation via previous_response_id
  Problem?: OK
  ────────────────────────────────────────
  #: 12
  Sequence: Deck + book → clarification → answer + same book
  What happens now: Continues and uses the book
  Problem?: OK
  ────────────────────────────────────────
  #: 13
  Sequence: Deck + book → clarification → book removed → answer
  What happens now: Continues, but file_search is no longer attached. The
  history still contains the earlier book search
  Problem?: ⚠️ The model may claim to use "the document" without being able
  to
  search it
  ────────────────────────────────────────
  #: 14
  Sequence: Deck + book A → clarification → switch to book B → answer
  What happens now: History shows book A's search; the new tool searches book

  B
  Problem?: ⚠️ May mix content from the two books
  ────────────────────────────────────────
  #: 15
  Sequence: Deck, no book → clarification → book attached → answer
  What happens now: Continues, and the book is now attached
  Problem?: Probably OK, and could even be the intended flow
  ────────────────────────────────────────
  #: 16
  Sequence: Deck → clarification → switch to a game mode and answer
  What happens now: Deck and game requests share the same conversation-id, so

  the game request continues the deck's history
  Problem?: ⚠️ Untested: a game request continuing a deck conversation, with
  or without a book
  ────────────────────────────────────────
  #: 17
  Sequence: Game → ??? → deck
  What happens now: Games have no clarification path. Inserting clears the
  conversation, but I didn't check whether dismissing a game preview does
  Problem?: Needs checking. If the conversation survives, a deck request
  would
  continue a game's history
  ────────────────────────────────────────
  #: 18
  Sequence: Deck with book A → preview → attach book B → edit a slide
  What happens now: The edit runs against book B, which the slide never came
  from
  Problem?: ⚠️ Mismatch
  ────────────────────────────────────────
  #: 19
  Sequence: Deck with a book → preview → remove the book → edit
  What happens now: The edit runs without the book, so "add another example
  from the book" can't work
  Problem?: ⚠️
  ────────────────────────────────────────
  #: 20
  Sequence: Deck without a book → attach a book → edit
  What happens now: The edit is suddenly grounded in the book
  Problem?: ⚠️ Could rewrite more of the slide than the teacher asked for
  ────────────────────────────────────────
  #: 21
  Sequence: Edit a game
  What happens now: Blocked in the add-in (editSlide returns when
  pendingActivity is set)
  Problem?: OK, not possible
  ────────────────────────────────────────
  #: 22
  Sequence: Upload a book while a request is in progress
  What happens now: The upload goes ahead; the running request doesn't use it
  Problem?: OK. The next request uses it once it's ready
  ────────────────────────────────────────
  #: 23
  Sequence: Insert, then a new request with the same book
  What happens now: The conversation is cleared but the book stays attached
  Problem?: OK, that's intended ("book per session")
  ────────────────────────────────────────
  #: 24
  Sequence: New chat
  What happens now: Clears the conversation and removes the book
  Problem?: OK, but worth confirming that's what teachers expect

  3. Content combinations to test (quality, not wiring)

  ┌─────┬────────────────────────────┬───────────────────────────────────┐
  │  #  │            Case            │          Why it matters           │
  ├─────┼────────────────────────────┼───────────────────────────────────┤
  │     │ Book language ≠ target     │ The prompts say "content in       │
  │ 25  │ language (e.g. a German    │ {{language}}", but file_search    │
  │     │ PDF for an English class)  │ passages may pull in the book's   │
  │     │                            │ language                          │
  ├─────┼────────────────────────────┼───────────────────────────────────┤
  │     │ Book with a native         │ Is the translation built from the │
  │ 26  │ language set               │  book's text or made up?          │
  │     │ (translations)             │                                   │
  ├─────┼────────────────────────────┼───────────────────────────────────┤
  │     │ "Chapter 3" or "page 12"   │ file_search finds text by         │
  │ 27  │ requests                   │ meaning, not by chapter or page   │
  │     │                            │ number, so retrieval may miss     │
  ├─────┼────────────────────────────┼───────────────────────────────────┤
  │     │                            │ The vector store is "completed"   │
  │ 28  │ A scanned (image-only) PDF │ but holds no text, so grounding   │
  │     │                            │ is silently empty                 │
  ├─────┼────────────────────────────┼───────────────────────────────────┤
  │     │ A game built from a book   │ Answers must be clearly right or  │
  │ 29  │ with a request like "quiz  │ wrong from the text; a mistake    │
  │     │ on the characters"         │ reaches students' phones through  │
  │     │                            │ the QR code                       │
  └─────┴────────────────────────────┴───────────────────────────────────┘

  Issues that show up across several cases

  1. The file-search instruction is written for slides only. The text in
     core.clj:191-194 says "build the slides", "do not ask for the topic" and
     "number of slides", but it's added to game and edit prompts too (cases
     5, 8, 18–20).
  2. Game requests with a book are the most fragile. They have no strict
     schema; the prompt just says "ONLY respond with JSON". With file_search
     the model is more likely to add text such as "Based on the document…" or
     citation marks, and then json/parse-string fails with an unknown error
     (case 5).
  3. Using a book that isn't ready fails silently (cases 3 and 6).
  4. Expired books keep failing with a misleading error, because the add-in
     never learns the book is gone (case 9).
  5. The edit request doesn't record which book the deck was made with (cases
     18–20).
  6. Deck and game requests share one conversation ID, and I haven't seen
     that cross-type path tested (cases 16–17).

  A reasonable test order: cases 5, 16, 9, 3 and 6, then 18–20, then 25–29.
  Case 5 is most likely to cause a visible error, 9 locks teachers out, 3 and
  6 silently give wrong output, and 25–29 affect quality.

  Want me to turn this into a test checklist you can run in PowerPoint, or
  look into case 17 to settle it?

✻


Book OCR — make it work for other books and languages (2026-10-13)
==================================================================

The pipeline is mostly language-neutral (Azure OCR, the word check, fallback to
Azure text), but some parts are tuned to Schritte / German:

- [ ] check.clj: word pattern is A-Z + German umlauts only. French é/ç, Spanish ñ,
      Croatian č/š/ž get split and fail. Use \p{L}.
- [ ] Prompt: says "German textbook" with German examples. Pass the book's language
      (the teacher picks it in the add-in) into the prompt.
- [ ] (Beispiel: …) tag → neutral (Example: …) in step ②, prompt and check.
- [ ] Audio rule is Schritte's "CD icon track" format. Other publishers differ;
      unknown formats stay plain numbers. Consider letting the model tag audio.
- [ ] Footer rule uses German number words. Rely on Azure's footer tags or a
      simpler rule.
- [ ] Foreign-letter rule flags Cyrillic/Greek. For a Russian/Greek book flag
      "a script different from Azure's text" instead.
- [ ] printed-page in step ② looks for "LEKTION". Make it unit-name-neutral.
- [ ] Eval set: 2–3 books per language teachers actually use (check the add-in
      settings / logs), chosen for variety (workbook, coursebook, other publisher,
      scan + digital), 10–20 pages each. Start: Schritte 5 + one English + one
      French book. Only books we own; outputs stay git-ignored.
- [x] Production: log the fallback rate per uploaded book (done: the "Book improved"
      log line has ok/retried-ok/fallback counts and tokens); high rates show which
      layouts/publishers need work next.


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
