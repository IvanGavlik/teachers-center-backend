(ns teachers-center-backend.conversation.game-schema
  "Interactive activity (game) contract — what resources/games/*.html read from the saved activity.

   OpenAI strict json_schema output guarantees valid JSON and the shape (a markdown-fenced or
   prose-wrapped answer can't happen, which plain JSON-in-text did with file_search attached).
   `normalize-game` then checks what a schema can't express — e.g. a guess-the-word answer
   must be one word that its definition doesn't give away — and drops items that would break
   or spoil the game, since the activity goes straight to students' phones via the QR code."
  (:require [teachers-center-backend.conversation.slide-schema :refer [obj string-schema nullable-string array-of blank->nil]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ── Limits ───────────────────────────────────────────────────────────────────

(def limits
  {:questions      {:min 1 :max 20}
   :quiz           {:options 4}
   :sentence-words {:min 3 :soft-max 12}})

;; ── Schemas ──────────────────────────────────────────────────────────────────

(defn- activity [item]
  (obj {:title     (string-schema "Short activity title (the topic).")
        :questions (array-of item
                             :min (get-in limits [:questions :min])
                             :max (get-in limits [:questions :max]))}))

(def ^:private hint
  (nullable-string "Short hint for students who get stuck, or null."))

(def ^:private explanation
  (string-schema "Short explanation of the correct answer."))

(def game-schemas
  {:quiz
   (activity (obj {:question    (string-schema "One concise question.")
                   :options     (array-of (string-schema)
                                          :min (get-in limits [:quiz :options])
                                          :max (get-in limits [:quiz :options])
                                          :description "Exactly 4 different options, only one correct.")
                   :correct     {:type        "integer"
                                 :enum        (vec (range (get-in limits [:quiz :options])))
                                 :description "0-based index of the correct option."}
                   :explanation explanation}))

   :true-false
   (activity (obj {:statement   (string-schema "One statement that is clearly true or false.")
                   :answer      {:type "boolean" :description "true if the statement is true."}
                   :explanation explanation}))

   :sentence-ordering
   (activity (obj {:sentence (array-of (string-schema)
                                       :min (get-in limits [:sentence-words :min])
                                       :description "The sentence in its correct order, one word per token, trailing punctuation attached to the last word. Not shuffled.")
                   :hint     hint}))

   :guess-the-word
   (activity (obj {:definition (string-schema "Definition or clue that leads to exactly one word, without containing it.")
                   :word       (string-schema "The answer — a single word, no spaces or hyphens.")
                   :hint       hint}))})

(def ^:private schema-names
  {:quiz              "quiz_activity"
   :true-false        "true_false_activity"
   :sentence-ordering "sentence_ordering_activity"
   :guess-the-word    "guess_the_word_activity"})

(defn schema-key?
  "True when `k` names a game schema (the template's :response-schema)."
  [k]
  (contains? game-schemas k))

(defn text-format
  "Responses API `text.format` for a game schema key."
  [schema-key]
  (let [schema (get game-schemas schema-key)]
    (when-not schema
      (throw (ex-info "Unknown game schema" {:schema-key schema-key})))
    {:type "json_schema" :name (schema-names schema-key) :strict true :schema schema}))

;; ── Item checks ──────────────────────────────────────────────────────────────
;; Each returns a problem keyword (the item is dropped) or nil. Strict output guarantees
;; types and counts, so these only cover content a schema can't express.

(defn- contains-ci? [s sub]
  (and s sub (str/includes? (str/lower-case s) (str/lower-case sub))))

(defmulti ^:private item-problem (fn [schema-key _item] schema-key))

(defmethod item-problem :quiz [_ {:keys [question options correct]}]
  (cond
    (str/blank? question)                                        :blank-question
    (some str/blank? options)                                    :blank-option
    (not= (count options)
          (count (distinct (map (comp str/lower-case str/trim) options)))) :duplicate-options
    (not (and (int? correct) (< -1 correct (count options))))    :correct-out-of-range))

(defmethod item-problem :true-false [_ {:keys [statement answer]}]
  (cond
    (str/blank? statement)   :blank-statement
    (not (boolean? answer))  :answer-not-boolean))

(defmethod item-problem :sentence-ordering [_ {:keys [sentence]}]
  (cond
    (< (count sentence) (get-in limits [:sentence-words :min])) :too-few-words
    (some #(re-find #"\s" %) sentence)                          :token-not-one-word))

(defmethod item-problem :guess-the-word [_ {:keys [definition word]}]
  (cond
    (str/blank? word)                 :blank-word
    ;; the game draws one letter tile per character
    (re-find #"[\s\-]" word)          :not-single-word
    (str/blank? definition)           :blank-definition
    (contains-ci? definition word)    :definition-gives-away-answer))

(defmethod item-problem :default [_ _] nil)

;; ── Clean-up (runs before the checks) ────────────────────────────────────────

(defmulti ^:private clean-item (fn [schema-key _item] schema-key))

(defmethod clean-item :quiz [_ item]
  (update item :options #(mapv str/trim %)))

(defmethod clean-item :sentence-ordering [_ item]
  (-> item
      (update :sentence #(mapv str/trim %))
      (update :hint blank->nil)))

(defmethod clean-item :guess-the-word [_ {:keys [word] :as item}]
  (-> item
      (update :word str/trim)
      ;; a hint that spells out the answer spoils the item — keep the item, lose the hint
      (update :hint #(when-not (contains-ci? % (str/trim word)) (blank->nil %)))))

(defmethod clean-item :default [_ item] item)

;; ── Normalize ────────────────────────────────────────────────────────────────

(defn normalize-game
  "Drops items that would break or spoil the game, cleans the rest, and sets question-count
   from what's kept. Throws (:api-error) when nothing playable is left."
  [schema-key {:keys [title questions]}]
  ;; clean first so stray whitespace (" elephant ") isn't mistaken for a multi-word answer
  (let [checked (map (fn [i q]
                       (let [q (clean-item schema-key q)]
                         {:i i :q q :problem (item-problem schema-key q)}))
                     (range) questions)
        dropped (filter :problem checked)
        kept    (->> checked (remove :problem) (mapv :q))]
    (when (seq dropped)
      (log/warn "game items dropped" {:schema-key schema-key
                                      :dropped    (mapv #(select-keys % [:i :problem :q]) dropped)}))
    (when (= :sentence-ordering schema-key)
      (doseq [q kept :when (> (count (:sentence q)) (get-in limits [:sentence-words :soft-max]))]
        (log/warn "sentence-ordering sentence longer than the prompt asks" {:words (count (:sentence q))})))
    (when (empty? kept)
      (throw (ex-info "AI returned no playable game items"
                      {:error-code :api-error :schema-key schema-key :dropped (count dropped)})))
    {:title          title
     :question-count (count kept)
     :questions      kept}))
