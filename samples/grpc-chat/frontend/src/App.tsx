import { type CSSProperties, useCallback, useEffect, useRef, useState } from 'react';
import { createClient } from '@connectrpc/connect';
import { createConnectTransport } from '@connectrpc/connect-web';
import { create } from '@bufbuild/protobuf';
import {
  AtmosphereService,
  AtmosphereMessageSchema,
  MessageType,
} from './gen/atmosphere_pb';

// --- Transport & Client ---

const transport = createConnectTransport({
  baseUrl: window.location.origin,
});

const client = createClient(AtmosphereService, transport);

// --- Types ---

interface ChatMessage {
  author: string;
  text: string;
  time: number;
}

type ConnectionState = 'disconnected' | 'connecting' | 'connected';

// --- Styles ---

const containerStyle: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  height: '100vh',
  background: '#0f1117',
  color: '#e4e6ea',
};

const headerStyle: CSSProperties = {
  padding: '16px 24px',
  background: '#1a1d23',
  borderBottom: '1px solid #2d3039',
};

const messagesStyle: CSSProperties = {
  flex: 1,
  overflow: 'auto',
  padding: 16,
};

const inputBarStyle: CSSProperties = {
  display: 'flex',
  gap: 8,
  padding: 16,
  background: '#1a1d23',
  borderTop: '1px solid #2d3039',
};

const inputStyle: CSSProperties = {
  flex: 1,
  padding: '10px 14px',
  borderRadius: 8,
  border: '1px solid #2d3039',
  background: '#252830',
  color: '#e4e6ea',
  fontSize: 14,
  outline: 'none',
};

const buttonStyle: CSSProperties = {
  padding: '10px 20px',
  borderRadius: 8,
  border: 'none',
  background: '#3b82f6',
  color: '#fff',
  fontSize: 14,
  fontWeight: 600,
  cursor: 'pointer',
};

const msgBubble = (isMe: boolean): CSSProperties => ({
  maxWidth: '75%',
  padding: '8px 14px',
  borderRadius: 12,
  marginBottom: 8,
  background: isMe ? '#3b82f6' : '#252830',
  alignSelf: isMe ? 'flex-end' : 'flex-start',
  wordBreak: 'break-word',
});

const statusDot = (state: ConnectionState): CSSProperties => ({
  display: 'inline-block',
  width: 8,
  height: 8,
  borderRadius: '50%',
  marginRight: 8,
  background:
    state === 'connected' ? '#22c55e' : state === 'connecting' ? '#f59e0b' : '#ef4444',
});

// --- App ---

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [state, setState] = useState<ConnectionState>('disconnected');
  const trackingIdRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

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
            { author: 'system', text: `Connected (${msg.trackingId.substring(0, 8)}...)`, time: Date.now() },
          ]);
          continue;
        }

        if (msg.type === MessageType.MESSAGE && msg.payload) {
          // Parse "name: message" format from Broadcaster
          const payload = msg.payload;
          const colonIdx = payload.indexOf(': ');
          const from = colonIdx > 0 ? payload.substring(0, colonIdx) : 'unknown';
          const text = colonIdx > 0 ? payload.substring(colonIdx + 2) : payload;

          // Skip our own messages (we add them locally on send)
          if (from === userName) continue;

          setMessages((prev) => [...prev, { author: from, text, time: Date.now() }]);
        }
      }
    } catch (e) {
      if (!abort.signal.aborted) {
        const errMsg = e instanceof Error ? e.message : String(e);
        setMessages((prev) => [
          ...prev,
          { author: 'system', text: `Disconnected: ${errMsg}`, time: Date.now() },
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

    // Format as "name: message" so all clients (gRPC + web) see the sender
    const payload = `${name}: ${text}`;

    const msg = create(AtmosphereMessageSchema, {
      type: MessageType.MESSAGE,
      topic: '/chat',
      payload,
      trackingId: tid,
    });

    try {
      await client.send(msg);
      // Add our own message locally
      setMessages((prev) => [...prev, { author: name, text, time: Date.now() }]);
    } catch (e) {
      const errMsg = e instanceof Error ? e.message : String(e);
      setMessages((prev) => [
        ...prev,
        { author: 'system', text: `Send failed: ${errMsg}`, time: Date.now() },
      ]);
    }
  }, [name]);

  const handleSubmit = () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    setInput('');

    if (!name) {
      setName(trimmed);
      connect(trimmed);
    } else {
      sendMessage(trimmed);
    }
  };

  return (
    <div style={containerStyle}>
      <div style={headerStyle}>
        <h1 style={{ fontSize: 18, fontWeight: 600, marginBottom: 4 }}>
          Atmosphere gRPC Chat
        </h1>
        <div style={{ fontSize: 12, color: '#9ca0a8' }}>
          <span style={statusDot(state)} />
          Connect-Web ({state}){name ? ` as ${name}` : ''}
        </div>
      </div>

      <div style={messagesStyle}>
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {messages.map((msg, i) => {
            if (msg.author === 'system') {
              return (
                <div key={i} style={{ textAlign: 'center', fontSize: 12, color: '#6b7280', margin: '8px 0' }}>
                  {msg.text}
                </div>
              );
            }
            const isMe = msg.author === name;
            return (
              <div key={i} style={msgBubble(isMe)}>
                {!isMe && (
                  <div style={{ fontSize: 11, color: '#9ca0a8', marginBottom: 2 }}>{msg.author}</div>
                )}
                <div style={{ fontSize: 14 }}>{msg.text}</div>
              </div>
            );
          })}
          <div ref={messagesEndRef} />
        </div>
      </div>

      <div style={inputBarStyle}>
        <input
          style={inputStyle}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
          placeholder={name ? 'Type a message...' : 'Enter your name to join...'}
          disabled={name !== null && state !== 'connected'}
        />
        <button
          style={{
            ...buttonStyle,
            opacity: name !== null && state !== 'connected' ? 0.5 : 1,
          }}
          onClick={handleSubmit}
          disabled={name !== null && state !== 'connected'}
        >
          {name ? 'Send' : 'Join'}
        </button>
      </div>
    </div>
  );
}
