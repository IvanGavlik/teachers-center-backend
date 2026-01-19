(require '[ring.adapter.jetty :as jetty])

(defn simple-handler [request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"status\": \"ok\"}"})

(println "Starting simple test server on port 3001...")
(jetty/run-jetty simple-handler {:port 3001 :join? false})
(println "Server started!")

(Thread/sleep 2000)
(println "Testing server...")
(try
  (let [response (slurp "http://localhost:3001")]
    (println "Response:" response))
  (catch Exception e
    (println "Error:" (.getMessage e))))