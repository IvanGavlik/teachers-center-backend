# Foreign Language Teachers Chat App - Technical Architecture

> AI-powered chat application for foreign language teachers with PowerPoint/Google Slides integration

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Frontend-Backend Communication Comparison](#frontend-backend-communication-comparison)
- [Technology Stack](#technology-stack)
- [Clojure Backend Setup](#clojure-backend-setup)
- [Implementation Examples](#implementation-examples)
- [Database Schema](#database-schema)
- [Deployment](#deployment)
- [Development Workflow](#development-workflow)

---

## Overview

### What We're Building

A chat application specifically designed for foreign language teachers that:
- Runs as a PowerPoint or Google Slides add-in
- Allows teachers to chat with an AI assistant
- AI responses can automatically create/update slides in the presentation
- Provides educational content, exercises, and teaching materials

### Key Features

- **Real-time chat** with AI (ChatGPT integration)
- **Streaming responses** (word-by-word like ChatGPT)
- **Slide manipulation** as side effects of AI responses
- **Cross-platform** (PowerPoint & Google Slides)
- **Multi-user support** with authentication
- **Chat history** persistence

---

## Architecture

### High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    FRONTEND (Add-in)                            │
│                                                                 │
│  ┌────────────────────────────┐  ┌─────────────────────────┐  │
│  │   PowerPoint Add-in        │  │   Google Slides Add-on  │  │
│  │   (Office.js)              │  │   (Apps Script)         │  │
│  │                            │  │                         │  │
│  │  ┌──────────┐ ┌──────────┐│  │ ┌──────────┐ ┌────────┐│  │
│  │  │ Chat UI  │ │  Slide   ││  │ │ Chat UI  │ │ Slide  ││  │
│  │  │          │ │Controller││  │ │          │ │Control ││  │
│  │  └──────────┘ └──────────┘│  │ └──────────┘ └────────┘│  │
│  └────────────────────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
           │                                    │
           │ WebSocket/Ajax (http-kit)          │ REST API (Ring/Compojure)
           │ - Real-time chat                   │ - CRUD operations
           │ - AI streaming                     │ - Content generation
           │ - Slide updates                    │ - Health checks
           ▼                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    BACKEND (Clojure/JVM)                        │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              Ring + Compojure (HTTP Router)               │ │
│  │              - REST endpoints                             │ │
│  │              - Middleware (CORS, JSON)                    │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │           http-kit (WebSocket + HTTP server)              │ │
│  │           - High-performance async server                 │ │
│  │           - WebSocket support                             │ │
│  │           - Lightweight and fast                          │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              AI Integration Layer                         │ │
│  │              - ChatGPT API wrapper (clj-openai)           │ │
│  │              - Prompt engineering                         │ │
│  │              - Streaming response handler                 │ │
│  │              - Slide action parser                        │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              Authentication (Buddy)                       │ │
│  │              - JWT token validation                       │ │
│  │              - OAuth 2.0 integration                      │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              Database Layer                               │ │
│  │              - next.jdbc (connection pool)                │ │
│  │              - HugSQL (SQL as data)                       │ │
│  │              - HoneySQL (query builder)                   │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │              Async/Concurrency                            │ │
│  │              - core.async (channels, go blocks)           │ │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│                  PostgreSQL Database                            │
│                  - User accounts & authentication               │
│                  - Chat history                                 │
│                  - Slide templates                              │
│                  - User preferences                             │
└─────────────────────────────────────────────────────────────────┘
```

### Communication Flow

```
User sends message
    │
    ▼
Frontend (Chat UI)
    │
    ├─── WebSocket (http-kit) ─────────────┐
    │                                       │
    ▼                                       ▼
Clojure Backend                     Real-time Event
    │                                       │
    ├─── Parse & validate                  │
    │                                       │
    ├─── Add context (user, presentation)  │
    │                                       │
    ▼                                       │
ChatGPT API (streaming)                    │
    │                                       │
    ├─── Word-by-word response             │
    │                                       │
    ▼                                       │
Backend streams chunks ◄───────────────────┘
    │
    ├─── Parse slide actions
    │
    ▼
Frontend receives chunks
    │
    ├─── Display text (streaming)
    │
    ├─── Execute slide actions
    │    (Office.js / Apps Script)
    │
    ▼
Slides updated in presentation
```

---

## Frontend-Backend Communication Comparison

### Complete Options Matrix

| Protocol | Latency | Bandwidth | Browser Support | Complexity | Bidirectional | Real-Time | Best Use Case |
|----------|---------|-----------|----------------|------------|---------------|-----------|---------------|
| **REST** | Medium-High | High | ✅ Universal | Low | ❌ | ❌ | CRUD, APIs |
| **GraphQL** | Medium | Medium | ✅ Universal | Medium | ⚠️ (subscriptions) | ⚠️ (subscriptions) | Complex queries |
| **gRPC** | Very Low | Very Low | ⚠️ Proxy needed | High | ✅ | ✅ | Backend-to-backend |
| **WebSocket** | Very Low | Very Low | ✅ Universal | Medium | ✅ | ✅ | **Chat ✅** |
| **Socket.IO** | Low | Low | ✅ Universal | Low | ✅ | ✅ | **Chat ✅** |
| **SSE** | Low | Medium | ✅ Universal | Low | ❌ | ⚠️ One-way | Live feeds |
| **WebRTC** | Extremely Low | Very Low | ✅ Good | Very High | ✅ | ✅ | P2P, gaming |
| **Short Polling** | High | Very High | ✅ Universal | Very Low | ⚠️ | ❌ | Legacy |
| **Long Polling** | Medium | Medium | ✅ Universal | Medium | ⚠️ | ⚠️ | Fallback |

### Our Choice: REST + WebSocket (via http-kit)

**REST for:**
- ✅ User authentication & registration
- ✅ Fetching slide templates
- ✅ Saving user preferences
- ✅ Uploading files
- ✅ Retrieving chat history

**WebSocket (http-kit) for:**
- ✅ Real-time chat messages
- ✅ Streaming AI responses (word-by-word)
- ✅ Live slide update notifications
- ✅ Typing indicators
- ✅ Presence (online/offline)

### Why http-kit?

**http-kit = High-Performance Async HTTP Server with WebSocket Support**

**Advantages:**
- ✅ **High performance**: Event-driven architecture
- ✅ **WebSocket support**: Built-in WebSocket handling
- ✅ **Lightweight**: Minimal dependencies
- ✅ **Async by default**: Non-blocking I/O
- ✅ **Simple API**: Easy to use and configure
- ✅ **Battle-tested**: Production-ready and reliable

---

## Technology Stack

### Backend (Clojure/JVM)

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Language** | Clojure | 1.12.0 | Functional, immutable, JVM-based |
| **HTTP Server** | http-kit | 2.8.0 | High-performance async HTTP server |
| **HTTP Framework** | Ring + Compojure | 1.12.2 | Request handling & routing |
| **Database** | PostgreSQL + next.jdbc | 1.3.939 | Relational database & JDBC wrapper |
| **SQL Library** | HugSQL | 0.5.3 | SQL in separate files |
| **HTTP Client** | clj-http | 3.13.0 | REST API calls |
| **AI Integration** | clj-openai | 0.11.2 | ChatGPT API wrapper |
| **Authentication** | Buddy | 3.0.323 | JWT & OAuth 2.0 |
| **Async** | core.async | 1.6.681 | Channels, go blocks |
| **JSON** | Cheshire | 5.13.0 | JSON parsing/generation |
| **Connection Pool** | HikariCP | 6.2.1 | Database connection pooling |

### Frontend (Add-in)

| Platform | Technology | API |
|----------|-----------|-----|
| **PowerPoint** | Office.js | JavaScript API for Office |
| **Google Slides** | Apps Script | Google Slides API |
| **Client WebSocket** | http-kit client | WebSocket |
| **Build** | shadow-cljs | ClojureScript compiler |

### Infrastructure

| Service | Technology | Purpose |
|---------|-----------|---------|
| **Database** | PostgreSQL 16+ | Primary data store |
| **Cache/Session** | Redis (optional) | Session storage, caching |
| **Hosting** | Railway / Render / Fly.io | Backend deployment |
| **Database Hosting** | Supabase / Railway | Managed PostgreSQL |

---

## Clojure Backend Setup

### Project Structure

```
teacher-chat-backend/
├── project.clj                  # Leiningen project file
├── README.md
├── TECHNICAL_ARCHITECTURE.md    # This file
├── resources/
│   ├── sql/
│   │   └── queries.sql          # HugSQL queries
│   └── config.edn               # Configuration
├── src/
│   └── teacher_chat/
│       ├── core.clj             # Main entry point
│       ├── config.clj           # Configuration loader
│       ├── routes.clj           # REST API routes
│       ├── websocket.clj        # http-kit WebSocket handlers
│       ├── middleware.clj       # Ring middleware
│       ├── auth.clj             # Authentication (Buddy)
│       ├── db.clj               # Database layer
│       ├── ai.clj               # ChatGPT integration
│       └── handlers/
│           ├── chat.clj         # Chat message handlers
│           ├── user.clj         # User CRUD handlers
│           └── template.clj     # Template handlers
└── test/
    └── teacher_chat/
        └── core_test.clj
```

### project.clj

```clojure
(defproject teacher-chat-backend "0.1.0-SNAPSHOT"
  :description "AI Chat Backend for Foreign Language Teachers"
  :url "https://github.com/yourorg/teacher-chat-backend"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.6.681"]

                 ;; Web Server & Framework
                 [ring/ring-core "1.12.2"]
                 [http-kit/http-kit "2.8.0"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.1"]

                 ;; Database
                 [com.github.seancorfield/next.jdbc "1.3.939"]
                 [com.layerware/hugsql "0.5.3"]
                 [org.postgresql/postgresql "42.7.4"]
                 [com.zaxxer/HikariCP "6.2.1"]

                 ;; HTTP Client & AI
                 [clj-http/clj-http "3.13.0"]
                 [net.clojars.flexiana/clj-openai "0.11.2"]

                 ;; Authentication
                 [buddy/buddy-auth "3.0.323"]
                 [buddy/buddy-sign "3.5.351"]
                 [buddy/buddy-hashers "2.0.167"]

                 ;; JSON
                 [cheshire/cheshire "5.13.0"]

                 ;; Configuration
                 [environ "1.2.0"]

                 ;; Logging
                 [com.taoensso/timbre "6.6.1"]]

  :main ^:skip-aot teacher-chat.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[ring/ring-mock "0.4.0"]]}})
```

### deps.edn (Alternative to Leiningen)

```clojure
{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/core.async {:mvn/version "1.6.681"}

        ;; Web
        ring/ring-core {:mvn/version "1.12.2"}
        http-kit/http-kit {:mvn/version "2.8.0"}
        ring/ring-json {:mvn/version "0.5.1"}
        compojure {:mvn/version "1.7.1"}

        ;; Database
        com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}
        com.layerware/hugsql {:mvn/version "0.5.3"}
        org.postgresql/postgresql {:mvn/version "42.7.4"}
        com.zaxxer/HikariCP {:mvn/version "6.2.1"}

        ;; HTTP Client & AI
        clj-http/clj-http {:mvn/version "3.13.0"}
        net.clojars.flexiana/clj-openai {:mvn/version "0.11.2"}

        ;; Auth
        buddy/buddy-auth {:mvn/version "3.0.323"}
        buddy/buddy-sign {:mvn/version "3.5.351"}
        buddy/buddy-hashers {:mvn/version "2.0.167"}

        ;; JSON
        cheshire/cheshire {:mvn/version "5.13.0"}

        ;; Config
        environ/environ {:mvn/version "1.2.0"}

        ;; Logging
        com.taoensso/timbre {:mvn/version "6.6.1"}}

 :aliases
 {:dev {:extra-paths ["test"]
        :extra-deps {ring/ring-mock {:mvn/version "0.4.0"}}}
  :run {:main-opts ["-m" "teacher-chat.core"]}
  :uberjar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.303"}}
            :exec-fn hf.depstar/uberjar
            :exec-args {:aot true
                        :jar "target/teacher-chat.jar"
                        :main-class teacher-chat.core}}}}
```

---

## Implementation Examples

### 1. Main Application Entry Point

**src/teacher_chat/core.clj**

```clojure
(ns teacher-chat.core
  (:require [org.httpkit.server :as httpkit]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [teacher-chat.routes :as routes]
            [teacher-chat.websocket :as ws]
            [teacher-chat.middleware :as middleware]
            [teacher-chat.db :as db]
            [taoensso.timbre :as log])
  (:gen-class))

(def app
  (ring/ring-handler
    (ring/router
      (concat routes/api-routes
              ws/sente-routes))
    (ring/create-default-handler)
    {:middleware [middleware/wrap-cors
                  middleware/wrap-auth
                  wrap-params
                  [wrap-json-body {:keywords? true}]
                  wrap-json-response]}))

(defn start-server! [port]
  (log/info "Starting server on port" port)
  (db/init-db!)
  (ws/start-router!)
  (httpkit/run-server #'app {:port port}))

(defn -main
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "3000"))]
    (start-server! port)))
