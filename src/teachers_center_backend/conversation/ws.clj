(ns teachers-center-backend.conversation.ws
  (:require [teachers-center-backend.conversation.core :as conversation]))

;; here I do business logic
(defn on-request-callback [open-api-client msg]
  (conversation/conversation open-api-client msg)
  "I have you" msg)