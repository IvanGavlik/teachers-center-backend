(ns teachers-center-backend.book.convert
  "Step ② of doc/book-ocr-investigation.md: Azure's layout result → one cleaned text per page →
   the book as one Markdown file with a marker per page. Pure functions, no I/O."
  (:require [clojure.string :as str]
            [teachers-center-backend.book.check :as check]))

(defn- page-range
  "[start end) of a page in Azure's :content, or nil for a page without text (a blank page)."
  [page]
  (when-let [spans (seq (:spans page))]
    [(apply min (map :offset spans))
     (apply max (map #(+ (:offset %) (:length %)) spans))]))

(defn- mark-examples
  "Wraps the handwritten spans inside [start end) as (Beispiel: …). In a workbook scan these are the
   book's printed example answers, so they're kept and labelled, never removed."
  [content [start end] styles]
  (let [spans (->> styles
                   (filter #(and (:isHandwritten %) (>= (or (:confidence %) 0) 0.8)))
                   (mapcat :spans)
                   (filter #(and (>= (:offset %) start) (<= (+ (:offset %) (:length %)) end)))
                   (sort-by :offset >))]
    (reduce (fn [text {:keys [offset length]}]
              (let [a (- offset start) b (+ a length)]
                (str (subs text 0 a) "(Beispiel: " (subs text a b) ")" (subs text b))))
            (subs content start end)
            spans)))

(defn- clean [text]
  (-> text
      (str/replace #"</?figure>" "")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- printed-page
  "\"LEKTION 8 AB 92 zweiundneunzig\" → 92, \"dreiundneunzig 93 AB LEKTION 8\" → 93."
  [footers]
  (some->> footers
           (map #(str/replace % #"LEKTION\s+\d+" ""))
           (mapcat #(re-seq #"\b\d{1,3}\b" %))
           last
           parse-long))

(defn split-pages
  "One map per page of an Azure :analyzeResult: :pdf-page (1-based), :azure-text (cleaned, step ②),
   :printed-page, :lesson."
  [{:keys [content pages styles]}]
  (map-indexed
    (fn [i page]
      (if-let [rng (page-range page)]
        (let [text    (mark-examples content rng styles)
              ;; Headers carry the unit number ("8 D"), not the page number — footers only.
              footers (map second (re-seq #"<!-- PageFooter=\"([^\"]*)\" -->" text))]
          {:pdf-page     (inc i)
           :azure-text   (clean (check/tag-audio text))
           :printed-page (printed-page footers)
           :lesson       (some #(some-> (re-find #"LEKTION\s+(\d+)" %) second parse-long) footers)})
        {:pdf-page (inc i) :azure-text ""}))
    pages))

(defn page-marker
  "<!-- Schritte 2 · Lektion 8 · S. 92 · PDF p. 14 --> — so file_search passages say where they come from."
  [title {:keys [pdf-page printed-page lesson]}]
  (str "<!-- " (str/join " · " (remove nil? [title
                                              (when lesson (str "Lektion " lesson))
                                              (when printed-page (str "S. " printed-page))
                                              (str "PDF p. " pdf-page)]))
       " -->"))

(defn book-markdown
  "Joins pages ({:pdf-page … :text …} plus the split-pages keys) into the book's Markdown file."
  [title pages]
  (str/join "\n\n" (map #(str (page-marker title %) "\n\n" (:text %)) pages)))

(defn azure-book-markdown
  "The whole book as Azure's cleaned text — the first, usable version of a book (Phase 1)."
  [title analyze-result]
  (book-markdown title (map #(assoc % :text (:azure-text %)) (split-pages analyze-result))))