```

### 2. REST API Routes

**src/teacher_chat/routes.clj**

```clojure
(ns teacher-chat.routes
  (:require [teacher-chat.handlers.user :as user]
            [teacher-chat.handlers.template :as template]
            [teacher-chat.handlers.chat :as chat]))

(def api-routes
  [["/api"
    ["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]

    ["/auth"
     ["/register" {:post user/register}]
     ["/login" {:post user/login}]
     ["/refresh" {:post user/refresh-token}]]

    ["/users"
     ["/:id" {:get user/get-user
              :put user/update-user
              :delete user/delete-user}]]

    ["/templates"
     ["" {:get template/list-templates
          :post template/create-template}]
     ["/:id" {:get template/get-template
              :put template/update-template
              :delete template/delete-template}]]

    ["/messages"
     ["" {:get chat/get-messages
          :post chat/save-message}]
     ["/history/:presentation-id" {:get chat/get-history}]]]])
```

### 3. WebSocket with http-kit

**src/teacher_chat/websocket.clj**

```clojure
(ns teacher-chat.websocket
  (:require [org.httpkit.server :as httpkit]
            [clojure.core.async :as async :refer [<! >! go go-loop chan]]
            [teacher-chat.ai :as ai]
            [taoensso.timbre :as log]))

;; Create http-kit WebSocket handler
(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket!
        (get-sch-adapter)
        {:user-id-fn (fn [ring-req]
                       (get-in ring-req [:params :client-id]))})]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))

