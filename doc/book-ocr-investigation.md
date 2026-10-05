# Book conversion: PDF → clean Markdown before the vector store

**Status (2026-10-05):**
- solution chosen
- eval script `dev/book_convert_eval.clj` built and run on a 10-page sample
- vision model chosen: **`gpt-6-luna`, reasoning effort `high`** (§5)
- production code not started

Next: the full-book run (needs Azure S0), then more books (§8).

---

## 1. Why

Today `library.clj` sends the teacher's PDF to OpenAI `/files` **as it is**, and OpenAI parses and indexes it. Two problems:

1. **Scanned books have no text.** The test book (*Schritte International neu 2*, 211 pages, made on an HP scanner) has a text layer on **1 of 211 pages**. Every page is one JPEG. OpenAI's vector store doesn't OCR, so the book shows "ready" but there's nothing to search, and slides come from GPT-4o's general knowledge. Many teachers will have scans like this.
2. **We don't control what's stored.** The PDF itself (images, metadata) sits at OpenAI for 7 days, and we can't fix what OpenAI's parser gets wrong.

**Goal:** convert the book into clean Markdown ourselves, upload **only that text** to the vector store, and delete the PDF.

A textbook page is hard to convert. It has:
- exercises with gaps (`____`, letter gaps like `K__ll__g__n`)
- checkboxes
- two or three columns
- tables
- dialogues with speaker symbols (◆ ○ ▲)
- audio references ("2 ◀)) 5" = CD 2, track 5)
- pictures that are part of the task
- the book's own example answers, printed in a handwriting-style font

The converted text must keep all of that **without inventing anything**. A generative parser that "helpfully" fills in gaps or ticks boxes is worse than no parser, because the AI then presents guesses as "what the book says".

---

## 2. The solution: Azure Layout + a vision model pass on each page

- **Azure Document Intelligence (Layout model)** reads the page **accurately**: right words, right spelling, page boundaries, checkbox positions, handwriting flags. It's OCR, not a generative model, so it can't invent words.
- A **vision model** (`gpt-6-luna`, our existing OpenAI account) then gets the **page image + Azure's text** and repairs the **layout**: gaps, reading order, speaker symbols, image descriptions, checkboxes. It isn't allowed to change the words.
- **Code checks** afterwards that the model really added no words, and falls back to Azure's text if it did.

```
Teacher uploads PDF
        │
        ▼
 ① Azure Layout ── whole PDF, one call ──► Markdown + JSON (words, positions, handwriting, checkboxes)
        │
        ▼
 ② Code: split into pages, tag audio, mark examples ──► "Azure page text"
        │
        ├──► ③ Page image (taken out of the PDF with PDFBox)
        ▼
 ④ Vision model for each page: image + Azure page text + rules ──► "repaired page"
        │
        ▼
 ⑤ Code check: did the model add or change any words? did it drop boxes or numbers?
        │            └─ failed → retry once → otherwise use the Azure page text as it is
        ▼
 ⑥ Join the pages with page markers ──► book.md
        │
        ▼
 ⑦ Upload book.md to OpenAI → vector store (as today) → delete the PDF
```

---

## 3. The steps in detail

### ① Azure Layout
- Model `prebuilt-layout`, API version `2024-11-30`, `outputContentFormat=markdown`.
- **One request per book** and asynchronous:
  1. `POST {endpoint}/documentintelligence/documentModels/prebuilt-layout:analyze?...` with the PDF bytes
  2. the response is `202` with an `Operation-Location` header
  3. poll that URL until `status: succeeded`
- Fields we use from `analyzeResult`:

| Field | Used for |
|---|---|
| `content` | the Markdown, with `<!-- PageBreak -->`, `<!-- PageFooter="LEKTION 8 AB 94 …" -->`, `<!-- PageHeader -->` |
| `pages[].spans` | where each page starts and ends in `content` (more reliable than splitting at `PageBreak`) |
| `styles[]` with `isHandwritten: true` | the book's example answers (see ②) |
| `pages[].selectionMarks` | checkbox positions (often wrong on their own, see ④) |

