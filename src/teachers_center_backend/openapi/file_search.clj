(ns teachers-center-backend.openapi.file-search
  (:require [teachers-center-backend.openapi.core :as openai-client]
            [clojure.java.io :as io]))

; Cretea/Upload file and pass that id to the create vector store
; one pdf per vector store (pdf is automatically parsed and indexed when added to the store)
; TODO if you want more file in the vector store POST /vector_stores/{id}/files endpoint
; - investigate when parsing and indexing is done and
;   do we have to upload file separetly on file endpoint

(defn create-vector-store
  [client {:keys [name file-ids expires-after-days metadata]}]
  (let [payload (cond-> {:name name}
                  file-ids           (assoc :file_ids file-ids)
                  expires-after-days (assoc :expires_after {:anchor "last_active_at"
                                                             :days   expires-after-days})
                  metadata           (assoc :metadata metadata))]
    (openai-client/make-request client "/vector_stores" payload)))

(defn list-vector-stores
  ([client] (list-vector-stores client {}))
  ([client {:keys [limit order after before]}]
   (let [query-params (cond-> {}
                         limit  (assoc :limit limit)
                         order  (assoc :order order)
                         after  (assoc :after after)
                         before (assoc :before before))]
     (openai-client/get-request client "/vector_stores" query-params))))

(defn upload-file
  ;; clj-http's multipart support for File content only honors a custom filename
  ;; when :mime-type is ALSO present (see clj-http.multipart/make-multipart-body's File
  ;; dispatch) — and it reads the filename from :name, not :filename. :part-name is what
  ;; sets the actual multipart field name ("file"), independent of the displayed filename.
  ;; Without :mime-type, clj-http falls back to a constructor that ignores the filename
  ;; entirely and uses the underlying File object's own name — which for an HTTP-uploaded
  ;; file is a meaningless Ring tempfile name like "ring-multipart-....tmp", and OpenAI's
  ;; /files endpoint rejects unrecognized extensions.
  [client {:keys [file-path purpose filename mime-type] :or {purpose "assistants"}}]
  (let [file (io/file file-path)]
    (openai-client/upload-request client "/files"
                                  [{:part-name "file"
                                    :name      (or filename (.getName file))
                                    :mime-type (or mime-type "application/octet-stream")
                                    :content   file}
                                   {:name "purpose" :content purpose}])))

(comment
  (require '[dev :as dev])
  (def test-client (dev/get-openai-client))                 ; for repl

  (def uploaded-file (upload-file test-client {:file-path "resources/deep_research_blog.pdf"}))
  (:id uploaded-file)

  (def store-with-file (create-vector-store test-client {:name "deep-research-blog"
                                                          :file-ids [(:id uploaded-file)]}))
  (:id store-with-file)
  (list-vector-stores test-client)
  (:file_counts store-with-file)

  )

; polling situation  Why you need this at all
;
;  When you create a vector store with file_ids (or attach a file afterward), the API call returns immediately — but the
;  actual parsing/chunking/embedding/indexing happens asynchronously in the background. The object you get back right
;  after creation will typically still show processing underway. If you try to run a search or attach the store to a
;  Responses API call before that finishes, you risk incomplete or empty results — the file looks attached but isn't
;  actually searchable yet. Polling is just "keep checking back until OpenAI says it's actually ready."
;
;  What "done" looks like
;
;  Turns out there are two signals available on the vector store object (confirmed from the retrieve endpoint):
;
;  - Store-level status — one of "in_progress", "completed", "expired". This is the simplest possible check: poll until
;    status == "completed".
;  - file_counts — {in_progress, completed, failed, cancelled, total}. More granular: even once the store's overall
;    status is "completed", a specific file could have ended up in failed rather than completed (e.g., a corrupt PDF).
;    Worth surfacing even though it's not the primary stop condition.
; ;
;  The mechanism
; ;
;  Parameters worth exposing
;
;  - interval-ms — how long to sleep between checks (e.g. default ~1500–2000ms; too fast wastes requests against rate
;    limits, too slow just adds latency you don't need).
;  - timeout-ms — max total time to wait before giving up (e.g. default 60–120s, tune based on typical PDF size).
;
;  Edge cases to account for
;
;  - Timeout → throw ex-info with the last known status, so the caller can decide to retry/alert instead of guessing why
;    it hung.
;  - status == "expired" → treat as terminal failure, not success.
;  - Partial file failure (file_counts.failed > 0 on an otherwise "completed" store) → don't hide this; return/report it
;    alongside the successful result.
;  - Network errors mid-poll → get-request already throws on network failure; simplest v1 behavior is to just let that
;    propagate rather than silently retrying transient errors.
;
;  One note for later (not needed now)
;
;  This is a blocking loop — fine for REPL testing and even fine for now, but if this ever runs inside a real HTTP
;  request handler in the backend, blocking a thread for up to a minute is a bad pattern; at that point you'd want either
;  a background job or OpenAI's webhook support for vector-store completion events instead of polling. Not a concern for
;  the basic flow you're building right now, just flagging it so it doesn't surprise you later.
;
;  Want me to implement it exactly this way — retrieve-vector-store + a poll-vector-store that blocks on status, with
;  interval-ms/timeout-ms opts and default values, and adds an example to the comment block?

(defn retrieve-vector-store [client vector-store-id]
  (openai-client/get-request client (str "/vector_stores/" vector-store-id) {}))

(defn poll-vector-store [client vector-store-id]
  (loop [store (retrieve-vector-store client vector-store-id)]
    (if (= "completed" (:status store))
      store
      (do
        (Thread/sleep 2000)
        (recur (retrieve-vector-store client vector-store-id))))))

(comment
  (def polled-store (poll-vector-store test-client (:id store-with-file)))
  (:status polled-store)
  (:file_counts polled-store)

  )

; search-vector-store is only here to test/inspect raw retrieval quality (which chunks come back for a query).
; It is NOT how the real implementation will answer questions — the real flow will use Approach 1: pass
; :tools [{:type "file_search" :vector_store_ids [...]}] straight into responses-api, so OpenAI runs the
; search itself server-side as part of one /responses call, instead of us calling this and stuffing results
; into a prompt ourselves.
(defn search-vector-store
  [client vector-store-id {:keys [query max-num-results filters rewrite-query]}]
  (let [payload (cond-> {:query query}
                  max-num-results (assoc :max_num_results max-num-results)
                  filters         (assoc :filters filters)
                  rewrite-query   (assoc :rewrite_query rewrite-query))]
    (openai-client/make-request client (str "/vector_stores/" vector-store-id "/search") payload)))

(comment
  (def search-results (search-vector-store test-client (:id store-with-file) {:query "what is deep research?"}))
  (:data search-results)

  )

