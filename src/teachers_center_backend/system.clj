(ns teachers-center-backend.system
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [environ.core :refer [env]]
            [teachers-center-backend.handler :as handler]
            [teachers-center-backend.openai :as openai]))

(defmethod ig/init-key :teachers-center-backend/server
  [_ {:keys [port handler]}]
  (let [actual-port (or (env :port) port)]
    (println (str "Environment PORT: " (env :port)))
    (println (str "Config port: " port))
    (println (str "Starting server on port " actual-port))
    (jetty/run-jetty handler {:port actual-port :join? false})))

(defmethod ig/halt-key! :teachers-center-backend/server
  [_ server]
  (println "Stopping server")
  (.stop server))

(defmethod ig/init-key :teachers-center-backend/handler
  [_ {:keys [openai-client]}]
  (handler/create-handler openai-client))

(defmethod ig/halt-key! :teachers-center-backend/handler
  [_ _]
  ;; Handler is stateless, nothing to clean up
  nil)

(defmethod ig/init-key :teachers-center-backend/openai-client
  [_ {:keys [api-key base-url]}]
  (let [actual-api-key (or (System/getenv "OPENAI_API_KEY") api-key)]
    (println (str "Environment OPENAI_API_KEY: " (if (System/getenv "OPENAI_API_KEY") "found" "not found")))
    (println (str "Config api-key: " (if api-key "found" "not found")))
    (when-not actual-api-key
      (throw (ex-info "OpenAI API key is required. Set OPENAI_API_KEY environment variable." 
                      {:env-var "OPENAI_API_KEY"})))
    (openai/create-client actual-api-key base-url)))

(defmethod ig/halt-key! :teachers-center-backend/openai-client
  [_ _]
  ;; Client is stateless, nothing to clean up
  nil)