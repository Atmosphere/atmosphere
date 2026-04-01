import { useCallback, useRef, useState } from 'react';
import { createClient } from '@connectrpc/connect';
import { createConnectTransport } from '@connectrpc/connect-web';
import { create } from '@bufbuild/protobuf';
import {
  AtmosphereService,
  AtmosphereMessageSchema,
  MessageType,
} from './gen/atmosphere_pb';
import { ChatLayout, MessageList, ChatInput } from 'atmosphere.js/chat';
import type { ChatMessage, ChatTheme } from 'atmosphere.js/chat';

const grpcTheme: ChatTheme = {
  gradient: ['#065f46', '#047857'],
  accent: '#10b981',
  dark: true,
};

const transport = createConnectTransport({
  baseUrl: window.location.origin,
});

const client = createClient(AtmosphereService, transport);

type ConnectionState = 'disconnected' | 'connecting' | 'connected';

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);
  const [state, setState] = useState<ConnectionState>('disconnected');
  const trackingIdRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const connect = useCallback(async (userName: string) => {
    setState('connecting');
    const abort = new AbortController();
    abortRef.current = abort;

    try {
      const subscribeMsg = create(AtmosphereMessageSchema, {
        type: MessageType.SUBSCRIBE,
        topic: '/chat',
      });

      const stream = client.subscribe(subscribeMsg, { signal: abort.signal });

      for await (const msg of stream) {
        if (msg.type === MessageType.ACK) {
          trackingIdRef.current = msg.trackingId;
          setState('connected');
          setMessages((prev) => [
            ...prev,
            { author: 'system', message: `Connected (${msg.trackingId.substring(0, 8)}...)`, time: Date.now() },
          ]);
          continue;
        }

        if (msg.type === MessageType.MESSAGE && msg.payload) {
          const payload = msg.payload;
          const colonIdx = payload.indexOf(': ');
          const from = colonIdx > 0 ? payload.substring(0, colonIdx) : 'unknown';
          const text = colonIdx > 0 ? payload.substring(colonIdx + 2) : payload;

          // Skip our own messages (we add them locally on send)
          if (from === userName) continue;

          setMessages((prev) => [...prev, { author: from, message: text, time: Date.now() }]);
        }
      }
    } catch (e) {
      if (!abort.signal.aborted) {
        const errMsg = e instanceof Error ? e.message : String(e);
        setMessages((prev) => [
          ...prev,
          { author: 'system', message: `Disconnected: ${errMsg}`, time: Date.now() },
        ]);
      }
    } finally {
      setState('disconnected');
      trackingIdRef.current = null;
    }
  }, []);

  const sendMessage = useCallback(async (text: string) => {
    const tid = trackingIdRef.current;
    if (!tid || !name) return;

    const payload = `${name}: ${text}`;

    const msg = create(AtmosphereMessageSchema, {
      type: MessageType.MESSAGE,
      topic: '/chat',
      payload,
      trackingId: tid,
    });

    try {
      await client.send(msg);
      setMessages((prev) => [...prev, { author: name, message: text, time: Date.now() }]);
    } catch (e) {
      const errMsg = e instanceof Error ? e.message : String(e);
      setMessages((prev) => [
        ...prev,
        { author: 'system', message: `Send failed: ${errMsg}`, time: Date.now() },
      ]);
    }
  }, [name]);

  const handleSend = (text: string) => {
    if (!name) {
      setName(text);
      connect(text);
    } else {
      sendMessage(text);
    }
  };

  // Map our connection state to the ChatLayout state type
  const layoutState = state === 'connected' ? 'connected'
    : state === 'connecting' ? 'connecting'
    : 'disconnected';

  return (
    <ChatLayout
      title="Atmosphere gRPC Chat"
      subtitle="Connect Protocol (gRPC-Web) — real-time multi-user chat"
      theme={grpcTheme}
      state={layoutState}
    >
      <MessageList messages={messages} currentUser={name ?? undefined} theme={grpcTheme} />
      <ChatInput
        onSend={handleSend}
        placeholder={name ? 'Type a message...' : 'Enter your name to join...'}
        disabled={name !== null && state !== 'connected'}
        theme={grpcTheme}
      />
    </ChatLayout>
  );
}
