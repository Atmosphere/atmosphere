import { useState, useEffect, useRef, useMemo, createElement } from 'react';
import { useStreaming } from 'atmosphere.js/react';
import { ChatLayout, ChatInput, StreamingProgress, StreamingError } from 'atmosphere.js/chat';
import { marked } from 'marked';

interface UserMessage {
  role: 'user';
  text: string;
}

interface AssistantMessage {
  role: 'assistant';
  text: string;
  complete: boolean;
}

type Message = UserMessage | AssistantMessage;

const ROOMS = [
  { id: 'math', label: 'Math', icon: '\u03C0', color: '#667eea' },
  { id: 'code', label: 'Code', icon: '<>', color: '#43b883' },
  { id: 'science', label: 'Science', icon: '\u269B', color: '#f093fb' },
] as const;

// Configure marked for safe rendering — escape raw HTML to prevent XSS
marked.setOptions({ breaks: true, gfm: true });
marked.use({
  renderer: {
    html({ text }: { text: string }) {
      return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    },
  },
});

/** Renders markdown text as HTML inside a styled bubble. */
function MarkdownBubble({ text, isStreaming }: { text: string; isStreaming: boolean }) {
  const html = marked.parse(text) as string;
  return createElement('div', {
    style: {
      alignSelf: 'flex-start',
      background: '#1a1a3e',
      border: '1px solid #2a2a4a',
      color: '#e0e0e0',
      padding: '10px 14px',
      borderRadius: '16px 16px 16px 4px',
      maxWidth: '85%',
      wordBreak: 'break-word' as const,
      lineHeight: 1.6,
    },
    className: 'markdown-body',
    dangerouslySetInnerHTML: { __html: html + (isStreaming ? '<span class="cursor"></span>' : '') },
  });
}

