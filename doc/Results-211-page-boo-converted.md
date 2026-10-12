# Results: full 211-page book converted

**Date:** 2026-10-11/12
**Book:** *Schritte International neu 2* (211 pages, scanned, text layer on 1 page, 46 MB)
**Pipeline:** as in `book-ocr-investigation.md`: Azure Layout → clean-up → `gpt-6-luna` (effort `high`) per page → `book/check.clj` → retry once → fallback to Azure text
**Prompt:** v3 (`dev/eval/book-page-prompt.txt`)
**Azure tier:** S0 (West Europe), paid from the trial credit

---

## 1. Summary

| | Result |
|---|---|
| Pages converted | **211 / 211** |
| Passed the check on the first try | **125 (59%)** |
| Passed after one retry | 40 (19%) |
| Fell back to Azure text | **46 (22%)** |
| Azure Layout, whole book | **23 s**, $2.11 |
| Luna, whole book | ~$0.64 (1.10M tokens in, 1.06M out) |
| **Total cost** | **~$2.75 per book** (the estimate was $2.70) |
| Luna time, 8 pages at a time | **~20 min** (median 25–34 s per page, 90% under ~87 s, slowest 115 s) |
| OpenAI rate limits | none hit at 8 pages at a time |

**Verdict:** cost and Azure speed are fine. The Luna stage is too slow to make the teacher wait, so it has to run in the background (the two-stage plan). The 22% fallback rate is too high, but about half of it is the check being too strict, not Luna making mistakes.

---

## 2. How the run went