;; Sente routes for Ring
(def sente-routes
  [["/chsk" {:get ring-ajax-get-or-ws-handshake
             :post ring-ajax-post}]])

;; Event handler multimethod
(defmulti event-msg-handler :id)

(defmethod event-msg-handler :default
  [{:keys [event id ?data ring-req ?reply-fn send-fn]}]
  (log/warn "Unhandled event:" event))

(defmethod event-msg-handler :chsk/ws-ping
  [_] nil) ; Ignore ping/pong

(defmethod event-msg-handler :chsk/uidport-open
  [{:keys [uid]}]
  (log/info "Client connected:" uid))

(defmethod event-msg-handler :chsk/uidport-close
  [{:keys [uid]}]
  (log/info "Client disconnected:" uid))

(defmethod event-msg-handler :chat/message
  [{:keys [?data uid ?reply-fn]}]
  (log/info "Received chat message from" uid ":" ?data)
  (go
    (try
      ;; Call AI API with streaming
      (let [response-chan (ai/stream-chatgpt-response
                            (:message ?data)
                            (:presentation-id ?data)
                            uid)]
        (loop []
          (when-let [chunk (<! response-chan)]
            (if (:error chunk)
              ;; Error occurred
              (chsk-send! uid [:chat/error {:message (:error chunk)}])

              ;; Send chunk to client
              (do
                (chsk-send! uid [:chat/ai-chunk chunk])
                (recur)))))

        ;; Signal completion
        (chsk-send! uid [:chat/ai-complete {}]))

      (catch Exception e
        (log/error e "Error processing chat message")
        (chsk-send! uid [:chat/error {:message "Internal server error"}])))))

