(ns teachers-center-backend.conversation.slide-schema
  "Layout-based slide contract shared with the PowerPoint add-in (see doc/slide-UIUX-poc-fe.md §2.2).

   The AI picks a `layout` per slide; OpenAI strict json_schema output guarantees the shape.
   `normalize-*` fixes what a JSON schema cannot express but the add-in's renderer relies on
   (exact-case highlight substrings, table rows as wide as the header), and `violations`
   reports soft-limit breaches for logs and the eval."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ── Limits ───────────────────────────────────────────────────────────────────
;; Item counts are enforced by the schema (maxItems/minItems); char caps are soft —
;; the prompt asks for them, the add-in shrinks/reflows/splits when they are exceeded.

(def limits
  {:bullets     {:max-items 5}
   :pattern     {:min-parts 2 :max-parts 4}
   :comparison  {:max-items 4}
   :vocab-table {:min-columns 2 :max-columns 4 :max-rows 8}
   :examples    {:max-items 5}
   :chars       {:title 60 :item 70 :cell 40}})

;; ── Schema builders ──────────────────────────────────────────────────────────
;; Strict mode requires every object to list all its properties in :required and to set
;; additionalProperties false; `obj` does both so no object can forget either.

(defn- obj [props]
  {:type                 "object"
   :properties           props
   :required             (mapv name (keys props))
   :additionalProperties false})

(defn- string-schema
  ([] {:type "string"})
  ([description] {:type "string" :description description}))

(defn- nullable-string [description]
  {:type ["string" "null"] :description description})

(defn- array-of [items & {:keys [min max description]}]
  (cond-> {:type "array" :items items}
    min         (assoc :minItems min)
    max         (assoc :maxItems max)
    description (assoc :description description)))

(defn- layout-tag [layout]
  {:type "string" :enum [layout]})

(def ^:private slide-title
  (string-schema (str "Short slide title, at most " (get-in limits [:chars :title]) " characters.")))

(def ^:private highlight
  (nullable-string "Exact substring of `text` (same spelling and casing) to emphasise, e.g. the target grammar form. null if nothing to emphasise."))

(def ^:private highlighted-item
  (obj {:text      (string-schema "One short sentence or phrase.")
        :highlight highlight}))

;; ── Layouts ──────────────────────────────────────────────────────────────────

(def layout-schemas
  {"bullets"
   (obj {:layout      (layout-tag "bullets")
         :slide-title slide-title
         :items       (array-of (string-schema) :min 1 :max (get-in limits [:bullets :max-items]))})

   "pattern"
   (obj {:layout      (layout-tag "pattern")
         :slide-title slide-title
         :parts       (array-of (string-schema "One building block of the form, e.g. \"Subject\" or \"don't / doesn't\".")
                                :min (get-in limits [:pattern :min-parts])
                                :max (get-in limits [:pattern :max-parts]))
         :example     highlighted-item})

   "comparison"
   (let [side (obj {:heading  (string-schema "Column heading, e.g. \"Correct\" / \"Wrong\".")
                    :polarity {:type        "string"
                               :enum        ["positive" "negative" "neutral"]
                               :description "positive/negative ONLY for correct vs wrong (left positive, right negative). Two correct forms side by side (e.g. Akkusativ vs Dativ, much vs many) are neutral on both sides."}
                    :items    (array-of highlighted-item :min 1 :max (get-in limits [:comparison :max-items]))})]
     (obj {:layout      (layout-tag "comparison")
           :slide-title slide-title
           :left        side
           :right       side}))

   "vocab-table"
   (obj {:layout      (layout-tag "vocab-table")
         :slide-title slide-title
         :columns     (array-of (string-schema)
                                :min (get-in limits [:vocab-table :min-columns])
                                :max (get-in limits [:vocab-table :max-columns]))
         :rows        (array-of (array-of (string-schema) :description "One cell per column, in column order.")
                                :min 1 :max (get-in limits [:vocab-table :max-rows]))})

   "examples"
   (obj {:layout      (layout-tag "examples")
         :slide-title slide-title
         :items       (array-of (obj {:text        (string-schema "One example sentence.")
                                      :highlight   highlight
                                      :translation (nullable-string "Translation into the students' native language, or null when no native language is set.")})
                                :min 1 :max (get-in limits [:examples :max-items]))})})

(def slide-schema
  {:anyOf (vec (vals layout-schemas))})

(def deck-schema
  (obj {:requirements-not-met (nullable-string "The single clarification question when the request is not specific enough; otherwise null.")
        :title                (nullable-string "Deck title; null when asking for clarification.")
        :subtitle             (nullable-string "Deck subtitle, e.g. \"Level: B1 | 6 slides\"; null when asking for clarification.")
        :slides               {:type        ["array" "null"]
                               :items       slide-schema
                               :description "The content slides (the title slide is built from title/subtitle); null when asking for clarification."}}))

;; Strict mode does not allow anyOf at the root, so an edited slide is wrapped.
(def edit-schema
  (obj {:slide slide-schema}))

(def ^:private schemas
  {:deck {:name "slide_deck" :schema deck-schema}
   :edit {:name "slide_edit" :schema edit-schema}})

(defn text-format
  "Responses API `text.format` for a schema key (:deck or :edit)."
  [schema-key]
  (let [{:keys [name schema]} (get schemas schema-key)]
    (when-not schema
      (throw (ex-info "Unknown response schema" {:schema-key schema-key})))
    {:type "json_schema" :name name :strict true :schema schema}))

;; ── Violations (soft limits) ─────────────────────────────────────────────────

(defn- too-long [kind limit-key s]
  (when (and s (> (count s) (get-in limits [:chars limit-key])))
    {:rule kind :value s}))

(defn- highlight-issue [{:keys [text highlight]}]
  (when (and highlight text (not (str/includes? text highlight)))
    {:rule :highlight-not-in-text :value highlight}))

(defn- slide-violations [{:keys [layout] :as slide}]
  (let [items-of (fn [k] (get slide k))]
    (concat
      [(too-long :title-too-long :title (:slide-title slide))]
      (case layout
        "bullets"
        (concat (map #(too-long :item-too-long :item %) (items-of :items))
                [(when (> (count (items-of :items)) (get-in limits [:bullets :max-items]))
                   {:rule :too-many-items :value (count (items-of :items))})])

        "pattern"
        (let [n (count (:parts slide))]
          [(when (or (< n (get-in limits [:pattern :min-parts])) (> n (get-in limits [:pattern :max-parts])))
             {:rule :parts-count :value n})
           (too-long :item-too-long :item (get-in slide [:example :text]))
           (highlight-issue (:example slide))])

        "comparison"
        (let [l (get-in slide [:left :items])
              r (get-in slide [:right :items])]
          (concat
            [(when (not= (count l) (count r))
               ;; the add-in takes the row count from `left` — extra `right` items are not drawn
               {:rule :comparison-uneven :value [(count l) (count r)]})
             (when (> (max (count l) (count r)) (get-in limits [:comparison :max-items]))
               {:rule :too-many-items :value (max (count l) (count r))})]
            (map #(too-long :item-too-long :item (:text %)) (concat l r))
            (map highlight-issue (concat l r))))

        "vocab-table"
        (let [n-cols (count (:columns slide))]
          (concat
            [(when (> (count (:rows slide)) (get-in limits [:vocab-table :max-rows]))
               {:rule :too-many-rows :value (count (:rows slide))})]
            (map #(when (not= n-cols (count %)) {:rule :row-width :value [n-cols (count %)]}) (:rows slide))
            (map #(too-long :cell-too-long :cell %) (apply concat (:rows slide)))))

        "examples"
        (concat
          [(when (> (count (:items slide)) (get-in limits [:examples :max-items]))
             {:rule :too-many-items :value (count (:items slide))})]
          (map #(too-long :item-too-long :item (:text %)) (:items slide))
          (map highlight-issue (:items slide)))

        [{:rule :unknown-layout :value layout}]))))

(defn violations
  "Soft-limit breaches in a deck's slides, as [{:slide i :layout l :rule kw :value v}]."
  [slides]
  (->> slides
       (map-indexed (fn [i slide]
                      (->> (slide-violations slide)
                           (remove nil?)
                           (map #(assoc % :slide i :layout (:layout slide))))))
       (apply concat)
       vec))

;; ── Normalize ────────────────────────────────────────────────────────────────

(defn- fix-highlight
  "The add-in emphasises `text.indexOf(highlight)` — case-sensitive. Repair a casing mismatch
   using the text's own spelling; drop a highlight that is not in the text at all."
  [{:keys [text highlight] :as item}]
  (cond
    (str/blank? highlight)                          (assoc item :highlight nil)
    (or (nil? text) (str/includes? text highlight)) item
    :else
    (let [i (str/index-of (str/lower-case text) (str/lower-case highlight))]
      (assoc item :highlight (when (and i (<= (+ i (count highlight)) (count text)))
                               (subs text i (+ i (count highlight))))))))

(defn- blank->nil [s]
  (when-not (str/blank? s) s))

(defn- fit-row [n-cols row]
  (vec (take n-cols (concat row (repeat "")))))

(defn normalize-slide [{:keys [layout] :as slide}]
  (case layout
    "pattern"     (update slide :example fix-highlight)
    "comparison"  (-> slide
                      (update-in [:left :items] #(mapv fix-highlight %))
                      (update-in [:right :items] #(mapv fix-highlight %)))
    "vocab-table" (update slide :rows (fn [rows] (mapv #(fit-row (count (:columns slide)) %) rows)))
    "examples"    (update slide :items #(mapv (fn [item] (-> item fix-highlight (update :translation blank->nil))) %))
    slide))

(defn normalize-deck
  "Strict output always carries all root fields; keep only the branch that applies so the wire
   shape matches what the add-in dispatches on: {:requirements-not-met q} or {:title :subtitle :slides}."
  [{:keys [requirements-not-met slides] :as deck}]
  (cond
    (not (str/blank? requirements-not-met))
    {:requirements-not-met requirements-not-met}

    (seq slides)
    (let [v (violations slides)]
      (when (seq v)
        (log/warn "slide soft-limit violations" {:count (count v) :violations v}))
      {:title    (:title deck)
       :subtitle (:subtitle deck)
       :slides   (mapv normalize-slide slides)})

    :else
    (throw (ex-info "AI returned neither slides nor a clarification question"
                    {:error-code :api-error :deck deck}))))
