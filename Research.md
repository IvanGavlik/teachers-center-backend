# Research interacive multi-turn AI chat system architertual options

## Communication protocol options

### WebSocket 
- biderectional, presistent
- Cons
	- complex to implement compared to REST
	- connection statemamagement
	- does now work throught all proxies/firewalls 
		- check for power point and google slides 
		- make code so that if does not work that we display mes and log it 
		- load balancing requires sticky sessinos or shared state
		- reconnection logic
		
- check how can I track progress and what is server doing  (- like chatGpt typing effect)
		
### Other options
- SSE 
- HTTP Long Polling 
- HTTP/2 Server Push 
- REST with Polling 		

## Flow management

## AI/LLM 
- AI decided what to ask based on context
- Cons:
	Unpredictable question sequences
	Multiple LLM calls
	Harder to ensure all required info collected
	May ask redundant or irrelevant questions
	latency for each question
- appproaches
		functional calling: LLM calls predefined functions when it need info 
		promot enfineering: System propmti instructs LLM to gather requirements
		hybrid: LLM + validation rules

### Other options
- Rule based engine
- programmatic/code-based flow
- State machine (converstation as states and transitions)
- template based . slot parametes 
- decision tree / branching logic

- ask on slow management stratefies what is best from the prespescitve of the user 
	- goal make user fendly easy to use but also dont ask reudant or irrelevant quesitons
	- user can have choise to 
	
## Message Protocl Design Options
- Custom JSON protocol (your own mwssage schema)
- RPC protocol over JSON/XML	
- GrapQL 
- gRPC 
	- binary protocol witch schema definitoin 
	- good for high performance and microservices 
- AI standars: Borframwork, Rasa Protocl 
	- investigate this 

- investigate how claude code and claude desktop use from technical perspective to 
communication

## AI integratio Patterns

### Single request (all in one ) 
- first collect all 
- just return one final result 

### LLM each turn/message

### Rules + LLM
- predefined rules for common questions
- LLM for clarification/edge cases
- coins: complex imple, need to define when to use LLM, state managmet more complex

### LLM function calling


## Architectual patterns

### Streaming responses
- serever stream partial result
- like chatGpt typing effect

### Asychronous with Polling
- clients submits request get job ID 
- polls for status 
- gets results when done
- PROS
	good for long operations
	server doesn block
	client can do other things
- cons
	pooling overhead
	delayed feedback 
	complex client logic
	