(defmethod event-msg-handler :chat/typing
  [{:keys [?data uid]}]
  ;; Broadcast typing indicator to other users in same presentation
  (let [presentation-id (:presentation-id ?data)]
    (doseq [other-uid (:any @connected-uids)]
      (when (not= other-uid uid)
        (chsk-send! other-uid [:chat/user-typing
                               {:user-id uid
                                :presentation-id presentation-id}])))))

(defmethod event-msg-handler :presentation/join
  [{:keys [?data uid]}]
  (log/info "User" uid "joined presentation" (:presentation-id ?data))
  ;; Could track which users are in which presentations
  (chsk-send! uid [:presentation/joined {:success true}]))

;; Start event router
(defonce router (atom nil))

(defn start-router! []
  (reset! router
          (sente/start-server-chsk-router! ch-chsk event-msg-handler)))

(defn stop-router! []
  (when-let [stop-fn @router]
    (stop-fn)))
```

### 4. AI Integration (ChatGPT)

**src/teacher_chat/ai.clj**

```clojure
(ns teacher-chat.ai
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.core.async :as async :refer [chan go >! close!]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def openai-api-key (System/getenv "OPENAI_API_KEY"))
(def openai-base-url "https://api.openai.com/v1")

(def system-prompt
  "You are a helpful AI assistant for foreign language teachers.
   You help create educational content, exercises, and teaching materials.

   When appropriate, you can create or update slides in the presentation.
   To create a slide, include in your response:

   [SLIDE_ACTION: CREATE]
   Title: Slide Title Here
   Content:
   - Bullet point 1
   - Bullet point 2
   [/SLIDE_ACTION]

   To update a slide, use:
   [SLIDE_ACTION: UPDATE]
   Slide: 1
   Content: Updated content here
   [/SLIDE_ACTION]")

(defn parse-slide-actions
  "Extract slide actions from AI response"
  [response-text]
  (let [action-pattern #"\[SLIDE_ACTION: (CREATE|UPDATE)\](.*?)\[/SLIDE_ACTION\]"
        matches (re-seq action-pattern response-text)]
    (mapv (fn [[_ action-type content]]
            (let [lines (str/split-lines (str/trim content))
                  title (when (str/starts-with? (first lines) "Title:")
                         (str/replace (first lines) "Title:" ""))
                  slide-num (when (str/starts-with? (first lines) "Slide:")
                             (Integer/parseInt
                               (str/trim
                                 (str/replace (first lines) "Slide:" ""))))
                  content-lines (filter #(str/starts-with? % "-") lines)
                  content (mapv #(str/replace % #"^-\s*" "") content-lines)]
              {:type (keyword (str/lower-case action-type))
               :title title
               :slide-number slide-num
               :content content}))
          matches)))

(defn call-chatgpt-api
  "Make synchronous call to ChatGPT API"
  [message]
  (try
    (let [response (http/post (str openai-base-url "/chat/completions")
                     {:headers {"Authorization" (str "Bearer " openai-api-key)
                                "Content-Type" "application/json"}
                      :body (json/generate-string
                              {:model "gpt-4"
                               :messages [{:role "system" :content system-prompt}
                                         {:role "user" :content message}]
                               :temperature 0.7
                               :stream false})
                      :as :json})]
      (-> response :body :choices first :message :content))
    (catch Exception e
      (log/error e "Error calling ChatGPT API")
      nil)))

(defn stream-chatgpt-response
  "Stream ChatGPT response word-by-word. Returns a channel."
  [message presentation-id uid]
  (let [response-chan (chan 100)]
    (async/thread
      (try
        (let [response (http/post (str openai-base-url "/chat/completions")
                         {:headers {"Authorization" (str "Bearer " openai-api-key)
                                    "Content-Type" "application/json"}
                          :body (json/generate-string
                                  {:model "gpt-4"
                                   :messages [{:role "system" :content system-prompt}
                                             {:role "user" :content message}]
                                   :temperature 0.7
                                   :stream true})
                          :as :stream})]

          ;; Read Server-Sent Events stream
          (with-open [rdr (clojure.java.io/reader (:body response))]
            (let [full-response (atom "")]
              (doseq [line (line-seq rdr)]
                (when (str/starts-with? line "data: ")
                  (let [data (subs line 6)]
                    (when-not (= data "[DONE]")
                      (try
                        (let [parsed (json/parse-string data true)
                              delta (get-in parsed [:choices 0 :delta :content])]
                          (when delta
                            (swap! full-response str delta)
                            (async/>!! response-chan {:text delta})))
                        (catch Exception e
                          (log/warn "Error parsing SSE chunk:" e)))))))

              ;; Parse slide actions from full response
              (let [actions (parse-slide-actions @full-response)]
                (when (seq actions)
                  (async/>!! response-chan {:slide-actions actions}))))))

        (catch Exception e
          (log/error e "Error streaming ChatGPT response")
          (async/>!! response-chan {:error (str "Error: " (.getMessage e))}))

        (finally
          (close! response-chan))))

    response-chan))
```

### 5. Database Layer

**src/teacher_chat/db.clj**

```clojure
(ns teacher-chat.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs]
            [hugsql.core :as hugsql])
  (:import (com.zaxxer.hikari HikariDataSource)))

(def db-spec
  {:dbtype "postgresql"
   :dbname (or (System/getenv "DB_NAME") "teacher_chat")
   :host (or (System/getenv "DB_HOST") "localhost")
   :port (Integer/parseInt (or (System/getenv "DB_PORT") "5432"))
   :user (or (System/getenv "DB_USER") "postgres")
   :password (or (System/getenv "DB_PASSWORD") "postgres")})

(defonce datasource (atom nil))

(defn init-db! []
  (when-not @datasource
    (reset! datasource (connection/->pool HikariDataSource db-spec))))

(defn get-datasource []
  @datasource)

;; Load HugSQL queries
(hugsql/def-db-fns "sql/queries.sql")

;; Helper functions
(defn execute! [sql-fn & args]
  (apply sql-fn (get-datasource) args))

(defn query [sql-fn & args]
  (apply sql-fn (get-datasource) args))
```

**resources/sql/queries.sql**

```sql
-- :name create-users-table! :!
-- :doc Create users table if not exists
CREATE TABLE IF NOT EXISTS users (
  id SERIAL PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(255),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

-- :name create-messages-table! :!
-- :doc Create messages table
CREATE TABLE IF NOT EXISTS messages (
  id SERIAL PRIMARY KEY,
  user_id INTEGER REFERENCES users(id),
  presentation_id VARCHAR(255) NOT NULL,
  message TEXT NOT NULL,
  response TEXT,
  slide_actions JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);

-- :name create-templates-table! :!
-- :doc Create slide templates table
CREATE TABLE IF NOT EXISTS slide_templates (
  id SERIAL PRIMARY KEY,
  user_id INTEGER REFERENCES users(id),
  title VARCHAR(255) NOT NULL,
  description TEXT,
  content JSONB NOT NULL,
  language VARCHAR(50),
  level VARCHAR(50),
  created_at TIMESTAMP DEFAULT NOW()
);

-- :name insert-user! :! :n
-- :doc Insert a new user
INSERT INTO users (email, password_hash, name)
VALUES (:email, :password-hash, :name)
RETURNING id;

-- :name get-user-by-email :? :1
-- :doc Get user by email
SELECT * FROM users WHERE email = :email;

-- :name get-user-by-id :? :1
-- :doc Get user by ID
SELECT id, email, name, created_at, updated_at
FROM users WHERE id = :id;

-- :name update-user! :! :n
-- :doc Update user
UPDATE users
SET name = :name, updated_at = NOW()
WHERE id = :id;

-- :name insert-message! :! :n
-- :doc Insert a chat message
INSERT INTO messages (user_id, presentation_id, message, response, slide_actions)
VALUES (:user-id, :presentation-id, :message, :response, :slide-actions::jsonb)
RETURNING id;

-- :name get-messages-by-presentation :? :*
-- :doc Get all messages for a presentation
SELECT m.*, u.name as user_name
FROM messages m
JOIN users u ON m.user_id = u.id
WHERE m.presentation_id = :presentation-id
ORDER BY m.created_at ASC
LIMIT :limit;

-- :name get-recent-messages :? :*
-- :doc Get recent messages for a user
SELECT m.*, u.name as user_name
FROM messages m
JOIN users u ON m.user_id = u.id
WHERE m.user_id = :user-id
ORDER BY m.created_at DESC
LIMIT :limit;

-- :name insert-template! :! :n
-- :doc Insert a slide template
INSERT INTO slide_templates (user_id, title, description, content, language, level)
VALUES (:user-id, :title, :description, :content::jsonb, :language, :level)
RETURNING id;

-- :name get-templates-by-user :? :*
-- :doc Get all templates for a user
SELECT * FROM slide_templates
WHERE user_id = :user-id
ORDER BY created_at DESC;

-- :name get-template-by-id :? :1
-- :doc Get template by ID
SELECT * FROM slide_templates WHERE id = :id;
```

### 6. Authentication with Buddy

**src/teacher_chat/auth.clj**

```clojure
(ns teacher-chat.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.backends.token :refer [jws-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.hashers :as hashers]
            [teacher-chat.db :as db]))

(def secret (or (System/getenv "JWT_SECRET") "change-this-secret-in-production"))

(def token-expiry-hours 24)

(defn create-token
  "Create JWT token for user"
  [user]
  (let [exp-time (+ (quot (System/currentTimeMillis) 1000)
                    (* token-expiry-hours 60 60))]
    (jwt/sign {:user-id (:id user)
               :email (:email user)
               :name (:name user)
               :exp exp-time}
              secret)))

(defn verify-token
  "Verify and decode JWT token"
  [token]
  (try
    (jwt/unsign token secret)
    (catch Exception e
      nil)))

(defn hash-password
  "Hash password using bcrypt+sha512"
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn check-password
  "Check if password matches hash"
  [password hash]
  (hashers/check password hash))

(defn authenticate-user
  "Authenticate user with email and password"
  [email password]
  (when-let [user (db/get-user-by-email {:email email})]
    (when (check-password password (:password_hash user))
      (dissoc user :password_hash))))

;; Auth backend for Ring middleware
(def auth-backend
  (jws-backend {:secret secret
                :token-name "Bearer"
                :unauthorized-handler
                (fn [request metadata]
                  {:status 401
                   :body {:error "Unauthorized"}})}))

(defn wrap-auth [handler]
  (-> handler
      (wrap-authentication auth-backend)
      (wrap-authorization auth-backend)))

(defn require-auth
  "Middleware to require authentication"
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      {:status 401
       :body {:error "Authentication required"}})))
