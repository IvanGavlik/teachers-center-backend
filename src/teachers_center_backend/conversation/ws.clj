(ns teachers-center-backend.conversation.ws
  (:require [teachers-center-backend.conversation.core :as conversation]
            [teachers-center-backend.email-logger :as email-logger]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn make-progress-sender
  "Create a progress callback that sends JSON progress updates."
  [send-fn]
  (fn [{:keys [stage]}]
    (send-fn (json/generate-string {:type "progress"
                                    :stage stage}))))

(def default-slide-format {:aspect "16:9" :width-in 13.33 :height-in 7.5})

(defn slide-format->text
  "{:aspect \"16:9\" :width-in 13.33 :height-in 7.5} → \"16:9 (13.33 × 7.5 in)\" — a layout hint for the AI.
   The add-in detects the real slide size itself; until it sends one we assume a new 16:9 deck."
  [{:keys [aspect width-in height-in]}]
  (if (and aspect width-in height-in)
    (format "%s (%s × %s in)" aspect width-in height-in)
    (slide-format->text default-slide-format)))

(defn- blank-or-nil? [v]
  (or (nil? v) (str/blank? (str v))))

(defn normalize-requirements
  "Fill defaults so every {{placeholder}} in the prompt templates gets a value."
  [requirements slide-format]
  (let [reqs (or requirements {})]
    (assoc reqs
           :age-group       (if (blank-or-nil? (:age-group reqs)) "Not required" (:age-group reqs))
           :native-language (if (blank-or-nil? (:native-language reqs)) "No" (:native-language reqs))
           :slide-format    (slide-format->text slide-format))))

(defn on-request-callback
  "Process incoming WebSocket message and send responses via send-fn."
  [open-api-client send-fn msg]
  (try
    (log/info "Conversation request:" msg)

    (let [parsed-msg (json/parse-string msg true)
          request-data {:user-id (:user-id parsed-msg)
                        :channel-name (:channel-name parsed-msg)
                        :conversation-id (:conversation-id parsed-msg)
                        :type (keyword (:type parsed-msg))  ; "edit" or "conversation" - see conversation/core's explicit case dispatch (future "interactivity" type adds a case there)
                        :content (:content parsed-msg)
                        :requirements (normalize-requirements (:requirements parsed-msg)
                                                              (:slide-format parsed-msg))
                        :edit (:edit parsed-msg)
                        :interactivity (:interactivity parsed-msg)
                        :book-ids (:book-ids parsed-msg [])
                        }
          on-progress (make-progress-sender send-fn)
          response (conversation/conversation open-api-client request-data on-progress)

          _ (log/info "Conversation response:" response)
          json-response (json/generate-string response)]
      (send-fn json-response)
      (email-logger/send-logs-to-email {:request msg :response response}))

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