(ns teachers-center-backend.openapi.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn create-client [api-key base-url]
  {:api-key api-key
   :base-url base-url})

(defn make-request [client endpoint payload]
  (try
    (let [response (http/post (str (:base-url client) endpoint)
                              {:headers {"Authorization" (str "Bearer " (:api-key client))
                                         "Content-Type" "application/json"}
                               :body (json/generate-string payload)
                               :timeout 60000})]
      (json/parse-string (:body response) true))
    (catch Exception e
      (log/error e "OpenAI API request failed")
      (throw (ex-info "OpenAI API request failed"
                      {:error (.getMessage e)} e)))))

(defn chat-completion [client messages {:keys [model temperature max-tokens]}]
  (make-request client "/chat/completions"
                {:model (or model "gpt-4")
                 :messages messages
                 :temperature temperature
                 :max_tokens max-tokens}))
