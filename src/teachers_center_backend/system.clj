(ns teachers-center-backend.system
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [environ.core :refer [env]]
            [teachers-center-backend.handler :as handler]
            [teachers-center-backend.openapi.core :as openai]))

;; If some keys are plain data (like :messages), we can handle them generically:
(defmethod ig/init-key :default [_ value] value)

(defmethod ig/init-key :teachers-center-backend/server
  [_ {:keys [port handler]}]
  (let [env-port (env :port)
        actual-port (if env-port 
                      (Integer/parseInt env-port)
                      port)]
    (println (str "Starting server on port " actual-port))
    (jetty/run-jetty handler {:port actual-port :join? false})))

(defmethod ig/halt-key! :teachers-center-backend/server
  [_ server]
  (println "Stopping server")
  (.stop server))

(defmethod ig/init-key :teachers-center-backend/handler
  [_ {:keys [openai-client openai-content]}]
  (handler/create-handler openai-client openai-content))

(defmethod ig/halt-key! :teachers-center-backend/handler
  [_ _]
  ;; Handler is stateless, nothing to clean up
  nil)

(defmethod ig/init-key :teachers-center-backend/openai-client
  [_ {:keys [api-key base-url]}]
  (let [env-api-key (env :openai-api-key)
        system-api-key (System/getenv "OPENAI_API_KEY")
        actual-api-key (or env-api-key system-api-key api-key)]
    (println (str "Environment OPENAI_API_KEY (environ): " (if env-api-key "found" "not found")))
    (println (str "Environment OPENAI_API_KEY (System): " (if system-api-key "found" "not found")))
    (println (str "Config api-key: " (if api-key "found" "not found")))
    (println (str "Using API key: " (if actual-api-key "found" "not found")))
    (when-not actual-api-key
      (throw (ex-info "OpenAI API key is required. Set OPENAI_API_KEY environment variable." 
                      {:env-var "OPENAI_API_KEY"})))
    (openai/create-client actual-api-key base-url)))

(defmethod ig/halt-key! :teachers-center-backend/openai-client
  [_ _]
  ;; Client is stateless, nothing to clean up
  nil)