(ns teachers-center-backend.openapi.core
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(defn create-client [api-key base-url]
  {:api-key api-key
   :base-url base-url})

(defn- classify-openai-error [status]
  (cond
    (= status 429)           :quota-exceeded
    (= status 401)           :auth-error
    (= status 400)           :bad-request
    (#{503 529} status)      :service-unavailable
    :else                    :api-error))

(defn make-request [client endpoint payload]
  (try
    (let [url (str (:base-url client) endpoint)
          body-json (json/generate-string payload)]
      (let [response (http/post url
                                {:headers {"Authorization" (str "Bearer " (:api-key client))
                                           "Content-Type" "application/json"}
                                 :body body-json
                                 :throw-exceptions false
                                 :cookie-policy :standard
                                 :timeout 60000})]
        (if (= 200 (:status response))
          (json/parse-string (:body response) true)
          (do
            (log/error "OpenAI API error response" {:status (:status response)
                                                     :body (:body response)})
            (throw (ex-info "OpenAI API returned error"
                            {:status     (:status response)
                             :body       (:body response)
                             :error-code (classify-openai-error (:status response))}))))))
    (catch Exception e
      (log/error e "OpenAI API request failed" {:message (.getMessage e)
                                                 :class (class e)})
      (throw (ex-info "OpenAI API request failed"
                      {:error      (.getMessage e)
                       :cause-type (class e)
                       :error-code :network-error} e)))))

(defn chat-completion [client messages {:keys [model temperature max-tokens]}]
  (make-request client "/chat/completions"
                {:model (or model "gpt-4")
                 :messages messages
                 :temperature temperature
                 :max_tokens max-tokens}))

(defn responses-api
  "Call the OpenAI Responses API (/v1/responses).
   request map keys:
   - :instructions          system prompt string (must be resent on every call; not carried forward)
   - :input                 the user's message string
   - :previous-response-id  id from the prior response to continue a conversation; nil to start fresh
   config map keys:
   - :model                 defaults to gpt-4o
   - :temperature
   - :max-output-tokens"
  [client {:keys [instructions input previous-response-id]} {:keys [model temperature max-output-tokens store]
                                                              :or   {store true}}]
  (let [payload (cond-> {:model        (or model "gpt-4o")
                         :instructions instructions
                         :input        input
                         ;; store: true  — OpenAI persists the response server-side, which is required
                         ;;               for previous-response-id chaining to work. Set to false only
                         ;;               for Zero Data Retention orgs (disables chaining entirely).
                         :store        store}
                  temperature          (assoc :temperature temperature)
                  max-output-tokens    (assoc :max_output_tokens max-output-tokens)
                  previous-response-id (assoc :previous_response_id previous-response-id))]
    (make-request client "/responses" payload)))

(defn response-output-text
  "Extract the assistant text from a Responses API response.
   output_text is an SDK convenience accessor — it is not present in the raw HTTP JSON.
   The text lives at response.output[0].content[0].text"
  [response]
  (-> response :output first :content first :text))

(comment
  ;; REPL test for responses-api
  ;; Run after (dev/go) — client is already in the running system, or create one manually:
  (def test-client (create-client key
                                  "https://api.openai.com/v1"))

  (def test-instructions
    "You are an AI assistant that helps language teachers create slide content for B1 level students learning English.
IMPORTANT: You must ONLY respond with valid JSON. Never include any text outside the JSON object.
Return either:
  {\"requirements-not-met\": \"your clarification question\"}
  OR
  {\"title\": \"topic\", \"slides\": [{\"slide-title\": \"...\", \"content\": \"...\"}]}")

  (def test-config {:model "gpt-4o" :temperature 0.7 :max-output-tokens 2000})

  ;; Turn 1 — new conversation
  (def resp1 (responses-api test-client
                            {:instructions test-instructions :input "teach present simple"}
                            test-config))
  (println "id:  " (:id resp1))
  (println "out: " (response-output-text resp1))

  ;; Turn 2 — continue using the id from turn 1
  (def resp2 (responses-api test-client
                            {:instructions        test-instructions
                             :input               "focus on the affirmative form, 5 slides"
                             :previous-response-id (:id resp1)}
                            test-config))
  (println "id:  " (:id resp2))
  (println "out: " (response-output-text resp2)))
