# Atmosphere gRPC Chat Sample

A chat application demonstrating Atmosphere's gRPC transport with a
[Connect-Web](https://connectrpc.com/) browser frontend. Native gRPC clients
and web browsers share the same Broadcaster, proving transport-agnostic pub/sub.

## Architecture

```
Browser (Connect-Web)              Java CLI (native gRPC)
         |                                   |
    Connect protocol (HTTP/1.1)         gRPC (HTTP/2)
    POST /AtmosphereService/*           Stream bidi RPC
         |                                   |
    Jetty :8080                        Netty gRPC :9090
    ConnectProtocolServlet             AtmosphereGrpcService
         |                                   |
         +----------> GrpcProcessor <--------+
                           |
                     Broadcaster "/chat"
                           |
               All subscribed clients
```

## How to run

### 1. Build the frontend (once)

```bash
cd samples/grpc-chat/frontend
npm install
npm run build        # builds to src/main/webapp/
cd ../../..
```

### 2. Start the server

```bash
mvn exec:java -pl samples/grpc-chat
```

This starts:
- **gRPC server** on `localhost:9090` (native bidi streaming)
- **HTTP server** on `localhost:8080` (Connect protocol + frontend)

### 3. Open the web UI

Open http://localhost:8080 — enter a name and start chatting.

### 4. Connect a Java CLI client (optional)

In another terminal:

```bash
mvn exec:java -pl samples/grpc-chat \
  -Dexec.mainClass=org.atmosphere.samples.grpc.GrpcChatClient
```

Messages flow between the browser and CLI — both use the same Broadcaster.

### 5. Test with grpcurl (optional)

```bash
# Subscribe
grpcurl -plaintext -d '{"type":"SUBSCRIBE","topic":"/chat"}' \
  localhost:9090 org.atmosphere.grpc.AtmosphereService/Stream

# Send a message (in another terminal)
grpcurl -plaintext -d '{"type":"MESSAGE","topic":"/chat","payload":"Hello from grpcurl!"}' \
  localhost:9090 org.atmosphere.grpc.AtmosphereService/Stream
```

## Frontend development

For hot-reload during frontend development:

```bash
cd samples/grpc-chat/frontend
npm run dev          # Vite dev server on :5173, proxies /AtmosphereService to :8080
```

### Regenerating TypeScript stubs

After changing `modules/grpc/src/main/proto/atmosphere.proto`:

```bash
cd samples/grpc-chat/frontend
npx buf generate     # outputs to src/gen/
```

## Key code

| File | Role |
|------|------|
| `GrpcChatServer.java` | Dual-port server: gRPC (:9090) + Jetty (:8080) |
| `ChatHandler.java` | `GrpcHandler` — connect/message/disconnect callbacks |
| `ConnectProtocolServlet.java` | Connect protocol bridge: HTTP to `GrpcProcessor` |
| `GrpcChatClient.java` | Interactive CLI client using native gRPC bidi streaming |
| `frontend/src/App.tsx` | React UI using `@connectrpc/connect-web` |
| `frontend/src/gen/atmosphere_pb.ts` | Generated TypeScript stubs from proto |

## Connect protocol details

The [Connect protocol](https://connectrpc.com/docs/protocol) is a simple HTTP-based
RPC protocol from [Buf](https://buf.build/). It supports unary and server-streaming
RPCs over HTTP/1.1 — no Envoy proxy or HTTP/2 required.

- **Subscribe** (`POST /org.atmosphere.grpc.AtmosphereService/Subscribe`):
  server-streaming — client sends a protobuf request, server streams back
  enveloped messages (5-byte header + protobuf per frame)
- **Send** (`POST /org.atmosphere.grpc.AtmosphereService/Send`):
  unary — client sends a protobuf message, server returns an ACK
