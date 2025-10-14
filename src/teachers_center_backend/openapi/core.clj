(ns teachers-center-backend.openapi.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn create-client [api-key base-url]
  {:api-key api-key
   :base-url base-url})

(defn make-request [client endpoint payload]
  (try
    (let [url (str (:base-url client) endpoint)
          body-json (json/generate-string payload)]
      (let [response (http/post url
                                {:headers {"Authorization" (str "Bearer " (:api-key client))
                                           "Content-Type" "application/json"}
                                 :body body-json
                                 :throw-exceptions false
                                 :timeout 60000})]
        (if (= 200 (:status response))
          (json/parse-string (:body response) true)
          (do
            (log/error "OpenAI API error response" {:status (:status response)
                                                     :body (:body response)})
            (throw (ex-info "OpenAI API returned error"
                            {:status (:status response)
                             :body (:body response)}))))))
    (catch Exception e
      (log/error e "OpenAI API request failed" {:message (.getMessage e)
                                                 :class (class e)})
      (throw (ex-info "OpenAI API request failed"
                      {:error (.getMessage e)
                       :cause-type (class e)} e)))))

(defn chat-completion [client messages {:keys [model temperature max-tokens]}]
  (make-request client "/chat/completions"
                {:model (or model "gpt-4")
                 :messages messages
                 :temperature temperature
                 :max_tokens max-tokens}))
