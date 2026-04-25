(ns teachers-center-backend.conversation.ws
  (:require [teachers-center-backend.conversation.core :as conversation]
            [cheshire.core :as json]
            [clojure.string :as str]
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
    (log/info "Conversation request:" msg)

    (let [parsed-msg (json/parse-string msg true)
          request-data {:user-id (:user-id parsed-msg)
                        :channel-name (:channel-name parsed-msg)
                        :conversation-id (:conversation-id parsed-msg)
                        :type (keyword (:type parsed-msg))  ; default always vocabulary on the FE TODO fix this type-selector on the FE
                        :content (:content parsed-msg)
                        :requirements (let [reqs (:requirements parsed-msg {})
                                           age  (get reqs :age-group)]
                                       (assoc reqs :age-group
                                              (if (or (nil? age) (str/blank? (str age)))
                                                "Not required"
                                                age)))
                        :messages (or (:messages parsed-msg) [])
                        :edit (:edit parsed-msg)}
          on-progress (make-progress-sender send-fn)
          response (conversation/conversation open-api-client request-data on-progress)

          _ (log/info "Conversation response:" response)

          json-response (json/generate-string response)]
      (send-fn json-response))

    (catch Exception e
      (log/error e "Error processing WebSocket message:" msg)
      (let [error-code (or (:error-code (ex-data e)) :unknown-error)
            user-msg   (case error-code
                         :quota-exceeded      "We've temporarily reached our AI usage limit. Please try again in a few minutes. If the problem persists, contact support."
                         :auth-error          "There's a configuration problem on our end. Please contact support."
                         :service-unavailable "The AI service is temporarily unavailable. Please try again shortly."
                         :network-error       "We couldn't reach the AI service. Please check your connection and try again."
                         :bad-request         "Your request could not be processed. Please try rephrasing it."
                         :api-error           "An unexpected error occurred with the AI service. Please try again."
                         "Something went wrong on our end. Please try again or contact support.")]
        (send-fn (json/generate-string {:type    "error"
                                        :code    (name error-code)
                                        :message user-msg}))))))