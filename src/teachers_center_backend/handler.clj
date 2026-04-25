(ns teachers-center-backend.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response]]
            [org.httpkit.server :refer [with-channel send! on-close on-receive]] ; web socket
            [teachers-center-backend.conversation.ws :as conversation-ws]))

(defn health-handler [_]
  (response {:status "ok" 
             :timestamp (str (java.time.Instant/now))
             :service "teachers-center-backend"}))

(defn ws-handler [open-api-client req]
  (with-channel req ch
                ;; send "hello world" on connect
                ;; TODO on connect should I based on user-id and :channel-id maybe
                ;; maybe even conversation-id fetch/get current conversion and how to handle this
                ;; also what about user logs
                ; (send! ch "hello world")

                (on-receive ch (fn [msg]
                                 (let [send-fn (fn [data] (send! ch data))]
                                   (conversation-ws/on-request-callback open-api-client send-fn msg))))

                ;; optional: handle close
                (on-close ch (fn [status] (println "WebSocket closed:" status)))))

(defroutes app-routes
  (GET "/health" [] health-handler)
  (GET "/ws" [] (fn [request] (ws-handler (:openapi-client request) request)))
  (route/not-found {:success false :error "Route not found"}))

(defn create-handler [openai-client]
  (let [inject (fn [handler]
                        (fn [request]
                          ;; Inject into request for handlers
                          (handler (assoc request
                                     :openapi-client openai-client))))]
    (-> app-routes
        inject
        (wrap-json-body {:keywords? true})
        wrap-json-response
        (wrap-cors
          :access-control-allow-origin [#".*"]
          :access-control-allow-methods [:get :post :put :delete :options]
          :access-control-allow-headers ["Content-Type" "Authorization"]))))