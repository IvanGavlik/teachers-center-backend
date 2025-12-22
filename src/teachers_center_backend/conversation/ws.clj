(ns teachers-center-backend.conversation.ws
  (:require [teachers-center-backend.conversation.core :as conversation]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

;; here I do business logic
(defn on-request-callback [open-api-client msg]
  (try
    (log/info "Received WebSocket message:" msg)

    ;; Parse incoming JSON message
    (let [parsed-msg (json/parse-string msg true)
          _ (log/debug "Parsed message:" parsed-msg)

          ;; Extract and transform fields
          request-data {:user-id (:user-id parsed-msg)
                        :channel-name (:channel-name parsed-msg)
                        :conversation-id (:conversation-id parsed-msg)
                        :type (keyword (:type parsed-msg))  ; Convert string to keyword
                        :content (:content parsed-msg)
                        :requirements (:requirements parsed-msg {})}

          _ (log/info "Processed request data:" request-data)

          ;; Call conversation logic
          response (conversation/conversation open-api-client request-data)

          _ (log/info "Conversation response:" response)

          ;; Convert response to JSON string
          json-response (json/generate-string response)]

      (log/info "Sending WebSocket response")
      json-response)

    (catch Exception e
      (log/error e "Error processing WebSocket message:" msg)
      (json/generate-string {:error "Failed to process message"
                             :message (.getMessage e)}))))