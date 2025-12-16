(ns teachers-center-backend.conversation.ws)

;; here I do business logic
(defn on-request-callback [msg]
  (prn "msg" msg)
  "I have you" msg)