(ns teachers-center-backend.conversation.slide-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.conversation.slide-schema :as ss]))

;; EDN copy of the POC `typical` fixture (teachers-center-powerpoint src/taskpane/poc/fixtures.js)
(def typical
  {:requirements-not-met nil
   :title    "Present Simple: Negatives"
   :subtitle "Level: B1 | 5 slides"
   :slides
   [{:layout "bullets" :slide-title "When do we use it?"
     :items ["Facts that are always true" "Habits and daily routines" "Things we don't like or don't do"]}
    {:layout "pattern" :slide-title "How to form it"
     :parts ["Subject" "don't / doesn't" "base verb"]
     :example {:text "She doesn't drink coffee." :highlight "doesn't"}}
    {:layout "comparison" :slide-title "Correct or wrong?"
     :left  {:heading "Correct" :polarity "positive"
             :items [{:text "She doesn't like tea." :highlight "doesn't"}
                     {:text "They don't play tennis." :highlight "don't"}]}
     :right {:heading "Wrong" :polarity "negative"
             :items [{:text "She don't like tea." :highlight "don't"}
                     {:text "They doesn't play tennis." :highlight "doesn't"}]}}
    {:layout "vocab-table" :slide-title "Food vocabulary"
     :columns ["Word" "Meaning" "Example"]
     :rows [["bread" "food made from flour" "I buy bread every day."]
            ["cheese" "food made from milk" "He doesn't eat cheese."]]}
    {:layout "examples" :slide-title "Examples"
     :items [{:text "I don't work on Sundays." :highlight "don't" :translation nil}
             {:text "My brother doesn't eat meat." :highlight "doesn't" :translation nil}]}]})

;; ── Schema invariants (strict mode rejects the request otherwise) ────────────

(defn- objects-in [schema]
  (->> (tree-seq coll? #(if (map? %) (vals %) (seq %)) schema)
       (filter #(and (map? %) (= "object" (:type %))))))

(deftest every-object-is-strict
  (doseq [[k schema] {:deck ss/deck-schema :edit ss/edit-schema}
          o (objects-in schema)]
    (testing (str k " " (keys (:properties o)))
      (is (false? (:additionalProperties o)))
      (is (= (set (map name (keys (:properties o)))) (set (:required o)))))))

(deftest no-max-length
  ;; OpenAI enforces maxLength by cutting the string mid-word — soft caps live in the prompt instead
  (is (not-any? #(and (map? %) (contains? % :maxLength))
                (tree-seq coll? #(if (map? %) (vals %) (seq %)) ss/deck-schema))))

(deftest every-layout-has-a-drawer-name
  (is (= #{"bullets" "pattern" "comparison" "vocab-table" "examples"}
         (set (keys ss/layout-schemas)))))

(deftest text-format-shape
  (is (= {:type "json_schema" :name "slide_deck" :strict true}
         (dissoc (ss/text-format :deck) :schema)))
  (is (thrown? clojure.lang.ExceptionInfo (ss/text-format :nope))))

;; ── normalize ────────────────────────────────────────────────────────────────

(deftest typical-deck-passes-through
  (is (= (dissoc typical :requirements-not-met) (ss/normalize-deck typical)))
  (is (empty? (ss/violations (:slides typical)))))

(deftest clarification-branch
  (is (= {:requirements-not-met "Which topic?"}
         (ss/normalize-deck {:requirements-not-met "Which topic?" :title nil :subtitle nil :slides nil}))))

(deftest empty-answer-is-an-error
  (is (thrown? clojure.lang.ExceptionInfo
               (ss/normalize-deck {:requirements-not-met nil :title nil :subtitle nil :slides nil}))))

(deftest highlight-repair
  (let [ex (fn [h] (-> (ss/normalize-slide {:layout "examples" :slide-title "t"
                                            :items [{:text "She doesn't drink." :highlight h :translation ""}]})
                       :items first))]
    (testing "exact match kept"      (is (= "doesn't" (:highlight (ex "doesn't")))))
    (testing "casing taken from text" (is (= "doesn't" (:highlight (ex "Doesn't")))))
    (testing "not in text → nil"     (is (nil? (:highlight (ex "does not")))))
    (testing "blank → nil"           (is (nil? (:highlight (ex "")))))
    (testing "blank translation → nil" (is (nil? (:translation (ex "doesn't")))))))

(deftest table-rows-fit-columns
  (is (= [["a" "b" ""] ["c" "d" "e"]]
         (:rows (ss/normalize-slide {:layout "vocab-table" :slide-title "t" :columns ["1" "2" "3"]
                                     :rows [["a" "b"] ["c" "d" "e" "extra"]]})))))

;; ── violations ───────────────────────────────────────────────────────────────

(deftest violations-found
  (let [rules (fn [slide] (set (map :rule (ss/violations [slide]))))]
    (is (contains? (rules {:layout "comparison" :slide-title "t"
                           :left  {:heading "a" :polarity "positive" :items [{:text "x" :highlight nil}]}
                           :right {:heading "b" :polarity "negative" :items [{:text "y" :highlight nil}
                                                                            {:text "z" :highlight nil}]}})
                   :comparison-uneven))
    (is (contains? (rules {:layout "bullets" :slide-title (apply str (repeat 61 "x")) :items ["a"]})
                   :title-too-long))
    (is (contains? (rules {:layout "examples" :slide-title "t"
                           :items [{:text "abc" :highlight "zzz" :translation nil}]})
                   :highlight-not-in-text))
    (is (contains? (rules {:layout "vocab-table" :slide-title "t" :columns ["a" "b"] :rows [["1"]]})
                   :row-width))
    (is (= [{:rule :unknown-layout :value "chart" :slide 0 :layout "chart"}]
           (ss/violations [{:layout "chart" :slide-title "t"}])))))
