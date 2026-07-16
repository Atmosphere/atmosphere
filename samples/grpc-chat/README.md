# Atmosphere gRPC Chat Sample

A chat application demonstrating Atmosphere's gRPC transport with a
browser clients speaking the [Connect protocol](https://connectrpc.com/) in JSON mode
(the bundled Atmosphere Console's `grpc` transport adapter). Native gRPC clients
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

```bash
mvn exec:java -pl samples/grpc-chat
```

This starts:
- **gRPC server** on `localhost:9090` (native bidi streaming)
- **HTTP server** on `localhost:8080` (Connect protocol + the bundled Atmosphere Console)

### Open the web UI

Open http://localhost:8080 — it redirects to the bundled Atmosphere Console at
`/atmosphere/console/`, whose `grpc` transport adapter speaks the Connect
protocol in JSON mode from the browser (enveloped `application/connect+json`
Subscribe stream + unary `application/json` Send — no protobuf codegen needed).
The console bundle is a committed copy kept fresh by
`scripts/sync-console-bundle.sh` (gated in CI).

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

### Regenerating TypeScript stubs

After changing `modules/grpc/src/main/proto/atmosphere.proto`:

Native/JVM clients use the generated stubs from `modules/grpc`; browser access
needs no codegen — the Console's adapter speaks Connect-JSON directly.

## Key code

| File | Role |
|------|------|
| `GrpcChatServer.java` | Dual-port server: gRPC (:9090) + Jetty (:8080) |
| `ChatHandler.java` | `GrpcHandler` — connect/message/disconnect callbacks |
| `ConnectProtocolServlet.java` | Connect protocol bridge: HTTP to `GrpcProcessor` |
| `GrpcChatClient.java` | Interactive CLI client using native gRPC bidi streaming |
| `ConsoleInfoServlet.java` | `/api/console/info` — points the Console at the Connect service with the `grpc` adapter |
| `src/main/webapp/atmosphere/console/` | Committed Atmosphere Console bundle (synced by `scripts/sync-console-bundle.sh`) |

## Connect protocol details

The [Connect protocol](https://connectrpc.com/docs/protocol) is a simple HTTP-based
RPC protocol from [Buf](https://buf.build/). It supports unary and server-streaming
RPCs over HTTP/1.1 — no Envoy proxy or HTTP/2 required.

- **Subscribe** (`POST /org.atmosphere.grpc.AtmosphereService/Subscribe`):
  server-streaming — client sends a protobuf request, server streams back
  enveloped messages (5-byte header + protobuf per frame)
- **Send** (`POST /org.atmosphere.grpc.AtmosphereService/Send`):
  unary — client sends a protobuf message, server returns an ACK
