(ns teachers-center-backend.library
  "Attached books (doc/book-production-plan.md, Phase 1).

   POST /library/upload checks the PDF and starts a background job; GET /library/book-status/:job-id
   reports it. The job runs Azure OCR (a scanned book has no text layer, so sending the PDF to OpenAI
   gave an empty index), turns the result into one Markdown file and indexes that in a vector store:

     ocr → indexing → usable → improving → ready   (or failed before usable)
     indexing → ready                              (the exact same PDF was converted before: book.cache)

   From usable on the book works with Azure's text. improving (Phase 2, book.improve) repairs the
   pages in the background and swaps the result into the same vector store; if that fails the book
   simply stays usable on Azure's text.

   If Azure isn't configured or fails, the job falls back to sending the PDF itself, as before —
   that still works for books with a text layer, and there's no improving step."
  (:require [teachers-center-backend.openapi.file-search :as file-search]
            [teachers-center-backend.azure.layout :as azure]
            [teachers-center-backend.book.convert :as convert]
            [teachers-center-backend.book.jobs :as jobs]
            [teachers-center-backend.book.improve :as improve]
            [teachers-center-backend.book.cache :as cache]
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
;; the uploaded file is deleted by OpenAI 29 days after upload. A book only lives for one taskpane
;; session, but the converted text is reused for 29 days when someone uploads the exact same PDF
;; again (book.cache). OpenAI allows at most 30 days.
(def ^:private book-index-expires-after-days 3)
(def ^:private book-file-expires-after-seconds (* 29 24 60 60))

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

(defn- upload-book-file!
  "Uploads one file of the book to OpenAI (deleted by OpenAI after book-file-expires-after-seconds).
   Returns the file id."
  [openai-client {:keys [file filename mime-type]}]
  (:id (file-search/upload-file openai-client {:file-path             (.getPath ^File file)
                                               :filename              filename
                                               :mime-type             mime-type
                                               :expires-after-seconds book-file-expires-after-seconds})))

(defn- index-file!
  "Uploads one file to OpenAI, puts it in a new vector store and waits until it's searchable.
   Returns {:vector-store-id :file-id}."
  [openai-client {:keys [title] :as file}]
  (let [file-id (upload-book-file! openai-client file)
        store   (file-search/create-vector-store openai-client
                  {:name               title
                   :file-ids           [file-id]
                   :expires-after-days book-index-expires-after-days})]
    (wait-for-index openai-client (:id store))
    {:vector-store-id (:id store) :file-id file-id}))

(defn- markdown-file
  "The book's Markdown in a temp file."
  ^File [markdown]
  (doto (File/createTempFile "book-" ".md") (spit markdown :encoding "UTF-8")))

(defn- improve-book!
  "Phase 2: the vision model repairs every page in the background, then the repaired book replaces
   Azure's text in the same vector store — the id doesn't change, so a conversation already using
   the book keeps working. Any failure leaves the book on Azure's text."
  [{:keys [openai-client book-config]} ^File pdf title sha pages {:keys [vector-store-id file-id]} job-id]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [done    (atom 0)
            results (improve/improve-pages openai-client pdf pages
                                           (assoc (select-keys book-config [:model :effort :parallel])
                                                  :on-page #(jobs/update-job! job-id {:pages-done (swap! done inc)})))
            md      (markdown-file (convert/book-markdown title (map #(assoc % :text (:final-text %)) results)))]
        (try
          (let [new-file-id (upload-book-file! openai-client {:file md :filename (cache/cached-filename (safe-filename title "") sha)
                                                              :mime-type "text/markdown"})]
            (file-search/add-file-to-vector-store openai-client vector-store-id new-file-id)
            (wait-for-index openai-client vector-store-id)
            ;; only now: until the new file is searchable, the Azure text keeps the book usable
            (file-search/remove-file-from-vector-store openai-client vector-store-id file-id)
            (file-search/delete-file openai-client file-id)
            ;; only finished books go into the cache, never Azure-only text
            (cache/remember! sha new-file-id (+ (quot (System/currentTimeMillis) 1000) book-file-expires-after-seconds))
            (jobs/update-job! job-id {:status "ready"})
            (log/info "Book improved" (merge {:job-id job-id :pages (count results)
                                              :ms (- (System/currentTimeMillis) t0)}
                                             (improve/summary results))))
          (finally (.delete md))))
      (catch Exception e
        (log/error e "Improving the book failed, it stays on Azure's text" {:job-id job-id})
        (jobs/update-job! job-id {:status "usable" :improve-error (ex-message e)})))))

(defn- from-cache!
  "If the exact same PDF was converted before (book.cache), gives this teacher a new vector store
   with that file and returns true. Returns false on a miss or if the cached file is gone."
  [openai-client title sha job-id]
  (when-let [file-id (cache/lookup openai-client sha)]
    (try
      (jobs/update-job! job-id {:status "indexing"})
      (let [store (file-search/create-vector-store openai-client
                    {:name title :file-ids [file-id] :expires-after-days book-index-expires-after-days})]
        (wait-for-index openai-client (:id store))
        (jobs/update-job! job-id {:status "ready" :vector-store-id (:id store) :method "cache"})
        (log/info "Book from cache" {:job-id job-id :sha (subs sha 0 12)})
        true)
      (catch Exception e
        (log/warn e "Cached book unusable, converting again" {:job-id job-id :sha (subs sha 0 12)})
        (cache/forget! sha)
        false))))

(defn- run-book-job
  "The job: the cached conversion if this exact PDF was done before; otherwise Azure text if
   possible, the PDF itself if not. With Azure text, Phase 2 continues on the improvement pool and
   takes over the PDF; otherwise the PDF is deleted here."
  [{:keys [openai-client azure-config book-config] :as ctx} ^File pdf title sha job-id]
  (let [handed-over? (atom false)]
   (try
    (when-not (from-cache! openai-client title sha job-id)
    (let [t0     (System/currentTimeMillis)
          pages  (when (azure/configured? azure-config)
                   (try
                     (jobs/update-job! job-id {:status "ocr"})
                     (vec (convert/split-pages (azure/analyze azure-config pdf)))
                     (catch Exception e
                       (log/warn e "Azure OCR failed, sending the PDF instead" {:job-id job-id})
                       nil)))
          _      (jobs/update-job! job-id {:status "indexing"})
          index  (if pages
                   (let [md (markdown-file (convert/book-markdown title (map #(assoc % :text (:azure-text %)) pages)))]
                     (try (index-file! openai-client {:file md :filename (safe-filename title ".md")
                                                      :mime-type "text/markdown" :title title})
                          (finally (.delete md))))
                   (index-file! openai-client {:file pdf :filename (safe-filename title ".pdf")
                                               :mime-type "application/pdf" :title title}))]
      (jobs/update-job! job-id {:status "usable" :vector-store-id (:vector-store-id index)
                                :method (if pages "azure" "pdf")})
      (log/info "Book usable" {:job-id job-id :method (if pages "azure" "pdf")
                               :pages (count pages) :ms (- (System/currentTimeMillis) t0)})
      (when (and pages (:improve? book-config))
        (jobs/update-job! job-id {:status "improving" :pages-done 0})
        (reset! handed-over? true)
        (jobs/continue-later! job-id #(try (improve-book! ctx pdf title sha pages index job-id)
                                            (finally (.delete pdf)))))))
    (finally
      (when-not @handed-over? (.delete pdf))))))

(defn- job-view
  "What the add-in sees of a job."
  [job]
  (select-keys job [:id :status :book-name :pages-total :pages-done :vector-store-id :error]))

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
          (let [sha    (cache/sha256 pdf)
                job-id (jobs/start-job! {:status "queued" :book-name title :pages-total pages}
                                        (partial run-book-job
                                                 {:openai-client (:openapi-client request)
                                                  :azure-config  (:azure-config request)
                                                  :book-config   (:book-config request)}
                                                 pdf title sha))]
            ;; :sha shows in the logs how often the exact same file comes back (worth caching?)
            (log/info "Book upload started" {:job-id job-id :pages pages :sha (subs sha 0 12)
                                             :mb (quot (:size file-part) (* 1024 1024))})
            (response (job-view (jobs/get-job job-id)))))))))

(defn status-handler [_request job-id]
  (if-let [job (jobs/get-job job-id)]
    (response (job-view job))
    ;; Unknown id: a restart lost the job, or it's older than a day.
    {:status 404 :body {:error "Unknown book upload" :status "failed"}}))
