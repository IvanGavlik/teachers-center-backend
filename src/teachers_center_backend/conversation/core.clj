(ns teachers-center-backend.conversation.core
  (:require [teachers-center-backend.openapi.core :as openai]
            [teachers-center-backend.content :as content]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

; TODO next step redis DB

; TODO - think simple working example for implementation v1
;  (do I need redis and postgres do I have client data)
; what about chatgpt before implememing think more about complicated cases and how can I
; go from v1 to v2 and v3 ... (V3 - maybe pdf support)

;   Lifecycle Example
;
;  1. User Opens App
;
;  - FE: Establishes WebSocket
;  - BE: Creates connection_id_abc, maps to user_123
;
;  2. User Starts New Chat
;
;  - FE: Sends { conversation_id: null, content: "Help me plan a lesson" }
;  - BE: Creates conversation_id_456, stores in DB, processes message
;  - BE: Responds with { conversation_id: "conv_456", content: "..." }
;  - FE: Stores conv_456 for subsequent messages
;
;  3. User Sends Follow-up
;
;  - FE: Sends { conversation_id: "conv_456", content: "Make it shorter" }
;  - BE: Loads conversation state, processes, responds
;
;  4. User Closes Browser
;
;  - BE: Detects disconnect, removes connection_id_abc
;  - BE: Conversation conv_456 stays in DB
;
;  5. User Returns Later
;
;  - FE: New WebSocket → BE assigns connection_id_xyz
;  - FE: Requests conversation list for user_123
;  - BE: Returns [conv_456, conv_789, ...]
;  - FE: User clicks conv_456 → loads history → continues chatting

; Correct structure (data model) - model.txt
;   * but what do I need for first implemenation (just simple chat-gpt wrapper)
;   * if I have good api for FE (BE implementation can change)
; on every disscooect/connect do i crate new room_channel_connection or new conversation

(defn get-conversation [channel-name id db]
  ; TODO full implementation when I have db filter by channel name
  {:conversation-id id
   :user "user-123"
   :type "generate vocabulary"
   :messages [{:message-id 1
               :user "user-123"
               :content "I need a vocabulary list for my Spanish A1 class about food and restaurants"}
              {:message-id 2
               :user "chat-gpt"
               :content "I'd be happy to help! What format would you like the vocabulary list in?"}
              {:message-id 3
               :user "user-123"
               :content "I need it as flashcards with Spanish word, English translation, and example sentences"}
              {:message-id 4
               :user "chat-gpt"
               :content "Perfect! How many vocabulary items would you like?"}
              {:message-id 5
               :user "user-123"
               :content "Around 15-20 words please"}
              {:message-id 6
               :user "chat-gpt"
               :content "Great! Here's your vocabulary list with 18 food and restaurant-related words..."}]
   :state "completed"
   :requirements {:format "flashcards"
                  :length "15-20"
                  :topic "food and restaurants"
                  :level "A1"
                  :language "Spanish"}})

(defn create-empty-conversation [user-id]
  {:conversation-id 1
   :user user-id
   :type "generate vocabulary"
   :messages []
   :state "completed"
   :requirements {:format "flashcards"
                  :length "15-20"
                  :topic "food and restaurants"
                  :level "A1"
                  :language "Spanish"}})

(defn create-conversation [user-id]
  {:conversation-id 1
   :user user-id
   :type "generate vocabulary"
   :messages [{:message-id 1
               :user "user-123"
               :content "I need a vocabulary list for my Spanish A1 class about food and restaurants"}
              {:message-id 2
               :user "chat-gpt"
               :content "I'd be happy to help! What format would you like the vocabulary list in?"}
              {:message-id 3
               :user "user-123"
               :content "I need it as flashcards with Spanish word, English translation, and example sentences"}
              {:message-id 4
               :user "chat-gpt"
               :content "Perfect! How many vocabulary items would you like?"}
              {:message-id 5
               :user "user-123"
               :content "Around 15-20 words please"}
              {:message-id 6
               :user "chat-gpt"
               :content "Great! Here's your vocabulary list with 18 food and restaurant-related words..."}]
   :state "completed"
   :requirements {:format "flashcards"
                  :length "15-20"
                  :topic "food and restaurants"
                  :level "A1"
                  :language "Spanish"}})

(defn current-conversation [req db]
  (if (seq (:conversation-id req))
    (get-conversation (:channel-name req) (:conversation-id req) db)
    (create-empty-conversation (:user-id req))))                  ; create-conversation create-empty-conversation


(defn get-conversation-template [type]
  "v1 Read requirements from edn file"
  "v2 Read requirements from db - also include (student constraints) class info"
  (-> (io/resource "openai-content.edn")
      slurp
      edn/read-string))

(defn ask-chat-gpt [openapi-client conversation-config current-messages request-msg]
  (let [msg-template (:message conversation-config)
        message (content/render-content msg-template {:request request-msg
                                                      :messages current-messages})
        config (:config conversation-config)]
    (openai/chat-completion openapi-client message config))
  )


; TODO NEXT WRITE FEW tests for few conversations to see how this will work
; explore ask more info, see preview, final answer (also the starting new conversation)

; TODO chat-gpt is not avare of all messages
;  think about some strategy (send summary or all messages to him for now send all messages form current conversation
;
; - open new conversation in chat gpt for each new conversation in url or until we are end (web socket disconects)
; TODO do I want to save messages or not the claude approach (for now save)
(defn conversation [open-api-client req]
  "request have :user-id :channel-name :conversation-id :type :content"
  ; get room or create new one - for now we only have one
  ; get conversation or create new one
  ; get messages - I dont need to send message to chat gpt just send him last one from the reques

  ; check state(GATHERING_INFO/GENERATE) and slots (tracking requirements - what is missing) - this is done by chatgpt
  ; if missing something ask (validation), if complete generate answer

  (let [conversation-config (get-conversation-template (:type req))
        db nil
        current-messages (:messages (current-conversation req db))
        request-msg (:content req)
        res (ask-chat-gpt open-api-client conversation-config current-messages request-msg)
        res-content (:content (:message (first (:choices res))))
        res-data (json/parse-string res-content true)]
      (if (:requirements-not-meet res-data)
        (do
          (prn "mark conversation as  as not done "))
        (do
          ;TODO if CONVERSATION is done no need to sent IN THE REquest message cuttnet massage
          ; we create new conversation
          (prn "mark conversation as done")))
      res-data
    )
  )


(comment
  ; TODO test with contesxt in which we have current message and it has to continue conversation
  (let [req {:user-id "user-123"
             :type "generate vocabulary"
             :content "I need a vocabulary list for my Spanish A1 class about food and restaurants"
             }
        res (conversation req)]
   (prn "data" res)))