import { useState, useEffect, useRef, useMemo, createElement } from 'react';
import { useStreaming } from 'atmosphere.js/react';
import { ChatLayout, ChatInput, StreamingMessage, StreamingProgress, StreamingError } from 'atmosphere.js/chat';

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

// Palette mirrors the Atmosphere AI Console (dark mode):
// --bg-primary / --bg-surface / --text-* / --border-color / --accent-color.
// Keeping these in one object so the classroom reads as a Console sibling.
const PALETTE = {
  bgPrimary: '#0f1117',
  bgSurface: '#1a1d23',
  bgTertiary: '#252830',
  bgHover: '#2a2d35',
  textPrimary: '#e4e5e7',
  textSecondary: '#9ca0a8',
  textTertiary: '#6b7280',
  borderColor: '#2d3039',
  accent: '#3b82f6',
  accentBg: 'rgba(59, 130, 246, .12)',
} as const;

const FONT_STACK = '-apple-system, BlinkMacSystemFont, "Segoe UI", "Helvetica Neue", Arial, sans-serif';

// Atmosphere logo (gold gradient) — identical to the console header mark.
const LOGO_SVG =
  "data:image/svg+xml;utf8," + encodeURIComponent(
    "<svg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'>" +
      "<defs>" +
      "<linearGradient id='ga' x1='30' y1='170' x2='160' y2='30' gradientUnits='userSpaceOnUse'>" +
      "<stop offset='0%' stop-color='#3d2508'/><stop offset='30%' stop-color='#6a4b1c'/>" +
      "<stop offset='60%' stop-color='#8c6c35'/><stop offset='85%' stop-color='#b8944a'/>" +
      "<stop offset='100%' stop-color='#d4b060'/></linearGradient>" +
      "<linearGradient id='gb' x1='170' y1='30' x2='40' y2='170' gradientUnits='userSpaceOnUse'>" +
      "<stop offset='0%' stop-color='#efe0b8'/><stop offset='25%' stop-color='#dbcda3'/>" +
      "<stop offset='50%' stop-color='#c4a86a'/><stop offset='80%' stop-color='#a08040'/>" +
      "<stop offset='100%' stop-color='#7a5a28'/></linearGradient>" +
      "</defs>" +
      "<path d='M 72 51.5 L 74.2 36 A 69 69 0 0 1 158.5 136.6 L 178.9 149.3 L 129.7 147.5 L 116.1 110.1 L 136.5 122.8 A 43 43 0 0 0 83.9 60.1 Z' fill='url(#ga)'/>" +
      "<path d='M 128 148.5 L 125.8 164 A 69 69 0 0 1 41.5 63.4 L 21.1 50.7 L 70.3 52.5 L 83.9 89.9 L 63.5 77.2 A 43 43 0 0 0 116.1 139.9 Z' fill='url(#gb)'/>" +
      "<path d='M 166.6 82.1 A 69 69 0 0 1 158.5 136.6 L 136.5 122.8 A 43 43 0 0 0 141.5 88.9 Z' fill='url(#ga)'/>" +
      "<path d='M 158.5 136.6 L 178.9 149.3 L 129.7 147.5 L 116.1 110.1 L 136.5 122.8 Z' fill='url(#ga)'/>" +
      "</svg>"
  );

// Room-specific accents. The room colour is used as a 3px left-border and a
// tinted badge; the main surface / text / input remain Console-neutral so the
// classroom reads as a sibling view of the AI Console, not a different app.
const ROOMS = [
  { id: 'math', label: 'Math', icon: 'π', color: '#6366f1', tagline: 'Step-by-step problem solving' },
  { id: 'code', label: 'Code', icon: '<>', color: '#10b981', tagline: 'Clean code, clean explanations' },
  { id: 'science', label: 'Science', icon: '⚛', color: '#f472b6', tagline: 'Curiosity, tested' },
] as const;

