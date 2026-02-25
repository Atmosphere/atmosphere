import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import { resolve } from 'path';

const PROTO_PATH = resolve(__dirname, '..', '..', '..', 'grpc', 'src', 'main', 'proto', 'atmosphere.proto');

/**
 * Creates a gRPC client that can subscribe and send messages to an
 * AtmosphereGrpcServer, using the atmosphere.proto definition.
 */
export class GrpcChatClient {
  private client: any;
  private stream: any;
  private channel: grpc.Channel;
  private received: any[] = [];
  private listeners: Map<string, ((msg: any) => void)[]> = new Map();

  constructor(private host: string, private port: number) {
    const packageDef = protoLoader.loadSync(PROTO_PATH, {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });
    const proto = grpc.loadPackageDefinition(packageDef) as any;
    this.client = new proto.org.atmosphere.grpc.AtmosphereService(
      `${host}:${port}`,
      grpc.credentials.createInsecure(),
    );
  }

  /** Open a bidirectional stream and start receiving messages. */
  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.stream = this.client.Stream();
      this.stream.on('data', (msg: any) => {
        this.received.push(msg);
        const type = msg.type;
        const handlers = this.listeners.get(type) || [];
        handlers.forEach((h) => h(msg));
      });
      this.stream.on('error', (err: Error) => {
        // Ignore cancellation errors on shutdown
        if (!(err as any).code || (err as any).code !== grpc.status.CANCELLED) {
          console.error('gRPC stream error:', err.message);
        }
      });
      // The stream is ready immediately after creation
      setTimeout(resolve, 100);
    });
  }

  /** Subscribe to a topic (broadcaster path). */
  subscribe(topic: string): Promise<void> {
    return new Promise((resolve) => {
      const onAck = (msg: any) => {
        if (msg.topic === topic) {
          this.off('ACK', onAck);
          resolve();
        }
      };
      this.on('ACK', onAck);
      this.stream.write({ type: 'SUBSCRIBE', topic });
    });
  }

  /** Send a message to a topic. */
  send(topic: string, payload: string): void {
    this.stream.write({ type: 'MESSAGE', topic, payload });
  }

  /** Listen for messages of a specific type. */
  on(type: string, handler: (msg: any) => void): void {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type)!.push(handler);
  }

  /** Remove a listener. */
  off(type: string, handler: (msg: any) => void): void {
    const handlers = this.listeners.get(type);
    if (handlers) {
      this.listeners.set(type, handlers.filter((h) => h !== handler));
    }
  }

  /** Wait for a MESSAGE containing the given text. */
  waitForMessage(text: string, timeoutMs = 10_000): Promise<any> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.off('MESSAGE', onMsg);
        reject(new Error(`Timeout waiting for message containing "${text}"`));
      }, timeoutMs);

      // Check already-received messages
      const existing = this.received.find(
        (m) => m.type === 'MESSAGE' && m.payload?.includes(text),
      );
      if (existing) {
        clearTimeout(timer);
        resolve(existing);
        return;
      }

      const onMsg = (msg: any) => {
        if (msg.payload?.includes(text)) {
          clearTimeout(timer);
          this.off('MESSAGE', onMsg);
          resolve(msg);
        }
      };
      this.on('MESSAGE', onMsg);
    });
  }

  /** Close the stream and channel. */
  close(): void {
    if (this.stream) {
      this.stream.end();
    }
    this.client?.close();
  }
}
