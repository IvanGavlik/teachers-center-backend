(ns dev
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [gniazdo.core :as ws-client]
            [cheshire.core :as json]
            [teachers-center-backend.system]
            [teachers-center-backend.core :refer [load-config]]))

(ig-repl/set-prep! (fn []
                     (load-config)))

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)
(def reset-all ig-repl/reset-all)

(defn start-system []
  (ig-repl/set-prep! (fn [] (load-config)))
  (ig-repl/go))


(defn clear-and-go [] ;; TODO this does not work
  (alter-var-root #'state/system (constantly nil))
  (alter-var-root #'state/config (constantly nil))
  (go))

(defn send-one-time-ws-request
  "Enhanced version with better debugging and response capture"
  []
  (let [request-data {:user-id 123
                      :channel-name "power-point-presentation-test.pxp"
                      :conversation-id nil
                      :type "generate-vocabulary"
                      :content "Generate vocabulary for topic food 5 words A1 level German language no examples"
                      :requirements {}}
        json-msg (json/generate-string request-data)
        received-messages (atom [])
        response-received (promise)

        socket (ws-client/connect "ws://localhost:2000/ws?name=John"
                                  :on-receive (fn [msg]
                                                (println "\n=== RECEIVED MESSAGE ===" )
                                                (flush)
                                                (swap! received-messages conj msg)
                                                (println "Raw:" msg)
                                                (flush)
                                                (try
                                                  (let [parsed (json/parse-string msg true)]
                                                    (println "\nParsed JSON:")
                                                    (clojure.pprint/pprint parsed)
                                                    (flush)
                                                    ;; Deliver promise on second message (first is "hello world")
                                                    (when (= (count @received-messages) 2)
                                                      (deliver response-received :done)))
                                                  (catch Exception e
                                                    (println "Not JSON:" (.getMessage e))
                                                    (flush)
                                                    ;; If it's the second message, deliver anyway
                                                    (when (= (count @received-messages) 2)
                                                      (deliver response-received :done)))))
                                  :on-close (fn [status reason]
                                              (println "\n=== CONNECTION CLOSED ===" status reason)
                                              (flush))
                                  :on-error (fn [e]
                                              (println "\n=== ERROR ===" e)
                                              (flush)))]

    (println "\n=== SENDING MESSAGE ===")
    (clojure.pprint/pprint request-data)
    (println "\nJSON string:" json-msg)
    (flush)

    (Thread/sleep 200)

    (println "\n=== SENDING TO SERVER ===")
    (flush)
    (ws-client/send-msg socket json-msg)

    (println "Waiting for response (max 60s)...")
    (flush)

    ;; Wait for response or timeout after 60 seconds
    (let [result (deref response-received 60000 :timeout)]
      (when (= result :timeout)
        (println "\n!!! TIMEOUT - No response after 60 seconds !!!")))

    (Thread/sleep 100) ; Small delay to ensure last message is processed

    (println "\n=== SUMMARY ===")
    (println "Total messages received:" (count @received-messages))
    (doseq [[idx msg] (map-indexed vector @received-messages)]
      (println (str "Message " (inc idx) ":") msg))
    (flush)

    (ws-client/close socket)

    @received-messages))

(comment
  (send-one-time-ws-request))