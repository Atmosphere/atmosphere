import { useState, useRef, useEffect, useMemo } from 'react';
import { useAtmosphere } from 'atmosphere.js/react';

interface ChatMessage {
  author: string;
  message: string;
  time?: string;
}

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [name, setName] = useState<string | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/chat`,
      transport: 'websocket' as const,
      fallbackTransport: 'long-polling' as const,
      reconnect: true,
      reconnectInterval: 5000,
      maxReconnectOnClose: 10,
      trackMessageLength: true,
      contentType: 'application/json',
    }),
    [],
  );

  const { data, state, push } = useAtmosphere<ChatMessage>({
    request,
  });

  useEffect(() => {
    if (!data) return;
    try {
      const msg: ChatMessage =
        typeof data === 'string' ? JSON.parse(data) : data;
      if (!msg.author) return;

      if (!name) {
        setName(msg.author);
      }
      setMessages((prev) => [...prev, msg]);
    } catch {
      // ignore parse errors
    }
  }, [data, name]);

  useEffect(() => {
    contentRef.current?.scrollTo(0, contentRef.current.scrollHeight);
  }, [messages]);

  const handleSend = () => {
    const text = input.trim();
    if (!text) return;

    if (!name) {
      setName(text);
      push(JSON.stringify({ author: text, message: `${text} has joined!` }));
    } else {
      push(JSON.stringify({ author: name, message: text }));
    }
    setInput('');
  };

  const formatTime = (time?: string) => {
    if (!time) return '';
    const d = new Date(parseInt(time));
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  };

  return (
    <div style={styles.container}>
      <header style={styles.header}>
        <h1 style={styles.title}>🚀 Atmosphere 4.0 — MCP + Chat</h1>
        <p style={styles.subtitle}>
          MCP Server at <code>/atmosphere/mcp</code> • Chat at{' '}
          <code>/atmosphere/chat</code>
          <br />
          AI agents can list users, broadcast messages, ban users via MCP tools
        </p>
      </header>

      <div style={styles.status}>
        <span
          style={{
            ...styles.statusDot,
            backgroundColor:
              state === 'connected'
                ? '#28a745'
                : state === 'reconnecting'
                  ? '#ffc107'
                  : '#dc3545',
          }}
        />
        {state === 'connected'
          ? name ?? 'Enter name'
          : state === 'reconnecting'
            ? 'Reconnecting...'
            : 'Connecting...'}
      </div>

      <div ref={contentRef} style={styles.content}>
        {state === 'connected' && !name && (
          <p style={styles.systemMsg}>
            Connected using websocket. Enter your name to join the chat...
          </p>
        )}
        {name && messages.length === 0 && (
          <p style={styles.welcomeMsg}>
            <strong>Welcome {name}!</strong> You joined the chat.
          </p>
        )}
        {messages.map((msg, i) => {
          const isMe = msg.author === name;
          return (
            <div
              key={i}
              style={{
                ...styles.message,
                ...(isMe ? styles.myMessage : {}),
              }}
            >
              <strong>{msg.author}</strong>{' '}
              <span style={styles.time}>{formatTime(msg.time)}</span>
              <br />
              {msg.message}
            </div>
          );
        })}
      </div>

      <div style={styles.inputRow}>
        <input
          style={styles.input}
          placeholder={
            name ? 'Type a message and press Enter...' : 'Enter your name...'
          }
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSend()}
          disabled={state !== 'connected'}
        />
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    maxWidth: 700,
    margin: '0 auto',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
  },
  header: {
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    color: 'white',
    padding: '20px',
    textAlign: 'center',
  },
  title: { margin: 0, fontSize: '1.4em' },
  subtitle: { margin: '8px 0 0', fontSize: '0.85em', opacity: 0.9 },
  status: {
    padding: '8px 16px',
    background: '#f8f9fa',
    borderBottom: '1px solid #dee2e6',
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    fontSize: '0.9em',
    fontWeight: 600,
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: '50%',
    display: 'inline-block',
  },
  content: {
    flex: 1,
    overflow: 'auto',
    padding: 16,
    background: '#fff',
  },
  systemMsg: {
    textAlign: 'center',
    color: '#28a745',
    fontStyle: 'italic',
  },
  welcomeMsg: {
    background: '#d4edda',
    borderLeft: '3px solid #28a745',
    padding: '10px 14px',
    textAlign: 'center',
  },
  message: {
    padding: '8px 12px',
    margin: '4px 0',
    borderRadius: 6,
  },
  myMessage: {
    background: '#e7f3ff',
    borderLeft: '3px solid #667eea',
  },
  time: { color: '#999', fontSize: 11 },
  inputRow: {
    padding: 12,
    borderTop: '1px solid #dee2e6',
    background: '#f8f9fa',
  },
  input: {
    width: '100%',
    padding: '10px 14px',
    border: '1px solid #ced4da',
    borderRadius: 6,
    fontSize: '0.95em',
    boxSizing: 'border-box',
  },
};
