(ns teachers-center-backend.library
  (:require [teachers-center-backend.openapi.file-search :as file-search]
            [ring.util.response :refer [response]]
            [clojure.tools.logging :as log]))

(def ^:private max-file-size-bytes (* 20 1024 1024))

(defn- pdf-content-type? [content-type]
  (= content-type "application/pdf"))

(defn upload-book!
  "Uploads a file to OpenAI and creates a vector store for it.
   No teacher/session scoping yet — just returns the raw ids."
  [openai-client {:keys [tempfile filename book-name]}]
  (let [uploaded (file-search/upload-file openai-client {:file-path (.getPath tempfile)
                                                          :filename  filename
                                                          :mime-type "application/pdf"})
        store    (file-search/create-vector-store openai-client
                   {:name               (or book-name filename)
                    :file-ids           [(:id uploaded)]
                    ;; No verified/ephemeral distinction yet — short-lived default so nothing
                    ;; lingers unbounded until that lands.
                    :expires-after-days 3})]
    {:vector-store-id (:id store)
     :status          (:status store)
     :book-name       (:name store)}))

(defn get-book-status [openai-client vector-store-id]
  (let [store (file-search/retrieve-vector-store openai-client vector-store-id)]
    {:vector-store-id (:id store)
     :status          (:status store)
     :file-counts     (:file_counts store)}))

(defn upload-handler [request]
  (let [openai-client (:openapi-client request)
        file-part     (get-in request [:multipart-params "file"])
        book-name     (get-in request [:multipart-params "book-name"])]
    (cond
      (nil? file-part)
      {:status 400 :body {:error "Missing file"}}

      (not (pdf-content-type? (:content-type file-part)))
      {:status 400 :body {:error "Only PDF files are supported"}}

      (> (:size file-part) max-file-size-bytes)
      {:status 400 :body {:error "File too large (20MB max)"}}

      :else
      (try
        (response (upload-book! openai-client {:tempfile  (:tempfile file-part)
                                                :filename  (:filename file-part)
                                                :book-name book-name}))
        (catch Exception e
          (log/error e "Failed to upload book")
          {:status 500 :body {:error "Failed to upload file"}})))))

(defn status-handler [request id]
  (try
    (response (get-book-status (:openapi-client request) id))
    (catch Exception e
      (log/error e "Failed to fetch book status" {:vector-store-id id})
      {:status 500 :body {:error "Failed to fetch book status"}})))

(comment
  ;; REPL walkthrough of the whole path: upload a PDF, wait for indexing, then use the
  ;; resulting vector-store-id to ground a real conversation request via file_search.
  ;; Run (dev/go) first (see dev/dev.clj) so a live openai-client exists.
  (require '[dev :as dev])
  (require '[clojure.java.io :as io])
  (require '[teachers-center-backend.conversation.core :as conversation])

  (def client (dev/get-openai-client))

  ;; 1. Upload a PDF and create its vector store — fast, a few seconds.
  (def uploaded (upload-book! client {:tempfile  (io/file "resources/deep_research_blog.pdf")
                                       :filename  "deep_research_blog.pdf"
                                       :book-name "Deep Research Blog"}))
  (:vector-store-id uploaded)
  (:status uploaded) ; usually "in_progress" right after creation

  ;; 2. Poll until indexing completes — re-run this line until :status is "completed"
  ;;    (a single small PDF like the test one here takes ~5s).
  (get-book-status client (:vector-store-id uploaded))

  ;; 3. Use the resulting vector-store-id as a book-id in a real conversation call.
  (def base-req
    {:user-id         "user-repl"
     :channel-name    "repl"
     :type            :conversation
     :conversation-id nil
     :content         "Based on the attached document, what is deep research? Answer in one slide."
     :requirements    {:language "English" :level "B1" :age-group "adults" :native-language "No"}
     :book-ids        [(:vector-store-id uploaded)]})

  (def res (conversation/generate-conversation client base-req nil))
  (:title res)
  (:slides res)
  ;; Content should reflect the PDF specifically (e.g. mentions of multi-step synthesis,
  ;; cited sources), not just generic knowledge about the topic — that's the confirmation
  ;; that upload -> vector store -> file_search tool -> response parsing all work together.
  )
