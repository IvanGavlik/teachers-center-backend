## Does the ChatGPT API remember past messages?
   ➤ No.

Every request to the ChatGPT API is stateless.
If you do not include prior messages → the model behaves as if the conversation just started.

Example

If you send only this request:

{
"model": "gpt-4.1-mini",
"messages": [
{"role": "user", "content": "Who are you?"}
]
}


The next request:

{
"model": "gpt-4.1-mini",
"messages": [
{"role": "user", "content": "Tell me more."}
]
}


The model has zero idea what “more” means.

To maintain a conversation, you must send:

{
"model": "gpt-4.1-mini",
"messages": [
{"role": "user", "content": "Who are you?"},
{"role": "assistant", "content": "I am ChatGPT..."},
{"role": "user", "content": "Tell me more."}
]
}

✅ 2. MANY USERS at once (high concurrency)

You must store each user’s conversation history on your backend.

Best solution:

✔ Redis for storing chat history (fast read/write)
✔ Postgres for long-term storage (optional)

Why Redis?

very fast (<1 ms)

perfect for WebSocket heavy traffic

n users can have thousands of messages → Redis handles this trivially

📌 3. How to store conversation history
Option A — Keep full chat log in Redis

Key format:

chat:user123:messages


Each request:

You LPUSH or RPUSH messages

Before calling OpenAI you LRANGE last N messages (e.g., 10)

Redis example (using Carmine):

(require '[taoensso.carmine :as car])

(def redis-conn {:pool {} :spec {:uri "redis://localhost:6379"}})

(defmacro wcar* [& body] `(car/wcar redis-conn ~@body))

(defn add-message! [user-id role content]
(wcar* (car/rpush (str "chat:" user-id ":messages")
{:role role :content content})))

(defn fetch-history [user-id limit]
(wcar* (car/lrange (str "chat:" user-id ":messages")
(- limit) -1)))

📌 4. How to call the ChatGPT API with history

Example:

(defn call-openai [history new-user-message]
(let [messages (concat history
[{:role "user" :content new-user-message}])
payload {:model "gpt-4.1-mini"
:messages messages}
response @(http/post "https://api.openai.com/v1/chat/completions"
{:headers {"Authorization" (str "Bearer " OPENAI_KEY)}
:body (json/generate-string payload)
:async? true})]
response))

💡 5. Flow for a single user message (important!)
When WebSocket event arrives:
on-receive ->
1. read last 10–20 messages from Redis
2. add the new message to Redis
3. send messages to OpenAI API (async)
4. send response back to FE
5. add assistant’s message to Redis

Why this works?

Redis handles thousands of users

OpenAI API gets full context on each call

Chatbot behaves like a real conversation

⚠️ 6. What about concurrency with 1000s of users?

Redis operations:

LPUSH/RPUSH: O(1)

LRANGE on last 20 messages: very cheap

OpenAI API:

each request is separate

Redis keeps user contexts separated

Memory:

If you limit history to last 20 messages per user:

2000 users × 20 messages × ~500 bytes/message ≈ 20MB → safe

If you want infinite history, move older messages to Postgres with a background worker.

🧠 7. Summary of recommended architecture
WebSockets (http-kit)

Handle connection + request events
NO heavy work inside event loop.

Redis

read chat history

write new messages

fast access on each request

OpenAI API

receives complete conversation context each time

Optional background workers

flush old messages from Redis → Postgres

🧩 Complete example snippet
(require '[org.httpkit.server :as ws])
(require '[taoensso.carmine :as car])
(require '[cheshire.core :as json])

(defn handle-user-msg [user-id msg callback]
(future
(let [history (fetch-history user-id 20)
          _       (add-message! user-id "user" msg)
ai-resp (call-openai history msg)]
(add-message! user-id "assistant" ai-resp)
(callback ai-resp))))

(defn ws-handler [req]
(ws/with-channel req channel
(ws/on-receive channel
(fn [msg]
(let [{:keys [user-id text]} (json/parse-string msg true)]
(handle-user-msg user-id text
(fn [resp]
(ws/send! channel (json/generate-string {:text resp})))))))))

(ws/run-server ws-handler {:port 3001})