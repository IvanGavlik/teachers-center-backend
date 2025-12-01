(ns teachers-center-backend.conversation.core)


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
{:user-id "1" :room_channel_connection [{:conversation-id "1" :messages []}]}

[{:room_channel_connection
  [{:conversation
    [{:message "1"}
     {:message "2"}]
    }]}]

(defn get-conversation [id db]
  {:user-id "TODO" :room_channel_connection [{:conversation-id id :messages []}]})

(defn create-conversation [user-id]
  {:user-id user-id :room_channel_connection [{:conversation-id (rand-int 100000) :messages []}]})

(defn current-conversation [req db]
  (let [user-id (get req :user-id)
        conversation (get req :conversation-id)]
    (if (seq conversation)
      (get-conversation (get req :conversation-id) db)
      (create-conversation user-id))))

(comment
  (def first-request { :user-id "123"
                      :conversation-id nil
                      :content "Help me plan a lesson" })
  (def second-request { :user-id "123"
                       :conversation-id "conv_456"
                       :content "Help me plan a lesson" })
  (current-conversation first-request nil)
  (current-conversation second-request nil)
  )


(comment
  (def empty-conv {:user-id "123" :room_channel_connection [{:conversation-id (rand-int 100000)
                                                             :state :gathering-info
                                                             :requirements {:format ""
                                                                            :length "112"
                                                                            :topic "123"
                                                                            :homework "123"}
                                                             :messages []}]})
  (def conv-one-msg {:user-id "123" :room_channel_connection [{:conversation-id (rand-int 100000)
                                                               :state :gathering-info
                                                               :requirements {:format ""
                                                                              :length "112"
                                                                              :topic "123"
                                                                              :homework "123"}
                                                               :messages [{:id    (rand-int 10)
                                                                           :created-at "timestap"
                                                                           :user-id "123"
                                                                           :content "Helo me create lesssion"
                                                                           }
                                                                          {:id    (rand-int 100)
                                                                           :created-at "timestap"
                                                                           :user-id "chat-gpt"
                                                                           :content "Here is the content"
                                                                           }
                                                                          ]}]})

  (current-messages empty-conv)
  (current-messages conv-one-msg)
  )

(defn current-messages [conversation]
  ; TODO we need to send all messages chat got does not remember history - investigate how to do this
  (let [user-id (get conversation :user-id)
        messages (:messages (first (get conversation :room_channel_connection)))]
    messages))


(defn ask-chat-gpt [conversation request-msg]
  (let [requirements (:requirements conversation)
        system-prompt ("from edn file")]
    (str "I am asking chat gpt passing requirements" requirements
         " system " system-prompt "user massage " request-msg "reciving msg in specific format")
    {:more-info true
     :content   "I have final answer app will mark conversation as completed"}
    ; TODO think about this
    ; it can ask for more info
    ; we can ask for preview
    ))


; TODO NEXT WRITE FEW tests for few conversations to see how this will work
; explore ask more info, see preview, final answer (also the starting new conversation)

; TODO chat-gpt is not avare of all messages
;  think about some strategy (send summary or all messages to him)
; - open new conversation in chat gpt for each new conversation in url or until we are end (web socket disconects)
; TODO do I want to save messages or not the claude approach (for now save)
(defn conversation [req]
  ; get room or create new one - for now we only have one
  ; get conversation or create new one
  ; get messages - I dont need to send message to chat gpt just send him last one from the reques

  ; check state(GATHERING_INFO/GENERATE) and slots (tracking requirements - what is missing) - this is done by chatgpt
  ; if missing something ask (validation), if complete generate answer

  (let [conversation (current-conversation req nil) ; TODO without the messages I dont need to send them to chat gpt I hope test this
        message (current-messages conversation)  ; - I dont need to send message to chat gpt just send him last one from the reques
        request-msg (:content req)]
    (ask-chat-gpt conversation request-msg))
  )

