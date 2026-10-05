(ns teachers-center-backend.book.check
  "Step ⑤ of doc/book-ocr-investigation.md: did the vision model change the words of a page?

   Azure's OCR text is the source of truth for words. The model may only add layout — blanks,
   checkboxes, speaker symbols, markup and tagged notes ([Image: …], [Audio: …], (Beispiel: …)).
   So every word may appear at most as often as in Azure's text: a solved gap shows up either as a
   word that isn't on the page, or as a word-bank phrase that now appears twice."
  (:require [clojure.string :as str]))

(def ^:private max-lost-words
  "Azure text the model may drop, on top of decoration (see decoration-words): a side label in
   normal case (Schreib-training), a stray OCR fragment."
  5)

(def ^:private max-lost-numbers
  "Item numbers the model may drop. A dropped `1 ☐ … 6 ☐` list loses six — that's the failure
   the word count can't see."
  2)

(def ^:private page-comment #"<!-- Page(?:Footer|Header|Number)=\"([^\"]*)\" -->")

(defn- page-comment-text
  "The printed footer/header Azure keeps inside comments. The model may write it out as text (so
   it doesn't count as added) or drop it (so it doesn't count as lost)."
  [s]
  (str/join " " (map second (re-seq page-comment s))))

(defn- strip-markup [s]
  (-> s
      (str/replace #"(?s)<!--.*?-->" " ")
      ;; score lines ("1 ____ /9 Punkte") are page decoration the model is told to drop
      (str/replace #"\d{0,2}[\s\\_-]*/\s*\d+\s*Punkte" " ")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"\[(?:Image|Audio):[^\]]*\]" " ")
      (str/replace "(Beispiel:" " ")))

(defn- image-text [s]
  (str/join " " (map second (re-seq #"\[Image:([^\]]*)\]" s))))

(defn- word-counts
  "Letter-gap words (K__ll__g__n, To_r_m_s) don't count on either side: their letters are
   fragments, and redrawing the gaps to match the image is the model's job."
  [s]
  (->> (-> (strip-markup s)
           ;; a blank before or after a word ("ein___ Monat") isn't a letter gap
           (str/replace #"_+(?=[\s.,;:!?)|]|$)" " ")
           (str/replace #"(?<=[\s(|]|^)_+" " ")
           (str/replace #"[^\s]*_[^\s]*" " "))
       (re-seq #"[A-Za-zÄÖÜäöüß]{2,}")
       (map str/lower-case)
       frequencies))

(defn- decoration-words
  "Words the model may drop without it counting as lost content: those Azure read only in capitals
   (side labels like KOMMUNIKATION, signs on drawings, crossword letters) and 1–2-letter fragments."
  [azure-text]
  (let [raw (re-seq #"[A-Za-zÄÖÜäöüß]{2,}" (strip-markup azure-text))]
    (->> (group-by str/lower-case raw)
         (keep (fn [[w forms]] (when (or (<= (count w) 2) (every? #(= % (str/upper-case %)) forms)) w)))
         set)))

(defn- number-counts
  "Standalone numbers — item numbers, (a)-style references don't count. Score ranges like 0-4
   are page decoration the model is told to drop, so they're removed first on both sides."
  [s]
  (->> (str/replace (strip-markup (str/replace s page-comment " ")) #"\d+\s*[-–]\s*\d+" " ")
       ;; "1." and "(3)" count; the parts of 11,50 or 9.30 don't
       (re-seq #"(?<!\d)(?<!\d[,.])\d{1,3}(?!\d)(?![,.]\d)")
       frequencies))

(defn- excess
  "Entries of `a` that occur more often than in `b`, with the difference."
  [a b]
  (into (sorted-map)
        (keep (fn [[k n]] (let [d (- n (get b k 0))] (when (pos? d) [k d]))))
        a))

(defn- dec-word [m w]
  (if (> (get m w 0) 1) (update m w dec) (dissoc m w)))

(defn- cancel-resplits
  "The same letters, split differently: the model separating a gap's example letters (\"einer\" →
   \"ein er\") or joining a hyphenated word (\"ARBEITS- ZEIT\" → \"Arbeitszeit\"). Cancels a lost word
   against two added words that make it up, and an added word against two lost words."
  [added lost]
  (letfn [(split-of [whole parts]
            (some (fn [p] (let [rest-w (subs whole (min (count p) (count whole)))]
                            (when (and (str/starts-with? whole p) (seq rest-w) (contains? parts rest-w)
                                       (or (not= p rest-w) (>= (get parts p 0) 2)))
                              [p rest-w])))
                  (keys parts)))]
    (loop [added added lost lost]
      (if-let [[w [a b]] (some (fn [w] (when-let [s (split-of w added)] [w s])) (keys lost))]
        (recur (-> added (dec-word a) (dec-word b)) (dec-word lost w))
        (if-let [[w [a b]] (some (fn [w] (when-let [s (split-of w lost)] [w s])) (keys added))]
          (recur (dec-word added w) (-> lost (dec-word a) (dec-word b)))
          [added lost])))))

(defn- dangling-ticks
  "Ticks that mark no option: followed by the end of the line, a blank, another box, a symbol or a
   number (\"…ein schönes Wochenende? [x]\", \"[x] 0-4\"). A real tick is followed by its option —
   a word, or a slash for the \"no preposition\" choice."
  [s]
  (count (re-seq #"(?im)(?:\[x\]|☒)(?=[ \t]*(?:$|\[|\(|_|\d|[◆○▲□●·]))" s)))

(defn- box-counts [s]
  {:ticked (count (re-seq #"(?i)☒|\[x\]" s))
   :empty  (count (re-seq #"☐|\[ \]" s))})

(defn check-page
  "Compares the model's page with Azure's page text. Returns
   {:ok? bool :reasons [..] :added-words {w n} :lost-words {w n} :lost-numbers {n k}
    :boxes {:azure {..} :model {..}} :blanks {:azure n :model n} :images n}.
   :reasons are written so they can be sent back to the model on a retry."
  [azure-text model-text]
  (let [az-words     (word-counts azure-text)
        md-words     (word-counts model-text)
        ;; footer words written out by the model come out of the footer allowance first
        md-to-check  (excess md-words (word-counts (page-comment-text azure-text)))
        ;; text printed on a picture (a sign saying ARBEITSZEIT) may move into its [Image: …] line
        md-with-imgs (merge-with + md-words (word-counts (image-text model-text)))
        [added-words lost-words] (cancel-resplits (excess md-to-check az-words)
                                                  (excess az-words md-with-imgs))
        decoration   (decoration-words azure-text)
        lost-deco    (into (sorted-map) (filter (comp decoration key)) lost-words)
        lost-words   (into (sorted-map) (remove (comp decoration key)) lost-words)
        lost-numbers (excess (number-counts azure-text) (number-counts model-text))
        added-nums   (excess (number-counts model-text) (number-counts azure-text))
        n-lost       (reduce + 0 (vals lost-words))
        n-lost-nums  (reduce + 0 (vals lost-numbers))
        n-added-nums (reduce + 0 (vals added-nums))
        n-dangling   (dangling-ticks model-text)
        reasons      (cond-> []
                       (seq added-words)
                       (conj (str "You added words that are not in the OCR text (or used them more often): "
                                  (str/join ", " (keys added-words))
                                  ". Remove them — never fill blanks or solve exercises."))

                       (> n-lost max-lost-words)
                       (conj (str "You dropped words from the OCR text: "
                                  (str/join ", " (keys lost-words)) ". Keep all of the page's text."))

                       (> n-lost-nums max-lost-numbers)
                       (conj (str "You dropped item numbers: " (str/join ", " (keys lost-numbers))
                                  ". Keep every item number and every checkbox of every item."))

                       ;; numbering the lines of an "Ordnen Sie …" task is solving it
                       (> n-added-nums max-lost-numbers)
                       (conj (str "You added numbers that are not on the page: " (str/join ", " (keys added-nums))
                                  ". Do not number or reorder items — keep them in the printed order."))

                       (pos? n-dangling)
                       (conj (str "You have " n-dangling " ticked box(es) [x] that belong to no answer option "
                                  "(at the end of a line, or before a blank). OCR reads speaker symbols and score "
                                  "dots as ticks — write [x] only where the image shows a cross on an option, "
                                  "and remove the others.")))]
    {:ok?          (empty? reasons)
     :reasons      reasons
     :added-words  added-words
     :lost-words   lost-words
     :lost-decoration lost-deco
     :lost-numbers lost-numbers
     :added-numbers added-nums
     :dangling-ticks n-dangling
     ;; Reported, not checked: Azure's own counts include decoration read as boxes, so a lower
     ;; count from the model is usually a fix, not a loss.
     :boxes        {:azure (box-counts azure-text) :model (box-counts model-text)}
     :blanks       {:azure (count (re-seq #"_{3,}" azure-text))
                    :model (count (re-seq #"_{3,}" model-text))}
     :images       (count (re-seq #"\[Image:" model-text))}))