function RoomSelector({ onJoin }: { onJoin: (room: string) => void }) {
  return (
    <div data-testid="room-selector" style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: '100vh',
      background: 'linear-gradient(135deg, #0f0c29, #302b63, #24243e)',
      color: '#fff',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    }}>
      <h1 style={{ fontSize: 32, marginBottom: 8 }}>AI Classroom</h1>
      <p style={{ color: 'rgba(255,255,255,0.6)', marginBottom: 40, maxWidth: 480, textAlign: 'center' }}>
        Join a room. Ask a question. Every student sees the AI response stream in real time.
      </p>
      <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', justifyContent: 'center' }}>
        {ROOMS.map((room) => (
          <button
            key={room.id}
            data-testid={`room-${room.id}`}
            onClick={() => onJoin(room.id)}
            style={{
              background: 'rgba(255,255,255,0.08)',
              border: `2px solid ${room.color}`,
              borderRadius: 16,
              padding: '32px 40px',
              color: '#fff',
              cursor: 'pointer',
              transition: 'transform 0.15s, background 0.15s',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 12,
              minWidth: 140,
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = 'rgba(255,255,255,0.15)';
              e.currentTarget.style.transform = 'translateY(-4px)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = 'rgba(255,255,255,0.08)';
              e.currentTarget.style.transform = 'translateY(0)';
            }}
          >
            <span style={{ fontSize: 36 }}>{room.icon}</span>
            <span style={{ fontSize: 18, fontWeight: 600 }}>{room.label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

function Classroom({ room, onLeave }: { room: string; onLeave: () => void }) {
  const [messages, setMessages] = useState<Message[]>([]);
  const endRef = useRef<HTMLDivElement>(null);
  const roomConfig = ROOMS.find((r) => r.id === room);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/classroom/${room}`,
      transport: 'websocket' as const,
      fallbackTransport: 'long-polling' as const,
      reconnect: true,
      maxReconnectOnClose: 10,
      reconnectInterval: 5000,
      trackMessageLength: true,
      enableProtocol: false,
      contentType: 'application/json',
    }),
    [room],
  );

  const { fullText, isStreaming, progress, metadata, stats, error, send, reset } =
    useStreaming({ request });

  useEffect(() => {
    if (!fullText) return;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant' && !last.complete) {
        return [...prev.slice(0, -1), { role: 'assistant', text: fullText, complete: false }];
      }
      return [...prev, { role: 'assistant', text: fullText, complete: false }];
    });
  }, [fullText]);

  useEffect(() => {
    if (!isStreaming && fullText) {
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        if (last && last.role === 'assistant' && !last.complete) {
          return [...prev.slice(0, -1), { ...last, complete: true }];
        }
        return prev;
      });
    }
  }, [isStreaming, fullText]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, fullText]);

  const handleSend = (text: string) => {
    setMessages((prev) => [...prev, { role: 'user', text }]);
    reset();
    send(text);
  };

  const displayModel = metadata.model as string | undefined;

  return (
    <ChatLayout
      title={
        <span data-testid="classroom-layout">
          AI Classroom
          <span data-testid="room-badge" style={{
            background: roomConfig?.color ?? '#667eea',
            fontSize: 12,
            padding: '3px 10px',
            borderRadius: 10,
            marginLeft: 10,
            verticalAlign: 'middle',
          }}>
            {roomConfig?.label ?? room}
          </span>
        </span>
      }
      subtitle={
        <span>
          Everyone in this room sees responses in real time
          <button
            onClick={onLeave}
            style={{
              background: 'rgba(255,255,255,0.15)',
              border: 'none',
              color: '#fff',
              padding: '2px 10px',
              borderRadius: 8,
              cursor: 'pointer',
              marginLeft: 12,
              fontSize: 11,
            }}
          >
            Leave Room
          </button>
        </span>
      }
      theme="ai"
      headerExtra={displayModel
        ? createElement('div', {
            style: { fontSize: 11, background: 'rgba(255,255,255,0.15)', padding: '3px 10px', borderRadius: 10, marginTop: 6 },
          }, displayModel)
        : null}
    >
      <style>{`
        .markdown-body h1, .markdown-body h2, .markdown-body h3 { margin: 8px 0 4px; }
        .markdown-body p { margin: 4px 0; }
        .markdown-body ul, .markdown-body ol { margin: 4px 0; padding-left: 20px; }
        .markdown-body code { background: rgba(255,255,255,0.1); padding: 1px 4px; border-radius: 3px; font-size: 0.9em; }
        .markdown-body pre { background: #0d1117; padding: 12px; border-radius: 8px; overflow-x: auto; margin: 8px 0; }
        .markdown-body pre code { background: none; padding: 0; }
        .markdown-body strong { color: #fff; }
        .markdown-body blockquote { border-left: 3px solid #444; padding-left: 12px; color: #aaa; margin: 8px 0; }
        .cursor { display: inline-block; width: 6px; height: 1em; background: #667eea; margin-left: 2px; vertical-align: text-bottom; animation: blink 1s step-end infinite; }
        @keyframes blink { 0%, 50% { opacity: 1; } 51%, 100% { opacity: 0; } }
      `}</style>
      <div style={{
        flex: 1,
        overflowY: 'auto',
        padding: '12px 16px',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        background: '#16213e',
      }}>
        {messages.map((msg, i) =>
          msg.role === 'user'
            ? createElement('div', {
                key: i,
                style: {
                  alignSelf: 'flex-end',
                  background: `linear-gradient(135deg, ${roomConfig?.color ?? '#667eea'}, #764ba2)`,
                  color: '#fff',
                  padding: '10px 14px',
                  borderRadius: '16px 16px 4px 16px',
                  maxWidth: '85%',
                  wordBreak: 'break-word',
                },
              }, msg.text)
            : createElement(MarkdownBubble, {
                key: i,
                text: msg.text,
                isStreaming: !msg.complete,
              }),
        )}
        {progress && createElement(StreamingProgress, { message: progress })}
        {error && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>
      {stats && !isStreaming && (
        <div style={{
          fontSize: 11,
          color: 'rgba(255,255,255,0.5)',
          padding: '4px 16px',
          background: '#16213e',
          display: 'flex',
          gap: 8,
        }}>
          <span>{stats.totalTokens} tokens</span>
          <span>&middot;</span>
          <span>{stats.elapsedMs}ms</span>
          <span>&middot;</span>
          <span>{stats.tokensPerSecond.toFixed(1)} tok/s</span>
        </div>
      )}
      <ChatInput
        onSend={handleSend}
        placeholder={`Ask a ${roomConfig?.label.toLowerCase() ?? ''} question\u2026`}
        disabled={isStreaming}
        theme="ai"
      />
    </ChatLayout>
  );
}

export function App() {
  const [room, setRoom] = useState<string | null>(null);

  if (!room) {
    return <RoomSelector onJoin={setRoom} />;
  }

  return <Classroom room={room} onLeave={() => setRoom(null)} />;
}
