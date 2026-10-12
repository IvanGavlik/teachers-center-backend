(ns teachers-center-backend.library
  "Attached books (doc/book-production-plan.md, Phase 1).

   POST /library/upload checks the PDF and starts a background job; GET /library/book-status/:job-id
   reports it. The job runs Azure OCR (a scanned book has no text layer, so sending the PDF to OpenAI
   gave an empty index), turns the result into one Markdown file and indexes that in a vector store:

     ocr → indexing → usable   (or failed)

   If Azure isn't configured or fails, the job falls back to sending the PDF itself, as before —
   that still works for books with a text layer."
  (:require [teachers-center-backend.openapi.file-search :as file-search]
            [teachers-center-backend.azure.layout :as azure]
            [teachers-center-backend.book.convert :as convert]
            [teachers-center-backend.book.jobs :as jobs]
            [ring.util.response :refer [response]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [org.apache.pdfbox Loader]))

;; Also raise http-kit's :max-body in system.clj and the add-in's BOOK_MAX_SIZE_BYTES with this.
(def ^:private max-file-size-mb 60)
(def ^:private max-pages 300)

;; Retention — both are stated in the privacy policy (teacher-assistant/privacy-policy.html),
;; change them together. The index (vector store) expires 3 days after it was last used;
;; the uploaded file is deleted by OpenAI 7 days after upload. A book only lives for one taskpane
;; session, so 7 days outlasts any real use of it.
(def ^:private book-index-expires-after-days 3)
(def ^:private book-file-expires-after-seconds (* 7 24 60 60))

(def ^:private index-timeout-ms (* 5 60 1000))

(defn- pdf-content-type? [content-type]
  (= content-type "application/pdf"))

(defn- page-count
  "Pages in the PDF, or nil if PDFBox can't open it (not a PDF, damaged, password-protected)."
  [^File pdf]
  (try
    (with-open [doc (Loader/loadPDF pdf)]
      (.getNumberOfPages doc))
    (catch Exception e
      (log/warn "Cannot open uploaded PDF" {:error (ex-message e)})
      nil)))

(defn- book-title [filename book-name]
  (or (some-> book-name str/trim not-empty)
      (some-> filename (str/replace #"(?i)\.pdf$" "") str/trim not-empty)
      "Book"))

(defn- safe-filename [title ext]
  (str (str/replace title #"[\\/:*?\"<>|]" "_") ext))

(defn- wait-for-index
  "Blocks until OpenAI has indexed the vector store's files. Throws if it expires, a file fails or it
   takes longer than index-timeout-ms."
  [openai-client vector-store-id]
  (let [deadline (+ (System/currentTimeMillis) index-timeout-ms)]
    (loop []
      (let [store (file-search/retrieve-vector-store openai-client vector-store-id)]
        (cond
          (pos? (or (get-in store [:file_counts :failed]) 0))
          (throw (ex-info "OpenAI could not index the book" {:file-counts (:file_counts store)}))

          (= "completed" (:status store)) store

          (= "expired" (:status store))
          (throw (ex-info "The book's index expired while indexing" {}))

          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Indexing the book timed out" {:status (:status store)}))

          :else (do (Thread/sleep 2000) (recur)))))))

(defn- index-file!
  "Uploads one file to OpenAI, puts it in a new vector store and waits until it's searchable.
   Returns the vector store id."
  [openai-client {:keys [file filename mime-type title]}]
  (let [uploaded (file-search/upload-file openai-client {:file-path             (.getPath ^File file)
                                                          :filename              filename
                                                          :mime-type             mime-type
                                                          :expires-after-seconds book-file-expires-after-seconds})
        store    (file-search/create-vector-store openai-client
                   {:name               title
                    :file-ids           [(:id uploaded)]
                    :expires-after-days book-index-expires-after-days})]
    (wait-for-index openai-client (:id store))
    (:id store)))

(defn- azure-text-file
  "Azure OCR → the book as Markdown in a temp file. Returns the file."
  [azure-config ^File pdf title]
  (let [md (File/createTempFile "book-" ".md")]
    (spit md (convert/azure-book-markdown title (azure/analyze azure-config pdf)) :encoding "UTF-8")
    md))

(defn- run-book-job
  "The job: Azure text if possible, the PDF itself if not. Deletes its temp files."
  [{:keys [openai-client azure-config]} ^File pdf title job-id]
  (let [t0 (System/currentTimeMillis)
        md (when (azure/configured? azure-config)
             (try
               (jobs/update-job! job-id {:status "ocr"})
               (azure-text-file azure-config pdf title)
               (catch Exception e
                 (log/warn e "Azure OCR failed, sending the PDF instead" {:job-id job-id})
                 nil)))]
    (try
      (jobs/update-job! job-id {:status "indexing"})
      (let [store-id (if md
                       (index-file! openai-client {:file      md
                                                   :filename  (safe-filename title ".md")
                                                   :mime-type "text/markdown"
                                                   :title     title})
                       (index-file! openai-client {:file      pdf
                                                   :filename  (safe-filename title ".pdf")
                                                   :mime-type "application/pdf"
                                                   :title     title}))]
        (jobs/update-job! job-id {:status "usable" :vector-store-id store-id :method (if md "azure" "pdf")})
        (log/info "Book ready" {:job-id job-id :method (if md "azure" "pdf")
                                :pages (:pages-total (jobs/get-job job-id))
                                :ms (- (System/currentTimeMillis) t0)}))
      (finally
        (some-> md .delete)
        (.delete pdf)))))

(defn- job-view
  "What the add-in sees of a job."
  [job]
  (select-keys job [:id :status :book-name :pages-total :vector-store-id :error]))

(defn upload-handler [request]
  (let [file-part (get-in request [:multipart-params "file"])
        book-name (get-in request [:multipart-params "book-name"])]
    (cond
      (nil? file-part)
      {:status 400 :body {:error "Missing file"}}

      (not (pdf-content-type? (:content-type file-part)))
      {:status 400 :body {:error "Only PDF files are supported"}}

      (> (:size file-part) (* max-file-size-mb 1024 1024))
      {:status 400 :body {:error (str "File too large (" max-file-size-mb " MB max)")}}

      :else
      ;; Ring deletes its multipart temp files on its own schedule — the job works on its own copy.
      (let [pdf   (doto (File/createTempFile "book-" ".pdf") (as-> f (io/copy (:tempfile file-part) f)))
            pages (page-count pdf)
            title (book-title (:filename file-part) book-name)]
        (cond
          (nil? pages)
          (do (.delete pdf) {:status 400 :body {:error "This PDF can't be opened (damaged or password-protected)"}})

          (> pages max-pages)
          (do (.delete pdf) {:status 400 :body {:error (str "Book too long (" max-pages " pages max)")}})

          :else
          (let [job-id (jobs/start-job! {:status "queued" :book-name title :pages-total pages}
                                        (partial run-book-job
                                                 {:openai-client (:openapi-client request)
                                                  :azure-config  (:azure-config request)}
                                                 pdf title))]
            (log/info "Book upload started" {:job-id job-id :pages pages :mb (quot (:size file-part) (* 1024 1024))})
            (response (job-view (jobs/get-job job-id)))))))))

(defn status-handler [_request job-id]
  (if-let [job (jobs/get-job job-id)]
    (response (job-view job))
    ;; Unknown id: a restart lost the job, or it's older than a day.
    {:status 404 :body {:error "Unknown book upload" :status "failed"}}))
