(ns teachers-center-backend.book.jobs
  "Book uploads run as background jobs (doc/book-production-plan.md, Phase 1): the upload request
   returns a job id at once and the add-in polls the job's status.

   Jobs live in memory, like conversations (db.clj): a restart loses a job that's still running, and
   the add-in then gets 404 for it and shows the upload as failed. The vector store a finished job
   created lives on at OpenAI; only the job record is gone."
  (:require [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.util.concurrent Executors ExecutorService ThreadFactory]))

(def ^:private keep-jobs-ms
  "How long a job record is kept. The add-in only polls during one upload, so a day is plenty."
  (* 24 60 60 1000))

(def ^:private concurrent-jobs
  "Books processed at once. A job is mostly waiting on Azure and OpenAI, so this is about not
   starting too many uploads to them at the same time, not about CPU."
  4)

(defonce ^:private jobs (atom {}))

(defonce ^:private ^ExecutorService pool
  ;; Daemon threads: a running book job must not keep the JVM alive on shutdown (or after tests)
  (Executors/newFixedThreadPool concurrent-jobs
                                (reify ThreadFactory
                                  (newThread [_ r]
                                    (doto (Thread. ^Runnable r "book-job") (.setDaemon true))))))

(defn get-job [id]
  (get @jobs id))

(defn update-job!
  "Merges `m` into the job and stamps :updated-at."
  [id m]
  (swap! jobs update id merge m {:updated-at (System/currentTimeMillis)}))

(defn- drop-old-jobs! []
  (let [cutoff (- (System/currentTimeMillis) keep-jobs-ms)]
    (swap! jobs #(into {} (remove (fn [[_ j]] (< (:created-at j) cutoff))) %))))

(defn start-job!
  "Registers a job with `initial` state and runs (work-fn job-id) on the pool. work-fn reports its
   progress with update-job!; if it throws, the job ends as \"failed\" with the message in :error.
   Returns the job id."
  [initial work-fn]
  (drop-old-jobs!)
  (let [id  (str (UUID/randomUUID))
        now (System/currentTimeMillis)]
    (swap! jobs assoc id (merge initial {:id id :created-at now :updated-at now}))
    (.submit pool ^Runnable
             (fn []
               (try
                 (work-fn id)
                 (catch Throwable e
                   (log/error e "Book job failed" {:job-id id})
                   (update-job! id {:status "failed" :error (or (ex-message e) "Unknown error")})))))
    id))
