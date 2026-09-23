# Improving Slide Visual Design — Options Report

## Context

Content quality from the AI is fine, but the inserted PowerPoint slides look plain: every slide (Title aside) is rendered as a fixed stack of 2–4 plain text boxes, with visual "structure" faked using inline emoji (📌 🔹 ✓ ✗ →) inside one unstructured content string. This investigation traces exactly why that's the ceiling today, what Office.js/PptxGenJS and OpenAI actually allow, and lays out options for raising it. This is a **written comparison to decide from — no code changes were made as part of this investigation.**

---

## Part 1 — How the current flow actually works

**Backend (`teachers-center-backend`)** generates a *flat, unstructured* shape per slide:
```json
{"slide-title": "...", "content": "one free-text string, 5-7 lines max"}
```
- No `bullets[]`, no `layout`, no `image` field — everything is one string.
- The prompt (`conversation-content.edn`) explicitly tells GPT‑4o to fake visual structure with Unicode symbols because "you generate text-only slides": *"visual structure must be created through: Symbols (✓ ✗ →) / Clear grouping / Pattern formatting / Transformation arrows."*
- No OpenAI Structured Outputs (`text.format` / `json_schema`, strict mode) are used — compliance is prompt-only ("respond with valid JSON, start with { end with }"), parsed manually with `json/parse-string`. If the model drifts from the shape, parsing just throws.
- Every slide type (Title/Content/Vocabulary/Grammar/Quiz/Homework) shares this one shape — there's no per-type structure at the data level either.

**Frontend (`teachers-center-powerpoint`)** renders that string into a slide with **one layout template**, branching only on `isTitle`:
- Title/Subtitle/Content/Example are each an independent `addTextBox` at a hard-coded `x/y/w/h`, sized against nothing (not measured against actual text length — long content can overflow/overlap).
- No shapes, no tables, no bulleted-list formatting, no background fill, no icons, and — notably — **no images anywhere except the QR code** on interactivity slides.
- Theme extraction (desktop only) pulls `title/subtitle/content` colors and font family from the real `.pptx` theme XML — and also extracts `bgSlide`, `bgSlideAlt`, and `accent2-6`. Of those, `bgSlide` and `accent6` are used only by the **taskpane preview CSS** (`taskpane.css:334`, `750–928`); `bgSlideAlt` and `accent2-5` are unused. **None of them are applied to the inserted slides.** This is a free lift sitting unused in the code today.
- The in-taskpane preview (`.slide-card` in CSS) is a generic chat-bubble card, not a WYSIWYG mock of the real 10×7.5in slide canvas — so what the teacher previews doesn't reliably match what gets inserted, and some preview-only CSS (e.g. the example box's colored border accent) never makes it into the real slide.
- The **one place richer visuals already exist** is `buildInteractivityPptxBase64` (QR-code slides): one image + four text boxes, built with PptxGenJS. This is proof the stack can already do more — it's just not used for regular content slides.

---

## Part 2 — Why desktop and web use different rendering code (investigated)

Today: **desktop** builds slides with native Office.js shape calls (`slides.add()` + `shapes.addTextBox()` inside `PowerPoint.run()`); **web** builds an entire deck client-side with PptxGenJS and inserts it in one `presentation.insertSlidesFromBase64()` call. Interactivity (QR) slides always use the PptxGenJS/base64 path on *both* platforms.

Git history (`e06b02d "suppor for web"`) shows PptxGenJS was introduced specifically to add web support, and the later interactivity feature (`97dfb5d`) just reused it because it was already there — it wasn't chosen for interactivity because native shapes are known to fail; it was reused for convenience.

One divergence is **confirmed directly by a code comment**, not a guess:
```js
// getFileAsync(FileType.Compressed) is not supported in PowerPoint for the web.
```
This is why *theme extraction* (reading the real `.pptx` theme XML for colors/fonts) is skipped on web and falls back to hard-coded defaults — that's a documented, real platform limitation.

The **insertion-method split (native shapes vs. base64 deck) is not documented anywhere** in code or commit messages. The most likely driver, based on Microsoft's own official guidance, is round-trip cost: *"Every call of `context.sync()` is a round trip... especially if the add-in is running in Office on the web because the round trips go across the internet."* Building N slides × several shapes each as individual native Office.js calls means many `context.sync()` round-trips; over the internet (web) that's meaningfully slower than over local IPC (desktop), which is a plausible reason a developer would choose "build the whole file client-side, insert once" specifically for web. But this is inference, not proven — it's equally possible it was simply easier to implement PptxGenJS once while web support was being added, with no rigorous head-to-head performance test done.

