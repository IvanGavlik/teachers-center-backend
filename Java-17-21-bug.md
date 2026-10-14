# Bug: improved books were empty in production (Java 17 vs Java 21)

**Date:** 2026-10-14 · **Affected:** 2.1.0 in production · **Fixed in:** 2.1.1 (`6809d4c`, tag `v2.1.1`)

## Summary

In production, every book that finished the Luna improvement step (`book/improve.clj`) was replaced by
an **empty book**: only the page markers, no text. The same code worked locally. The cause was a
Clojure Java-interop call that is resolved by **reflection at runtime**, and the method that reflection
picked differed between the Java version on the developer machine (21) and the one in the Docker image
(17): Java 17 picked `ExecutorService.submit(Runnable)`, whose `Future` returns `nil`.

## What teachers saw

1. Attach a book → the chip shows Reading your book… → Indexing… → **Ready**. Up to here everything
   worked: the book was usable on Azure's text.
2. 20 s to a few minutes later, Luna finished in the background and the backend swapped its version in
   (`library/improve-book!`). From then on the book contained no text.
3. Requests that used the book got nothing from `file_search`: the model asked "which vocabulary topic
   or chapter?" and then made generic slides (Bread, Pasta, Menu…) instead of the book's words
   (booking, specials, starter…).
4. Only finished books are cached by file fingerprint (`book/cache.clj`), so the **empty** version was
   also what every later upload of the same PDF got, for 29 days.

What it looked like in the Render log and in the vector store:

```
file_search debug {... :text <!-- English-Today-scanned · PDF p.  -->\n\n\n\n<!-- English-Today-scanned · PDF ...
```

The stored book (`English-Today-scanned [sha256-d0d464…].md`) was **133 bytes**: three page markers,
each without its page number and with nothing after it. The missing page number was the clue: the
marker is built from each page's `:pdf-page`, so the pages themselves were `nil`.

## Root cause

`improve-pages` repairs the pages in a thread pool and collects the results:

```clojure
(defn- daemon-pool [n]                       ; no return type
  (Executors/newFixedThreadPool n ...))

(let [pool (daemon-pool parallel)]           ; so `pool` has no known type
  (->> pages
       (mapv (fn [page] (.submit pool ^Callable (fn [] ... r))))   ; ← reflective call
       (mapv deref)))                                               ; ← the page results
```

- Because `pool` has no type hint, the Clojure compiler can't resolve `.submit` at compile time. It
  emits a **reflective call**, and the method is chosen at runtime from the argument's actual class.
  (The `^Callable` hint on the argument only helps when the target's type is known.)
- `ExecutorService` has `submit(Runnable)` and `submit(Callable)`. A Clojure `fn` implements **both**
  `Runnable` and `Callable`, so both methods match.
- Clojure's `Reflector` then takes a match in the order Java lists the methods
  (`Class.getMethods()`). That order is **not specified** and changed between JDK versions:
  - Java 21 (local): `submit(Callable)` → `deref` returns the page map.
  - Java 17 (Docker image `clojure:openjdk-17-tools-deps-alpine`, reports `17-ea`):
    `submit(Runnable)` → `Runnable.run()` discards the return value → `deref` returns **`nil`**.
- `improve-book!` built the Markdown from `nil` pages (`(assoc nil :text nil)` → no text, no page number),
  uploaded it, swapped it into the vector store in place of Azure's text, and cached it as finished.

Minimal reproduction (same code, no API calls):

```clojure
(let [pool (identity (Executors/newFixedThreadPool 2))   ; type unknown to the compiler
      f    (.submit pool ^Callable (fn [] 42))]
  (deref f))
;; Java 21.0.8 (local)          → 42
;; Java 17-ea (prod Docker)     → nil
```

## Why we didn't catch it

- All development, unit tests (`clojure -X:test`) and live test runs were on **Java 21** locally, where
  the call happened to work. The 211-page runs, the 2-page live checks and the manual PowerPoint tests
  all used the local backend.
- The unit tests for `improve` tested `convert-page` (one page) with a fake model, never the thread pool
  in `improve-pages`.
- The one Docker check before the release (2026-10-13) only tested PDF page rendering in the image,
  not the improvement step.
- Nothing failed loudly: no exception, the job ended `ready`, and the log said
  `Book improved` (its `:status` counts would have shown `{nil 3}`).

## Fix (2.1.1)

`src/teachers_center_backend/book/improve.clj`:

1. **Typed pool:** `daemon-pool` returns `^ExecutorService`, so `(.submit pool ^Callable …)` is resolved
   at **compile time** to `submit(Callable)` on every Java version. No reflection, no ambiguity.
2. **Guard:** if any page comes back without a result, `improve-pages` throws. `improve-book!` then keeps
   the book on Azure's text (status `usable` with `:improve-error`) instead of swapping in an empty book.
3. The semaphore `max-model-calls` is typed `^Semaphore` too (its reflective calls were harmless, but
   now `improve.clj` has no reflection warnings).

`test/teachers_center_backend/book/improve_test.clj`: `improve-pages-returns-every-page` runs the real
pool on a 2-page PDF with a fake model and checks every page comes back with its number, status and text.
(On Java 21 the old code passes this test too, so the test guards the guard; the real proof is below.)

## Verification

- `clojure -X:test`: 71 tests, 0 failures.
- In the **production Docker image** (Java 17-ea), built from the fixed code, `improve-pages` with a fake
  model returned `([1 :ok "Seite eins"] [2 :ok "Seite zwei"])`.
- In production after the 2.1.1 deploy, `English-Today-scanned.pdf`: usable in 12 s, `Book improved
  {:status {:ok 3}}` in 30 s, stored book **3,374 bytes** of text, only Luna's file left in the vector
  store, and a deck used the book's vocabulary (booking, specials, starter, "Mr Okafor asked for the
  recipe").

## Cleanup done

- The broken cached file `file-QpJLUNhqzsD9FXDte51iDh` (133 bytes) was deleted at OpenAI. The next upload
  of that PDF converted it again correctly (see Verification).
- All other cached books were made locally on Java 21 and have normal sizes; only this one was broken.
  2.1.0 was live in production for about 50 minutes (21:48–22:37 UTC, 2026-10-13); the only upload that
  finished Luna in that time was this test.

## Follow-ups

1. **Move the Docker image off `openjdk-17` early access.** The `openjdk` images are deprecated and this
   one reports `17-ea`. Use a maintained Temurin image, e.g. `clojure:temurin-21-tools-deps` (pin a full
   tag), so production runs the same Java major version as development. After switching, re-run the
   Docker checks: PDF page rendering (digital + scanned) and `improve-pages` with a fake model.
2. **Test in the production image before each release.** A small script that builds the image and runs
   the fake-model `improve-pages` check plus page rendering would have caught this in a minute.
3. **Fix the remaining reflective calls in our code** (found with `(set! *warn-on-reflection* true)`):
   - `openapi/core.clj:201`: `Math/round` on a `Number` (two overloads, `round(float)`/`round(double)`, so
     the same kind of runtime choice)
   - `azure/layout.clj:31`: `Thread/sleep` on an unknown type
   - `core.clj:26`: `.get` on the dotenv object
   - `system.clj:24`: `.stop`

   None of them has a result that silently disappears like `submit`, but each picks its method at
   runtime. Consider `(set! *warn-on-reflection* true)` in every namespace so new ones show up when
   the code loads.
4. **Log the result counts loudly.** `Book improved` already logs `:status` counts. A count of `nil`, or
   an improved book much smaller than Azure's text, could be logged as an error (the guard now throws in
   the first case).