- **Resource:** tier **S0** (the free F0 tier reads only the first 2 pages of a document), region **West Europe**. About $10 per 1,000 pages.
- **Known weaknesses**, which step ④ fixes:
  - gaps are lost (the sentence just breaks onto a new line)
  - speaker symbols are dropped
  - figures are empty `<figure>` tags
  - reading order mixes up dense two-column pages
  - decorations (score dots, speaker symbols) are reported as **ticked** checkboxes: 19 false ticks on one test page

### ② Code clean-up (rules only, no AI)
- **Split into pages** with `pages[].spans`. Read the printed page number and unit from the `PageFooter` comment (e.g. `LEKTION 8 AB 94`).
- **Audio:** `2 ) 5` / `2 4) 7` next to "Hören Sie" → `[Audio: CD 2, Track 5]`. Azure reads the ◀)) icon as `)` or `4)` consistently.
- **Examples:** each `isHandwritten` span with confidence ≥ 0.8 becomes `(Beispiel: …)`. In a workbook scan these are almost always the **printed example answers**, not a student's handwriting, so they must be **kept and labelled**, never removed.
- Remove empty `<figure>` tags and extra blank lines.

### ③ Page image
- **Scanned PDF:** each page is one embedded JPEG. Take it out unchanged with PDFBox (1280×1744 for the test book).
- **Digital PDF:** render the page at about 150 dpi with PDFBox.
- Send it to the model as a base64 data URL. The model scales it down internally (to about 768×1046), which is enough to see gaps, ticks, symbols and pictures.

### ④ Vision model pass on each page
- OpenAI Responses API, the same client as today (`openai/responses-api`):
  - input = one user message with `input_text` (rules + Azure page text) and `input_image` (the page)
  - **`store: false`** (OpenAI keeps nothing)
  - **model `gpt-6-luna`** with `reasoning: {effort: "high"}`. It's a reasoning model: no temperature, and the thinking is billed as output.
  - `max_output_tokens` 16000 (the reasoning counts towards it; a page used 1.7k–5.7k)
- Pages are independent, so they run **in parallel** (8 at a time in the eval; see §6 for production).
- The model ID and effort are settings (`:model`, `:effort` in the eval).

The prompt (version 3, `dev/eval/book-page-prompt.txt`):
```
You are fixing the LAYOUT of OCR text from one page of a German language textbook.
The OCR TEXT below is correct word-for-word. The attached IMAGE shows how the page really looks.

You MAY:
- restore blanks as ____ where the image shows an empty line or gap
- restore letter gaps exactly as printed (e.g. K__ll__g__n). Letter-gap words are NOT
  words: redraw their underscores, dots and spaces to match the image and join fragments
  split across lines. Keep the printed letters; never add letters that are not printed.
- keep every item number (1, 2, 3 … / a, b, c …) and every checkbox of every item,
  each written as [ ] or [x]
- fix reading order (columns, boxes A–E, items a–f) so it matches the image
- add speaker symbols ◆ ○ ▲ □ at the start of dialogue lines, as printed
- mark text that is crossed out in the image as ~~text~~
- set checkboxes: [x] only if the image clearly shows a cross, otherwise [ ]
- describe each picture in one line: [Image: ...]
- use Markdown headings for exercise numbers and Markdown tables for tables
- drop page decoration (score boxes like 0-4 / 5-7 / 8-9, side labels like KOMMUNIKATION)

You MUST NOT:
- add, remove or change any word that is not in the OCR text
- fill in any blank, solve any exercise, or tick any box that is empty in the image
- reorder or number the items of an exercise (e.g. "Ordnen Sie das Gespräch") — keep
  them in the printed order; reading-order fixes are only for columns and boxes
- keep a tick from the OCR text that is not on an answer option — OCR reads speaker
  symbols and score dots as ☒
- translate or correct spelling
- remove the tags [Audio: ...] and (Beispiel: ...)

Return only the Markdown for this page.

OCR TEXT:
<Azure page text from step ②>
```
In the eval, the rules are sent as `instructions` and the OCR text goes with the image in the user message.

Version 3 added the two rules about reordering and false ticks after gpt-4o solved an ordering exercise (§5). The prompt still says "German" and uses Schritte examples. It has to be made language-neutral before other books (§8).