```

### 7. Request Handlers

**src/teacher_chat/handlers/user.clj**

```clojure
(ns teacher-chat.handlers.user
  (:require [teacher-chat.db :as db]
            [teacher-chat.auth :as auth]
            [buddy.auth :refer [authenticated?]]))

(defn register
  "Register a new user"
  [{:keys [body]}]
  (let [{:keys [email password name]} body]
    (if (and email password)
      (try
        (let [password-hash (auth/hash-password password)
              user-id (db/insert-user! {:email email
                                        :password-hash password-hash
                                        :name name})
              user {:id user-id :email email :name name}
              token (auth/create-token user)]
          {:status 201
           :body {:user user
                  :token token}})
        (catch Exception e
          {:status 400
           :body {:error "User already exists or invalid data"}}))
      {:status 400
       :body {:error "Email and password required"}})))

(defn login
  "Login user"
  [{:keys [body]}]
  (let [{:keys [email password]} body]
    (if-let [user (auth/authenticate-user email password)]
      (let [token (auth/create-token user)]
        {:status 200
         :body {:user user
                :token token}})
      {:status 401
       :body {:error "Invalid credentials"}})))

(defn get-user
  "Get user by ID"
  [{{:keys [id]} :path-params :as request}]
  (if (authenticated? request)
    (if-let [user (db/get-user-by-id {:id (Integer/parseInt id)})]
      {:status 200
       :body user}
      {:status 404
       :body {:error "User not found"}})
    {:status 401
     :body {:error "Unauthorized"}}))

