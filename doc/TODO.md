16.12.2025
REPL log levels set 

12.11.2025.
create web-scket-tutorial with example implementationa
use this as starting point to finish and make impl for web socket 
and then implement client 

After this lets go to production alpha version

23.11.2025.
start implementation of web socket

13.11.2025.
stao next to implement WebSocket comunication


TODO - posalji mu ponovo ovu poruku takošer neka poglega moj FE (power point prezentacija) da vidi kako je postavljen 
i koja polja koristim 
takošer neka pogleda BE implementaciju (edn file) za definiranje razgovora sa chat gpt

također imat cemo i siri kontest sa rest endpointa koji ce stici 
- sve ga to ispitaj 


I want to implement chating app on the FE the user is human person on the BE the server is AI tool ... what I want
ot have is that durring converstations that will be initiaded by human server can send not only response but
aditional messages asking for more info so that he can give to user better answer .... Example User a: generate me
vocabulary for A1 language level on english Server instad of answering he askes how many words do you need ? User a:
I need 10 then Server; what is output, how it should look like? User I want to have powwer point slides for 3 items
per page then Server do you want examples ? .... so the question is how to design this type of the communication
from the perspective of web sockets and how to implement/define the questions that Server has to ask

● Designing a Conversational AI with Clarifying Questions

This is a great use case! Let me help you design this multi-turn conversation system where the AI asks clarifying
questions before generating content.

Architecture Overview

User                    WebSocket                   AI Backend
|                          |                            |
|--"Generate vocabulary"-->|                            |
|                          |----> Parse & Analyze       |
|                          |      Need: word_count?     |
|                          |      Need: output_format?  |
|                          |      Need: examples?       |
|<---"How many words?"-----|<-------                    |
|                          |                            |
|--"10 words"------------->|                            |
|                          |----> Store: word_count=10  |
|                          |      Still need: format    |
|<---"What format?"--------|<-------                    |
|                          |                            |
|--"PowerPoint slides"---->|                            |
|                          |----> All info collected!   |
|                          |----> Generate content ---->|
|<---"Here's the vocab"----|<-------                    |

Design Approach: Two Strategies

Strategy 1: Rule-Based (Recommended for Structured Content)

Define explicit parameters for each content type and ask systematically.

Strategy 2: LLM-Driven (More Flexible)

Let the AI decide what questions to ask based on the conversation.

Implementation: Hybrid Approach

I recommend a hybrid approach: Use structured parameters but let the LLM generate natural-sounding questions.

  ---
1. Message Protocol Design

Message Types

;; Message types flowing through WebSocket
{:type :user-message          ;; User's response
:content "10 words"}

{:type :ai-question           ;; AI asking for clarification
:content "How many words do you need?"
:parameter :word-count       ;; What we're asking about
:options ["5" "10" "15" "20"] ;; Optional: suggest options
:conversation-id "conv-123"}

{:type :ai-response           ;; AI's final answer
:content "Here's your vocabulary..."
:conversation-id "conv-123"}

{:type :ai-thinking           ;; Optional: show AI is processing
:content "Generating vocabulary..."}

Conversation State

;; Track conversation state on backend
{:conversation-id "conv-123"
:user-id "user-456"
:intent :generate-vocabulary
:required-params {:word-count {:required true :collected false}
:output-format {:required true :collected false}
:include-examples {:required true :collected false}
:language {:required true :collected true :value "english"}
:level {:required true :collected true :value "A1"}}
:collected-values {:language "english"
:level "A1"}
:conversation-history [{:role "user" :content "generate me vocabulary for A1 level"}
{:role "assistant" :content "How many words do you need?"}
{:role "user" :content "10 words"}]
:status :gathering-info}  ;; or :ready-to-generate

  ---
2. Backend Implementation (Clojure)

Define Content Type Parameters

(ns teachers-center-backend.conversation
(:require [clojure.string :as str]))

