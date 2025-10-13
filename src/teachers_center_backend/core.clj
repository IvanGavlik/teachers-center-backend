(ns teachers-center-backend.core
  (:require [aero.core :refer [read-config]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [teachers-center-backend.system])
  (:import [io.github.cdimascio.dotenv Dotenv])
  (:gen-class))

; using areo and intergrant together
; https://pixelated-noise.com/blog/2022/04/28/integrant-and-aero/index.html
(defmethod aero.core/reader 'ig/ref
  [{:keys [profile] :as opts} tag value]
  (integrant.core/ref value))

; Custom Aero reader for #env that works with dotenv-java
; This allows #env to read from .env file in addition to system environment
(def ^:dynamic *dotenv* nil)

(defmethod aero.core/reader 'env
  [opts tag value]
  (let [env-var (name value)]
    (or
      ;; First try system environment
      (System/getenv env-var)
      ;; Then try dotenv if loaded
      (when *dotenv* (.get *dotenv* env-var)))))

; Load .env file and read config
(defn load-config []
  (binding [*dotenv* (-> (Dotenv/configure)
                         (.ignoreIfMissing)
                         (.load))]
    (when (.exists (io/file ".env"))
      (println "Loading environment variables from .env file"))
    (read-config (io/resource "config.edn"))))

(defn -main [& args]
  (println "Starting Teachers Center Backend...")
  (let [config (load-config)]
    (ig/init config)))

(defn start-system []
  (let [config (load-config)]
    (ig/init config)))

(defn stop-system [system]
  (ig/halt! system))