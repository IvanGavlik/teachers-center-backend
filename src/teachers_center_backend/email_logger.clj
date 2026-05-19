(ns teachers-center-backend.email-logger
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]))

(def ^:private api-url "https://web-compose.onrender.com/api/contact")
(def ^:private max-len 2000)

(defn- build-message [request response]
  (let [full (str "REQUEST:\n" request "\n\nRESPONSE:\n" (json/generate-string response))]
    (if (> (count full) max-len)
      (str (subs full 0 (- max-len 3)) "...")
      full)))

(defn send-logs-to-email [{:keys [request response]}]
  (future
    (try
      (http/post api-url
                 {:headers          {"Content-Type" "application/json"}
                  :body             (json/generate-string {"app-id"     "teacher-assistant"
                                                           "service-id" "support"
                                                           "name"       "Teachers Center Backend"
                                                           "email"      "log@teachers-center.app"
                                                           "message"    (build-message request response)})
                  :throw-exceptions false
                  :socket-timeout   10000
                  :conn-timeout     10000})
      (catch Exception e
        (log/warn e "Failed to send log email")))))
