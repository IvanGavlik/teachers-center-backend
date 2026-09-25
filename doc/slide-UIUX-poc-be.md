# Slide Visual Design: POC 2 (back end)

Follows `slide-UIUX-poc-fe.md`. POC 1 showed the add-in can draw five content layouts plus a title slide from structured data. POC 2 makes the backend produce that data with OpenAI strict structured output, and measures how the AI uses it on realistic requests.

Branch: **`poc/slide-layouts`** in this repo. The old `{slide-title, content}` format is **replaced outright**, so this branch can't be deployed until the add-in renders the new format.

---

## 1. What changed

| Layer | Change |
|---|---|
| `openapi/core.clj` | `responses-api` takes `:text-format` → `text.format`. `response-output-text` throws `:api-error` on a refusal or `status: incomplete` (truncated JSON), instead of returning nil or broken JSON. |
| `conversation/slide_schema.clj` (new) | The contract as data: `layout-schemas`, `deck-schema`, `edit-schema` (`{slide: …}`, because the root can't be `anyOf`), `limits`, `normalize-deck`/`normalize-slide`, `violations`. |
| `conversation/core.clj` | Templates with `:response-schema` get strict output. Generate → `normalize-deck`; edit → unwrap `:slide` → `normalize-slide`. Interactivity is untouched. |
| `conversation/ws.clj` | `normalize-requirements`: defaults `native-language` to "No" and adds `slide-format` (optional in the request, default 16:9). |
| `conversation-content.edn`, `conversation-edit-content.edn` | Emoji/formatting rules removed; layout guidance, highlight/polarity/translation rules and limits added. New placeholders `{{native-language}}` and `{{slide-format}}`. |

**Bug fixed along the way:** the "Native Language Rule" never received the setting, because there was no placeholder for it. Translations never worked before this change.

### Wire format (backend → add-in)
The message types are unchanged: `{requirements-not-met, conversation-id}`, `{title, subtitle, slides, conversation-id}`, `{type:"edit", edit:{slideIndex, slide}}`. Each slide is one of:

| `layout` | Fields | Limits |
|---|---|---|
| `bullets` | `slide-title`, `items[]` | 1–5 items |
| `pattern` | `slide-title`, `parts[]`, `example{text, highlight}` | 2–4 parts |
| `comparison` | `slide-title`, `left`/`right{heading, polarity, items[{text, highlight}]}` | 1–4 per side |
| `vocab-table` | `slide-title`, `columns[]`, `rows[][]` | 2–4 columns, 1–8 rows |
| `examples` | `slide-title`, `items[{text, highlight, translation}]` | 1–5 items |

- `highlight` and `translation` are `string|null`.
- `polarity` is `positive|negative|neutral`.
- The request can carry an optional `"slide-format": {"aspect":"16:9","width-in":13.33,"height-in":7.5}`.

**Normalize** fixes what the add-in relies on but a schema can't express:
- A `highlight` with the wrong casing gets the text's own casing; one that isn't in the text becomes null. The add-in matches with case-sensitive `indexOf`.
- Table rows are padded or trimmed to the number of columns.
- A blank `translation` becomes null.
- The unused root branch is dropped.
- It throws `:api-error` if there are neither slides nor a question.

---

## 2. Probe results

| Question | Answer |
|---|---|
| Does strict mode accept `maxItems`/`minItems`? | **Yes**, and they're enforced. They're in the schema. |
| Does strict mode accept `maxLength`? | It's accepted, but **enforced by cutting text off mid-word** ("Using the…", "Learning B"). **Not used.** Character caps live in the prompt, and the add-in's fitting handles overflow. A unit test keeps `maxLength` out of the schema. |
| Does `text.format` work with `file_search` ("attach a book")? | **Yes.** `file_search_call` runs and the slides use the document's content. **But no citation annotations** come back with strict output. The `file_search debug` log now requests `include: ["file_search_call.results"]` and logs `:searched?`, `:queries`, `:result-count` and the top 3 passages (`openai/file-search-summary`). |

---

### Book requests (2026-09-26)
With the sample PDF attached, "Make 3 slides of vocabulary from the attached document" was answered with a clarification question. The clarification rule doesn't count "the document" as a topic, and the search for the literal word "vocabulary" found weak matches. `file-search-instruction` in `conversation/core.clj` now says:
- The document's content counts as the topic.
- Search for its subject and key terms, not for the words of the request.
- The other rules (such as the slide count) still apply.

Results after the change:

| Request | Result |
|---|---|
| Vocabulary from the document (3 runs) | 3 of 3 decks of `vocab-table` slides built from the document's terms (Henry's law, Langmuir, fugacity, plasticization) |
| Summarize the key ideas of the book | Deck built from the book (before and after the change) |
| Example sentences using words from the document | Deck built from the book, but one sentence per `examples` slide (thin) |
| "vocabulary from the document", no slide count | Asks only for the slide count |
| Unrelated topic (present simple negatives) with the book attached | Normal deck. The search still runs, harmlessly |

