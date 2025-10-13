(ns teachers-center-backend.core
  (:require [aero.core :refer [read-config]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [teachers-center-backend.system])
  (:gen-class))

; using areo and intergrant together
; https://pixelated-noise.com/blog/2022/04/28/integrant-and-aero/index.html
(defmethod aero.core/reader 'ig/ref
  [{:keys [profile] :as opts} tag value]
  (integrant.core/ref value))

; should this and aero.core/reader be moved to system
(defn load-config []
  (read-config (io/resource "config.edn")))

(defn -main [& args]
  (println "Starting Teachers Center Backend...")
  (let [config (load-config)]
    (ig/init config)))

(defn start-system []
  (let [config (load-config)]
    (ig/init config)))

(defn stop-system [system]
  (ig/halt! system))