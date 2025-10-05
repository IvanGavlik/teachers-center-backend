(ns teachers-center-backend.core
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [teachers-center-backend.system])
  (:gen-class))

; load-config 
(defn load-config []
  (-> "config.edn"
      io/resource
      slurp
      ig/read-string))

(defn -main [& args]
  (println "Starting Teachers Center Backend...")
  (let [config (load-config)]
    (ig/init config)))

(defn start-system []
  (let [config (load-config)]
    (ig/init config)))

(defn stop-system [system]
  (ig/halt! system))