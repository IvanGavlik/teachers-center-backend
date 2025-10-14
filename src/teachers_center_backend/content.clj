(ns teachers-center-backend.content
  (:require [teachers-center-backend.openapi.core :as openai]
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

; TODO I should have general name like generate
; it accepts three functions - fn-pre, fn-process, fn-postprocess
; this is to think about approach - how to do this
(defn generate-vocabulary [openai-client openapi-content request-data]
  (try
    (let [fn-pre (requiring-resolve (:fn-pre openapi-content))
          fn-post (requiring-resolve (:fn-post openapi-content))
          message (render-content (:message openapi-content) (fn-pre request-data))
          response (openai/chat-completion openai-client message (:config openapi-content))
          post-response (fn-post response)]
      (log/info "Successfully generated vocabulary content")
      post-response)
    
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