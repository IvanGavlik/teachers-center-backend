(ns teachers-center-backend.db
  (:require [clojure.tools.logging :as log]))

;; In-memory store: { "conversation-id" -> { :last-response-id "resp_..." } }
;; Resets on server restart. Sufficient for v1 — replace with Redis/DB in a later iteration.
(def conversation-store (atom {}))

(defn get-last-response-id
  "Returns the last OpenAI response-id for the given conversation-id, or nil."
  [conversation-id]
  (get-in @conversation-store [conversation-id :last-response-id]))

(defn save-last-response-id!
  "Stores the latest response-id for a conversation. Non-blocking — runs in a background thread."
  [conversation-id response-id]
  (future
    (try
      (swap! conversation-store assoc-in [conversation-id :last-response-id] response-id)
      (catch Exception e
        (log/warn e "Failed to save last-response-id for conversation" conversation-id)))))

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
