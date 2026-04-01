# Atmosphere Transport Layer

Atmosphere supports multiple transport protocols, automatically negotiating the best available option for each client.

## WebSocket

WebSocket provides full-duplex, bidirectional communication over a single TCP connection. It is the preferred transport when available. Atmosphere uses JSR 356 (Jakarta WebSocket API) for WebSocket support.

Key characteristics:
- Lowest latency of all transports
- Full bidirectional communication
- Persistent connection
- Supported by all modern browsers

## Server-Sent Events (SSE)

SSE provides server-to-client streaming over HTTP. It is simpler than WebSocket but only supports one-way communication from server to client. Client-to-server communication uses regular HTTP requests.

Key characteristics:
- Server-to-client streaming only
- Automatic reconnection built into the browser API
- Works through HTTP proxies without special configuration
- Text-based protocol (UTF-8)

## Long-Polling

Long-polling is a fallback transport that simulates real-time communication over standard HTTP. The client sends a request, and the server holds it open until data is available, then responds and the client immediately reconnects.

Key characteristics:
- Works everywhere HTTP works
- Higher latency than WebSocket or SSE
- More server resources per connection
- Useful as a fallback when other transports are blocked

## WebTransport over HTTP/3

WebTransport provides full-duplex, bidirectional communication over HTTP/3 (QUIC). It runs as a sidecar server alongside the servlet container, using a separate UDP port. The atmosphere.js client connects via the browser's WebTransport API with automatic fallback to WebSocket.

Key characteristics:
- HTTP/3 over QUIC — multiplexed streams without head-of-line blocking
- Lower latency than WebSocket on lossy networks
- Built-in TLS 1.3 (QUIC requires encryption)
- Self-signed certificate support via `serverCertificateHashes` for development
- Auth tokens must use post-connection authentication (Chrome strips query params from the CONNECT `:path`)

Configuration (Spring Boot):
```yaml
atmosphere:
  web-transport:
    enabled: true
    port: 4443
```

Requires `reactor-netty-http` on the classpath. The `/api/webtransport-info` endpoint exposes the port and certificate hash for the client.

## gRPC

gRPC transport enables bidirectional streaming over HTTP/2 using Protocol Buffers. The `atmosphere-grpc` module provides gRPC server and client support.

Key characteristics:
- HTTP/2 based, efficient binary protocol
- Bidirectional streaming
- Strong typing via Protocol Buffers
- Ideal for service-to-service communication

## Transport Negotiation

The atmosphere.js client library automatically negotiates transports in this order:
1. WebTransport (if configured and available)
2. WebSocket (preferred fallback)
3. SSE
4. Long-polling (fallback)

Server-side, the `AtmosphereFramework` detects the transport from the incoming request headers and dispatches to the appropriate `AsyncSupport` implementation.