**Why Azure's words win:** if Azure misread a word, the model may not "correct" it. This is on purpose. The whole design relies on one source of truth for the words, and OCR is a more reliable source than a generative model. One example from the test: Azure read the fictitious address `qmail.com` as `gmail.com`, and the model kept it. That's the accepted cost.

### ⑤ The check (protection against invented content)
Implemented in `src/teachers_center_backend/book/check.clj` (pure code, unit tests in `test/…/book/check_test.clj`). For each page, code compares the model's output with the Azure page text.

**What may be new or different without counting:**
- blanks `____`, boxes `[ ]` / `[x]`, speaker symbols ◆ ○ ▲ □, Markdown and HTML markup
- anything inside `[Image: …]`, `[Audio: …]` or `(Beispiel: …)`
- letter-gap words (`K__ll__g__n`): their letters are fragments
- the same letters split or joined differently (`einer` → `ein er`, `ARBEITS- ZEIT` → `Arbeitszeit`)
- text from a picture (a sign saying ARBEITSZEIT) moved into its `[Image: …]` line
- the printed footer or header (Azure keeps it in a comment); the model may write it out or drop it
- score lines (`1 ____ /9 Punkte`, `0-4`)
- **decoration**: words Azure read only in capitals (side labels, signs, crossword letters) and 1–2-letter fragments

**What fails a page** (each reason is worded so it can be sent back to the model on a retry):
1. **Added words.** Every word may appear **at most as many times as in Azure's page text**:
   - A gap filled with a word that isn't on the page (`studiert`, when the page only has the scrambled `sdieturt`) is caught: the word **doesn't exist**.
   - A gap filled with a phrase from the word bank on the same page is caught too: the phrase now appears **twice** while Azure has it once.
2. **Lost words:** more than 5 non-decoration words.
3. **Lost item numbers:** more than 2. The word check alone can't see a dropped `1 ☐ … 6 ☐` list.
4. **Added numbers:** more than 2. Numbering the lines of an "Ordnen Sie…" task is solving it. gpt-4o did exactly that.
5. **Ticks that mark no option:** an `[x]` at the end of a line, or before a blank, a box, a symbol or a number. These are Azure's false ticks carried over.

**Reported, not checked:** box counts, blank counts, number of images.

**Known blind spots:**
- reordering items *without* adding numbers
- swapping two words between gaps (the counts stay the same)

The prompt forbids both, but the check can't see them.

**If the check fails:** retry once and tell the model what went wrong ("you added X", "you dropped the checkboxes of 31b"). If it fails again, that page uses the **Azure page text from step ②**: faithful, just less tidy. One bad page never stops a book. Every fallback is logged with the page number and the reason.

### ⑥ Join
One `book.md`, each page starting with a marker:
```
<!-- Schritte International neu 2 · LEKTION 8 AB · S. 94 -->
```
The markers let the AI say *where* something comes from, and keep each exercise together with its page.

### ⑦ Upload
As today: upload `book.md` to OpenAI `/files` (purpose `assistants`, expires after 7 days) and create a vector store (expires 3 days after last use). Then **delete the PDF**. It never reaches OpenAI.

---

## 4. What the output looks like
Book page 94, Ex. 31, from the manual test:
```markdown
D1 31 Im Café
[Audio: CD 2, Track 5]
a Was ist richtig? Hören Sie und kreuzen Sie an.
[Image: A man and a woman sit at a café table with a laptop.]
Antek und Luisa suchen [ ] einen Praktikumsplatz. [ ] einen Job.
```
Book page 96 (unit test), the gaps stay empty and only the printed example is ticked:
```markdown
b Emilia ____ Wirtschaft. Am Abend hat sie einen ____
als ____ in einem Restaurant.
...
○ [ ] Vor [ ] Seit (a) zwei Jahren. Zuerst habe ich [x] / [ ] seit (b) ein Jahr ...
```

---

## 5. Validation so far (manual, 3 pages)

Pages 92, 94, 96 of the test book, run through the Playground with the prompt above. Page 96 used version 2; pages 92 and 94 used version 1, without the letter-gap and checkbox rules. The check was run with `check.js`, a prototype of step ⑤.