(defn update-user
  "Update user"
  [{{:keys [id]} :path-params
    body :body
    :as request}]
  (if (authenticated? request)
    (let [user-id (Integer/parseInt id)
          current-user-id (get-in request [:identity :user-id])]
      (if (= user-id current-user-id)
        (do
          (db/update-user! {:id user-id :name (:name body)})
          {:status 200
           :body {:success true}})
        {:status 403
         :body {:error "Forbidden"}}))
    {:status 401
     :body {:error "Unauthorized"}}))

(defn delete-user
  "Delete user (admin only or self)"
  [{{:keys [id]} :path-params :as request}]
  {:status 501
   :body {:error "Not implemented"}})
```

### 8. Middleware

**src/teacher_chat/middleware.clj**

```clojure
(ns teacher-chat.middleware)

(defn wrap-cors
  "Add CORS headers"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization")))))

(defn wrap-logging
  "Log requests"
  [handler]
  (fn [request]
    (println "Request:" (:request-method request) (:uri request))
    (let [response (handler request)]
      (println "Response:" (:status response))
      response)))
```

---

## Database Schema

### Tables

#### users
```sql
CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(255),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
```

#### messages
```sql
CREATE TABLE messages (
  id SERIAL PRIMARY KEY,
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  presentation_id VARCHAR(255) NOT NULL,
  message TEXT NOT NULL,
  response TEXT,
  slide_actions JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_messages_user_id ON messages(user_id);
CREATE INDEX idx_messages_presentation_id ON messages(presentation_id);
CREATE INDEX idx_messages_created_at ON messages(created_at DESC);
```

#### slide_templates
```sql
CREATE TABLE slide_templates (
  id SERIAL PRIMARY KEY,
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  title VARCHAR(255) NOT NULL,
  description TEXT,
  content JSONB NOT NULL,
  language VARCHAR(50),
  level VARCHAR(50),
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_templates_user_id ON slide_templates(user_id);
CREATE INDEX idx_templates_language ON slide_templates(language);
CREATE INDEX idx_templates_level ON slide_templates(level);
```

---

## Deployment

### Environment Variables

```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=teacher_chat
DB_USER=postgres
DB_PASSWORD=your-password

# API Keys
OPENAI_API_KEY=sk-...

# Security
JWT_SECRET=change-this-to-a-random-secret-key

# Server
PORT=3000
ENV=production
```

### Docker Deployment

**Dockerfile**

```dockerfile
FROM clojure:temurin-21-lein-alpine

WORKDIR /app

# Copy project files
COPY project.clj /app/
COPY src /app/src
COPY resources /app/resources

# Download dependencies
RUN lein deps

# Build uberjar
RUN lein uberjar

# Expose port
EXPOSE 3000

# Run the application
CMD ["java", "-jar", "target/uberjar/teacher-chat-backend-0.1.0-SNAPSHOT-standalone.jar"]
```

**docker-compose.yml**

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: teacher_chat
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  backend:
    build: .
    ports:
      - "3000:3000"
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: teacher_chat
      DB_USER: postgres
      DB_PASSWORD: postgres
      OPENAI_API_KEY: ${OPENAI_API_KEY}
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      - postgres

volumes:
  postgres_data:
```

### Deploy to Railway

1. Create account at https://railway.app
2. Install Railway CLI: `npm i -g @railway/cli`
3. Login: `railway login`
4. Initialize: `railway init`
5. Add PostgreSQL: `railway add`
6. Deploy: `railway up`

### Deploy to Render

1. Create `render.yaml`:

```yaml
services:
  - type: web
    name: teacher-chat-backend
    env: clojure
    buildCommand: lein uberjar
    startCommand: java -jar target/uberjar/teacher-chat-backend-0.1.0-SNAPSHOT-standalone.jar
    envVars:
      - key: OPENAI_API_KEY
        sync: false
      - key: JWT_SECRET
        generateValue: true
      - key: DATABASE_URL
        fromDatabase:
          name: teacher-chat-db
          property: connectionString

databases:
  - name: teacher-chat-db
    databaseName: teacher_chat
    user: teacher_chat
```

2. Connect GitHub repo
3. Deploy

---

## Development Workflow

### REPL-Driven Development

```bash
# Start REPL
lein repl

# Or with deps.edn
clj -M:dev
```

In REPL:

```clojure
;; Load namespace
(require '[teacher-chat.core :as core] :reload)

;; Start server
(def server (core/start-server! 3000))

;; Test individual functions
(require '[teacher-chat.ai :as ai])
(ai/call-chatgpt-api "Hello, how are you?")

;; Reload specific namespace
(require 'teacher-chat.websocket :reload)

;; Test database
(require '[teacher-chat.db :as db])
(db/get-user-by-email {:email "test@example.com"})

;; Stop server
(.stop server)
```

### Testing

```bash
# Run all tests
lein test

# Run specific test
lein test teacher-chat.core-test

# With deps.edn
clj -M:test
```

### Building

```bash
# Create uberjar
lein uberjar

# Run uberjar
java -jar target/uberjar/teacher-chat-backend-0.1.0-SNAPSHOT-standalone.jar
```

---

## API Documentation

### REST Endpoints

#### Authentication

**POST /api/auth/register**
```json
// Request
{
  "email": "teacher@example.com",
  "password": "securepassword",
  "name": "John Doe"
}

// Response (201)
{
  "user": {
    "id": 1,
    "email": "teacher@example.com",
    "name": "John Doe"
  },
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**POST /api/auth/login**
```json
// Request
{
  "email": "teacher@example.com",
  "password": "securepassword"
}

// Response (200)
{
  "user": {
    "id": 1,
    "email": "teacher@example.com",
    "name": "John Doe"
  },
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

#### Templates

**GET /api/templates**
```json
// Response (200)
[
  {
    "id": 1,
    "title": "Spanish Greetings",
    "description": "Common greetings in Spanish",
    "language": "spanish",
    "level": "beginner",
    "content": {
      "slides": [...]
    }
  }
]
```

#### Messages

**GET /api/messages/history/:presentation-id**
```json
// Response (200)
[
  {
    "id": 1,
    "user_id": 1,
    "user_name": "John Doe",
    "message": "Create a slide about greetings",
    "response": "Here's a slide with common greetings...",
    "slide_actions": [
      {
        "type": "create",
        "title": "Common Greetings",
        "content": ["Hello", "Hi", "Good morning"]
      }
    ],
    "created_at": "2025-01-15T10:30:00Z"
  }
]
```

### WebSocket Events

#### Client → Server

**:chat/message**
```clojure
[:chat/message {:message "Create a vocabulary slide"
                :presentation-id "pres-123"}]
```

**:chat/typing**
```clojure
[:chat/typing {:presentation-id "pres-123"}]
```

**:presentation/join**
```clojure
[:presentation/join {:presentation-id "pres-123"}]
```

#### Server → Client

**:chat/ai-chunk**
```clojure
[:chat/ai-chunk {:text "Here's "}]
[:chat/ai-chunk {:text "a "}]
[:chat/ai-chunk {:text "slide..."}]
```

**:chat/ai-complete**
```clojure
[:chat/ai-complete {:slide-actions [{:type :create
                                     :title "Vocabulary"
                                     :content [...]}]}]
```

**:chat/error**
```clojure
[:chat/error {:message "Failed to connect to AI service"}]
```

---

## Frontend Integration Example

### PowerPoint Add-in (Office.js)

```javascript
// Initialize WebSocket connection

const socket = io('wss://your-backend.com/chsk', {
  auth: { token: userToken }
});

// Connection events
socket.on('connect', () => {
  console.log('Connected to backend');
  socket.emit(':presentation/join', {
    presentationId: getCurrentPresentationId()
  });
});

// Receive AI response chunks
socket.on(':chat/ai-chunk', (data) => {
  appendToChatUI(data.text);
});

// Receive slide actions
socket.on(':chat/ai-complete', async (data) => {
  if (data.slideActions) {
    for (const action of data.slideActions) {
      await executeSlideAction(action);
    }
  }
});

// Send chat message
function sendMessage(text) {
  socket.emit(':chat/message', {
    message: text,
    presentationId: getCurrentPresentationId()
  });
}

// Execute slide action using Office.js
async function executeSlideAction(action) {
  await PowerPoint.run(async (context) => {
    const slides = context.presentation.slides;

    if (action.type === 'create') {
      // Create new slide
      const slide = slides.add();
      slide.shapes.addTextBox(action.title, {
        left: 100,
        top: 100,
        width: 600,
        height: 50
      });

      // Add content bullets
      let y = 200;
      for (const item of action.content) {
        slide.shapes.addTextBox(`• ${item}`, {
          left: 150,
          top: y,
          width: 500,
          height: 30
        });
        y += 40;
      }
    }

    await context.sync();
  });
}
```

### Google Slides Add-on (Apps Script)

```javascript
// HTML sidebar for chat UI
function onOpen() {
  SlidesApp.getUi()
    .createMenu('Teacher Chat')
    .addItem('Open Chat', 'showSidebar')
    .addToUi();
}

function showSidebar() {
  const html = HtmlService.createHtmlOutputFromFile('Sidebar')
    .setTitle('AI Teacher Assistant');
  SlidesApp.getUi().showSidebar(html);
}

// Execute slide action
function createSlideFromAction(action) {
  const presentation = SlidesApp.getActivePresentation();
  const slide = presentation.appendSlide(SlidesApp.PredefinedLayout.TITLE_AND_BODY);

  // Set title
  const titleShape = slide.getShapes()[0];
  titleShape.getText().setText(action.title);

  // Add content
  const bodyShape = slide.getShapes()[1];
  const bodyText = bodyShape.getText();
  bodyText.clear();

  action.content.forEach((item, index) => {
    bodyText.appendText(`• ${item}\n`);
  });
}
```

---

## Next Steps

1. **Set up development environment**
   - Install Leiningen or Clojure CLI
   - Set up PostgreSQL locally
   - Get OpenAI API key

2. **Initialize project**
   ```bash
   lein new app teacher-chat-backend
   cd teacher-chat-backend
   ```

3. **Add dependencies** (copy from project.clj above)

4. **Create database schema**
   ```bash
   psql -U postgres -d teacher_chat -f resources/sql/schema.sql
   ```

5. **Start REPL and develop iteratively**
   ```bash
   lein repl
   ```

6. **Build frontend add-ins** for PowerPoint and Google Slides

7. **Deploy to production** (Railway, Render, or Fly.io)

---

## Resources

### Clojure
- [Clojure Official Docs](https://clojure.org/)
- [ClojureDocs](https://clojuredocs.org/)
- [Clojure for the Brave and True](https://www.braveclojure.com/)

### Libraries Documentation
- [Ring](https://github.com/ring-clojure/ring)
- [Reitit](https://cljdoc.org/d/metosin/reitit/)
- [http-kit](https://http-kit.github.io/)
- [next.jdbc](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/)
- [HugSQL](https://www.hugsql.org/)
- [Buddy](https://funcool.github.io/buddy-auth/latest/)

### Office Add-ins
- [Office.js Documentation](https://learn.microsoft.com/en-us/office/dev/add-ins/)
- [PowerPoint JavaScript API](https://learn.microsoft.com/en-us/javascript/api/powerpoint)
- [Google Slides API](https://developers.google.com/slides)
- [Google Apps Script](https://developers.google.com/apps-script)

### AI Integration
- [OpenAI API Documentation](https://platform.openai.com/docs)
- [clj-openai](https://github.com/Flexiana/clj-openai)

---

## License

MIT License - See LICENSE file for details

---

## Contact

For questions or support, contact: your-email@example.com
