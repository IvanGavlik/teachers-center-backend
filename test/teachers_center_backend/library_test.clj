(ns teachers-center-backend.library-test
  "Also makes sure library.clj (the upload flow) compiles — the other tests don't load it."
  (:require [clojure.test :refer [deftest is testing]]
            [teachers-center-backend.library :as library]))

(def ^:private book-title #'library/book-title)
(def ^:private client-ip #'library/client-ip)

(deftest the-title-has-no-pdf-extension
  (is (= "Schritte 2" (book-title "x.pdf" "Schritte 2.pdf")) "the add-in sends the file name")
  (is (= "Schritte 2" (book-title "Schritte 2.PDF" nil)))
  (is (= "Book" (book-title nil "  "))))

(deftest the-ip-comes-from-the-proxy-header
  (is (= "203.0.113.5" (client-ip {:headers {"x-forwarded-for" "203.0.113.5, 10.0.0.1"} :remote-addr "10.0.0.2"})))
  (testing "without a proxy"
    (is (= "10.0.0.2" (client-ip {:headers {} :remote-addr "10.0.0.2"})))))

(defn- upload-request [^java.io.File pdf]
  (let [tmp (java.io.File/createTempFile "upload-" ".pdf")]
    (clojure.java.io/copy pdf tmp)
    {:multipart-params {"file" {:filename "Book.pdf" :content-type "application/pdf"
                                :tempfile tmp :size (.length tmp)}}
     :headers          {"x-forwarded-for" "203.0.113.9"}
     :book-config      {:daily-pages 1000 :ip-daily-books 10}}))

(deftest the-same-pdf-uploaded-twice-joins-the-running-job
  (let [pdf (java.io.File/createTempFile "dedupe-" ".pdf")]
    (with-open [doc (org.apache.pdfbox.pdmodel.PDDocument.)]
      (.addPage doc (org.apache.pdfbox.pdmodel.PDPage.))
      ;; a unique PDF per run, so an earlier run's job doesn't count
      (.setTitle (.getDocumentInformation doc) (str (random-uuid)))
      (.save doc pdf))
    (with-redefs [library/run-book-job (fn [& _] (Thread/sleep 500))]
      (let [first-id  (get-in (library/upload-handler (upload-request pdf)) [:body :id])
            second-id (get-in (library/upload-handler (upload-request pdf)) [:body :id])]
        (is (some? first-id))
        (is (= first-id second-id))))
    (.delete pdf)))

(def ^:private text-layer? #'library/text-layer?)
(def ^:private run-book-job #'library/run-book-job)

(defn- pdf-with-text
  "A PDF of n pages, each with `line` written on it (or blank when line is nil) — digital vs scan."
  ^java.io.File [n line]
  (let [f (java.io.File/createTempFile "text-layer-" ".pdf")]
    (with-open [doc (org.apache.pdfbox.pdmodel.PDDocument.)]
      (dotimes [_ n]
        (let [page (org.apache.pdfbox.pdmodel.PDPage.)]
          (.addPage doc page)
          (when line
            (with-open [cs (org.apache.pdfbox.pdmodel.PDPageContentStream. doc page)]
              (doto cs
                (.beginText)
                (.setFont (org.apache.pdfbox.pdmodel.font.PDType1Font.
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts$FontName/HELVETICA) 12)
                (.newLineAtOffset 50 700)
                (.showText line)
                (.endText))))))
      (.save doc f))
    f))

(deftest a-digital-pdf-has-a-text-layer-a-scan-has-not
  (let [digital (pdf-with-text 30 "Lektion 8: Beruf und Arbeit. Ich bin Lehrerin von Beruf.")
        scan    (pdf-with-text 30 nil)]
    (is (true? (text-layer? digital)))
    (is (false? (text-layer? scan)))
    (testing "a stamped page number alone doesn't make a scan digital"
      (let [numbered (pdf-with-text 30 "92")]
        (is (false? (text-layer? numbered)))
        (.delete numbered)))
    (.delete digital)
    (.delete scan)))

(deftest a-scan-without-azure-text-fails-instead-of-becoming-an-empty-book
  (let [scan (pdf-with-text 3 nil)]
    (is (thrown-with-msg? Exception #"couldn't read this book"
                          (run-book-job {:openai-client nil :azure-config nil :book-config {}}
                                        scan "Scan" (str (random-uuid)) "job-x")))
    (is (not (.exists scan)) "the job still deletes its copy of the PDF")))
