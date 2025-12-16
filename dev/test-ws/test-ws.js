const WebSocket = require('ws');

const ws = new WebSocket('ws://localhost:2000/ws?name=John');

let messageCount = 0;

ws.on('open', function open() {
  console.log('Connected to WebSocket');
  // Send a test message after connecting
  setTimeout(() => {
    console.log('Sending message to server...');

    const testMessage = {
      "user-id": 123,
      "channel-name": "power-point-presentation-test.pxp",
      "conversation-id": null,
      "type": "generate-vocabulary",
      "content": "Generate vocabulary for topic food 5 words A1 level German language no examples",
      "requirements": {}
    };

    ws.send(JSON.stringify(testMessage));
  }, 100);
});

ws.on('message', function message(data) {
  messageCount++;
  const dataStr = data.toString();
  console.log('Received from server (raw):', dataStr);

  // Try to parse as JSON
  try {
    const jsonData = JSON.parse(dataStr);
    console.log('Parsed JSON response:', JSON.stringify(jsonData, null, 2));
  } catch (e) {
    console.log('Not JSON, plain text message');
  }

  // Close after receiving 2 messages (greeting + echo)
  if (messageCount >= 2) {
    ws.close();
  }
});

ws.on('error', function error(err) {
  console.error('Error:', err.message);
});

ws.on('close', function close() {
  console.log('Connection closed');
});