| | p. 92 (ads, seit/vor/für) | p. 94 (audio, cards) | p. 96 (unit test) |
|---|---|---|---|
| Words added | 0 | 0 | 0 |
| Words lost | 0 | 0 | 3 side labels (allowed) |
| Invented ticks / filled gaps | none | none | none (19 false Azure ticks → 1 correct tick, 27 gaps restored) |
| Layout fixed | ads A–E back in order | images described, speech bubbles placed with their pictures | gaps, speaker symbols |
| Problems | letter gaps left garbled (v1) | numbers and boxes of Ex. 31b dropped (v1); the box count caught it, the word check didn't | – |

**Conclusion:** completely faithful with the words, which is what matters most and what we can't add afterwards. The remaining problems are layout details, handled by prompt v2 and the structure check.

### 5.1 Model comparison (eval script, 10 pages, book pages 90–99)
The same 10 pages, the same Azure text, prompt v3. One answer per page, saved to files and compared with `check-files` and by reading them against the scans. The answers are in `~/Downloads/ocr-test/page-NN-<model>.md`.

| | gpt-4o | gpt-6-luna `medium` | **gpt-6-luna `high`** |
|---|---|---|---|
| Pages passing the check, first answer | 5 / 10 | 6 / 10 | **8 / 10** (9 really good, see below) |
| Traps (empty gaps, invented ticks, ordering task) | ❌ solved Ex. 34 by numbering it 1–9; kept 8 false ticks on p. 96 | ✅ all | ✅ all |
| Model cost, 10 pages | $0.16 | $0.019 | **$0.023** |
| Time, 10 pages (8 at a time) | ~12 s | ~60 s | ~68 s |
| Avg time per page | ~5 s | ~25 s | **~38 s** (19–56 s) |

The two pages that fail with Luna `high`:
- **p. 92** is actually correct: the letter gaps are redrawn well, but `…k_m in` separates an "in" that Azure glued to a gap. The check is still too strict here.
- **p. 99** added a table heading "Buchstaben" to the crossword that isn't printed. That's a harmless invention, but the check is right to fail it, and the page falls back to the Azure text.

**Decision: `gpt-6-luna` at `high` effort.** It's the best quality of the three, and about 25× cheaper per token than gpt-4o. The model now costs less than Azure (~$0.002 per page).

Not tried yet: `gpt-6-sol` (~$0.03/page) and `gpt-6-astra` (~$0.15/page), useful only if other books show Luna's limits. Claude models (Sonnet 5.5 / Opus 5.5 / Fable 5.1) are an alternative, with a separate API key, Anthropic as a further processor, and Fable needing 30-day retention.

---

## 6. What changes when this goes into production

**Backend**
- New `azure/layout.clj`: analyze plus polling.
- New `book/convert.clj`: steps ②–⑥.
- New dependency `org.apache.pdfbox/pdfbox`.
- **Upload becomes a background job.** Converting takes minutes, so:
  - `POST /library/upload` returns a `job-id` immediately
  - `GET /library/book-status/:job-id` reports `converting` (with "page n/N") → `indexing` → `ready` (+ `vector-store-id`) or `failed`
- Jobs live in memory, like conversations, so a restart loses a book that's still converting.
- **Cache by file hash:** a second teacher uploading the same PDF gets the converted `book.md` straight away, with no cost. That's likely to matter, since many teachers use the same textbooks.
- Upload limits: today 20 MB / PDF only. The test book is 46 MB, so the limit has to go up. Add a page limit (e.g. 300) to cap cost.
- New environment variables: `AZURE_DI_ENDPOINT`, `AZURE_DI_KEY`.

**Add-in**
- Poll with the `job-id`.
- Timeout up from about 2 to about 15 minutes.
- The chip shows progress ("Reading your book… 40/211").
- The teacher can keep working while the book is converting.

### Waiting time and how to shorten it
Measured: Luna `high` takes **~38 s per page** (19–56 s). Pages don't depend on each other, so time scales with how many run at once:

| Step (200-page book) | 8 at a time | 16 at a time | 32 at a time |
|---|---|---|---|
| Upload PDF (~45 MB) | ~0.5–1 min | same | same |
| Azure Layout | ~1–4 min (**not measured yet**) | same | same |
| Luna, all pages | ~16 min | ~8 min | ~4 min |
| Retries (~10–20% of pages) | +1–3 min | +0.5–1.5 min | +0.5 min |
| Vector store | ~1 min | same | same |
| **Total** | **~20 min** | **~11 min** | **~6–8 min** |

