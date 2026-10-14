(ns teachers-center-backend.book.convert-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [teachers-center-backend.book.convert :as convert]))

;; A hand-made Azure :analyzeResult: two pages of text and a blank page in between.
(def ^:private page-1 "# Lektion 8\n<figure>\n2 41) 10 a Hören Sie.\nIch bin Lehrerin.\n<!-- PageFooter=\"LEKTION 8 AB 92 zweiundneunzig\" -->\n")
(def ^:private page-3 "b Ordnen Sie zu.\n<!-- PageFooter=\"dreiundneunzig 93 AB LEKTION 8\" -->")

(def ^:private analyze-result
  {:content (str page-1 page-3)
   :pages   [{:spans [{:offset 0 :length (count page-1)}]}
             {:spans []}
             {:spans [{:offset (count page-1) :length (count page-3)}]}]
   ;; "Lehrerin" is handwritten — the book's example answer
   :styles  [{:isHandwritten true :confidence 0.95
              :spans [{:offset (str/index-of page-1 "Lehrerin") :length (count "Lehrerin")}]}]})

(deftest split-pages-cleans-each-page
  (let [[p1 p2 p3] (convert/split-pages analyze-result)]
    (testing "page numbers, lesson and printed page come from the footer"
      (is (= [1 8 92] ((juxt :pdf-page :lesson :printed-page) p1)))
      (is (= [3 8 93] ((juxt :pdf-page :lesson :printed-page) p3))))
    (testing "audio tagged, handwriting labelled, figure tags gone"
      (is (str/includes? (:azure-text p1) "[Audio: CD 2, Track 10] a Hören Sie."))
      (is (str/includes? (:azure-text p1) "Ich bin (Beispiel: Lehrerin)."))
      (is (not (str/includes? (:azure-text p1) "<figure>"))))
    (testing "a blank page doesn't break the book"
      (is (= {:pdf-page 2 :azure-text ""} p2)))))

(deftest the-book-has-a-marker-per-page
  (let [md (convert/azure-book-markdown "Schritte 2" analyze-result)]
    (is (str/includes? md "<!-- Schritte 2 · Lektion 8 · S. 92 · PDF p. 1 -->"))
    (is (str/includes? md "<!-- Schritte 2 · PDF p. 2 -->"))
    (is (str/includes? md "<!-- Schritte 2 · Lektion 8 · S. 93 · PDF p. 3 -->\n\nb Ordnen Sie zu."))))

(deftest a-book-is-cut-into-parts
  (is (= [[1 20] [21 40] [41 45]] (convert/part-ranges 45 20)))
  (is (= [[1 20]] (convert/part-ranges 20 20)))
  (is (= [[1 3]] (convert/part-ranges 3 20))))

(deftest parts-join-into-one-book
  (let [pages (convert/join-parts [{:range [1 3] :result analyze-result}
                                   {:range [4 5] :result nil}
                                   {:range [6 8] :result analyze-result}])]
    (testing "each part keeps its own text, numbered as pages of the whole book"
      (is (= (range 1 9) (map :pdf-page pages)))
      (is (str/includes? (:azure-text (nth pages 5)) "Ich bin (Beispiel: Lehrerin)."))
      (is (= 92 (:printed-page (nth pages 5)))))
    (testing "a part Azure couldn't read becomes empty pages, marked"
      (is (= [{:pdf-page 4 :azure-text "" :ocr-failed? true}
              {:pdf-page 5 :azure-text "" :ocr-failed? true}]
             (subvec pages 3 5))))
    (is (= 1/4 (convert/missing-share pages)))))
