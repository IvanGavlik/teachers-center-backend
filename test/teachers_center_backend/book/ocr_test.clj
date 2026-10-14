(ns teachers-center-backend.book.ocr-test
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.book.ocr :as ocr])
  (:import [java.io File]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]))

(defn- blank-pdf
  "A PDF of n empty pages."
  ^File [n]
  (let [f (File/createTempFile "ocr-test-" ".pdf")]
    (with-open [doc (PDDocument.)]
      (dotimes [_ n] (.addPage doc (PDPage.)))
      (.save doc f))
    f))

(defn- fake-azure
  "An :analyze stand-in: one line of text per page of the part it gets; throws for parts whose
   page count is in `failing` (each part is told apart by its size)."
  [failing calls]
  (fn [^File part]
    (let [n (with-open [d (Loader/loadPDF part)] (.getNumberOfPages d))]
      (swap! calls conj n)
      (when (failing n) (throw (ex-info "Azure analyze timed out" {})))
      (let [texts (map #(str "page " % " of " n "\n") (range 1 (inc n)))]
        {:content (apply str texts)
         :pages   (first (reduce (fn [[ps off] t] [(conj ps {:spans [{:offset off :length (count t)}]}) (+ off (count t))])
                                 [[] 0] texts))
         :styles  []}))))

(deftest a-book-is-read-in-parts
  (let [pdf   (blank-pdf 45)
        calls (atom [])
        seen  (atom [])
        pages (ocr/analyze-book nil pdf {:analyze (fake-azure #{} calls) :on-part #(swap! seen conj %)})]
    (testing "45 pages → parts of 20, 20 and 5, each sent once"
      (is (= [5 20 20] (sort @calls))))
    (testing "the pages come back in book order, each with its own part's text"
      (is (= (range 1 46) (map :pdf-page pages)))
      (is (= "page 1 of 20" (:azure-text (nth pages 20))))
      (is (= "page 5 of 5" (:azure-text (nth pages 44)))))
    (testing "progress counts pages and ends at the whole book"
      (is (= 45 (apply max @seen))))
    (.delete pdf)))

(deftest a-failing-part-is-tried-again-then-left-empty
  (let [pdf   (blank-pdf 105)
        calls (atom [])
        pages (ocr/analyze-book nil pdf {:analyze (fake-azure #{5} calls)})]
    (is (= 2 (count (filter #{5} @calls))) "the 5-page part was tried twice")
    (is (= 100 (count (remove :ocr-failed? pages))))
    (is (every? :ocr-failed? (subvec pages 100 105)))
    (.delete pdf)))

(deftest too-many-missing-pages-fail-the-book
  (testing "half the book unread is not a usable book"
    (let [pdf (blank-pdf 25)]
      (is (thrown-with-msg? Exception #"too many pages"
                            (ocr/analyze-book nil pdf {:analyze (fake-azure #{20} (atom []))})))
      (.delete pdf))))

(deftest parts-already-read-are-not-sent-again
  (ocr/clear-part-cache-for-tests!)
  (let [pdf   (blank-pdf 45)
        calls (atom [])]
    (testing "first upload: the 5-page part fails, the rest is read"
      (ocr/analyze-book nil pdf {:sha "sha-a" :analyze (fake-azure #{5} calls)}))
    (reset! calls [])
    (testing "the retry only reads the part that failed"
      (let [pages (ocr/analyze-book nil pdf {:sha "sha-a" :analyze (fake-azure #{} calls)})]
        (is (= [5] @calls))
        (is (= (range 1 46) (map :pdf-page pages)))
        (is (not-any? :ocr-failed? pages))))
    (reset! calls [])
    (testing "another PDF shares nothing"
      (ocr/analyze-book nil pdf {:sha "sha-b" :analyze (fake-azure #{} calls)})
      (is (= [5 20 20] (sort @calls))))
    (.delete pdf)))
