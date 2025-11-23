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
           │ WebSocket/Ajax                     │ REST API
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

### Docker Deployment

TODO check do I have it in readme

---

## Development Workflow

### REPL-Driven Development

TODO check this

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

todo how to run test


## API Documentation

rest and web sockets


## License

MIT License - See LICENSE file for details