function RoomSelector({ onJoin }: { onJoin: (room: string) => void }) {
  return (
    <div
      data-testid="room-selector"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        background: PALETTE.bgPrimary,
        color: PALETTE.textPrimary,
        fontFamily: FONT_STACK,
        padding: 32,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <img src={LOGO_SVG} alt="Atmosphere" width={40} height={40} />
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 600, margin: 0, lineHeight: 1.2 }}>
            AI Classroom
          </h1>
          <div style={{ fontSize: 12, color: PALETTE.textSecondary }}>
            Real-time collaborative streaming &bull; powered by Atmosphere
          </div>
        </div>
      </div>
      <p
        style={{
          color: PALETTE.textTertiary,
          marginBottom: 36,
          maxWidth: 480,
          textAlign: 'center',
          fontSize: 14,
          lineHeight: 1.5,
        }}
      >
        Join a room. Ask a question. Every student sees the AI response stream in real time.
      </p>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', justifyContent: 'center' }}>
        {ROOMS.map((room) => (
          <button
            key={room.id}
            data-testid={`room-${room.id}`}
            onClick={() => onJoin(room.id)}
            style={{
              background: PALETTE.bgSurface,
              border: `1px solid ${PALETTE.borderColor}`,
              borderLeft: `3px solid ${room.color}`,
              borderRadius: 10,
              padding: '24px 32px',
              color: PALETTE.textPrimary,
              cursor: 'pointer',
              transition: 'transform 0.15s, background 0.15s, border-color 0.15s',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'flex-start',
              gap: 10,
              minWidth: 200,
              textAlign: 'left',
              fontFamily: FONT_STACK,
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = PALETTE.bgHover;
              e.currentTarget.style.transform = 'translateY(-2px)';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = PALETTE.bgSurface;
              e.currentTarget.style.transform = 'translateY(0)';
            }}
          >
            <span
              style={{
                fontSize: 22,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 36,
                height: 36,
                borderRadius: 8,
                background: room.color + '22',
                color: room.color,
              }}
            >
              {room.icon}
            </span>
            <span style={{ fontSize: 16, fontWeight: 600 }}>{room.label}</span>
            <span style={{ fontSize: 12, color: PALETTE.textTertiary }}>{room.tagline}</span>
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
  const accent = roomConfig?.color ?? PALETTE.accent;

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

  // Neutral Console-aligned theme (dark, blue accent); room tint rides on the
  // badge + message accents rather than the shell, so the classroom feels like
  // a sibling of /atmosphere/console/.
  const chatTheme = useMemo(
    () => ({
      gradient: [PALETTE.bgSurface, PALETTE.bgPrimary] as [string, string],
      accent: accent,
      dark: true,
    }),
    [accent],
  );

  return (
    <ChatLayout
      title={
        <span data-testid="classroom-layout" style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
          <img src={LOGO_SVG} alt="Atmosphere" width={22} height={22} style={{ flexShrink: 0 }} />
          <span style={{ fontWeight: 600 }}>AI Classroom</span>
          <span
            data-testid="room-badge"
            style={{
              background: accent + '22',
              color: accent,
              fontSize: 11,
              fontWeight: 600,
              padding: '2px 8px',
              borderRadius: 9999,
              letterSpacing: '.025em',
              textTransform: 'uppercase',
            }}
          >
            {roomConfig?.label ?? room}
          </span>
        </span>
      }
      subtitle={
        <span style={{ color: PALETTE.textSecondary, fontSize: 12 }}>
          Everyone in this room sees responses in real time
          <button
            onClick={onLeave}
            style={{
              background: 'transparent',
              border: `1px solid ${PALETTE.borderColor}`,
              color: PALETTE.textSecondary,
              padding: '2px 10px',
              borderRadius: 6,
              cursor: 'pointer',
              marginLeft: 12,
              fontSize: 11,
              fontFamily: FONT_STACK,
            }}
          >
            Leave Room
          </button>
        </span>
      }
      theme={chatTheme}
      headerExtra={
        displayModel
          ? createElement(
              'div',
              {
                style: {
                  fontSize: 11,
                  color: PALETTE.textSecondary,
                  background: PALETTE.bgTertiary,
                  padding: '3px 10px',
                  borderRadius: 9999,
                  marginTop: 4,
                  border: `1px solid ${PALETTE.borderColor}`,
                  fontFamily: FONT_STACK,
                },
              },
              displayModel,
            )
          : null
      }
    >
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '16px 20px',
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
          background: PALETTE.bgPrimary,
        }}
      >
        {messages.map((msg, i) =>
          msg.role === 'user'
            ? createElement(
                'div',
                {
                  key: i,
                  style: {
                    alignSelf: 'flex-end',
                    background: accent,
                    color: '#fff',
                    padding: '10px 14px',
                    borderRadius: '12px 12px 4px 12px',
                    maxWidth: '85%',
                    wordBreak: 'break-word',
                    fontSize: 14,
                    lineHeight: 1.5,
                    boxShadow: '0 1px 2px rgba(0,0,0,0.2)',
                  },
                },
                msg.text,
              )
            : createElement(StreamingMessage, {
                key: i,
                text: msg.text,
                isStreaming: !msg.complete,
                dark: true,
              }),
        )}
        {progress && createElement(StreamingProgress, { message: progress })}
        {error && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>
      {stats && !isStreaming && (
        <div
          style={{
            fontSize: 11,
            color: PALETTE.textTertiary,
            padding: '6px 20px',
            background: PALETTE.bgSurface,
            borderTop: `1px solid ${PALETTE.borderColor}`,
            display: 'flex',
            gap: 8,
            fontFamily: FONT_STACK,
          }}
        >
          <span>{stats.totalStreamingTexts} tokens</span>
          <span>&middot;</span>
          <span>{stats.elapsedMs}ms</span>
          <span>&middot;</span>
          <span>{stats.streamingTextsPerSecond.toFixed(1)} tok/s</span>
        </div>
      )}
      <ChatInput
        onSend={handleSend}
        placeholder={`Ask a ${roomConfig?.label.toLowerCase() ?? ''} question…`}
        disabled={isStreaming}
        theme={chatTheme}
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
