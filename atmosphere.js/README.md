# atmosphere.js

Modern TypeScript client for the Atmosphere Framework - WebSocket, SSE, and Comet support for real-time web applications.

[![npm version](https://img.shields.io/npm/v/atmosphere.js)](https://www.npmjs.com/package/atmosphere.js)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.4-blue)](https://www.typescriptlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

✨ **Modern TypeScript** - Full type safety and IntelliSense support  
🚀 **Multiple Transports** - WebSocket, SSE, Long-polling, Streaming  
🔄 **Auto-reconnection** - Intelligent reconnection with exponential backoff  
📦 **Tree-shakeable** - Import only what you need  
🎯 **Zero Dependencies** - Lightweight and fast  
🔌 **Promise-based API** - Modern async/await support  
🧪 **Well Tested** - Comprehensive test coverage

## Installation

```bash
npm install atmosphere.js
```

## Quick Start

```typescript
import { atmosphere } from 'atmosphere.js';

// Subscribe to an endpoint
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
}, {
  message: (response) => {
    console.log('Received:', response.responseBody);
  },
  open: (response) => {
    console.log('Connected with transport:', response.transport);
  },
  close: (response) => {
    console.log('Connection closed');
  },
  error: (error) => {
    console.error('Error:', error);
  }
});

// Send a message
subscription.push({ 
  user: 'John', 
  message: 'Hello World' 
});

// Close the connection
await subscription.close();
```

## API Reference

### Creating an Atmosphere Instance

```typescript
import { Atmosphere } from 'atmosphere.js';

const atmosphere = new Atmosphere({
  logLevel: 'info',
  defaultTransport: 'websocket',
  fallbackTransport: 'long-polling'
});
```

### Subscribe to an Endpoint

```typescript
const subscription = await atmosphere.subscribe(
  {
    url: 'http://localhost:8080/chat',
    transport: 'websocket',
    reconnect: true,
    reconnectInterval: 5000,
    maxReconnectOnClose: 10,
    trackMessageLength: false,
    headers: {
      'Authorization': 'Bearer token123'
    }
  },
  {
    message: (response) => { /* handle message */ },
    open: (response) => { /* handle open */ },
    close: (response) => { /* handle close */ },
    error: (error) => { /* handle error */ },
    reconnect: (request, response) => { /* handle reconnect */ }
  }
);
```

### Request Options

```typescript
interface AtmosphereRequest {
  url: string;                      // Endpoint URL
  transport: TransportType;         // 'websocket' | 'sse' | 'long-polling' | 'streaming' | 'jsonp'
  fallbackTransport?: TransportType;// Transport to use if primary fails
  contentType?: string;             // Content-Type header
  timeout?: number;                 // Request timeout in milliseconds
  reconnect?: boolean;              // Enable auto-reconnection
  reconnectInterval?: number;       // Time between reconnections (ms)
  maxReconnectOnClose?: number;     // Maximum reconnection attempts
  trackMessageLength?: boolean;     // Enable message length tracking
  messageDelimiter?: string;        // Delimiter for split messages
  enableProtocol?: boolean;         // Enable Atmosphere protocol
  headers?: Record<string, string>; // Custom headers
  withCredentials?: boolean;        // Include credentials
}
```

### Subscription Methods

```typescript
// Send a message
subscription.push('Hello');                    // String
subscription.push({ message: 'Hello' });       // Object (auto-stringified)
subscription.push(new ArrayBuffer(8));         // Binary data

// Get current state
const state = subscription.state; // 'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'error'

// Close the subscription
await subscription.close();

// Event emitter style
subscription.on('custom-event', (data) => {
  console.log(data);
});

subscription.off('custom-event', handler);
```

## Examples

### Basic WebSocket Connection

```typescript
import { atmosphere } from 'atmosphere.js';

const subscription = await atmosphere.subscribe({
  url: 'ws://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => {
    console.log(response.responseBody);
  }
});

subscription.push('Hello server!');
```

### With Reconnection

```typescript
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket',
  reconnect: true,
  reconnectInterval: 3000,
  maxReconnectOnClose: 10
}, {
  message: (response) => {
    console.log('Message:', response.responseBody);
  },
  reconnect: (request, response) => {
    console.log('Reconnecting... Attempt:', request);
  },
  open: (response) => {
    console.log('Connection established');
  }
});
```

### Custom Headers and Authentication

```typescript
const subscription = await atmosphere.subscribe({
  url: 'http://localhost:8080/secure-chat',
  transport: 'websocket',
  headers: {
    'Authorization': `Bearer ${authToken}`,
    'X-Custom-Header': 'value'
  },
  withCredentials: true
}, {
  message: (response) => {
    console.log(response.responseBody);
  }
});
```

### Type-Safe Messages

```typescript
interface ChatMessage {
  user: string;
  message: string;
  timestamp: number;
}

const subscription = await atmosphere.subscribe<ChatMessage>({
  url: 'http://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => {
    // response.responseBody is typed as ChatMessage
    const msg = response.responseBody;
    console.log(`${msg.user}: ${msg.message}`);
  }
});
```

### Multiple Subscriptions

```typescript
const chat = await atmosphere.subscribe({
  url: 'http://localhost:8080/chat',
  transport: 'websocket'
}, {
  message: (response) => console.log('Chat:', response.responseBody)
});

const notifications = await atmosphere.subscribe({
  url: 'http://localhost:8080/notifications',
  transport: 'websocket'
}, {
  message: (response) => console.log('Notification:', response.responseBody)
});

// Close all subscriptions
await atmosphere.closeAll();
```

### Error Handling

```typescript
try {
  const subscription = await atmosphere.subscribe({
    url: 'http://localhost:8080/chat',
    transport: 'websocket'
  }, {
    error: (error) => {
      console.error('Connection error:', error);
    },
    close: (response) => {
      console.log('Connection closed:', response.reasonPhrase);
    }
  });
} catch (error) {
  console.error('Failed to connect:', error);
}
```

## Browser Compatibility

- Chrome/Edge: Last 2 versions
- Firefox: Last 2 versions + ESR
- Safari: Last 2 versions
- Mobile Safari (iOS): Last 2 versions
- Chrome Android: Last 2 versions

## Development

```bash
# Install dependencies
npm install

# Run tests
npm test

# Run tests with UI
npm run test:ui

# Run tests with coverage
npm run test:ci

# Build
npm run build

# Development mode (watch)
npm run dev

# Type checking
npm run type-check

# Linting
npm run lint

# Format code
npm run format
```

## License

Apache License 2.0 - see [LICENSE](LICENSE) file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Links

- [Atmosphere Framework](https://github.com/Atmosphere/atmosphere)
- [Documentation](https://github.com/Atmosphere/atmosphere/wiki)
- [Issues](https://github.com/Atmosphere/atmosphere/issues)

---

**Built with ❤️ by the Atmosphere team**
