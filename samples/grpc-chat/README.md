# Atmosphere gRPC Chat Sample

A simple chat application demonstrating Atmosphere's gRPC transport.

## What it does

This sample starts a standalone gRPC server on port 9090 using Atmosphere's gRPC transport.
Clients connect via gRPC bidirectional streaming, subscribe to the `/chat` topic, and
exchange messages that are broadcast to all connected clients.

## How to run

```bash
# From the repository root
mvn exec:java -pl samples/grpc-chat
```

The server starts on `localhost:9090`.

## Testing with grpcurl

Install [grpcurl](https://github.com/fullstorydev/grpcurl), then:

```bash
# Subscribe to the /chat topic (uses server reflection)
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream

# In another terminal, send a message
grpcurl -plaintext -d '{"type":"MESSAGE","topic":"/chat","payload":"Hello from grpcurl!"}' \
  localhost:9090 atmosphere.AtmosphereService/Stream
```

## Key code

- **`ChatHandler.java`** — Implements `GrpcHandler` to handle connect/message/disconnect events
- **`GrpcChatServer.java`** — Creates `AtmosphereFramework` and `AtmosphereGrpcServer`, wires them together

## Architecture

```
gRPC Client ←→ AtmosphereGrpcServer (port 9090)
                    ↓
              GrpcProcessor (lifecycle)
                    ↓
              Broadcaster "/chat" (pub/sub)
                    ↓
              All subscribed gRPC clients
```
