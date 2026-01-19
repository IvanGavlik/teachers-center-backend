# How Redis fits into your architecture

## Flow with Redis as the fast read-path:
WebSocket request arrives
You fetch configuration / static params from Redis → <1 ms
You call integration endpoint using that data
You return the result to FE immediately
You optionally persist the result to Postgres asynchronously

## Flow for updating config:
Admin/dev updates config in Postgres
A background process or "cache invalidation" syncs it to Redis
Redis becomes the hot source for fast reads
This keeps Redis small, fresh, and extremely fast.

## Below is a detailed, practical plan for integrating:

http-kit WebSockets
Redis (via Carmine)
PostgreSQL (optional, async)

with a focus on high concurrency, low latency, and clean architecture.
This gives you an actionable blueprint you can implement immediately.

### http-kit is highly scalable

Based on non-blocking I/O (Netty-like)
Each WebSocket connection is handled by an event loop
A single JVM instance can maintain tens of thousands of connections

### Redis lookups must be non-blocking

To prevent backpressure and blocking the http-kit event loop:
* Use async Redis client API (Carmine async)
* Move any CPU-heavy or blocking logic to a thread pool (future/pool)

### Responses to the client must be sent ASAP

Your critical path:
WS Request → Redis lookup → Integration call → Respond to FE

### Postgres writes must be async, off main event loop
Use:
+ core.async
* claypoole (thread pool)
* simple futures/task executors

### Implement the WebSocket message handler

We keep the handler non-blocking by:

* Calling Redis async
* Calling integration async
* Writing to Postgres async

'''clojure
(require '[org.httpkit.server :as httpkit])

(defn on-ws-message [channel msg]
;; 1. Parse incoming message
(let [request-data (json/parse-string msg true)
user-id (:user-id request-data)]

    ;; 2. Get configuration from Redis (async)
    (car/async-get redis-conn (str "cfg:" user-id)
      (fn [config-json]
        (let [config (edn/read-string config-json)]

          ;; 3. Call integration endpoint (async HTTP)
          (httpkit/request
            {:method :post
             :url "https://integration.example.com/api/run"
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:input request-data
                                          :config config})}

            (fn [{:keys [status body]}]
              ;; 4. Respond to FE immediately
              (httpkit/send! channel
                             (json/generate-string {:status status
                                                    :response body}))

              ;; 5. Async DB write (Postgres)
              (future
                (try
                  (save-response-to-postgres user-id body)
                  (catch Exception e
                    (println "DB write failed:" e)))))))))))
'''

This ensures:

http-kit event loop is never blocked 
* Redis async is non-blocking
* HTTP kit request is async
* DB write is offloaded to a future

TODO
* Handling High Concurrency in http-kit (http-kit is non-blocking, but these must NOT block)
  * DO NOT:
    * Do Redis synchronous operations inside on-receive 
    * Do JDBC calls inside on-receive 
    * Do heavy parsing/JSON in the event loop 
    * Make integration HTTP requests in blocking mode
  * DO:
    * Use redis async calls 
    * Use futures / thread pools for heavy computation 
    * Use non-blocking HTTP client (http-kit
* Redis Cache Invalidation Strategy
  * A background worker refreshes Redis periodically:
    * Every 10 seconds 
    * Or watches a Postgres NOTIFY event