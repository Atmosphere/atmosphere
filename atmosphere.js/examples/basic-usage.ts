import { atmosphere } from '../src/index';

async function main() {
  console.log('Atmosphere Client Example');
  console.log(`Version: ${atmosphere.version}`);

  try {
    // Subscribe to a chat endpoint
    const subscription = await atmosphere.subscribe(
      {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        reconnect: true,
        reconnectInterval: 3000,
      },
      {
        open: (response) => {
          console.log('✅ Connected:', response.transport);
        },
        message: (response) => {
          console.log('📨 Message received:', response.responseBody);
        },
        close: (response) => {
          console.log('❌ Connection closed:', response.reasonPhrase);
        },
        error: (error) => {
          console.error('⚠️  Error:', error);
        },
        reconnect: (request, response) => {
          console.log('🔄 Reconnecting...');
        },
      },
    );

    // Send a message after connection
    console.log('Sending message...');
    subscription.push({
      user: 'Example User',
      message: 'Hello from Atmosphere TypeScript client!',
      timestamp: Date.now(),
    });

    // Close after 10 seconds
    setTimeout(async () => {
      console.log('Closing connection...');
      await subscription.close();
      process.exit(0);
    }, 10000);
  } catch (error) {
    console.error('Failed to connect:', error);
    process.exit(1);
  }
}

await main();