- **Run 1** (`dev/eval/out/book/schritte-2-full/`): Azure analysed the whole PDF in one call (23 s). Luna finished 113 pages. Then the computer lost its internet connection (all errors within one second, 20:33:31: `UnknownHostException api.openai.com`, `connection aborted`), so the other 98 pages ended as `:error`. These weren't model failures; the requests never reached OpenAI.
- **Re-run** (`dev/eval/out/book/schritte-2-full-rerun/`): the 98 pages (+ page 118 by mistake) again with `:pages`, Azure result taken from the cache (the report's "$0.99 Azure" is the formula only; nothing was charged). 99 pages in 10.5 min, 0 errors.
- The numbers in §1 combine both runs (run 1 for the pages it finished, the re-run for the rest).

| | Run 1 (113 pages done) | Re-run (99 pages) |
|---|---|---|
| ok / retried-ok / fallback | 75 / 16 / 22 | 50 / 25 / 24 |
| Fallback rate | 19% | 24% |
| Median time per page | 25 s | 34 s |
| Luna cost | $0.32 | $0.34 |

The second half of the book is harder: denser exercise pages, plus the appendix (word list, photo credits).

---

## 3. Why pages fail the check

Reasons over all failed attempts (first tries and retries, both runs):

| Reason | Count |
|---|---|
| Added words not in the OCR text | 64 |
| Dropped item numbers | 67 |
| Added numbers | 12 |
| Dropped words | 6 |
| Ticks that mark no option | 6 |

### 3.1 False positives: the page was fine, the check is too strict
Seen when reading fallback pages against the Azure text:

| Pattern | Example | What happens | Fix |
|---|---|---|---|
| Audio icon variants not caught by step ② | Azure: `44)1 3`, `2 41) 10`, `2 41) 46` | Luna correctly writes `[Audio: …]`, the check reports `44`, `41` as **lost item numbers** | extend the audio rule in `tag-audio` |
| Numbered picture tags | `[Image 2: A doctor walking down a hospital corridor.]` | only `[Image: …]` is exempt, so `corridor`, `desk`, `behind` count as **added words** | allow `[Image N: …]` in the check (or forbid it in the prompt) |
| Crossed-out letter gaps | `eu~~x~~re` | the fragments `eu`, `re` count as added words | treat fragments around `~~…~~` like letter-gap fragments |
| Appendix pages (no printed page number) | photo credits (`istockphoto`), word list (`pl`, `ug`, `äg`) | abbreviations and fragments count as added words | add these to decoration, or handle appendix pages separately |
| Big numbers in lists | `144`, `530`, `001` on index or credit pages | reported as lost or added item numbers | count only small numbers (≤ ~50) as item numbers |

### 3.2 Real problems: the check is right
| Page (book) | What Luna did |
|---|---|
| p. 120 | added a label `Wortbank:` and a table header `Linke Seite \| Rechte Seite` that aren't printed |
| p. 113 | put the printed example line into `(Beispiel: …)` **and** wrote it again as the first dialogue line, so the sentence appears twice |
| p. 164, p. 177 | added numbers `1, 2, 3 …`: probably numbered an unnumbered list, as gpt-4o did on the sample (not read yet) |

Harmless, but against the rules. The fallback handles them correctly.

**Not seen in the fallback pages read:** filled gaps or solved exercises (the main risk). About 8 of the 46 fallback pages were read by hand, so this is a spot check, not a full audit.

### 3.3 All fallback pages
PDF page (printed page), last reason:

| PDF p. | Book p. | Reason |
|---|---|---|
| 14 | 97 | added: jahren |
| 30 | 113 | added: grüezi, helfen, ich, ihnen, kann, mitenand (example written twice) |
| 36 | 119 | added: and, at, behind, corridor, desk … (`[Image N: …]`) |
| 37 | 120 | added: linke, rechte, seite, wortbank (invented labels) |
| 39 | – | added: ioanna |
| 43 | 126 | added: eu, re, schn (crossed-out letter gap) |
| 48 | 131 | lost numbers: 1, 3, 44, 8 (audio variant) |
| 56 | 139 | added: antwort, frage |
| 59 | 142 | lost numbers: 1, 5, 6, 7 |
| 62 | 145 | added: antworten, fragen; lost numbers: 44, 5 |
| 68 | 151 | lost numbers: 3, 8 |
| 71 | 154 | lost numbers: 13, 41, 5, 6 (audio variant) |
| 72 | 155 | added: ioanna; lost numbers: 3, 4, 7, 8 |
| 73 | 156 | 2 ticks that mark no option |
| 81 | 164 | added numbers: 1, 2, 3 |
| 83 | 166 | lost numbers: 1, 5, 6 |
| 94 | 177 | added numbers: 1, 2, 3, 5, 7 |
| 98 | 87 | added: was; lost numbers: 1, 2, 3 |
| 100 | 89 | added: acht, er, ist, monaten, vor |
| 103 | 92 | added: ein, in, mit |
| 104 | 93 | lost numbers: 1 |
| 110 | 99 | lost numbers: 2, 3 |
| 112 | 101 | lost numbers: 0, 10, 2, 3, 41, 71, 8 (audio variant) |
| 115 | 104 | 1 tick that marks no option |
| 116 | 105 | added numbers: 15, 2, 24, 6 |
| 119 | 108 | added: die, haare |
| 122 | 111 | added: pass; lost numbers: 1, 2 |
| 128 | 117 | lost numbers: 1, 2, 20, 4, 50 |
| 133 | 122 | lost numbers: 0, 1, 2 |
| 135 | 124 | added: was |
| 139 | 128 | lost words: falsch, prüfung, richtig; added numbers: 15, 31, 4, 52 … |
| 142 | 131 | added: beim, der, nach, vor; lost numbers: 1, 2, 3, 4 |
| 146 | 135 | 1 tick that marks no option |
| 150 | 139 | lost numbers: 3 |
| 151 | 140 | added: und |
| 153 | 142 | lost numbers: 2, 41, 46 (audio variant) |
| 154 | 143 | lost numbers: 0, 2, 3, 5 |
| 155 | – | lost numbers: 1, 13, 144, 2, 3, 4 |
| 157 | 146 | lost numbers: 0, 2, 41, 48 (audio variant) |
| 163 | 152 | added: dieser, schön, sind; lost numbers: 0, 3, 4 |
| 164 | 153 | lost words: beruf, fokus, sprechen, vorlieben, über |
| 178 | – | added: pl (appendix) |
| 204 | – | added: äg (appendix) |
| 206 | – | added: ug (appendix) |
| 209 | – | added: istockphoto (photo credits) |
| 211 | 4 | added numbers: 001, 02, 1, 2, 4, 530 (back cover / ISBN) |

A cause in brackets was checked by hand (p. 30, 36, 37, 43, 48, 112) or follows from the same pattern (`41`/`44`/`46`/`48` next to audio, appendix fragments). The others haven't been read yet.

---

## 4. Timing and cost for production

| Step | Measured |
|---|---|
| Azure Layout, 211 pages | 23 s |
| Luna, 211 pages at 8 at a time | ~20 min |
| Luna at 16 at a time (estimate) | ~10 min; no rate limits were hit at 8 |
| Cost per book | ~$2.75 (Azure ~77%, Luna ~23%) |

So the teacher can use the book on Azure text after **upload + ~1–2 minutes**; the Luna version arrives 10–20 minutes later in the background. Azure was much faster than estimated (1–4 min).

---

## 5. Next steps

**Proposed next (no API costs, just code and tests):**
1. **Fix the false positives** (§3.1) that cause about half of the fallbacks, in `tag-audio` and `book/check.clj`:
   - the audio icon variants (`44)1 3`, `2 41) 10`)
   - allowing `[Image 2: …]`
   - fragments of crossed-out letter gaps (`eu~~x~~re`)
   - the appendix pages (photo credits, word list fragments, big numbers)
2. **Add each case to `check_test.clj`**.
3. **Re-check all 211 saved Luna answers** (`pages/pNNN-model.md` vs `pNNN-azure.md`) to see the real fallback rate. No model calls, no cost. The goal is ~10% or less.

**After that:**

4. **Tighten the prompt** for the real inventions (§3.2): no labels or headers that aren't printed; write the example only once.
5. Read the fallback pages not yet checked by hand (§3.3).
6. Then the next books (language-neutral prompt) and production (`book-ocr-investigation.md` §6).

**Output folders** (git-ignored, book content is copyrighted):
- `dev/eval/out/book/schritte-2-full/`: run 1, Azure JSON cache, pages 1–109, 112–114 and 118 (the rest are `:error` there)
- `dev/eval/out/book/schritte-2-full-rerun/`: re-run, pages 110, 111, 115–211

---

## 6. Re-check after fixing the check (2026-10-13)

The saved Luna answers re-checked with the new `book/check.clj`, with no model calls
(`book-convert-eval/recheck-book`). The Azure text is rebuilt from the cached JSON, so the new audio
rule counts too. Only the last answer of each page is saved, so a fallback page that now passes
counts as "retried-ok".

| | Before | After |
|---|---|---|
| ok | 124 | 124 |
| retried-ok | 41 | 52 |
| **fallback** | **46 (22%)** | **35 (17%)** |

No page that passed before fails now.

**What changed in the check** (each one has a test in `check_test.clj`):
- **Audio markers.** `tag-audio` moved into `check.clj` and now knows every form in the book (96 markers, all checked
  by hand): icons `)` `4)` `41)` `40)` `0)` `(1)` `M)` `))`, the icon stuck to the CD number (`44)`, `50)`), track
  ranges, markers in table cells. The check also recognizes the model's copies: untagged, bold, with an em space
  (U+2003, in 36 of Luna's pages), `3 [Audio: …]`, or with the icon dropped (`### 38 B1`).
- **Footer numbers** written out as text (`LEKTION 10 118`) don't count as added.
- **Photo numbers** may move into the image line (`[Image: 1 — …]`, `(Foto 5)`); `[Image N: …]` counts as an image line.
- **A `0` alone on its line** is an answer circle ○, not an item number.
- **Crossed-out letters** stay part of their word (`eu~~x~~re` = `euxre`).
- **Capital I read as l** (`loanna` → `Ioanna`) may be fixed; no other spelling fix.

**The 35 that still fall back:**

| Kind | Pages | What to do |
|---|---|---|
| Real: example written twice (into the gap **and** as `(Beispiel: …)`) | 30, 98, 100, 119, 142 | prompt |
| Real: invented labels/headers | 37, 56, 62 | prompt |
| Real: numbered an unnumbered list | 81, 94, 116(?) | prompt |
| Real: changed a word (`Wo warst du` → `Was warst du`) | 135 | prompt |
| Real: Cyrillic letters in a German word (`schnел`) | 43 | prompt, or a check for non-Latin letters |
| OCR noise: self-rating smileys read as `8`, `3`, `e`, `®` | 68, 73, 110, 154, … | not fixed: can't be told apart from item numbers by text alone |
| Appendix: word list / photo credits fragments (`pl`, `äg`, `istockphoto`) | 178, 204, 206, 209 | handle appendix pages separately |
| Not read yet | the rest | |

**Blind spot found:** 1–2-letter words count as decoration when lost, so on p. 135 the dropped `Wo` didn't count (the
page failed on the added `Was`). Added words count whatever their length, so a swap is still caught, but a model that
only **drops** a short word (`wo`, `zu`, `am`) passes.

---

## 7. Prompt v4 (2026-10-13)

New rules in `dev/eval/book-page-prompt.txt` against the real errors of §6: write a `(Beispiel: …)` only once and
leave its gap as `____`; no headings, labels or table headers that aren't printed (a table without a printed
header gets an empty one); never number an unnumbered list; never change even a short word; no letters from
another alphabet. The check also got a clear retry reason for Cyrillic/Greek letters, and ignores HTML
entities (`&nbsp;`).

