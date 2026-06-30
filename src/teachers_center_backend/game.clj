(ns teachers-center-backend.game
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [teachers-center-backend.db :as db]))

(def mode->template
  {"quiz"            "games/quiz.html"
   "multiple-choice" "games/quiz.html"})

(defn- safe-json [data]
  (-> (json/generate-string data)
      (str/replace "</" "<\\/")))

(defn- load-template [resource-path]
  (when-let [url (io/resource resource-path)]
    (slurp url)))

(defn- inject-data [html data]
  (str/replace html "{{QUIZ_DATA}}" (safe-json data)))

(def not-found-page
  "<!DOCTYPE html>
<html lang=\"en\">
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>Activity Not Found</title>
  <style>
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
         background:#f0f4f8;min-height:100vh;display:flex;
         align-items:center;justify-content:center;margin:0;padding:16px}
    .card{background:white;border-radius:16px;padding:48px 32px;
          max-width:400px;width:100%;text-align:center;
          box-shadow:0 4px 24px rgba(0,0,0,0.08)}
    h1{font-size:20px;color:#1a1a2e;margin-bottom:8px}
    p{font-size:14px;color:#6b7280;line-height:1.6}
  </style>
</head>
<body>
  <div class=\"card\">
    <h1>Activity not found</h1>
    <p>This activity may have expired or the link is incorrect.<br>Ask your teacher for a new link.</p>
  </div>
</body>
</html>")

(defn game-handler [activity-id]
  (let [activity (db/get-interactivity activity-id)]
    (if-let [template-path (and activity (mode->template (:mode activity)))]
      (if-let [html (load-template template-path)]
        (do
          (log/info "Serving game" (:mode activity) "for activity" activity-id)
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (inject-data html activity)})
        (do
          (log/error "Template not found:" template-path)
          {:status  500
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    "<html><body>Game template missing. Contact support.</body></html>"}))
      (do
        (log/warn "Activity not found or unknown mode for id:" activity-id)
        {:status  404
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    not-found-page}))))