## 3. Eval

`dev/slide_eval.clj` + `dev/eval/requests.edn`:
- 25 drafted requests: EN/DE/ES/FR/IT, A1–C1, some with a native language set, 4 that should trigger clarification, 1 written by the teacher in Croatian.
- 3 edits.

It runs the real generate/edit code, writes `dev/eval/out/<ts>.edn` and `<ts>-decks.json` (in fixture shape, for `/poc-layouts`), and prints a summary.

Second run, after the polarity fix:

| Metric | Result |
|---|---|
| Errors / schema failures / truncations | 0 / 0 / 0 |
| Decks / slides | 22 / 106 |
| Layouts | examples 32, pattern 25, bullets 20 (**19%**), vocab-table 18, comparison 11 |
| Clarification | 3 of 4 correct; `reflexive verbs` (no slide count given) generated a deck instead of asking |
| Slide count respected | 21 of 22 (one asked for 4, got 5); the first run was 22 of 22 |
| Highlights that weren't exact substrings (before normalize) | 0 |
| Uneven comparisons | 0 |
| Soft-limit violations | 5, all `cell-too-long`: C1 example sentences in a table |
| Edits | 3 of 3 returned the requested layout (table, examples + 1, comparison) |
| Output tokens | average ~305, max 571 (cap 3000). Latency ~4–5 s |

**Changed after the first run:** 1 of 14 comparisons (Akkusativ vs Dativ) used `positive`/`positive`, which the add-in would draw as two green ✓ columns. The schema description and both prompts now say to use neutral for two correct forms. The second run had 0 such pairs.

---

## 4. Open issues

1. **Translations only fit in the `examples` layout.** Decks with a native language set but no `examples` slide get no translations: 2 of 7 in the first run. Options:
   - Add `translation` to comparison and pattern items as well.
   - Tell the prompt to include at least one `examples` slide when a native language is set.
2. **The subtitle's slide count can disagree with `slides`.** `no-count` said "5 slides" and returned 2. The backend could build the subtitle itself from the level and `(count slides)`.
3. **A missing slide count doesn't always trigger clarification.** This is existing prompt behaviour and hasn't changed.
4. **Add-in, comparison rows:** the add-in takes the row count from `left.items`, so any extra `right` items are dropped. The eval found no uneven comparisons, but the real renderer should use the max of both sides.
5. **The eval requests are drafted.** They should be swapped for real requests from the email logs before drawing firm conclusions.

## 5. Next
- Decide issues 1 and 2.
- Build the add-in's production renderer. It would send `slide-format`, move the POC drawers into the insert flow, rebuild the preview, and handle edits in the new shape.
- Merge and deploy both repos together.

Run it:
- `clojure -X:test`
- For the eval, see the `slide-eval` docstring. From the REPL: `(require 'slide-eval) (slide-eval/run-eval)`.
