(ns user
  (:require [dev :as dev]))


(comment
  ;; Development workflow
  (dev/go)     ;; Start the system
  (dev/halt)   ;; Stop the system
  (dev/reset)  ;; Restart the systemC
  
  ;; Access the running system
  state/system
  
  ;; Test endpoints manually
  (require '[clj-http.client :as http])
  (http/get "http://localhost:2000/health"))