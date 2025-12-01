## Handling High Concurrency in http-kit (http-kit is non-blocking, but these must NOT block)
    * DO NOT:
        * Do Redis synchronous operations inside on-receive
        * Do JDBC calls inside on-receive
        * Do heavy parsing/JSON in the event loop
        * Make integration HTTP requests in blocking mode
    * DO:
        * Use redis async calls
        * Use futures / thread pools for heavy computation
        * Use non-blocking HTTP client (http-kit)



## Why “DO NOT” things are dangerous in on-receive

### ❌ 1. Redis synchronous call → blocks event loop

    (wcar* (car/get "cfg:123"))  ;; BAD!

This pauses the WebSocket thread until Redis responds.
With 1000+ WS connections → explosion.

### ❌ 2. JDBC call (Postgres) → blocking

    (jdbc/execute! db-spec ...)

This may take 5ms–50ms → death for event loop.


### ❌ 3. Heavy JSON parsing → blocking

    (json/parse-string huge-payload)

Large payloads can take tens of milliseconds.

### ❌ 4. Blocking HTTP request to integration

    (slurp "https://integration-api") ;; REALLY BAD

Blocking the event loop with network I/O = catastrophe.


## Where SHOULD you do these things?

### ✔ Inside async callbacks (Redis async)

Redis returns immediately → real work happens in the callback.

### ✔ On a dedicated thread pool (JDBC writes, heavy logic)
  * futures 
  * executor service 
  * core.async

### ✔ Using a non-blocking HTTP client

http-kit supports async requests.

### Arch example

WebSocket event loop (light)
│
▼
Lightweight handler (no blocking)
│
▼
Async tasks (Redis async, HTTP async)
│
▼
Heavy tasks offloaded to thread pool


clean, minimal, correct pattern:

🔹 WebSocket handler → only schedules async work
🔹 Redis async → triggers next callback
🔹 Integration API async → triggers next callback
🔹 DB write in thread pool → no blocking
🔹 FE response is immediate after integration
🔹 No blocking in event-loop

Parsing JSON JDBC write/read CPU heavly transformation with Worker Pool
Redis get (async) with Event Loop (async callback is also option)
Integration API HTTP with async callback

### Code example

(require '[org.httpkit.server :as httpkit])
(require '[taoensso.carmine :as car])
(require '[cheshire.core :as json])
(require '[clojure.edn :as edn])
(require '[clojure.java.jdbc :as jdbc])

;; Redis connection
(def redis-conn {:pool {} :spec {:uri "redis://localhost:6379"}})

;; Thread pool for blocking tasks (DB writes, heavy JSON, CPU)
(def worker-pool
(java.util.concurrent.Executors/newFixedThreadPool 16))

(defn schedule-worker [f]
(.submit worker-pool ^Runnable f))

;; WebSocket message handler
(defn on-ws-message [channel msg]
;; ❗ Do lightweight work only
(let [data (json/parse-string msg true)
user-id (:user-id data)
api-payload (:payload data)]

    ;; 1) Redis async (DO THIS – non-blocking)
    (car/async-get redis-conn (str "cfg:" user-id)
      (fn [config-json]

        ;; decode config asynchronously when needed
        (let [config (edn/read-string config-json)]

          ;; 2) Integration API async
          (httpkit/request
            {:method :post
             :url "https://integration.example.com/api/run"
             :timeout 30000
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:payload api-payload
                                          :config config})}

            (fn [{:keys [status body]}]

              ;; 3) Send FE response NOW (non-blocking)
              (httpkit/send! channel
                             (json/generate-string
                               {:status status
                                :body body}))

              ;; 4) Async DB write (off event loop)
              (schedule-worker
                (fn []
                  (try
                    (jdbc/insert! db-spec
                                  :responses
                                  {:user_id user-id
                                   :response body})
                    (catch Exception e
                      (println "DB write failed:" e))))))))))))
