(ns teachers-center-backend.openai
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

(defn chat-completion [client messages model]
  (make-request client "/chat/completions"
                {:model (or model "gpt-4")
                 :messages messages
                 :temperature 0.7
                 :max_tokens 2000}))