### Other 	
	- Request Response	
	- Event driven (pubs/sub)
		- need message broker, more infra, complex error handling 

	
  Combination 1: "Production Ready, Balanced"

  | Component        | Choice                   | Rationale                                     |
  |------------------|--------------------------|-----------------------------------------------|
  | Communication    | WebSocket                | Real-time bidirectional, best for chat        |
  | Question Flow    | Declarative Config (EDN) | Easy to modify, version control, fits Clojure |
  | State Management | Redis                    | Scales horizontally, fast, TTL support        |
  | Message Protocol | Custom JSON              | Simple, sufficient, debuggable                |
  | Config Storage   | Hybrid (EDN + DB)        | Start with EDN, migrate to DB later           |
  | AI Pattern       | Hybrid (Rules + LLM)     | Cost-effective, predictable                   |
  | Architecture     | Event-driven             | Real-time, scalable                           |

  Complexity: Medium
  Cost: Medium
  Scalability: High
  Flexibility: High

  ---
  Combination 2: "Quick Start, Minimal"

  | Component        | Choice             | Rationale                           |
  |------------------|--------------------|-------------------------------------|
  | Communication    | Server-Sent Events | Simpler than WebSocket, good enough |
  | Question Flow    | Template/Slots     | Simplest to implement               |
  | State Management | In-Memory          | No external dependencies            |
  | Message Protocol | Custom JSON        | Minimal overhead                    |
  | Config Storage   | EDN Files          | Already familiar, version control   |
  | AI Pattern       | Single Request     | Simplest, lowest cost               |
  | Architecture     | Synchronous        | Easiest implementation              |

  Complexity: Low
  Cost: Low
  Scalability: Low
  Flexibility: Medium

  ---
  Combination 3: "Enterprise Scale"

  | Component        | Choice                          | Rationale                    |
  |------------------|---------------------------------|------------------------------|
  | Communication    | WebSocket + Load Balancer       | High throughput, HA          |
  | Question Flow    | State Machine + DB              | Formal, versioned, auditable |
  | State Management | Hybrid (Redis + PostgreSQL)     | Fast + durable               |
  | Message Protocol | Custom JSON with versioning     | Controlled evolution         |
  | Config Storage   | Database with versioning        | Multi-tenant, admin UI       |
  | AI Pattern       | Hybrid with function calling    | Flexible, structured         |
  | Architecture     | Event-driven with message queue | Decoupled, scalable          |

  Complexity: High
  Cost: High
  Scalability: Very High
  Flexibility: Very High

  ---
  Combination 4: "AI-First, Flexible"

  | Component        | Choice                  | Rationale                         |
  |------------------|-------------------------|-----------------------------------|
  | Communication    | WebSocket               | Real-time for conversational flow |
  | Question Flow    | AI-Driven (LLM decides) | Maximum flexibility               |
  | State Management | Database (PostgreSQL)   | Full history for context          |
  | Message Protocol | Custom JSON             | Simple, sufficient                |
  | Config Storage   | Database                | Dynamic prompt management         |
  | AI Pattern       | LLM Function Calling    | Structured + flexible             |
  | Architecture     | Streaming Response      | Best UX                           |

  Complexity: Medium-High
  Cost: High (multiple LLM calls)
  Scalability: Medium
  Flexibility: Very High


10. Key Decision Criteria

  Choose based on:

  If you prioritize SPEED TO MARKET:
  - SSE + Template/Slots + In-Memory + EDN files

  If you prioritize USER EXPERIENCE:
  - WebSocket + AI-Driven + Streaming + Database

  If you prioritize COST:
  - SSE + Declarative Config + In-Memory + Single LLM call

  If you prioritize SCALABILITY:
  - WebSocket + State Machine + Redis + Event-driven

  If you prioritize FLEXIBILITY:
  - WebSocket + AI-Driven + Database + Hybrid AI pattern

  If you prioritize SIMPLICITY:
  - REST polling + Template/Slots + Client-side state + EDN files
	
	
If you want more natural conversations and have budget:

  1. Communication: WebSocket (better for multi-turn)
  2. Question Flow: LLM Function Calling
  3. State Management: PostgreSQL (need history for context)
  4. AI Pattern: Multi-turn with GPT-4

  Trade-offs:
  - ➕ Much more natural conversations
  - ➕ Handles edge cases automatically
  - ➖ 3-5x more expensive (multiple LLM calls)
  - ➖ Less predictable behavior
  - ➖ Harder to test
  
  

Office Add-in Constraints:

  - HTTPS required: Development needs SSL certificate
  - Content Security Policy: May restrict WebSocket connections
  - Trusted domains: Need to configure allowed domains
  - Sandbox limitations: Some browser APIs restricted
  - Version compatibility: Different Office versions, platforms (Windows, Mac, Web)

  Recommendation:

  Start with SSE or REST, test WebSocket compatibility across your target Office platforms before committing.

  OpenAI API Cost Management:

  For vocabulary generation example:
  - Current approach (single call): ~$0.01 - 0.02 per request (GPT-4)
  - Multi-turn approach (5 questions): ~$0.05 - 0.10 per request
  - At 100 requests/day: $1-2/day vs $5-10/day

Recommendation:

Use hybrid: predefined questions for common scenarios, LLM for clarification only when needed.
  
  
  TODO 
  - after each change in the slied (we have to send them all to the BE 
  so that chatgpt knows how presentation looks like)