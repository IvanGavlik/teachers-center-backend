# Slide Visual Design: POC 1 (front end)

Follows `slide-UIUX-investigation.md`. That report compared options for making the inserted slides look less plain and recommended "structured templates". This document covers what came next: the decisions we made, the proof of concept (POC) we built in the PowerPoint add-in, and where things stand.

---

## 1. Why a POC first

The design in Part 5 of the investigation depends on things about PowerPoint that had never been tested. If any of them turned out false, the design would have to change. So we tested those first, using fixed sample data: no backend, no AI and no prompt changes.

The risky assumptions were:

1. **Theme colours carry over.** Shapes coloured with theme colours (`accent1`, `tx1`, `bg2`…) and inserted with `UseDestinationTheme` take the teacher's own theme colours, on desktop and on the web.
2. **Theme fonts carry over.** `+mj-lt` / `+mn-lt` become the deck's heading and body fonts.
3. **Slide size.** The add-in can find out the deck's slide size, and slides land correctly on 16:9 and 4:3 decks. Found during planning: `buildPptxBase64` never sets `pptx.layout`, so today's web path builds slides at PptxGenJS's default size of 10×5.625in. A new PowerPoint deck is 13.33×7.5in.
4. **PptxGenJS works on desktop too.** One drawing path (PptxGenJS + `insertSlidesFromBase64`) can replace the native Office.js text boxes on desktop, tables included, without raising `PowerPointApi` above 1.5.
5. **Text fits.** Text can be kept inside its boxes, even though neither Office.js nor PptxGenJS can measure text.

We chose this over testing the AI side first (layout choice, schema limits) because everything else is built on top of rendering, and rendering could be tested in about a day with no AI cost.

---

## 2. Decisions made along the way

### 2.1 Response shape: the AI picks the layout, the add-in makes it fit

We compared two ways for the backend to describe slides:

| | **A: AI picks a layout** (Presenton-style, Part 5.2) | **B: AI says what the content is** (Gamma "Smart Layouts") |
|---|---|---|
| The AI returns | `layout: "comparison"` + that layout's fields | `kind: "contrast"` + fields; the add-in chooses the layout |
| Good | Simple drawing code; the AI decides what the slide looks like | Layout is decided where the slide size is known; redesigns only touch the front end |
| Bad | The AI can't see the slide size or measure text | More logic in the add-in; the set of content kinds must be designed carefully |

**Decision: A, with the add-in adjusting the layout to fit.**
- **The AI picks the layout.** It also gets the slide format as a hint, e.g. `{"aspect":"16:9","width-in":13.33,"height-in":7.5}`, so it can make rough choices such as preferring two columns on wide slides.
- **Making text fit is the add-in's job.** Models can't tell whether a sentence wraps at 24pt in a 4-inch box.
  - Positions come from the real slide size.
  - Text height is estimated with a hidden canvas `measureText` call in the taskpane.
  - When text doesn't fit, it goes through this sequence: shrink to a minimum font size → rearrange (two columns, or stack vertically) → split onto a continuation slide "(2/2)".
- **Schema limits keep content short**, so the add-in rarely has to rescue a slide.

### 2.2 Candidate contract (what the fixtures use)

```json
{"title": "Present Simple: Negatives",
 "subtitle": "Level: B1 | 6 slides",
 "slides": [
   {"layout": "comparison",
    "slide-title": "Correct or wrong?",
    "left":  {"heading": "Correct", "polarity": "positive",
              "items": [{"text": "She doesn't like tea.", "highlight": "doesn't"}]},
    "right": {"heading": "Wrong", "polarity": "negative",
              "items": [{"text": "She don't like tea.", "highlight": "don't"}]}}]}
```

| `layout` | Fields |
|---|---|
| title slide | top-level `title`, `subtitle` (as today) |
| `bullets` | `slide-title`, `items[]` |
| `pattern` | `slide-title`, `parts[]` (≤4), `example {text, highlight}` |
| `comparison` | `slide-title`, `left` / `right {heading, polarity, items[{text, highlight}]}` |
| `vocab-table` | `slide-title`, `columns[]`, `rows[][]` |
| `examples` | `slide-title`, `items[{text, highlight, translation}]` |

- `highlight` and `polarity` replace today's emoji (📌 🔹 ✓ ✗). The add-in draws emphasis as bold accent-coloured text and draws ✓/✗ headers itself.
- `translation` is filled in when the native-language setting is on; otherwise it's `null`.

---

## 3. What was built

**Where:** `teachers-center-powerpoint`, branch **`poc/slide-layouts`**. It's a throwaway branch: not merged, not pushed, and not deployed.

| File | What it is |
|---|---|
| `src/taskpane/poc/fixtures.js` | Sample decks in the contract above. **typical**: B1 English. **max**: German with long compound nouns, at the proposed limits (5 bullets × ~60 chars, 4 pattern parts, 4 items per side, 8 table rows, 5 examples). **overflow**: deliberately over the limits. Also short calibration texts. |
| `src/taskpane/poc/layoutPoc.js` | Everything else, described below |
| `src/taskpane/taskpane.js` | About 30 lines: a hidden `/poc-layouts` chat command (dev builds only) that loads the POC code on demand |
| `doc/poc-1-rendering-results.md` | How to run it, the test matrix, and space for the decision |

