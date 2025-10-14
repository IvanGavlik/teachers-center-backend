(ns teachers-center-backend.openapi.process
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]))
; rename to business

(defn language-name [{:keys [language]}]
  (case language
    "en" "English"
    "de" "German"
    "fr" "French"
    "es" "Spanish"
    "it" "Italian"
    "pt" "Portuguese"
    "English"))

(defn prepare-request-data [request-data]
  (let [params (:parameters request-data)
        request-prepared {:level (:level request-data)
                          :language (language-name request-data)
                          :topic (:topic params)
                          :word-count (:word_count params)
                          :include-examples (:include_examples params)}]
    request-prepared))

(defn parse-vocabulary-response [response-text]
  (try
    (json/parse-string response-text true)
    (catch Exception e
      (log/error e "Failed to parse OpenAI response as JSON")
      (throw (ex-info "Failed to parse AI response" {:raw-response response-text})))))

(defn format-vocabulary-slides [parsed-content]
  (let [{:keys [title subtitle words]} parsed-content]
    {:success true
     :content_type "vocabulary"
     :slides [{:type "title"
               :title title
               :subtitle subtitle}
              {:type "content"
               :title "Vocabulary Words"
               :content (map (fn [word]
                               {:word (:word word)
                                :definition (:definition word)
                                :translation (:translation word)
                                :example (:example word)})
                             words)}]
     :metadata {:word_count (count words)
                :generated_at (str (java.time.Instant/now))}}))

(defn process-response [response]
  (let [content-text (get-in response [:choices 0 :message :content])
        parsed-content (parse-vocabulary-response content-text)
        formatted-slides (format-vocabulary-slides parsed-content)]
    formatted-slides))