(ns teachers-center-backend.db
  (:require [clojure.tools.logging :as log]))

;; In-memory store: { "activity-id" -> activity-data }
;; Resets on server restart. Replace with persistent DB in a later iteration.
(def interactivity-store (atom {}))

(defn save-interactivity!
  "Persists activity data under activity-id. Non-blocking — runs in a background thread."
  [activity-id activity-data]
  (future
    (try
      (swap! interactivity-store assoc activity-id activity-data)
      (catch Exception e
        (log/warn e "Failed to save activity" activity-id)))))

(defn get-interactivity
  "Returns activity data for the given activity-id, or nil if not found."
  [activity-id]
  (get @interactivity-store activity-id))
