# Teachers Center Backend

Clojure backend service for the AI Slides Assistant, providing content generation endpoints for language teachers.

## Tech Stack

- **Clojure** - Core language
- **deps.edn** - Dependency management
- **Integrant** - System component management
- **Ring** - HTTP server abstraction
- **Compojure** - Routing
- **http-kit** - Web server
- **OpenAI API** - AI content generation

## Setup

1. **Prerequisites**
   ```bash
   # Install Clojure CLI tools
   # macOS: brew install clojure/tools/clojure
   # Ubuntu: curl -O https://download.clojure.org/install/linux-install-1.11.1.1273.sh
   ```

2. **Environment Configuration**
   ```bash
   # Copy the example environment file
   cp .env.example .env
   
   # Edit the .env file with your favorite editor
   nano .env
   # or
   vim .env
   # or
   code .env
   ```

3. **Get OpenAI API Key**
   - Visit https://platform.openai.com/account/api-keys
   - Sign in to your OpenAI account (create one if needed)
   - Click "Create new secret key"
   - Copy the API key (starts with "sk-...")
   - Add it to your `.env` file:
     ```bash
     OPENAI_API_KEY=sk-your-actual-api-key-here
     PORT=3000
     ```

4. **Environment Variables Reference**
   
   | Variable | Required | Default | Description |
   |----------|----------|---------|-------------|
   | `OPENAI_API_KEY` | ✅ Yes | -       | Your OpenAI API key for content generation |
   | `PORT` | ❌ No | `2000`  | Port number for the web server |
   
   **Setting Environment Variables (Alternative Methods):**
   
   - **Using .env file (Recommended for development):**
     ```bash
     # Create .env file in project root
     echo "OPENAI_API_KEY=sk-your-key-here" > .env
     echo "PORT=2000" >> .env
     ```
   
   - **Export in shell (Temporary):**
     ```bash
     export OPENAI_API_KEY="sk-your-key-here"
     export PORT=2000
     clj -M -m teachers-center-backend.core
     ```
   
   - **Pass directly when running:**
     ```bash
     OPENAI_API_KEY="sk-your-key-here" clj -M -m teachers-center-backend.core
     ```
   
   - **For production deployment:**
     ```bash
     # Heroku
     heroku config:set OPENAI_API_KEY="sk-your-key-here"
     
     # Docker
     docker run -e OPENAI_API_KEY="sk-your-key-here" your-image
     
     # Systemd service
     Environment="OPENAI_API_KEY=sk-your-key-here"
     ```

5. **Verify Configuration**
   ```bash
   # Test that environment variables are loaded
   clj -M -e "(println \"API Key configured:\" (boolean (System/getenv \"OPENAI_API_KEY\")))"
   
   # Should output: API Key configured: true
   ```

## Development

### Start REPL

Start repl from terminal 
```bash
clj -M:dev:repl
```

Intelj specific: Then connect using remote 
    (somethimes does not work from first so try to connect one more time)


### Start System (in REPL)
```clojure
(go)     ; Start the system
(halt)   ; Stop the system  
(reset)  ; Restart the system
```

### Run Server Directly
```bash
clj -M -m teachers-center-backend.core
```

## API Endpoints

### Health Check
```
GET /health
```

### Generate Content
```
POST /api/generate
Content-Type: application/json

{
  "content_type": "vocabulary",
  "language": "en",
  "level": "B1", 
  "parameters": {
    "topic": "food and cooking",
    "word_count": 10,
    "include_examples": true,
    "include_images": false
  }
}
```

## Content Types

- **vocabulary** - Vocabulary word lists with definitions and examples
- **grammar** - Grammar explanations with examples (TODO)
- **reading** - Reading passages with comprehension questions (TODO)  
- **exercises** - Practice exercises and assessments (TODO)

## Testing

Test the vocabulary endpoint:
```bash
curl -X POST http://localhost:2000/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "content_type": "vocabulary",
    "language": "en", 
    "level": "B1",
    "parameters": {
      "topic": "travel",
      "word_count": 5,
      "include_examples": true
    }
  }'
```

## Troubleshooting

### Common Issues

**❌ "OpenAI API key is required" error:**
```bash
# Check if environment variable is set
echo $OPENAI_API_KEY

# If empty, set it:
export OPENAI_API_KEY="sk-your-key-here"

# Or check your .env file exists and has the key
cat .env
```

**❌ "Port already in use" error:**
```bash
# Check what's using port 3000
lsof -i :3000

# Kill the process or use a different port
export PORT=3001
```

**❌ "Connection refused" when testing API:**
```bash
# Make sure the server is running
curl http://localhost:3000/health

# Check server logs for errors
```

**❌ OpenAI API errors:**
- Check your API key is valid and active
- Verify you have sufficient credits/quota
- Check OpenAI service status: https://status.openai.com/

### Debugging Tips

**Enable debug logging:**
```bash
# Add to your .env file
export CLOJURE_LOG_LEVEL=DEBUG
```

**Check system status in REPL:**
```clojure
(require '[integrant.repl.state :as state])
state/system  ; View running system components
```

**Test OpenAI connection manually:**
```bash
curl -X POST https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [{"role": "user", "content": "Hello!"}],
    "max_tokens": 10
  }'
```

## Architecture

- **System Management**: Integrant manages component lifecycle
- **HTTP Layer**: Ring/Compojure for web server and routing
- **AI Integration**: Dedicated OpenAI client for content generation
- **Content Generation**: Modular content generators for different types
- **Error Handling**: Comprehensive error handling with logging

## Security Notes

- **Never commit `.env` files** - they contain sensitive API keys
- **Use environment variables** for all secrets in production
- **Rotate API keys** regularly for security
- **Monitor API usage** to detect unauthorized access
- **Use HTTPS** in production (handled by reverse proxy/load balancer)