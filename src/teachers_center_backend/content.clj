(ns teachers-center-backend.content
  (:require [teachers-center-backend.openapi.core :as openai]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]))

(defn replace-placeholders [text params]
  "Replace {{placeholder}} patterns in text with values from params map"
  (reduce (fn [acc [k v]]
            (str/replace acc (re-pattern (str "\\{\\{" (name k) "\\}\\}")) (str v)))
          text
          params))

(defn render-content [content params]
  "Recursively render content structure, replacing placeholders only in string values"
  (walk/postwalk
    (fn [x]
      (if (string? x)
        (replace-placeholders x params)
        x))
    content))

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

; TODO I should have general name like generate
; it accepts three functions - fn-pre, fn-process, fn-postprocess
; this is to think about approach - how to do this
(defn generate-vocabulary [openai-client openapi-content request-data]
  (try
    (let [fn-pre (requiring-resolve (:fn-pre openapi-content))
          message (render-content (:message openapi-content) (fn-pre request-data))
          response (openai/chat-completion openai-client message (:config openapi-content))
          content-text (get-in response [:choices 0 :message :content])
          parsed-content (parse-vocabulary-response content-text)
          formatted-slides (format-vocabulary-slides parsed-content)]
      
      (log/info "Successfully generated vocabulary content")
      formatted-slides)
    
    (catch Exception e
      (log/error e "Error generating vocabulary content")
      {:success false 
       :error "Failed to generate vocabulary content"
       :details (.getMessage e)})))

;; Placeholder implementations for other content types
(defn generate-grammar [openai-client request-data]
  {:success false 
   :error "Grammar generation not implemented yet"})

(defn generate-reading [openai-client request-data]
  {:success false 
   :error "Reading generation not implemented yet"})

(defn generate-exercises [openai-client request-data]
  {:success false 
   :error "Exercise generation not implemented yet"})