;; Define required parameters for each content type
(def content-parameters
{:vocabulary {:word-count {:required true
:type :number
:question "How many vocabulary words would you like?"
:options [5 10 15 20]
:validation (fn [v] (and (number? v) (> v 0) (<= v 50)))}

                  :output-format {:required true
                                  :type :choice
                                  :question "What output format would you like?"
                                  :options ["PowerPoint slides" "PDF document" "Plain list" "Flashcards"]
                                  :validation (fn [v] (some #(= v %) ["powerpoint" "pdf" "list" "flashcards"]))}

                  :items-per-slide {:required false
                                    :type :number
                                    :question "How many words per slide?"
                                    :options [3 5 10]
                                    :depends-on {:output-format "powerpoint"}
                                    :validation (fn [v] (and (number? v) (> v 0)))}

                  :include-examples {:required true
                                     :type :boolean
                                     :question "Would you like example sentences for each word?"
                                     :options ["Yes" "No"]
                                     :validation (fn [v] (boolean? v))}}

     :grammar {:topic {:required true
                       :type :string
                       :question "Which grammar topic? (e.g., 'present simple', 'past tense')"
                       :validation (fn [v] (and (string? v) (> (count v) 2)))}

               :explanation-level {:required true
                                   :type :choice
                                   :question "How detailed should the explanation be?"
                                   :options ["Simple" "Detailed" "With examples"]
                                   :validation (fn [v] (some #(= v %) ["simple" "detailed" "with-examples"]))}

               :include-exercises {:required true
                                   :type :boolean
                                   :question "Include practice exercises?"
                                   :options ["Yes" "No"]}}})

;; Extract intent and initial parameters from user message
(defn parse-user-intent [message]
(let [lower-msg (str/lower-case message)]
(cond
(str/includes? lower-msg "vocabulary")
{:intent :vocabulary
:extracted-params (extract-vocabulary-params message)}

        (str/includes? lower-msg "grammar")
        {:intent :grammar
         :extracted-params (extract-grammar-params message)}

        :else
        {:intent :unknown})))

(defn extract-vocabulary-params [message]
(let [lower-msg (str/lower-case message)]
(cond-> {}
;; Extract language
(re-find #"(english|spanish|french|german)" lower-msg)
(assoc :language (second (re-find #"(english|spanish|french|german)" lower-msg)))

        ;; Extract level
        (re-find #"(A1|A2|B1|B2|C1|C2)" message)
        (assoc :level (second (re-find #"(A1|A2|B1|B2|C1|C2)" message)))

        ;; Extract word count
        (re-find #"(\d+)\s+words?" lower-msg)
        (assoc :word-count (Integer/parseInt (second (re-find #"(\d+)\s+words?" lower-msg))))

        ;; Extract format
        (str/includes? lower-msg "powerpoint")
        (assoc :output-format "powerpoint")

        (str/includes? lower-msg "pdf")
        (assoc :output-format "pdf"))))

;; Initialize conversation state
(defn create-conversation [user-id message]
(let [{:keys [intent extracted-params]} (parse-user-intent message)]
{:conversation-id (str "conv-" (java.util.UUID/randomUUID))
:user-id user-id
:intent intent
:required-params (get content-parameters intent)
:collected-values extracted-params
:conversation-history [{:role "user" :content message}]
:status :gathering-info
:created-at (java.time.Instant/now)}))

;; Find next missing parameter
(defn find-next-question [conversation]
(let [{:keys [required-params collected-values]} conversation]
(->> required-params
(filter (fn [[param-key param-config]]
(and
;; Not collected yet
(not (contains? collected-values param-key))
;; Check if it depends on another param
(if-let [depends-on (:depends-on param-config)]
(= (get collected-values (first (keys depends-on)))
(first (vals depends-on)))
true))))
(first))))

;; Generate question for missing parameter
(defn generate-question [param-key param-config]
{:type :ai-question
:parameter param-key
:content (:question param-config)
:options (when (:options param-config)
(:options param-config))
:input-type (:type param-config)})

;; Parse user response to question
(defn parse-response [response param-config]
(case (:type param-config)
:number (try
(Integer/parseInt (str/trim response))
(catch Exception _ nil))

      :boolean (if (re-find #"(?i)yes|sure|ok|yep" response)
                 true
                 (if (re-find #"(?i)no|nope|nah" response)
                   false
                   nil))

      :choice (let [lower-response (str/lower-case response)
                    options (:options param-config)]
                (->> options
                     (filter #(str/includes? lower-response (str/lower-case %)))
                     (first)))

      :string (str/trim response)))

;; Update conversation with user response
(defn update-conversation [conversation param-key response]
(let [param-config (get-in conversation [:required-params param-key])
parsed-value (parse-response response param-config)]
(if (and parsed-value ((:validation param-config) parsed-value))
(-> conversation
(update :collected-values assoc param-key parsed-value)
(update :conversation-history conj
{:role "user" :content response}))
;; Invalid response - ask again
conversation)))

;; Check if all required params are collected
(defn all-params-collected? [conversation]
(let [{:keys [required-params collected-values]} conversation]
(every? (fn [[param-key param-config]]
(or (not (:required param-config))
(contains? collected-values param-key)))
required-params)))

WebSocket Handler

(ns teachers-center-backend.websocket
(:require [org.httpkit.server :as httpkit]
[cheshire.core :as json]
[teachers-center-backend.conversation :as conv]
[teachers-center-backend.content :as content]))

;; Store active conversations (in production, use database)
(def conversations (atom {}))

(defn send-message [channel message]
(httpkit/send! channel (json/generate-string message)))

(defn handle-user-message [channel conversation-id user-message]
(let [conversation (get @conversations conversation-id)]
(if conversation
;; Existing conversation - answer to question
(handle-conversation-response channel conversation user-message)

        ;; New conversation - initial request
        (handle-new-conversation channel user-message))))

(defn handle-new-conversation [channel user-message]
(let [conversation (conv/create-conversation "user-123" user-message)
conversation-id (:conversation-id conversation)]

      ;; Store conversation
      (swap! conversations assoc conversation-id conversation)

      (if (= :unknown (:intent conversation))
        ;; Don't understand intent
        (send-message channel
          {:type :ai-response
           :content "I'm sorry, I didn't understand. I can help you generate vocabulary, grammar lessons, or
exercises. What would you like?"})

        ;; Start asking questions
        (ask-next-question channel conversation))))

(defn handle-conversation-response [channel conversation user-response]
(let [;; Find what parameter we were asking about
last-ai-msg (->> (:conversation-history conversation)
(filter #(= (:role %) "assistant"))
(last))
param-key (:parameter last-ai-msg)

          ;; Update conversation with response
          updated-conv (conv/update-conversation
                         conversation param-key user-response)]

      (if (= updated-conv conversation)
        ;; Invalid response - ask again
        (send-message channel
          {:type :ai-question
           :content (str "I didn't quite understand. "
                        (get-in conversation [:required-params param-key :question]))
           :parameter param-key})

        ;; Valid response - continue
        (do
          (swap! conversations assoc (:conversation-id conversation) updated-conv)

          (if (conv/all-params-collected? updated-conv)
            ;; All info collected - generate content
            (generate-and-send-content channel updated-conv)

            ;; Ask next question
            (ask-next-question channel updated-conv))))))

(defn ask-next-question [channel conversation]
(if-let [[param-key param-config] (conv/find-next-question conversation)]
(let [question (conv/generate-question param-key param-config)
updated-conv (update conversation :conversation-history conj
{:role "assistant"
:content (:content question)
:parameter param-key})]

        ;; Update stored conversation
        (swap! conversations assoc (:conversation-id conversation) updated-conv)

        ;; Send question to user
        (send-message channel question))

      ;; No more questions - generate
      (generate-and-send-content channel conversation)))

(defn generate-and-send-content [channel conversation]
(let [{:keys [intent collected-values conversation-id]} conversation]

      ;; Send "thinking" message
      (send-message channel
        {:type :ai-thinking
         :content "Great! Let me generate that for you..."})

      ;; Generate content (async)
      (future
        (try
          (let [result (case intent
                         :vocabulary (content/generate-vocabulary
                                       collected-values)
                         :grammar (content/generate-grammar
                                    collected-values))]

            ;; Send final result
            (send-message channel
              {:type :ai-response
               :conversation-id conversation-id
               :content (:content result)
               :data result})

            ;; Clean up conversation
            (swap! conversations dissoc conversation-id))

          (catch Exception e
            (send-message channel
              {:type :ai-error
               :content "Sorry, something went wrong. Please try again."}))))))

;; WebSocket connection handler
(defn ws-handler [request]
(httpkit/with-channel request channel
(if (httpkit/websocket? channel)
(do
(println "Client connected")

          (httpkit/on-receive channel
            (fn [raw-message]
              (try
                (let [message (json/parse-string raw-message true)
                      {:keys [type content conversation-id]} message]

                  (case type
                    :user-message
                    (handle-user-message channel conversation-id content)

                    (println "Unknown message type:" type)))

                (catch Exception e
                  (println "Error handling message:" e)
                  (send-message channel
                    {:type :error
                     :content "Invalid message format"})))))

          (httpkit/on-close channel
            (fn [status]
              (println "Client disconnected:" status))))

        {:status 400
         :body "Expected WebSocket connection"})))

  ---
3. Frontend Implementation (JavaScript)

WebSocket Client with Conversation Flow

class ConversationalAI {
constructor(wsUrl) {
this.wsUrl = wsUrl;
this.ws = null;
this.conversationId = null;
this.messageCallbacks = [];
this.connect();
}

    connect() {
      this.ws = new WebSocket(this.wsUrl);

      this.ws.onopen = () => {
        console.log('Connected to AI');
        this.onStatusChange('connected');
      };

      this.ws.onmessage = (event) => {
        const message = JSON.parse(event.data);
        this.handleMessage(message);
      };

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        this.onStatusChange('error');
      };

      this.ws.onclose = () => {
        console.log('Disconnected');
        this.onStatusChange('disconnected');

        // Reconnect after 3 seconds
        setTimeout(() => this.connect(), 3000);
      };
    }

    handleMessage(message) {
      const { type, content, parameter, options, conversation_id } = message;

      // Store conversation ID
      if (conversation_id) {
        this.conversationId = conversation_id;
      }

      switch (type) {
        case 'ai-question':
          this.onQuestion(content, parameter, options);
          break;

        case 'ai-thinking':
          this.onThinking(content);
          break;

        case 'ai-response':
          this.onResponse(content, message.data);
          this.conversationId = null; // Reset for new conversation
          break;

        case 'ai-error':
          this.onError(content);
          break;
      }

      // Notify all callbacks
      this.messageCallbacks.forEach(cb => cb(message));
    }

    sendMessage(content) {
      if (this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({
          type: 'user-message',
          content: content,
          conversation_id: this.conversationId
        }));
      } else {
        console.error('WebSocket not connected');
      }
    }

    // Override these methods or use callbacks
    onQuestion(question, parameter, options) {
      console.log('AI Question:', question);
      console.log('Parameter:', parameter);
      console.log('Options:', options);
    }

    onThinking(message) {
      console.log('AI Thinking:', message);
    }

    onResponse(content, data) {
      console.log('AI Response:', content);
    }

    onError(message) {
      console.error('AI Error:', message);
    }

    onStatusChange(status) {
      console.log('Status:', status);
    }

    onMessage(callback) {
      this.messageCallbacks.push(callback);
    }
}

Chat UI Implementation

  <!DOCTYPE html>
  <html>
  <head>
    <title>AI Content Generator</title>
    <style>
      * {
        margin: 0;
        padding: 0;
        box-sizing: border-box;
      }

      body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        height: 100vh;
        display: flex;
        flex-direction: column;
      }

      #chat-container {
        flex: 1;
        overflow-y: auto;
        padding: 20px;
        background: #f5f5f5;
      }

      .message {
        margin-bottom: 15px;
        display: flex;
        align-items: flex-start;
      }

      .message.user {
        justify-content: flex-end;
      }

      .message-content {
        max-width: 70%;
        padding: 12px 16px;
        border-radius: 18px;
        word-wrap: break-word;
      }

      .message.user .message-content {
        background: #007bff;
        color: white;
      }

      .message.ai .message-content {
        background: white;
        color: #333;
        box-shadow: 0 1px 2px rgba(0,0,0,0.1);
      }

      .message.ai.question .message-content {
        background: #fff3cd;
        border-left: 4px solid #ffc107;
      }

      .message.thinking .message-content {
        background: #e3f2fd;
        font-style: italic;
        color: #666;
      }

      .quick-options {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-top: 10px;
      }

      .quick-option {
        padding: 8px 16px;
        background: white;
        border: 2px solid #007bff;
        border-radius: 20px;
        cursor: pointer;
        font-size: 14px;
        color: #007bff;
        transition: all 0.2s;
      }

      .quick-option:hover {
        background: #007bff;
        color: white;
      }

      #input-container {
        padding: 20px;
        background: white;
        border-top: 1px solid #ddd;
        display: flex;
        gap: 10px;
      }

      #message-input {
        flex: 1;
        padding: 12px 16px;
        border: 2px solid #ddd;
        border-radius: 24px;
        font-size: 15px;
        outline: none;
      }

      #message-input:focus {
        border-color: #007bff;
      }

      #send-button {
        padding: 12px 24px;
        background: #007bff;
        color: white;
        border: none;
        border-radius: 24px;
        cursor: pointer;
        font-size: 15px;
        font-weight: 600;
      }

      #send-button:hover {
        background: #0056b3;
      }

      #send-button:disabled {
        background: #ccc;
        cursor: not-allowed;
      }

      .status-indicator {
        padding: 8px 16px;
        background: #28a745;
        color: white;
        text-align: center;
        font-size: 12px;
      }

      .status-indicator.disconnected {
        background: #dc3545;
      }
    </style>
  </head>
  <body>
    <div id="status" class="status-indicator">Connecting...</div>

    <div id="chat-container"></div>

    <div id="input-container">
      <input
        id="message-input"
        type="text"
        placeholder="Ask me to generate content..."
        disabled
      >
      <button id="send-button" disabled>Send</button>
    </div>

    <script>
      class ChatUI {
        constructor() {
          this.chatContainer = document.getElementById('chat-container');
          this.messageInput = document.getElementById('message-input');
          this.sendButton = document.getElementById('send-button');
          this.statusIndicator = document.getElementById('status');

          // Initialize AI client
          this.ai = new ConversationalAI('ws://localhost:2000/ws');

          // Setup event handlers
          this.setupEventHandlers();
          this.setupAIHandlers();
        }

        setupEventHandlers() {
          this.sendButton.addEventListener('click', () => this.sendMessage());

          this.messageInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.sendMessage();
          });
        }

        setupAIHandlers() {
          this.ai.onQuestion = (question, parameter, options) => {
            this.addMessage(question, 'ai', 'question');

            // Show quick options if available
            if (options && options.length > 0) {
              this.addQuickOptions(options);
            }
          };

          this.ai.onThinking = (message) => {
            this.addMessage(message, 'thinking');
          };

          this.ai.onResponse = (content, data) => {
            this.addMessage(content, 'ai');

            // If there's structured data, display it
            if (data && data.slides) {
              this.displaySlides(data.slides);
            }
          };

          this.ai.onError = (message) => {
            this.addMessage(message, 'error');
          };

          this.ai.onStatusChange = (status) => {
            this.updateStatus(status);
          };
        }

        sendMessage() {
          const message = this.messageInput.value.trim();

          if (message) {
            // Add to UI
            this.addMessage(message, 'user');

            // Send to server
            this.ai.sendMessage(message);

            // Clear input
            this.messageInput.value = '';
          }
        }

        addMessage(content, type, subtype = '') {
          const messageDiv = document.createElement('div');
          messageDiv.className = `message ${type} ${subtype}`;

          const contentDiv = document.createElement('div');
          contentDiv.className = 'message-content';
          contentDiv.textContent = content;

          messageDiv.appendChild(contentDiv);
          this.chatContainer.appendChild(messageDiv);

          // Scroll to bottom
          this.chatContainer.scrollTop = this.chatContainer.scrollHeight;
        }

        addQuickOptions(options) {
          const optionsContainer = document.createElement('div');
          optionsContainer.className = 'quick-options';

          options.forEach(option => {
            const button = document.createElement('button');
            button.className = 'quick-option';
            button.textContent = option;
            button.addEventListener('click', () => {
              // Remove options after selection
              optionsContainer.remove();

              // Send selected option
              this.messageInput.value = option;
              this.sendMessage();
            });

            optionsContainer.appendChild(button);
          });

          // Add to last AI message
          const lastMessage = this.chatContainer.lastElementChild;
          lastMessage.appendChild(optionsContainer);
        }

        displaySlides(slides) {
          // Create a preview of the slides
          const previewDiv = document.createElement('div');
          previewDiv.className = 'slides-preview';
          previewDiv.innerHTML = '<h3>Generated Slides:</h3>';

          slides.forEach((slide, index) => {
            const slideDiv = document.createElement('div');
            slideDiv.className = 'slide-item';
            slideDiv.innerHTML = `
              <strong>Slide ${index + 1}: ${slide.title || ''}</strong>
              <ul>${(slide.content || []).map(item => `<li>${item}</li>`).join('')}</ul>
            `;
            previewDiv.appendChild(slideDiv);
          });

          this.chatContainer.appendChild(previewDiv);
          this.chatContainer.scrollTop = this.chatContainer.scrollHeight;
        }

        updateStatus(status) {
          this.statusIndicator.className = `status-indicator ${status}`;

          switch(status) {
            case 'connected':
              this.statusIndicator.textContent = 'Connected';
              this.messageInput.disabled = false;
              this.sendButton.disabled = false;
              break;
            case 'disconnected':
              this.statusIndicator.textContent = 'Disconnected - Reconnecting...';
              this.messageInput.disabled = true;
              this.sendButton.disabled = true;
              break;
            case 'error':
              this.statusIndicator.textContent = 'Connection Error';
              break;
          }
        }
      }

      // Start chat UI
      const chatUI = new ChatUI();
    </script>
  </body>
  </html>

  ---
4. Strategy 2: LLM-Driven Questions (Alternative)

If you want the AI to dynamically decide what to ask:

(defn generate-clarifying-questions [user-request conversation-history]
(let [prompt (str "The user wants: " user-request "\n\n"
"Previous conversation:\n"
(str/join "\n"
(map #(str (:role %) ": " (:content %))
conversation-history))
"\n\n"
"What information do you still need? "
"Respond with JSON: {\"needs_more_info\": true/false, "
"\"next_question\": \"question text\", "
"\"parameter\": \"what_you're_asking_about\"}")

          response (openai/chat-completion
                     [{:role "system" :content "You are a helpful assistant that asks clarifying questions."}
                      {:role "user" :content prompt}])]

      (json/parse-string (:content response) true)))

  ---
Key Recommendations

1. Use Strategy 1 (Rule-Based) for structured content with known parameters
2. Use Strategy 2 (LLM-Driven) for open-ended conversations
3. Implement conversation timeouts (auto-cancel after 10 minutes of inactivity)
4. Store conversations in database for persistence across reconnections
5. Add conversation restart command ("start over", "cancel")
6. Show progress indicator ("2 of 4 questions answered")
7. Allow users to go back and change previous answers
8. Validate responses before moving to next question

Would you like me to implement any specific part in more detail?
