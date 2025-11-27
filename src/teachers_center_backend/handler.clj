(ns teachers-center-backend.handler
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response status]]
            [org.httpkit.server :refer [with-channel send! on-close]] ; web socket
            [clojure.tools.logging :as log]
            [teachers-center-backend.content :as content]))

(defn health-handler [_]
  (response {:status "ok" 
             :timestamp (str (java.time.Instant/now))
             :service "teachers-center-backend"}))

; TODO WITH CHANNEL IS DEPRECATED
(defn ws-handler [req]
  (with-channel req ch
                ;; send "hello world" on connect
                (send! ch "hello world")
                ;; optional: handle close
                (on-close ch (fn [status] (println "WebSocket closed:" status)))))

(defn generate-content-handler [openai-client openapi-content]
  (fn [request]
    (try
      (let [body (:body request)
            content-type (:content_type body)
            result (case content-type
                     "vocabulary" (content/generate-vocabulary openai-client openapi-content body)
                     "grammar" (content/generate-grammar openai-client body)
                     "reading" (content/generate-reading openai-client body)
                     "exercises" (content/generate-exercises openai-client body)
                     {:success false :error "Unknown content type"})]
        (if (:success result)
          (response result)
          (-> (response result)
              (status 400))))
      (catch Exception e
        (log/error e "Error generating content")
        (-> (response {:success false 
                       :error "Internal server error"})
            (status 500))))))

(defroutes app-routes
  (GET "/health" [] health-handler)
  (GET "/ws" [] ws-handler)
  (POST "/api/generate" [] (fn [request]
                             (let [openai-client (:openapi-client request)
                                   openai-content (:openapi-content request)]
                               ((generate-content-handler openai-client openai-content) request))))
  (route/not-found {:success false :error "Route not found"}))

(defn create-handler [openai-client openapi-content]
  (let [inject (fn [handler]
                        (fn [request]
                          ;; Inject into request for handlers
                          (handler (assoc request
                                     :openapi-client openai-client
                                     :openapi-content openapi-content))))]
    (-> app-routes
        inject
        (wrap-json-body {:keywords? true})
        wrap-json-response
        (wrap-cors
          :access-control-allow-origin [#".*"]
          :access-control-allow-methods [:get :post :put :delete :options]
          :access-control-allow-headers ["Content-Type" "Authorization"]))))