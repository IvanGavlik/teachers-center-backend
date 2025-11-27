(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [teachers-center-backend.system]
            [teachers-center-backend.core :refer [load-config]]))

; TODO move this to dev namespace
(ig-repl/set-prep! (fn []
                     (load-config)))

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