**`layoutPoc.js` in short:**
- **Theme references only.** Colours are `tx1`, `tx2`, `bg1`, `bg2`, `accent1` and `accent2`, and fonts are `+mj-lt` / `+mn-lt`. No slide background is set, so the deck's own background shows through. The one exception is correct/wrong, which keeps fixed green/red plus ✓/✗, so "wrong" never takes on a random theme colour.
- **Slide-size detection**, tried in order:
  1. The Office.js page-setup API, if the running Office version has it.
  2. On desktop, `<p:sldSz>` read from `ppt/presentation.xml`.
  3. Otherwise, assume 13.33×7.5in.
- **Layout grid** worked out from the slide size (margins, title band, content area, footer). No fixed 620pt widths.
- **Six drawing functions:**
  - **Title:** accent band, fitted title and subtitle.
  - **Bullets:** accent bar and real bullets. If it's too long it goes to two columns, then gets split.
  - **Pattern:** rounded boxes joined by → arrows, with an example panel. If the boxes get too narrow, they stack vertically.
  - **Comparison:** two coloured columns, with highlighted words.
  - **Vocab-table:** a real table with an accent header, alternating row colours and column widths sized to the content. Long tables are split and the header is repeated.
  - **Examples:** callout boxes with highlighted words and an optional translation.
- **Fitting:** font sizes start at title 36 / body 24 / table 20 and go no smaller than 28 / 18 / 16. Each slide's footer records what fitting did: `POC · layout · variant · size · title pt · body pt · action`.
- **Self-check after insert.** Every shape is named, so it can be found again after insert with Office.js.
  - Each shape's position and size are compared with what was drawn, which shows any scaling or shifting.
  - On a calibration slide, each box starts at our estimated height. Then PowerPoint is told to resize it to fit its text, and the real height is read back. The difference is the estimate's error.
- **Report** in the chat, plus the full JSON in the console and in `window.__pocLastReport`.

**Running it:**
- `npm run dev-server` + `npm run start:dev` for desktop.
- On the web: upload `manifest.dev.xml` via **Add-ins → More Add-ins → My Add-ins → Upload My Add-in**. It loads from `https://localhost:3000`, so nothing needs pushing.
- In the chat: `/poc-layouts <typical|max|overflow> <match|default>`. `default` reproduces today's behaviour, where no slide size is set.

---

## 4. Current situation

- **Tested on PowerPoint desktop and PowerPoint on the web: slides look OK on both.** The layouts render and insert on both platforms through the single PptxGenJS path.
- That's first evidence for assumptions 1, 2 and 4, and that the fitting approach (5) is workable.
- **Not recorded yet:** the measured numbers per run. That means detected size and source, geometry scale, calibration error %, and insert time for each deck (default 16:9, dark theme, 4:3) and platform. The table in `teachers-center-powerpoint/doc/poc-1-rendering-results.md` is still empty. Fill it in before relying on the details below.

| Question | Status |
|---|---|
| Theme colours on desktop and web | Looks OK. Still to record: dark theme, and recolouring after a theme switch |
| Theme fonts | Looks OK, per the visual check |
| Slide size on web: which source works (`pageSetup` or fallback) | **To record** from the chat summary |
| `default` size: are slides scaled or left at the top-left? | **To record** (`max default` run) |
| PptxGenJS on desktop, including tables | Works |
| Accuracy of the text-height estimate | **To record** (calibration `errPct`) |

---

## 5. Next steps

1. **Finish POC 1's record:** fill in the results table and its Decision section. The two numbers that matter most are the calibration error and the web size source. If the estimate is off by more than about ±10%, tighten the schema limits or add a safety margin.
2. **POC 2, backend and AI.** Nothing in the backend has changed yet.
   - Add the six-layout schema as OpenAI structured output (`text.format` / strict `json_schema`) in `openapi/core.clj`.
   - Rewrite `conversation-content.edn` to pick layouts and drop the emoji rules. Add a `{{slide-format}}` placeholder.
   - With strict mode, the root must be one object with every field required. So `requirements-not-met` and `slides` both become fields, with exactly one of them null.
   - Check whether strict mode enforces string `maxLength`. If it doesn't, the prompt and the add-in's fitting have to cover it.
   - Check that structured output still works with `file_search` ("attach a book").
   - Run 20–30 real teacher requests from the email logs. Measure how often the AI falls back to `bullets`, how often it breaks the limits, and whether its layout choices make sense.
3. **Then the real implementation:** WebSocket request carries `slide-format`, layout drawers move from the POC into `taskpane.js`, and the old desktop `createSlideContent` path and the theme-file reading code are removed. The preview in the taskpane gets rebuilt to match the real slide. The edit prompt returns the same per-layout shape.
4. **Later:** a "Change layout" button in the preview, and the AI-image option (Part 3, Option 2) as an image area inside the templates.
