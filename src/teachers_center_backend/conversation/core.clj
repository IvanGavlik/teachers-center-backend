(ns teachers-center-backend.conversation.core
  (:require [teachers-center-backend.openapi.core :as openai]
            [teachers-center-backend.content :as content]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))


(def progress-messages
  {:starting ["Getting your lesson materials ready..."
              "Preparing your content..."
              "Setting up your request..."
              "Starting to work on your materials..."]
   :thinking ["Thinking about the best way to teach this..."
              "Crafting your lesson content..."
              "Working on your materials..."
              "Putting together your content..."]
   :creating ["Creating engaging content for your students..."
              "Building your slides..."
              "Generating learning materials..."
              "Designing your lesson content..."
              "Making learning fun and effective..."
              "Preparing classroom-ready materials..."
              "Tailoring content to your students' level..."
              "Crafting clear explanations and examples..."]
   :polishing ["Adding the finishing touches..."
               "Almost ready for your lesson..."
               "Polishing your materials..."
               "Finalizing your content..."
               "Making sure everything is classroom-ready..."]
   :complete ["Your materials are ready!"
              "All done! Ready for review."
              "Content created successfully!"
              "Ready to preview your slides!"]})

(defn report-progress!
  "Report progress for a given stage. Calls the provided callback with stage message.
   Randomly selects a message for the given stage."
  [on-progress-fn stage]
  (when on-progress-fn
    (let [messages (get progress-messages stage)
          message (rand-nth messages)]
      (log/debug "Reporting progress:" stage message)
      (on-progress-fn {:stage message}))))

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
  ;; TODO full implementation when I have db filter by channel name
  ;; For now return nil to create fresh conversation each time
  nil
  #_{:conversation-id id
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

(defn create-empty-conversation [user-id type requirements]
  {:conversation-id (rand-int 100000)
   :user user-id
   :type type
   :messages []
   :state :gathering-info ; TODO can be completed or gathering-info
   :requirements (if (seq requirements) requirements {}) #_{:format "flashcards" ; TODO for now implemeted in the model (openai-content.edn file)
                  :length "15-20"
                  :topic "food and restaurants"
                  :level "A1"
                  :language "Spanish"}})

;; TODO: Implement when DB is ready
#_(defn create-conversation [user-id]
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
  ;; Try to get existing conversation, fall back to empty if not found (or DB not implemented)
  (or (when (seq (:conversation-id req))
        (get-conversation (:channel-name req) (:conversation-id req) db))
      (create-empty-conversation (:user-id req) (:type req) (:requirements req))))


(defn get-conversation-template [type]
  (let [type-name (if (keyword? type) (name type) (str type))
        filename (str "openai-" type-name "-content.edn")
        resource (io/resource filename)]
    (if resource
      (-> resource slurp edn/read-string)
      (do
        (log/warn "Template not found for type:" type-name ", falling back to vocabulary")
        (-> (io/resource "openai-vocabulary-content.edn")
            slurp
            edn/read-string)))))

(defn ask-chat-gpt [openapi-client conversation-config current-messages request-msg settings]
  (let [msg-template (:message conversation-config)
        message (content/render-content msg-template (merge settings
                                                          {:request request-msg
                                                           :messages current-messages}))
        _ (prn "message for chat gpt " message)
        config (:config conversation-config)]
    (openai/chat-completion openapi-client message config))
  )


