(ns teachers-center-backend.system
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [teachers-center-backend.handler :as handler]
            [teachers-center-backend.openapi.core :as openai]))

;; If some keys are plain data (like :messages), we can handle them generically:
(defmethod ig/init-key :default [_ value] value)

(defmethod ig/init-key :teachers-center-backend/server
  [_ {:keys [port handler]}]
  (println (str "Starting server on port " port))
  (jetty/run-jetty handler {:port port :join? false}))

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
  (when-not api-key
    (throw (ex-info "OpenAI API key is required. Set OPENAI_API_KEY environment variable."
                    {:env-var "OPENAI_API_KEY"})))
  (openai/create-client api-key base-url))

(defmethod ig/halt-key! :teachers-center-backend/openai-client
  [_ _]
  ;; Client is stateless, nothing to clean up
  nil)