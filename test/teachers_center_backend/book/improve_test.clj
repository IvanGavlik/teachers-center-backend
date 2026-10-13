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

(defn- failing-then [errors value]
  (let [left (atom errors)]
    (fn []
      (if-let [e (first @left)]
        (do (swap! left rest) (throw e))
        value))))

;; make-request wraps the HTTP error, so the status is one cause down
(defn- wrapped [data]
  (ex-info "OpenAI API request failed" {:error-code :network-error} (ex-info "OpenAI API returned error" data)))

(deftest a-rate-limit-is-waited-out
  (let [sleeps (atom [])
        f      (failing-then [(wrapped {:status 429 :body "rate_limit_exceeded"})
                              (wrapped {:status 429 :body "rate_limit_exceeded"})] :answer)]
    (is (= :answer (improve/call-with-backoff f #(swap! sleeps conj %))))
    (is (= [5000 10000] @sleeps))))

(deftest a-rate-limit-that-lasts-gives-up
  (let [sleeps (atom [])
        f      (failing-then (repeat 10 (wrapped {:status 429})) :answer)]
    (is (thrown? Exception (improve/call-with-backoff f #(swap! sleeps conj %))))
    (is (= [5000 10000 20000 40000] @sleeps))))

(deftest other-errors-are-not-waited-out
  (doseq [data [{:status 429 :body "{\"error\":{\"code\":\"insufficient_quota\"}}"}
                {:status 500}]]
    (let [sleeps (atom [])]
      (is (thrown? Exception (improve/call-with-backoff (failing-then [(wrapped data)] :answer)
                                                        #(swap! sleeps conj %))))
      (is (empty? @sleeps)))))

(deftest improve-pages-returns-every-page
  ;; On Java 17 a reflective (.submit pool …) picked submit(Runnable) and every result was nil,
  ;; so the improved book came out empty. Run the pool for real; the model is faked.
  (let [pdf (java.io.File/createTempFile "improve-test-" ".pdf")]
    (try
      (with-open [doc (org.apache.pdfbox.pdmodel.PDDocument.)]
        (.addPage doc (org.apache.pdfbox.pdmodel.PDPage.))
        (.addPage doc (org.apache.pdfbox.pdmodel.PDPage.))
        (.save doc pdf))
      (with-redefs [improve/repair-page (fn [_client _opts azure-text _image _feedback]
                                          {:text azure-text :usage {:in 1 :out 1} :ms 1})]
        (let [r (improve/improve-pages nil pdf [{:pdf-page 1 :azure-text "Er ist müde."}
                                               {:pdf-page 2 :azure-text ""}]
                                       {:parallel 2})]
          (is (= [1 2] (map :pdf-page r)))
          (is (= [:ok :blank] (map :status r)))
          (is (= ["Er ist müde." ""] (map :final-text r)))))
      (finally (.delete pdf)))))

(deftest page-images-are-plain-jpegs
  (testing "a page with a transparent image (the JPEG writer would write nothing) and a page without images"
    (with-open [doc (org.apache.pdfbox.pdmodel.PDDocument.)]
      (let [argb (java.awt.image.BufferedImage. 1200 800 java.awt.image.BufferedImage/TYPE_INT_ARGB)
            img  (org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory/createFromImage doc argb)
            p1   (org.apache.pdfbox.pdmodel.PDPage.)]
        (.addPage doc p1)
        (with-open [cs (org.apache.pdfbox.pdmodel.PDPageContentStream. doc p1)]
          (.drawImage cs img (float 0) (float 0)))
        (.addPage doc (org.apache.pdfbox.pdmodel.PDPage.))
        (doseq [i [0 1]]
          (let [url (improve/page-image-url doc i)]
            (is (str/starts-with? url "data:image/jpeg;base64,"))
            (is (> (count url) 1000) "a real image, not an empty one")))))))
