(ns teachers-center-backend.handler
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response status]]
            [clojure.tools.logging :as log]
            [teachers-center-backend.content :as content]))

(defn health-handler [_]
  (response {:status "ok" 
             :timestamp (java.time.Instant/now)
             :service "teachers-center-backend"}))

(defn generate-content-handler [openai-client]
  (fn [request]
    (try
      (let [body (:body request)
            content-type (:content_type body)
            result (case content-type
                     "vocabulary" (content/generate-vocabulary openai-client body)
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
  (POST "/api/generate" [] (fn [request]
                             (let [openai-client (:openai-client request)]
                               ((generate-content-handler openai-client) request))))
  (route/not-found {:success false :error "Route not found"}))

(defn create-handler [openai-client]
  (-> app-routes
      (wrap-json-body {:keywords? true})
      wrap-json-response
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers ["Content-Type" "Authorization"])
      (fn [handler]
        (fn [request]
          ;; Inject openai-client into request for handlers
          (handler (assoc request :openai-client openai-client))))))