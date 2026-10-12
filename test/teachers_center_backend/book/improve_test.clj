(ns teachers-center-backend.book.improve-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [teachers-center-backend.book.improve :as improve]))

(def ^:private page {:pdf-page 3 :azure-text "Er ist müde.\nEr\nKaffee" :image-url "data:image/jpeg;base64,x"})

(defn- fake-model
  "A repair fn that answers with `answers` in turn and records the feedback it got."
  [answers calls]
  (let [left (atom answers)]
    (fn [_azure-text _image-url feedback]
      (swap! calls conj feedback)
      (let [a (first @left)]
        (swap! left rest)
        (if (instance? Exception a)
          (throw a)
          {:text a :usage {:in 10 :out 20} :ms 1})))))

(deftest a-good-answer-is-used
  (let [calls (atom [])
        r     (improve/convert-page (fake-model ["Er ist müde. Er ____ Kaffee."] calls) page)]
    (is (= :ok (:status r)))
    (is (= "Er ist müde. Er ____ Kaffee." (:final-text r)))
    (is (= [nil] @calls))))

(deftest a-bad-answer-gets-one-retry-with-the-reasons
  (let [calls (atom [])
        r     (improve/convert-page (fake-model ["Er ist müde. Er trinkt Kaffee." "Er ist müde. Er ____ Kaffee."] calls) page)]
    (is (= :retried-ok (:status r)))
    (is (= {:in 20 :out 40} (:usage r)))
    (testing "the retry is told what the check found"
      (is (str/includes? (first (second @calls)) "trinkt")))))

(deftest two-bad-answers-fall-back-to-azure-text
  (let [r (improve/convert-page (fake-model ["Er trinkt Kaffee." "Er trinkt Kaffee."] (atom [])) page)]
    (is (= :fallback (:status r)))
    (is (= (:azure-text page) (:final-text r)))))

(deftest a-failing-request-falls-back-too
  (let [r (improve/convert-page (fake-model [(ex-info "rate limited" {:status 429})
                                             (ex-info "rate limited" {:status 429})] (atom [])) page)]
    (is (= :error (:status r)))
    (is (= (:azure-text page) (:final-text r)))))