Re-run with Luna `high` on 53 pages: the 38 earlier fallbacks + 15 control pages that passed and have examples
and tables (`dev/eval/out/book/schritte-2-prompt-v4/`). ~11 min at 8 at a time, Luna $0.26 (the report's
"$0.53 Azure" is the formula only; the Azure result came from the cache).

| | Old answers, today's check | Prompt v4 |
|---|---|---|
| ok | 7 | 22 |
| retried-ok | 11 | 9 |
| **fallback** | **35** | **22** |

- **No control page got worse**; 6 that needed a retry now pass first time.
- Fixed and read by hand: p. 135 keeps "**Wo** warst du", p. 37 has no `Wortbank`/table header, p. 100 writes the
  example once.
- **Whole book, estimated:** 22 / 211 = **~10% fallback** (the goal), *if* the 143 pages not re-run behave like
  the 15 controls. Confirm with a full run before production.

**Still failing (22):** added numbering (81, 94, 116, 139), ticks on no option (73, 115, 146), lost item
numbers (112, 119, 153, 155), smiley/OCR noise (154), appendix (209), and single extra words (14 `im`, 30
`grüezi mitenand`, 43 `eure`, 98 `was`, 103, 104, 122 `pass`, 163 `sind`, 164 `Fuß` from a crossed-out
syllable). Not read yet one by one.