**Limit:** the OpenAI rate limit of the account. One page is ~7.5k tokens (~3.7k in, ~3.8k out), so 32 at a time is roughly 50 requests and 400k tokens per minute. Check Settings → Limits on the OpenAI platform; parallelism must be a setting.

**Plan: the teacher can use the book after ~3–5 minutes.**
1. **First, the Azure text.** As soon as Azure is done, join its page texts (step ②, with page markers) and upload them to the vector store. The book is usable: faithful, just not tidied.
2. **Then the repaired version in the background.** Luna repairs the pages. When all are done, the repaired `book.md` replaces the first upload: a new file in the same vector store, then the old file is removed. The teacher doesn't wait for this.
3. **Cache by file hash.** A book that has been converted before (same file) is ready immediately.
4. **Effort stays `high`.** `medium` is a bit faster but clearly worse (6/10 vs 8/10).

The status endpoint therefore reports one more stage: `ocr` → `usable` (Azure text indexed, with `vector-store-id`) → `improving` (page n/N) → `ready`, or `failed`. The add-in enables the book at `usable`.

**Privacy policy**
- Add Microsoft (Azure, EU region) as a processor.
- Change "your PDF is stored at OpenAI for 7 days" to "only the extracted text is stored".

### Estimated cost (211-page book)
| Step | Cost | Basis |
|---|---|---|
| Azure Layout | ~$2.10 | $10 per 1,000 pages |
| Luna `high` (~3.7k tokens in, ~3.8k out per page) | **~$0.50** | measured on 10 pages: $0.023 |
| Retries | ~$0.05–0.10 | |
| Vector store | ~$0 | |
| **Total** | **~$2.70 per book** | Azure is ~80% of it |

A cache hit (the same book again) costs nothing.

---

## 7. Open questions
- ~~Which vision model?~~ Decided: `gpt-6-luna`, effort `high` (§5.1). "luna" in the manual test was this model.
- **Rate limits:** how many pages at once does the OpenAI account allow? That decides the waiting time (§6).
- **Other books and languages:** the prompt, the `(Beispiel: …)` tag, the audio rule (Schritte's ◀)) icon) and the page-number rule (`LEKTION 8 AB 92`) are tuned to Schritte. They need to become general:
  - `(Example: …)`
  - audio tagged by the model (the number check stops invented track numbers)
  - the footer number for any unit name
- **Digital PDFs** (with a real text layer): run them through the same pipeline (simplest; Azure handles them fine), or skip Azure and use the text layer? Default: the same pipeline.
- **Blind spot of step ⑤:** a model that swaps two words between gaps keeps the word counts the same. Low risk, but watch for it in the eval.
- **Speed of Azure on a full 211-page scan**: not measured yet.

---

## 8. Eval script and next steps

`dev/book_convert_eval.clj` (done):

| Function | What it does |
|---|---|
| `convert-book` | the whole pipeline ①–⑥ with check, retry and fallback. Writes `book.md`, a file per page and `report.edn` to `dev/eval/out/book/<name>/` (git-ignored: book content is copyrighted). Uses saved Azure JSON (`:azure-json`) or calls Azure (cached by file hash) |
| `run-raw` / `run-raw-sample` | one model answer per page straight to files, with no check and no retry. Used for model comparisons |
| `check-files` | runs the check over every answer file in a folder against its `page-NN-azure.md` and prints a table |

**Next, in order:**
1. **Full book (211 pages)** with Luna `high`. Needs the Azure **S0** tier and `AZURE_DI_ENDPOINT` / `AZURE_DI_KEY` in `.env`. It measures Azure's time, the real total time and cost, the fallback rate, and the rate limits. ~$2.70.
2. **Make it general:** the prompt language-neutral, `(Example: …)`, audio by the model, the page number from any footer (§7).
3. **More books:** Schritte 5, the Penguin reader, *English Today* (digital), plus 2–3 English coursebooks or grammar books. Use `run-raw` on ~10 varied pages each and compare. The aim is a small fixed test set to re-run on every prompt, model or check change.
4. **Production** (§6), including the two-stage "usable after Azure, improved in the background" flow.
