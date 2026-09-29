(ns teachers-center-backend.conversation.game-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [teachers-center-backend.conversation.core :as core]
            [teachers-center-backend.conversation.game-schema :as gs]))

;; ── Schema invariants (strict mode rejects the request otherwise) ────────────

(defn- nodes [schema]
  (tree-seq coll? #(if (map? %) (vals %) (seq %)) schema))

(deftest every-object-is-strict
  (doseq [[k schema] gs/game-schemas
          o (filter #(and (map? %) (= "object" (:type %))) (nodes schema))]
    (testing (str k " " (keys (:properties o)))
      (is (false? (:additionalProperties o)))
      (is (= (set (map name (keys (:properties o)))) (set (:required o)))))))

(deftest no-max-length
  (is (not-any? #(and (map? %) (contains? % :maxLength))
                (mapcat nodes (vals gs/game-schemas)))))

(deftest text-format-shape
  (is (= {:type "json_schema" :name "true_false_activity" :strict true}
         (dissoc (gs/text-format :true-false) :schema)))
  (is (thrown? clojure.lang.ExceptionInfo (gs/text-format :deck))))

;; ── Templates ────────────────────────────────────────────────────────────────

(defn- template [resource] (-> resource io/resource slurp edn/read-string))

(deftest every-game-template-has-a-schema-and-book-note
  (doseq [[mode resource] core/interactivity-mode->resource]
    (testing mode
      (let [t (template resource)]
        (is (gs/schema-key? (get-in t [:config :response-schema])))
        (is (string? (:file-search-instructions t)))
        (testing "no slide wording in a game's book note"
          (is (not (re-find #"(?i)slide" (:file-search-instructions t)))))))))

(deftest slide-templates-have-a-book-note
  (doseq [resource ["conversation-content.edn" "conversation-edit-content.edn"]]
    (is (string? (:file-search-instructions (template resource))) resource)))

;; ── normalize ────────────────────────────────────────────────────────────────

(deftest valid-game-passes-through
  (let [game {:title "Donna's day"
              :questions [{:statement "Donna is on the boat." :answer false :explanation "She is at a table."}
                          {:statement "Donna is with her family." :answer true :explanation "The book says so."}]}]
    (is (= (assoc game :question-count 2) (gs/normalize-game :true-false game)))))

(deftest quiz-checks
  (let [q  {:question "Pick one" :options ["a" "b" "c" "d"] :correct 1 :explanation "b"}
        n  (fn [& qs] (:questions (gs/normalize-game :quiz {:title "t" :questions (vec qs)})))]
    (testing "valid kept, options trimmed" (is (= ["a" "b" "c" "d"] (:options (first (n (assoc q :options [" a" "b " "c" "d"])))))))
    (testing "duplicate options dropped"   (is (= 1 (count (n q (assoc q :options ["a" "A" "c" "d"]))))))
    (testing "correct out of range dropped" (is (= 1 (count (n q (assoc q :correct 4))))))))

(deftest guess-the-word-checks
  (let [q {:definition "A large grey animal with a trunk." :word "elephant" :hint "starts with 'e'"}
        n (fn [& qs] (:questions (gs/normalize-game :guess-the-word {:title "t" :questions (vec qs)})))]
    (testing "phrase dropped"             (is (= 1 (count (n q (assoc q :word "ice cream"))))))
    (testing "hyphenated dropped"         (is (= 1 (count (n q (assoc q :word "T-shirt"))))))
    (testing "definition gives it away"   (is (= 1 (count (n q (assoc q :definition "An Elephant is big."))))))
    (testing "hint with the answer → nil" (is (nil? (:hint (first (n (assoc q :hint "It's an elephant")))))))
    (testing "word trimmed"               (is (= "elephant" (:word (first (n (assoc q :word " elephant ")))))))))

(deftest sentence-ordering-checks
  (let [q {:sentence ["Donna" "is" "sitting" "under" "the" "trees."] :hint ""}
        n (fn [& qs] (:questions (gs/normalize-game :sentence-ordering {:title "t" :questions (vec qs)})))]
    (testing "blank hint → nil"       (is (nil? (:hint (first (n q))))))
    (testing "too few words dropped"  (is (= 1 (count (n q (assoc q :sentence ["Hi" "there."]))))))
    (testing "multi-word token dropped" (is (= 1 (count (n q (assoc q :sentence ["Donna is" "sitting" "here."]))))))))

(deftest question-count-follows-kept-items
  (is (= 1 (:question-count (gs/normalize-game :true-false
                                               {:title "t"
                                                :questions [{:statement "" :answer true :explanation "x"}
                                                            {:statement "ok" :answer true :explanation "x"}]})))))

(deftest nothing-playable-is-an-error
  (let [e (try (gs/normalize-game :true-false {:title "t" :questions [{:statement " " :answer true :explanation "x"}]})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :api-error (:error-code (ex-data e))))))
