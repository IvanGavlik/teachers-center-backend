(ns teachers-center-backend.book.ocr
  "Azure OCR for a whole book, in parts of 20 pages.

   One call for a whole book isn't reliable: on 2026-10-14 Azure left a 211-page scan \"running\"
   for over 20 minutes (on 2026-10-12 the same file took 23 s), while a 20-page part of it took 5 s.
   So the PDF is cut into parts, a few go to Azure at once, and each part has its own short timeout
   and one retry. A part that still fails only costs its own pages (convert/join-parts); a book that
   misses too many fails instead of becoming a book with holes everywhere."
  (:require [teachers-center-backend.azure.layout :as azure]
            [teachers-center-backend.book.convert :as convert]
            [teachers-center-backend.book.jobs :as jobs]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.multipdf Splitter]
           [org.apache.pdfbox.pdmodel PDDocument]))

(def ^:private part-size 20)

(def ^:private parallel
  "Parts at Azure at once: each is a ~4 MB upload from a 0.5-CPU Render instance."
  4)

(def ^:private part-timeout-ms
  "A 20-page part took 5 s; this is for a slow day, not for a stuck operation."
  (* 2 60 1000))

(def ^:private max-missing
  "More than this share of pages unread → the job fails rather than give the teacher half a book."
  1/5)

;; ── Parts already read ───────────────────────────────────────────────────────
;; So a retry after a failed or incomplete book only pays Azure for the parts that failed. Keyed by
;; the PDF's SHA-256 like book.cache (a hit needs the exact same file), in memory only: losing it on
;; a restart just means paying for OCR once more.

(def ^:private part-cache-ms (* 24 60 60 1000))

(defonce ^:private part-cache (atom {})) ; {[sha [first last]] {:pages [...] :at ms}}

(defn- cached-part [sha rng]
  (when sha
    (let [{:keys [pages at]} (get @part-cache [sha rng])]
      (when (and pages (> at (- (System/currentTimeMillis) part-cache-ms)))
        pages))))

(defn- remember-part! [sha rng pages]
  (when sha
    (let [now (System/currentTimeMillis)]
      (swap! part-cache (fn [c] (-> (into {} (filter #(> (:at (val %)) (- now part-cache-ms))) c)
                                    (assoc [sha rng] {:pages pages :at now})))))))

(defn clear-part-cache-for-tests! [] (reset! part-cache {}))

(defn- split-pdf!
  "Saves each part of `pdf` as its own temp file: [[first-page last-page] file] in order. PDFBox
   copies the scanned images without decoding them, so this is cheap even for a 60 MB book."
  [^File pdf]
  (with-open [doc (Loader/loadPDF pdf)]
    (let [parts (.split (doto (Splitter.) (.setSplitAtPage part-size)) doc)]
      (mapv (fn [rng ^PDDocument part]
              (with-open [part part]
                [rng (doto (File/createTempFile "book-part-" ".pdf") (->> (.save part)))]))
            (convert/part-ranges (.getNumberOfPages doc) part-size)
            parts))))

(defn- analyze-part
  "Azure's result for one part, or nil if it failed twice."
  [analyze [first-page last-page] ^File file]
  (loop [attempt 1]
    (let [result (try (analyze file)
                      (catch Exception e
                        (log/warn e "Azure OCR of a part failed" {:pages [first-page last-page] :attempt attempt})
                        nil))]
      (if (or result (= attempt 2)) result (recur (inc attempt))))))

(defn analyze-book
  "The book's pages (convert/split-pages shape, numbered for the whole book) from Azure, read in
   parts. Throws if more than max-missing of the pages couldn't be read.
   opts: :sha (the PDF's SHA-256; with it, parts read in the last 24 h aren't sent again), :on-part
   (called with the pages read so far after each part), :analyze (file → Azure's :analyzeResult;
   tests replace Azure with it)."
  [azure ^File pdf {:keys [sha on-part analyze]
                    :or   {on-part (fn [_])
                           analyze #(azure/analyze azure % {:timeout-ms part-timeout-ms})}}]
  (let [parts  (split-pdf! pdf)
        done   (atom 0)
        cached (atom 0)
        pool   (jobs/daemon-pool parallel "book-ocr")
        t0     (System/currentTimeMillis)]
    (try
      (let [pages (->> parts
                       (mapv (fn [[[a b :as rng] file]]
                               (.submit pool ^Callable
                                        (fn []
                                          (let [part-pages (if-let [hit (cached-part sha rng)]
                                                             (do (swap! cached inc) hit)
                                                             (let [ps (convert/join-parts [{:range  rng
                                                                                            :result (analyze-part analyze rng file)}])]
                                                               ;; only parts Azure read — a failed one is tried again next time
                                                               (when-not (some :ocr-failed? ps) (remember-part! sha rng ps))
                                                               ps))]
                                            (on-part (swap! done + (inc (- b a))))
                                            part-pages)))))
                       (into [] (mapcat deref)))
            missing (convert/missing-share pages)]
        (log/info "Book OCR done" {:pages (count pages) :parts (count parts) :parts-cached @cached
                                   :missing (count (filter :ocr-failed? pages))
                                   :ms (- (System/currentTimeMillis) t0)})
        (when (> missing max-missing)
          (throw (ex-info "Azure couldn't read too many pages of the book"
                          {:missing (count (filter :ocr-failed? pages)) :pages (count pages)})))
        pages)
      (finally
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/MINUTES)
        (run! (fn [[_ ^File f]] (.delete f)) parts)))))