---

## 8. The 22 prompt-v4 fallbacks, judged against the page image (2026-10-13)

Each flag compared with the page image (rendered at 110 dpi from the PDF), Azure's text and Luna's answer.
Verdicts: **Luna** = real model error (keep failing) · **Check** = check false positive (fix the check) ·
**Azure** = Azure misread the page, Luna is right (check can't know without the image) · **?** = not settled.

| PDF p. | Book p. | Flagged | Verdict | Evidence from the image |
|---|---|---|---|---|
| 14 | 97 | added `im` | Azure | "im Büro" is circled by hand; Azure read circle + i as `(m` |
| 30 | 113 | added `grüezi mitenand`, lost `helfe hilfst hilft` | **Luna** | example written twice again; grammar box *ich helfe / du hilfst / er hilft* dropped |
| 43 | 126 | added `eure` | Azure | printed "euere" with a red X over the 2nd e; Azure read the X as a letter (`euxre`) |
| 73 | 156 | 2 ticks on no option | **Luna** (small) | circles hold handwritten letters D, C (examples); Luna made them `[x]` and lost the letters |
| 81 | 164 | added 1 2 3, lost 123 | Azure | table header "1 2 3 4" read as `123 4`; exercise numbers 1, 2 missing in Azure |
| 94 | 177 | added 1 2 3 5 7 | Check | `1,2,5` (Azure) vs `1, 2, 5` (Luna): a space after the comma makes the numbers count |
| 98 | 87 | added `was` | Azure | only "machen" is handwritten; Azure's handwriting span also took the printed "Was" |
| 103 | 92 | added `ein in mit` | Check | letter-gap words split differently (`Deutschk _n_t_s_n` vs `Deutschk_n_t_s__n`) |
| 104 | 93 | lost item number 1 | ? | not read |
| 112 | 101 | lost 0 2 3 8 | Check (mostly) | audio marker without a track (`2 0)`) isn't tagged; rest not read |
| 115 | 104 | tick on no option | Check | `Getreidegasse [x] 9 [ ] 17`: the options are numbers |
| 116 | 105 | added 1 2 6 15 24 | Check + Azure | dates `24.2.` vs `24. 2.` (space); circled ①② example answers Azure skipped, Luna kept |
| 119 | 108 | lost 08 1 4 7 | ? | crossword grid numbers dropped by Luna; harmless, not read closely |
| 122 | 111 | added `pass` | **Luna** | repeated the line "m____ Pass." in item c; wrote the example "dein" into the text |
| 139 | 128 | added 8 4 15 31 … | Azure | dense timetable; every number Luna wrote is printed (platforms, footnote) |
| 146 | 135 | tick before a blank | Check | box really is ticked in example 1: `[x] ____ (Beispiel: Würdest)` |
| 153 | 142 | lost numbers + labels | ? | article dots and item numbers in a word puzzle; not settled |
| 154 | 143 | lost 0 2 3 5 | Azure (noise) | self-rating smileys and score boxes read as digits |
| 155 | 144 | lost 1 2 3 4 13 144 | Check | Azure didn't mark the footer as a footer (`LEKTION 13 AB 144`), so its numbers count |
| 163 | 152 | added `sind` | Azure | Azure glued the gap to the word (`D_sind`), so "sind" vanished from its words |
| 164 | 153 | added `fuß` | Azure | crossed-out syllable "Fuß" read by Azure as `Euß` |
| 209 | – | added `istockphoto` | Azure | photo credits, "©" read as `o` (`oistockphoto`) |

**Count:** Luna 3 (30, 73, 122) · Check 7 · Azure 9 · not settled 3.

So only **3 of the 22** fallbacks protect against a real model error. The 9 "Azure" pages are a real cost of
trusting Azure: Luna's version was better, and the book gets Azure's worse text instead.

**Check fixes that follow** (each needs a test, then a re-check):
- numbers: ignore spacing after `,` and `.` between digits (`1, 2, 5` = `1,2,5`, `24. 2.` = `24.2.`)
- an audio marker without a track (`2 0)`)
- a tick before `____ (Beispiel: …)` is a real tick; a tick before a number option (`[x] 9`) too
- a footer Azure didn't recognize as one (`LEKTION 13 AB 144 …` on its own line)
- letter-gap words split differently around spaces

### 8.1 Check fixes from §8 (2026-10-13)

Built in `book/check.clj`, each with a test (49 tests pass):
- spacing after `,` / `.` between digits is ignored on both sides (`1, 2, 5` = `1,2,5`, `24. 2.` = `24.2.`, also
  Azure's escaped `4\. 3\.`)
- an audio marker without a track (`2 0)`) is tagged `[Audio: CD 2]`
- a tick before `____ (Beispiel: …)`, and before a number when the line's other options are numbers, is real
- a footer line Azure didn't mark (`LEKTION 13 AB`, `144 einhundertvierundvierzig`) gets the footer allowance
- a word + space + short gap (`Deutschk _n_t_s_n`) is one letter-gap word — only with 2+ letters before it,
  and only in that direction (the other way swallowed words next to Azure's short blanks: `d .__ gefällt`)

| Re-check | Fallbacks before | After |
|---|---|---|
| Prompt v4 answers (53 pages) | 23 | **18** |
| Saved full run, old prompt (211 pages) | 46 (start of the day) | **32** |

No page that passed before fails now (two regressions found on the way, p. 3 and p. 161, fixed and tested).

**The 18 left in the v4 set:** Luna 3 (30, 73, 122) · Azure 9 (14, 43, 81, 98, 139, 154, 163, 164, 209) ·
partly fixed / not settled 6 (103 `in mit`, 112, 155 lost item numbers 1–4, 104, 119, 153).
