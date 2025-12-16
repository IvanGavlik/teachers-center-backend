const WebSocket = require('ws');

const ws = new WebSocket('ws://localhost:8080/ws?name=John');

let messageCount = 0;

ws.on('open', function open() {
  console.log('Connected to WebSocket');
  // Send a test message after connecting
  setTimeout(() => {
    console.log('Sending message to server...');
    ws.send('Hello from client!');
  }, 100);
});

ws.on('message', function message(data) {
  messageCount++;
  console.log('Received from server:', data.toString());

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
