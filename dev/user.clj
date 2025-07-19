(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [teachers-center-backend.system]))

(ig-repl/set-prep! (fn []
                     (-> "config.edn"
                         io/resource
                         slurp
                         ig/read-string)))

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)
(def reset-all ig-repl/reset-all)

(comment
  ;; Development workflow
  (go)     ;; Start the system
  (halt)   ;; Stop the system
  (reset)  ;; Restart the system
  
  ;; Access the running system
  state/system
  
  ;; Test endpoints manually
  (require '[clj-http.client :as http])
  (http/get "http://localhost:3000/health"))