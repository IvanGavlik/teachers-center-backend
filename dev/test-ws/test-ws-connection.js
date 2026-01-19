const WebSocket = require('ws');

const baseUrl = 'wss://teachers-center-be.onrender.com';
const ports = [8080, 8000, 2000, 3000];

async function testWebSocket(url) {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      resolve({ success: false, error: 'Connection timeout' });
    }, 10000);

    try {
      const ws = new WebSocket(url);

      ws.on('open', () => {
        console.log(`✅ SUCCESS: Connected to ${url}`);
        clearTimeout(timeout);

        // Send a test message
        const testMessage = {
          "user-id": 123,
          "channel-name": "test-connection",
          "conversation-id": null,
          "type": "vocabulary",
          "content": "test connection",
          "requirements": {}
        };

        ws.send(JSON.stringify(testMessage));
        console.log(`   Sent test message to ${url}`);
      });

      ws.on('message', (data) => {
        console.log(`   Received response from ${url}:`, data.toString().substring(0, 100));
        clearTimeout(timeout);
        ws.close();
        resolve({ success: true, response: data.toString() });
      });

      ws.on('error', (error) => {
        console.log(`❌ ERROR: Failed to connect to ${url}`);
        console.log(`   Error: ${error.message}`);
        clearTimeout(timeout);
        resolve({ success: false, error: error.message });
      });

      ws.on('close', (code, reason) => {
        console.log(`   Connection closed for ${url} (code: ${code})`);
        clearTimeout(timeout);
        resolve({ success: false, error: `Connection closed: ${code}` });
      });

    } catch (error) {
      clearTimeout(timeout);
      console.log(`❌ EXCEPTION: ${url} - ${error.message}`);
      resolve({ success: false, error: error.message });
    }
  });
}

async function testAllPorts() {
  console.log('='.repeat(60));
  console.log('Testing WebSocket connections to Render backend');
  console.log('='.repeat(60));
  console.log('');

  for (const port of ports) {
    const url = `${baseUrl}:${port}/ws`;
    console.log(`\nTesting port ${port}...`);
    console.log(`URL: ${url}`);
    await testWebSocket(url);

    // Wait a bit between tests
    await new Promise(resolve => setTimeout(resolve, 1000));
  }

  // Also test without explicit port (default HTTPS port 443)
  console.log(`\nTesting default HTTPS port (443)...`);
  console.log(`URL: ${baseUrl}/ws`);
  await testWebSocket(`${baseUrl}/ws`);

  console.log('\n' + '='.repeat(60));
  console.log('Testing complete!');
  console.log('='.repeat(60));
}

testAllPorts().catch(console.error);