**To actually confirm this before deciding whether to keep, unify, or change either path**, the concrete next step (still just investigation, not a code change) would be:
1. Temporarily force the *native* Office.js shape-insertion path to run on Office Online (bypass the `isWeb` branch in a local build) and see whether it (a) throws/fails outright, or (b) merely runs slower.
2. If it runs but slower, measure actual insertion time for a typical 5-slide deck on both platforms to get a real number instead of an assumption.
3. Check the `PowerPointApi` requirement-set docs for `slides.add`/`shapes.addTextBox` to confirm both are available at the `1.5` minimum version this manifest already requires (`manifest.dev.xml` sets `MinVersion="1.5"`) on both Desktop and Online — i.e. rule out an availability gap, not just a performance one.

This would tell you definitively whether the two paths exist because one platform *can't* do something (like theme reading) or because of an unverified performance assumption — and only then would it make sense to revisit whether unifying them is worth it.

---

## Part 3 — Option comparison: shapes/layout only vs. AI-generated images

| | **Option 1: Shapes/colors/layout only** | **Option 2: + AI-generated icons/illustrations** |
|---|---|---|
| **What changes** | Backend returns structured content (see Part 4); frontend draws real bullet lists, colored accent bars/rectangles, tables for vocab lists, and finally *uses* the already-extracted theme colors for backgrounds/accents. | Everything in Option 1, **plus** a generated image per slide (or per deck) via OpenAI's image model, inserted as a real picture. |
| **New backend work** | Extend content schema; no new external API integration. | All of Option 1's schema work, **plus**: a new call to OpenAI's current image model (`gpt-image-2`/`2.5` — DALL·E is retired, requests to it now 400) per slide needing art; the model returns base64 image data directly (fits the codebase's existing base64-handling patterns — theme file reading and the QR code already move base64 around). Needs a new backend endpoint or an addition to the existing conversation flow to trigger and return image data alongside slide JSON. |
| **New frontend work** | A small per-layout-type renderer library (see Part 4) using PptxGenJS/Office.js shapes, tables, and fills already available today — no new dependency. | Same renderer library, plus wiring the returned base64 image into `addImage`/`addPicture` per slide (mechanically simple — the QR-code path already proves base64→slide image works end to end). |
| **Cost per slide** | $0 (no new API calls). | ~$0.005–$0.05+ per image depending on quality tier (`gpt-image-2` pricing: ~$0.005 at low quality up to ~$0.16+ at high quality, per current provider pricing pages). A 6-slide deck could add $0.03–$1+ depending on quality tier chosen. |
| **Latency per slide** | No change to current generation time. | Adds a real image-generation round trip per image — noticeably slower than text generation; needs to run in parallel with or overlap the existing progress-stage UI so the teacher isn't staring at a spinner for longer than today. |
| **Reliability / failure modes** | Same failure surface as today (just JSON parsing of a richer schema). | New failure mode to handle: image generation can fail, time out, or return content that doesn't fit the topic — needs a fallback (skip the image, fall back to a shape/icon) so a flaky image call never blocks slide insertion. |
| **Visual payoff** | Solid, professional-looking "template" feel (real bullets, colored headers/accents, tables) — comparable to a well-designed static PowerPoint template. | Distinctive, illustrated look (custom icon/illustration per topic) — closer to tools like Gamma/Beautiful.ai's AI-art decks, but with real per-request cost and latency, and generated images can be inconsistent in style slide-to-slide unless prompted carefully (e.g. one fixed "flat icon, single accent color, transparent background" style instruction). |
| **Rollout risk** | Low — everything used already exists in the current dependency tree (PptxGenJS, Office.js). | Medium — needs cost controls (e.g. cap images per deck, or make it a paid-tier feature only, given the product is currently free early-access with no account), plus new error handling and possibly caching to avoid regenerating art for repeated topics. |

**What Option 2 specifically requires beyond Option 1**, concretely:
1. Backend: new `openapi/image.clj`-style client wrapping `gpt-image-2`'s generate endpoint (mirrors the existing `openapi/file_search.clj` pattern for a new OpenAI capability).
2. A prompt strategy for consistent style (flat icon vs. photographic vs. line art) — this needs its own small design decision, since inconsistent styles across slides in one deck would look worse than no images at all.
3. Backend flow change: decide whether image generation blocks slide generation (slower, simpler) or runs async/best-effort with a placeholder fallback (faster perceived response, more moving parts).
4. Frontend: extend the WS response schema to carry per-slide image data (or a follow-up fetch), and an `addImage` call per slide layout that has an image region.
5. Cost/quota guardrails given the product's current free/no-account model — e.g. a per-session or per-deck image cap.

---

## Part 4 — Other improvement options (apply regardless of Part 3's choice)

These are the levers that give the most visible improvement for the least risk, independent of the AI-image decision:

1. **Structured content schema + OpenAI Structured Outputs.** Replace the flat `{slide-title, content}` shape with something like:
   ```json
   {"slide-title": "...", "layout": "bullets|comparison|vocab-table|quote|two-column",
    "bullets": [{"text": "...", "emphasis": "positive|negative|neutral"}], ...}
   ```
   using strict `json_schema` response format (Responses API `text.format`) so the model *cannot* return a malformed shape — removing the current "parsing just throws" fragility and the "fake bullets via emoji" workaround at the same time.

2. **A small library of real layout templates on the frontend**, keyed by that `layout` field — e.g. a bulleted list with a colored accent bar, a two-column compare view, a real `addTable` for vocabulary (word/translation/example columns instead of a text blob), a quote/callout box. This mirrors how AI-deck tools like Gamma/Beautiful.ai separate "content" from "which of a fixed set of designed templates to render it with."

3. **Actually use the theme colors already being extracted.** `bgSlide`, `bgSlideAlt`, and `accent2-6` are pulled from the real presentation theme today and thrown away. Alternating slide backgrounds and accent-colored bars/chips using these would meaningfully lift the "generic textbox" look with zero new extraction work.

4. **Replace emoji pseudo-icons with real drawn glyphs.** A small shape (colored circle/rounded-square) with a simple symbol, or a tiny bundled SVG/PNG icon set (checkmark, arrow, warning) rendered via `addImage`, reads as more "designed" than raw Unicode emoji and renders consistently across platforms/fonts (Unicode emoji can render differently depending on OS emoji font).

5. **WYSIWYG preview.** Rebuild the taskpane preview as a properly scaled 10×7.5in canvas with absolutely-positioned elements matching the real pt values, instead of a generic chat-bubble card — this both looks better and prevents the current risk of preview/insertion mismatch (and the overflow/overlap risk from fixed-size boxes not measured against text length).

6. **Auto-fit / overflow handling.** Today's boxes are fixed-size regardless of content length. Either shrink font size when content is long, or size boxes against actual measured text — avoids clipped or overlapping text on longer slides.

---

## Part 5 — Option 1 design: shapes, colours, layout, tables

**Short answer:** you mostly don't have to choose colours or let users configure them. PowerPoint can take them from the teacher's own deck, and both platforms can use that.

### 5.1 Colours: point at the deck's theme colours

Each PowerPoint deck has a theme with 12 named colour slots: `tx1/tx2` for text, `bg1/bg2` for backgrounds, and `accent1–6`.

Today the add-in reads the theme file on desktop only, copies out hex values, and paints with those. Two things already in the code make that unnecessary:

- **PptxGenJS 4.0.1 can refer to theme slots by name.** You write `color: pptx.SchemeColor.accent1`, not a hex value (`node_modules/pptxgenjs/types/index.d.ts:183`).
- **The web insert already uses `formatting: 'UseDestinationTheme'`** (`taskpane.js:1592`).

Together, a shape coloured `accent1` should come out in the teacher's accent colour, whatever their theme is. That includes the web, where the theme file can't be read today. If the teacher switches themes later, the slides should recolour too.

Colours are assigned by role:

| Role | Theme slot |
|---|---|
| Slide background / panels | `bg1` / `bg2` |
| Title, body text | `tx1` / `tx2` |
| Main emphasis (accent bar, table header, key word) | `accent1` |
| Secondary emphasis (second column, callout border) | `accent2` |
| Correct / incorrect (✓ / ✗) | **Fixed** green/red, always with an icon |

The one exception is correct/incorrect. A theme's `accent6` might be purple, and students need to read "wrong" as wrong. So those two stay fixed colours, always shown with a ✓/✗ symbol as well as colour.

**This needs a quick test before relying on it.** Insert one test slide with scheme colours into a non-default theme, on desktop and on web. It's likely to work, since that's what scheme colours are for, but it hasn't been run yet. The same test can check theme fonts (`+mj-lt`/`+mn-lt`) for headings and body.

### 5.2 Layout: the AI picks from a fixed list, the frontend draws it

This is the pattern tools like Gamma, Beautiful.ai and Presenton follow (see 5.7). The AI never decides positions or colours; it only picks which template fits each slide. For language teaching, about six templates would cover it:

| `layout` | Used for | Drawn as |
|---|---|---|
| `title` | Opening slide | Centred title, accent band |
| `bullets` | Rule or explanation | Accent bar + real bulleted list (max 5) |
| `pattern` | Grammar form, e.g. *Subject + verb + -s* | Row of rounded boxes joined by → |
| `comparison` | ✓ vs ✗, affirmative vs negative, A vs B | Two coloured columns with headers |
| `vocab-table` | Vocabulary, conjugations | Real table, `accent1` header row |
| `examples` | Example sentences, practice | Callout boxes, with the key word in `accent1` |

The backend would move to structured output with a JSON schema that the API enforces (Responses API `text.format` / `json_schema`, strict mode). Each layout has its own fields and hard limits:

```json
{"layout": "comparison",
 "slide-title": "Present simple: negatives",
 "left":  {"heading": "✓ Correct", "items": ["She doesn't like tea."]},
 "right": {"heading": "✗ Wrong",   "items": ["She don't like tea."]}}
```

The limits (max 5 bullets, max 60 characters per line, max 8 table rows) replace autofit. Neither Office.js nor PptxGenJS can reliably measure text, so keeping content short is safer than resizing boxes after the fact.

Two geometry fixes belong in the same change:

- Work out positions from the **slide size**, not fixed numbers. Today every box is 620pt wide (`taskpane.js:1885-1950`), which fits a 4:3 slide but looks off on the 16:9 slides PowerPoint creates by default.
- Lay everything out on a simple grid: margins, a title band, and a content area.

### 5.3 Tables: only where the content really is a table

Use them for vocabulary (word | meaning | example) and conjugations (pronoun | form). Don't use them for explanations or examples; there, the `pattern` and `comparison` layouts do the job better.

There's a technical catch. Native Office.js tables (`shapes.addTable`) need PowerPointApi **1.8**, but the manifest only requires **1.5** (`manifest.xml`, `MinVersion="1.5"`). PptxGenJS `addTable` works everywhere today. That points to the biggest decision in this change (5.4).

### 5.4 Build every slide with PptxGenJS on both platforms

Today desktop draws native text boxes while web builds a `.pptx` with PptxGenJS. PptxGenJS already works on desktop for the interactivity (QR) slides. Using it for everything gives:

- theme colours on both platforms (5.1)
- tables without raising the API version (5.3)
- one set of drawing code to maintain, not two
- the ability to delete the theme-file reading code, which only exists because of the desktop path

The cost is fairly small: the desktop path loses per-shape control after insert, which isn't used anyway. It also makes the three checks in Part 2 optional.

### 5.5 User configuration: none to start with

Teachers already chose their look when they picked a PowerPoint theme, and the slides will follow it. A colour picker in the sidebar would add work without helping them. Two small options for later, if teachers ask for them:

- **A "Change layout" button in the preview**, e.g. to show a slide as a table instead of bullets. This becomes cheap once the data is structured.
- **One style switch**, "Colourful / Minimal", which changes how much of the slide gets accent fill.

### 5.6 Other options

- **The deck's own slide layouts:** create slides from the template's layouts and fill in their placeholders (title/body).
  - It looks exactly like the teacher's template, and PowerPoint Designer suggestions work better on it.
  - But placeholders differ from template to template, so only title + body can be relied on, with no tables or patterns.
  - It could make sense as a "plain" mode later.
- **Built-in PptxGenJS master slides:** define a fixed look inside PptxGenJS. It's consistent, but it ignores the teacher's theme, which gives up the main advantage of 5.1.

### 5.7 How existing tools solve it

Gamma doesn't publish its internals, but what these tools say publicly, plus one open-source project whose code is visible, all point to the same idea: **the AI supplies content, and a fixed design system decides what it looks like.**

**Gamma**
- Its guides say it picks a "Smart Layout" by **content type**: heavy on data, a story, or image-led. Content is arranged automatically, with no dragging.
- Themes are fully separate from content. One click restyles the whole deck without changing a word.
- It uses scrolling "cards" rather than fixed slides, so a card can grow taller. That avoids the overflow problem of fixed 10×7.5in slides.

**Beautiful.ai: a rules engine, the strictest of the four**
- The user picks from about 60 "Smart Slide" types: charts, infographics, bullets, photo collages.
- The engine places everything: text boxes and visuals resize and align themselves. Users can change colours and fonts but **cannot override the layout rules**.
- It tells users when they've added too much text or data. It limits content rather than squeezing it to fit.

**PowerPoint Designer: already built into teachers' PowerPoint**
- It matches a slide's content to pre-made layouts and suggests icons for bullet points.
- It only works well on simple, clean slides (title + body placeholders). It may give no ideas for slides with lots of manual design or a custom theme.

**Presenton: open source, so the mechanics are visible**
- Every layout has an ID and a schema with hard limits. From their API docs:

  ```
  layout: "title-and-bullets"
    title:   string, 8–80 chars
    bullets: array of strings, 2–5 items
  ```
  ```json
  {"title": "Quarterly customer health",
   "bullets": ["Enterprise retention remained above target",
               "Time to first value improved",
               "Support response time needs attention"]}
  ```
- The AI **picks a layout and fills in its fields**, shortening text where needed so it fits the limits. The template then draws it. This is almost exactly the design in 5.2.

**What they have in common**

| Who decides | Gamma | Beautiful.ai | Presenton | Teacher Assistant (proposed) |
|---|---|---|---|---|
| Which layout | AI, by content type | User | AI | AI (`layout` field) |
| Content | AI | User | AI | AI, within schema limits |
| Positions | Layout engine | Rules engine | Template | Layout functions in the frontend |
| Colours and fonts | Theme | Theme | Template | Teacher's PowerPoint theme |
| Too much content | Card grows | Warns the user | Schema limits | Schema limits |

**The main lesson:** none of them let the AI choose coordinates or colours. The AI's output is limited to "which template + what fills it".

**The same idea on our slides.** One teacher request, *"present simple negatives, B1"*:

- **Today:** one text box containing `📌 Form\n🔹 don't/doesn't + verb\n✓ She doesn't like tea\n✗ She don't like tea`
- **`pattern` slide:** three rounded boxes `[Subject] → [don't / doesn't] → [base verb]` in `accent1`, with an example underneath
- **`comparison` slide:** a left column titled "✓ Correct" and a right column titled "✗ Wrong", with the same sentences side by side
- **`vocab-table` slide** (for "food vocabulary"): a real table with columns word | meaning | example sentence and an `accent1` header row

The content is the same in each case. Only the drawing changes, and the drawing is our code, not the AI's.

**Sources**
- [Gamma: card-based layouts](https://gamma.app/explore/content/guides/ai-presentation-tool-card-based-layouts)
- [Gamma: visual hierarchy](https://gamma.app/explore/content/guides/how-gamma-builds-clean-modern-presentations-with-visual-hierarchy)
- [Gamma: layout customization guide](https://gamma.app/explore/content/guides/gamma-ai-presentation-tool-flexible-layout-customization-guide)
- [Beautiful.ai: what are Smart Slides](https://www.beautiful.ai/blog/what-the-heck-are-smart-slides)
- [Beautiful.ai: design rules](https://www.beautiful.ai/blog/ai-can-build-slides-fast--but-great-presentations-still-need-design-rules)
- [Microsoft: Designer in PowerPoint](https://support.microsoft.com/en-us/office/create-professional-slide-layouts-with-designer-53c77d7b-dc40-45c2-b684-81415eac0617)
- [Presenton: create from JSON (layout schemas)](https://docs.presenton.ai/api-guides/standard-from-json.md)
- [Presenton on GitHub](https://github.com/presenton/presenton)

### 5.8 Suggested order

1. **Test theme colours:** insert one slide with theme colours and fonts on desktop and web. This is about an hour, and it decides 5.1.
2. **Backend:** schema with the `layout` field, enforced structured output, and prompt changes that remove the emoji instructions (`conversation-content.edn:28-39`).
3. **Frontend:** the six layout drawers on PptxGenJS, with the old desktop drawing code (`createSlideContent`) removed.
4. **Preview** in the sidebar that matches the real slide, reusing the same layout geometry.

### 5.9 Which layout approach to use: cards vs. constraints vs. structured templates

The three tools in 5.7 stand for three ways of handling layout: **Gamma's flexible cards**, **Beautiful.ai's strong constraints ("Smart Slides")** and **Presenton's structured templates**.

**One limit decides most of this.** Our output is **ordinary PowerPoint slides**: a fixed 16:9 or 4:3 page that the teacher edits after insert, with no engine of ours running afterwards. Gamma and Beautiful.ai both rely on their own editor staying in charge after generation. Our add-in hands control to PowerPoint the moment it inserts.

#### 1. Flexible cards (Gamma)
Content goes into cards that grow taller as needed, and layout adapts to the content.

**Pros**
- Almost never overflows, because the card grows.
- Very forgiving of whatever the AI produces.
- Restyling the whole deck in one click works well.

**Cons**
- **Doesn't fit PowerPoint:** a slide can't grow. Projected in a classroom, a card cut to 16:9 is just an overfull slide.
- Relies on Gamma's own viewer and editor, which we don't have.
- Gamma itself flattens cards into slides when exporting to PPTX, and that's where its output looks worst.

**Verdict:** don't use it as the base. The one idea worth keeping, restyling without touching content, we already get from theme colours (5.1).

#### 2. Strong constraints / Smart Slides (Beautiful.ai)
A rules engine owns the layout. It resizes and realigns live as content changes, refuses too much content, and users can't break the design.

**Pros**
- Consistently professional results; the rules can't be overridden.
- Stops overcrowded slides, which suits a classroom ("one idea per slide", which is already in our prompt).

**Cons**
- **The live resizing can't be rebuilt:** once a slide is in PowerPoint, nothing of ours re-runs when the teacher edits it.
- Building a real rules engine means measuring text, which Office.js and PptxGenJS can't do reliably.
- Rigid for teachers who want to tweak things (though in PowerPoint they can always move shapes anyway).

**Verdict:** take the **rules**, not the engine. Enforce limits **when content is generated** (schema maximums), not while the teacher edits.

#### 3. Structured templates (Presenton)
A fixed list of layouts, each with an ID and a schema with hard limits. The AI picks a layout and fills in its fields, and a template draws it.

**Pros**
- **Fits our setup exactly:** we generate once, draw once, and the result is ordinary editable PowerPoint.
- OpenAI structured output (strict `json_schema`) can enforce the schema. That removes today's "parsing just throws" problem.
- Cheap to extend: adding a layout means one schema entry plus one drawing function.
- Easy to test: a fixed input gives a predictable slide.
- Sets up later features: per-slide "Change layout", and image areas for Option 2.

**Cons**
- Only as good as its list of layouts. Content that fits none of them gets forced into one (fall back to `bullets`).
- Slides can look template-like if there are too few layouts or they're too plain.
- Limits sometimes make the AI shorten or reword content. Mitigation: allow "split into two slides" rather than cutting.
- Writing the prompt and schema takes work up front: six layouts, each with fields and limits.

**Verdict:** use this as the base.

#### Recommendation: structured templates, with Beautiful.ai-style limits

| Take from | What | How in our code |
|---|---|---|
| **Presenton** (base) | Layout ID + schema per layout, AI picks and fills | `layout` enum + strict `json_schema` in `conversation-content.edn` / `openapi/core.clj`; six drawing functions in PptxGenJS |
| **Beautiful.ai** | Hard limits and "too much content" guards | `maxItems`/`maxLength` in the schema, plus a prompt rule: split into two slides rather than overfill |
| **Gamma** | Restyle without touching content | Theme colour slots + `UseDestinationTheme`, so the teacher's theme drives the look |

This is the design already described in 5.1–5.6; the three tools just confirm it. The risky part isn't the approach, it's two unknowns:
1. **Whether theme colours really come through when inserting on desktop and web.** That's the one-hour test in 5.8, step 1.
2. **Whether six layouts are enough for real teacher requests.** Check this cheaply: run 20–30 real requests from the email logs through the new prompt and see how often the AI falls back to `bullets`.

---

## Recommendation (if a starting point is wanted later)

Independent of the Option 1 vs. 2 image decision: items 1–3 in Part 4 are the highest-value, lowest-risk changes — they use only what's already in the dependency tree (PptxGenJS/Office.js shapes+tables, already-extracted theme colors) and fix the most visible "flat text box" complaint without adding cost, latency, or new failure modes. The AI-image option (Part 3, Option 2) is a legitimate follow-on once the structural schema/template work is in place — it's easier to bolt an image *region* onto an existing template than to design around images before the template system exists. The desktop/web rendering divergence (Part 2) is worth actually verifying (the 3-step check above) before any unification decision, since right now the reason is inferred, not confirmed.

**Part 5 is the concrete design for Part 4 items 1–3** (theme colours, layout list with schema, tables, a single PptxGenJS rendering path), with the step order in 5.8.

---

## Read more on the options

- [ChatGPT discussion on the slide design options](https://chatgpt.com/share/6ab303fd-89dc-83ed-8e99-c90bfd984f10)
