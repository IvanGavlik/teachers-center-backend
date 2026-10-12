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