(defn edit-slide
  "Edit a single slide based on user instruction.

   Parameters:
   - open-api-client: OpenAI client for API calls
   - req: Request map with :edit containing {:slideIndex :currentSlide :originalRequest :originalType}
   - on-progress: Optional callback fn

   Returns: {:type \"edit\" :edit {:slideIndex N :slide {...}}}"
  [open-api-client req on-progress]
  (report-progress! on-progress :starting)

  (let [edit-data (:edit req)
        slide-index (:slideIndex edit-data)
        current-slide (:currentSlide edit-data)
        original-request (:originalRequest edit-data)
        original-type (:originalType edit-data)

        ;; Build combined request string with all context
        combined-request (str "ORIGINAL REQUEST: " original-request "\n\n"
                              "CURRENT SLIDE DATA: " (json/generate-string current-slide) "\n\n"
                              "EDIT INSTRUCTION: " (:content req))

        ;; Load per-type edit template (e.g., :edit-vocabulary)
        edit-template-type (keyword (str "edit-" original-type))
        conversation-config (get-conversation-template edit-template-type)

        _ (report-progress! on-progress :thinking)
        _ (report-progress! on-progress :creating)

        ;; Call GPT with combined request (empty messages since context is in request)
        settings (:requirements req)
        res (ask-chat-gpt open-api-client conversation-config [] combined-request settings)

        _ (report-progress! on-progress :polishing)

        res-content (:content (:message (first (:choices res))))
        _ (log/debug "edit-slide response:" res-content)
        res-data (json/parse-string res-content true)]

    ;; Wrap response in edit format
    {:type "edit"
     :edit {:slideIndex slide-index
            :slide res-data}}))

; TODO NEXT WRITE FEW tests for few conversations to see how this will work
; explore ask more info, see preview, final answer (also the starting new conversation)

; TODO chat-gpt is not avare of all messages
;  think about some strategy (send summary or all messages to him for now send all messages form current conversation
;
; - open new conversation in chat gpt for each new conversation in url or until we are end (web socket disconects)
; TODO do I want to save messages or not the claude approach (for now save)
; TODO lets have open-api-client as callback fn as on-progress  - expolore other options I want here have only business logic
(defn conversation
  "Process a conversation request and return response data.

   Parameters:
   - open-api-client: OpenAI client for API calls
   - req: Request map with :user-id :channel-name :conversation-id :type :content :requirements
   - on-progress: Optional callback fn called with {:stage \"message\" :percent N} at each stage

   Requirements example: {:format \"flashcards\" :length \"15-20\" :topic \"food\" :level \"A1\" :language \"Spanish\"}"
  ([open-api-client req]
   (conversation open-api-client req nil))
  ([open-api-client req on-progress]
   ;; Route edit requests to edit-slide function
   (if (= :edit (:type req))
     (edit-slide open-api-client req on-progress)

     ;; Normal generation flow
     (do
       ; get room or create new one - for now we only have one
       ; get conversation or create new one
       ; get messages - I dont need to send message to chat gpt just send him last one from the reques

       ; check state(GATHERING_INFO/GENERATE) and slots (tracking requirements - what is missing) - this is done by chatgpt
       ; if missing something ask (validation), if complete generate answer

       (log/debug "req " req)

       ;; Stage 1: Starting (10%)
       (report-progress! on-progress :starting)

       (let [req-type (:type req)
         type-name (if (keyword? req-type) (name req-type) (str req-type))
         conversation-config (get-conversation-template req-type)
         db nil
         current-messages (:messages (current-conversation req db))
         _ (log/debug "current-messages" current-messages)

         _ (report-progress! on-progress :thinking)

         request-msg (:content req)

         _ (report-progress! on-progress :creating)

         settings (:requirements req)
         res (ask-chat-gpt open-api-client conversation-config current-messages request-msg settings)

         _ (report-progress! on-progress :polishing)

         res-content (:content (:message (first (:choices res))))
         _ (log/debug "res-content " res-content)
         res-data (json/parse-string res-content true)]
     (if (:requirements-not-met res-data)
       (do
         (log/debug "mark conversation as not done"))
       (do
         (log/debug "mark conversation as done")))
     ;; Include type in response for frontend routing
     ;; Stage 5 (100%) is implicit when the final response is sent
     (assoc res-data :type type-name))))))


(comment
  ; TODO test with contesxt in which we have current message and it has to continue conversation
  (let [req {:user-id "user-123"
             :type "generate vocabulary"
             :content "I need a vocabulary list for my Spanish A1 class about food and restaurants"
             }
        res (conversation req)]
   (prn "data" res)))