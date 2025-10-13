(ns teachers-center-backend.openapi.process)
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
