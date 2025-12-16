(ns dev
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [gniazdo.core :as ws-client]
            [teachers-center-backend.system]
            [teachers-center-backend.core :refer [load-config]]))

(ig-repl/set-prep! (fn []
                     (load-config)))

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)
(def reset-all ig-repl/reset-all)


(defn send-one-time-ws-request  []
  (let [socket (ws-client/connect "ws://localhost:2000/ws?name=John"
                                  :on-receive (fn [msg] (println "Received:" msg))
                                  :on-close (fn [status reason] (println "Closed:" status reason))
                                  :on-error (fn [e] (println "Error:" e)))]
    (ws-client/send-msg socket "Hello from REPL! 3")
    (ws-client/close socket)))

(comment
  (send-one-time-ws-request))