(ns teachers-center-backend.conversation.ws
  (:require [teachers-center-backend.conversation.core :as conversation]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn make-progress-sender
  "Create a progress callback that sends JSON progress updates."
  [send-fn]
  (fn [{:keys [stage]}]
    (send-fn (json/generate-string {:type "progress"
                                    :stage stage}))))

(defn on-request-callback
  "Process incoming WebSocket message and send responses via send-fn."
  [open-api-client send-fn msg]
  (try
    (log/info "Received WebSocket message:" msg)

    (let [parsed-msg (json/parse-string msg true)
          _ (log/debug "Parsed message:" parsed-msg)

          request-data {:user-id (:user-id parsed-msg)
                        :channel-name (:channel-name parsed-msg)
                        :conversation-id (:conversation-id parsed-msg)
                        :type (keyword (:type parsed-msg))
                        :content (:content parsed-msg)
                        :requirements (:requirements parsed-msg {})}

          _ (log/info "Processed request data:" request-data)

          on-progress (make-progress-sender send-fn)
          response (conversation/conversation open-api-client request-data on-progress)

          _ (log/info "Conversation response:" response)

          json-response (json/generate-string response)]

      (log/info "Sending WebSocket response")
      (send-fn json-response))

    (catch Exception e
      (log/error e "Error processing WebSocket message:" msg)
      (send-fn (json/generate-string {:error "Failed to process message"
                                      :message (.getMessage e)})))))