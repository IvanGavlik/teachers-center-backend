(ns teachers-center-backend.content
  (:require [teachers-center-backend.openapi.core :as openai]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn create-vocabulary-prompt [language level topic word-count include-examples]
  (let [language-name (case language
                        "en" "English"
                        "de" "German" 
                        "fr" "French"
                        "es" "Spanish"
                        "it" "Italian"
                        "pt" "Portuguese"
                        "English")]
    (str "Generate " word-count " vocabulary words for " language-name " language learners at CEFR level " level 
         " on the topic: \"" topic "\". "
         
         "Format the response as a JSON object with this exact structure:
{
  \"title\": \"" (str/capitalize topic) " Vocabulary\",
  \"subtitle\": \"Level: " level " | " word-count " words | " language-name "\",
  \"words\": [
    {
      \"word\": \"word in " language-name "\",
      \"definition\": \"clear definition appropriate for " level " level\",
      \"translation\": \"translation to English if not English, or synonym if English\""
      (if include-examples 
        ",\n      \"example\": \"example sentence using the word\""
        "")
    "}
  ]
}

Requirements:
- Words should be appropriate for CEFR level " level "
- Definitions should be clear and at the right difficulty level
- Topics should be relevant and useful for language learning
- " (if include-examples "Include example sentences that demonstrate proper usage" "Do not include example sentences") "
- Ensure all content is educationally appropriate and culturally neutral
- Focus on commonly used, practical vocabulary")))

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

(defn generate-vocabulary [openai-client request-data]
  (try
    (let [{:keys [language level parameters]} request-data
          {:keys [topic word_count include_examples include_images]} parameters
          
          prompt (create-vocabulary-prompt language level topic word_count include_examples)
          
          messages [{:role "system" 
                     :content "You are an expert language teacher creating educational vocabulary content. Always respond with valid JSON in the exact format requested."}
                    {:role "user" 
                     :content prompt}]
          
          response (openai/chat-completion openai-client messages {:model "gpt-4"
                                                                   :temperature 0.7
                                                                   :max-tokens 2000})
          content-text (get-in response [:choices 0 :message :content])
          
          parsed-content (parse-vocabulary-response content-text)
          formatted-slides (format-vocabulary-slides parsed-content)]
      
      (log/info "Successfully generated vocabulary content" 
                {:topic topic :level level :word-count word_count})
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