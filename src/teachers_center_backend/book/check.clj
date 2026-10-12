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

(def ^:private audio-marker
  "CD number, the ◀)) icon, track or tracks — at the start of a line or a table cell. Azure reads the
   icon as `)` `4)` `41)` `40)` `0)` `(1)` `M)` `))` `41) )`, sometimes stuck to the CD number
   (`44)` = CD 4, `50)` = CD 5). Without a space before it, the icon needs a character in front of
   the `)`, so an item number like `1) 12 Uhr` isn't taken for one. The model's copies of it may be
   bold (`**2 41) 38**`), have an en dash (`1–8`) or an em space after them (\\h, not a plain space)."
  #"(?m)(^|<t[dh]>)(?:#+\h)?(?:\*\*)?(\d)(?:\h\(?[\dM!]{0,2}|\(?[\dM!]{1,2})\)(?:\h?\))?\h?(\d{1,2}(?:[-–]\d{1,2})?)(?:\*\*)?(?:\h+|(?=<|$))")

(def ^:private audio-marker-without-track
  "CD number and icon alone on a line, the track lost (\"2 0)\"). The space before the icon keeps an
   item number like `2)` out."
  #"(?m)^(?:#+\h)?(\d)\h\(?[\dM!]{0,2}\)\h*$")

(defn tag-audio
  "Step ②: \"2 41) 10 a Hören Sie\" → \"[Audio: CD 2, Track 10] a Hören Sie\". The check also drops
   markers the model copied untagged, so they count neither as added nor as lost numbers."
  [text]
  (-> text
      (str/replace audio-marker (fn [[_ cell cd track]] (str cell "[Audio: CD " cd ", Track " track "] ")))
      (str/replace audio-marker-without-track "[Audio: CD $1]")))

(def ^:private bare-footer
  "A footer line Azure didn't mark as one: \"LEKTION 13 AB\", \"144 einhundertvierundvierzig\",
   \"siebenundachtzig 87\". The spelled-out number has to be long (≥ 10 letters, so page 87 and up)
   and end like one, so a number exercise like \"12 zwölf\" isn't taken for a footer."
  #"(?m)^\h*((?:LEKTION\h+\d{1,2}(?:\h+AB)?)|(?:\d{2,3}\h+[a-zäöüß]{6,}(?:zig|ßig|hundert|zehn|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|elf|zwölf))|(?:[a-zäöüß]{6,}(?:zig|ßig|hundert|zehn|eins|zwei|drei|vier|fünf|sechs|sieben|acht|neun|elf|zwölf)\h+\d{2,3}))\h*$")

(defn- mark-bare-footers
  "Wraps bare footer lines like Azure's own <!-- PageFooter=\"…\" --> comments, so they get the same
   allowance: the model may drop them or write them out."
  [azure-text]
  (str/replace azure-text bare-footer (fn [[_ footer]] (str "<!-- PageFooter=\"" footer "\" -->"))))

(defn- page-comment-text
  "The printed footer/header Azure keeps inside comments. The model may write it out as text (so
   it doesn't count as added) or drop it (so it doesn't count as lost)."
  [s]
  (str/join " " (map second (re-seq page-comment s))))

(def ^:private image-tag
  "[Image: …], or [Image 2: …] when the model keeps the photo's printed number in the tag."
  #"\[Image(?:\h*(\d{1,2}))?:([^\]]*)\]")

(defn- strip-markup [s]
  (-> (tag-audio s)
      (str/replace #"(?s)<!--.*?-->" " ")
      ;; score lines ("1 ____ /9 Punkte") are page decoration the model is told to drop
      (str/replace #"\d{0,2}[\s\\_-]*/\s*\d+\s*Punkte" " ")
      (str/replace #"<[^>]+>" " ")
      ;; HTML entities the model writes for spacing in tables (&nbsp;) aren't words
      (str/replace #"&(?:[a-zA-Z]+|#\d+);" " ")
      ;; the model sometimes leaves the CD number outside its tag: "3 [Audio: 36–43]"
      (str/replace #"(?:\b\d\h)?\[Audio:[^\]]*\]" " ")
      (str/replace image-tag " ")
      (str/replace "(Beispiel:" " ")))

(defn- image-text [s]
  (str/join " " (map (fn [[_ n text]] (str n " " text)) (re-seq image-tag s))))

(defn- word-counts
  "Letter-gap words (K__ll__g__n, To_r_m_s) don't count on either side: their letters are
   fragments, and redrawing the gaps to match the image is the model's job."
  [s]
  (->> (-> (strip-markup s)
           ;; a crossed-out letter stays part of its word: eu~~x~~re is Azure's euxre
           (str/replace "~~" "")
           ;; a letter gap split off its first letters ("Deutschk _n_t_s_n") is one gap word; blanks
           ;; (3+ _) keep their space. Only this direction: Azure's short blanks before a word
           ;; ("d .__ gefällt") would swallow the word. Two letters before it, so a speaker symbol
           ;; Azure read as O ("O _Und?") isn't glued on.
           (str/replace #"(?<=\p{L}{2})\h+(?=_{1,2}(?!_))" "")
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
  (->> (-> (strip-markup (str/replace s page-comment " "))
           ;; "1, 2, 5" and "24. 2." are "1,2,5" and "24.2." with spaces — the same on both sides
           ;; (Azure escapes the dot in "4\. 3\. 2\.")
           (str/replace #"(?<=\d)\\?([,.])\h+(?=\d)" "$1")
           (str/replace #"\d+\s*[-–]\s*\d+" " ")
           ;; a 0 alone on its line is an answer circle ○ Azure read as a digit — items start at 1
           (str/replace #"(?m)^\h*0\h*$" " "))
       ;; "1." and "(3)" count; the parts of 11,50 or 9.30 don't
       (re-seq #"(?<!\d)(?<!\d[,.])\d{1,3}(?!\d)(?![,.]\d)")
       frequencies))

(defn- number-allowance
  "Numbers the model may write without them counting as added: the printed footer written out as
   text (\"LEKTION 10 118\") and Azure's audio markers copied without their icon (\"### 38 B1\",
   \"2 47\"). They still count as lost if the model drops them from the text."
  [azure-text]
  (let [audio (->> (re-seq #"\[Audio:[^\]]*\]" (tag-audio azure-text))
                   (map #(str/replace % #"\d+\s*[-–]\s*\d+" " "))
                   (str/join " "))]
    (frequencies (re-seq #"\d{1,3}" (str (page-comment-text azure-text) " " audio)))))

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

(defn- cancel-i-l-misreads
  "Azure reads a capital I as l (Ioanna → loanna); the model reading the image puts it back. Cancels
   an added word against a lost one that differs only there — no other spelling fix is allowed."
  [added lost]
  (letfn [(i-l-twin [w words]
            (some #(when (and (not= % w) (= (count %) (count w))
                              (every? (fn [[a b]] (or (= a b) (= #{a b} #{\i \l}))) (map vector w %)))
                     %)
                  (keys words)))]
    (loop [added added lost lost]
      (if-let [[w twin] (some (fn [w] (when-let [t (i-l-twin w lost)] [w t])) (keys added))]
        (recur (dec-word added w) (dec-word lost twin))
        [added lost]))))

(defn- foreign-letter-words
  "Words with Cyrillic or Greek letters (Luna wrote \"schnел\" for \"schnel\"). The word count only
   sees their Latin part, so this names the whole word for the retry."
  [s]
  (frequencies (re-seq #"\S*[\p{IsCyrillic}\p{IsGreek}]\S*" s)))

(defn- dangling-ticks
  "Ticks that mark no option: followed by the end of the line, a blank, another box, a symbol or a
   number (\"…ein schönes Wochenende? [x]\", \"[x] 0-4\"). A real tick is followed by its option —
   a word, or a slash for the \"no preposition\" choice. Also real: a tick before the book's example
   in a blank (\"[x] ____ (Beispiel: Würdest)\"), and before a number when the line's other options
   are numbers too (\"Getreidegasse [x] 9 [ ] 17\") — not a score range like \"[x] 0-4\"."
  [s]
  (reduce + (for [line (str/split-lines s)
                  :let [number-options? (re-find #"(?:\[ \]|☐)\h*\d" line)]]
              (count (re-seq (if number-options?
                               #"(?i)(?:\[x\]|☒)(?=\h*(?:$|\[|\(|_(?!_*\h*\(Beispiel:)|\d+\h*[-–]|[◆○▲□●·]))"
                               #"(?i)(?:\[x\]|☒)(?=\h*(?:$|\[|\(|_(?!_*\h*\(Beispiel:)|\d|[◆○▲□●·]))")
                             line)))))

(defn- box-counts [s]
  {:ticked (count (re-seq #"(?i)☒|\[x\]" s))
   :empty  (count (re-seq #"☐|\[ \]" s))})

(defn check-page
  "Compares the model's page with Azure's page text. Returns
   {:ok? bool :reasons [..] :added-words {w n} :lost-words {w n} :lost-numbers {n k}
    :boxes {:azure {..} :model {..}} :blanks {:azure n :model n} :images n}.
   :reasons are written so they can be sent back to the model on a retry."
  [azure-text model-text]
  (let [azure-text   (mark-bare-footers azure-text)
        az-words     (word-counts azure-text)
        md-words     (word-counts model-text)
        ;; footer words written out by the model come out of the footer allowance first
        md-to-check  (excess md-words (word-counts (page-comment-text azure-text)))
        ;; text printed on a picture (a sign saying ARBEITSZEIT) may move into its [Image: …] line
        md-with-imgs (merge-with + md-words (word-counts (image-text model-text)))
        [added-words lost-words] (->> (cancel-resplits (excess md-to-check az-words)
                                                       (excess az-words md-with-imgs))
                                      (apply cancel-i-l-misreads))
        decoration   (decoration-words azure-text)
        lost-deco    (into (sorted-map) (filter (comp decoration key)) lost-words)
        lost-words   (into (sorted-map) (remove (comp decoration key)) lost-words)
        ;; a photo's printed number may move into its image line too: [Image: 1 — …], [Image 2: …], (Foto 5)
        lost-numbers (excess (number-counts azure-text)
                             (merge-with + (number-counts model-text) (number-counts (image-text model-text))))
        added-nums   (excess (number-counts model-text)
                             (merge-with + (number-counts azure-text) (number-allowance azure-text)))
        n-lost       (reduce + 0 (vals lost-words))
        n-lost-nums  (reduce + 0 (vals lost-numbers))
        n-added-nums (reduce + 0 (vals added-nums))
        n-dangling   (dangling-ticks model-text)
        foreign      (excess (foreign-letter-words model-text) (foreign-letter-words azure-text))
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
                                  "and remove the others."))

                       (seq foreign)
                       (conj (str "These words contain letters from another alphabet (Cyrillic or Greek): "
                                  (str/join ", " (keys foreign)) ". Write them with the Latin letters of the OCR text.")))]
    {:ok?          (empty? reasons)
     :reasons      reasons
     :added-words  added-words
     :lost-words   lost-words
     :lost-decoration lost-deco
     :lost-numbers lost-numbers
     :added-numbers added-nums
     :dangling-ticks n-dangling
     :foreign-words foreign
     ;; Reported, not checked: Azure's own counts include decoration read as boxes, so a lower
     ;; count from the model is usually a fix, not a loss.
     :boxes        {:azure (box-counts azure-text) :model (box-counts model-text)}
     :blanks       {:azure (count (re-seq #"_{3,}" azure-text))
                    :model (count (re-seq #"_{3,}" model-text))}
     :images       (count (re-seq #"\[Image:" model-text))}))
