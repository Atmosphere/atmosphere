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

interface ToolEvent {
  event: string;
  data: Record<string, unknown>;
}

function ToolActivity({ events }: { events: ToolEvent[] }) {
  if (events.length === 0) return null;
  return createElement('div', {
    'data-testid': 'tool-activity',
    style: {
      background: 'rgba(224,64,251,0.08)',
      border: '1px solid rgba(224,64,251,0.25)',
      borderRadius: 10,
      padding: '8px 12px',
      marginBottom: 8,
      fontSize: 12,
      fontFamily: 'monospace',
      color: '#ccc',
    },
  }, events.map((ev, i) =>
    createElement('div', { key: i, style: { marginBottom: 2 } },
      ev.event === 'tool-start'
        ? `\u{1F527} Calling ${ev.data.toolName}(${JSON.stringify(ev.data.arguments ?? {})})`
        : ev.event === 'tool-result'
        ? `\u2705 ${ev.data.toolName} returned`
        : `${ev.event}: ${JSON.stringify(ev.data)}`,
    ),
  ));
}

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const endRef = useRef<HTMLDivElement>(null);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/adk-tools`,
      transport: 'websocket' as const,
      fallbackTransport: 'long-polling' as const,
      reconnect: true,
      maxReconnectOnClose: 10,
      reconnectInterval: 5000,
      trackMessageLength: true,
      enableProtocol: false,
      contentType: 'application/json',
    }),
    [],
  );

  const { fullText, isStreaming, progress, aiEvents, error, send, reset } =
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

  return (
    <ChatLayout
      title={<><img src="/logo.png" alt="" style={{ height: '1.2em', verticalAlign: 'middle', marginRight: 8 }} />Atmosphere + ADK Tools</>}
      subtitle="Google ADK Agent • Streaming Tokens • WebSocket Transport"
      theme="ai"
    >
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
                  background: 'linear-gradient(135deg, #667eea, #764ba2)',
                  color: '#fff',
                  padding: '10px 14px',
                  borderRadius: '16px 16px 4px 16px',
                  maxWidth: '85%',
                  wordBreak: 'break-word',
                },
              }, msg.text)
            : createElement(StreamingMessage, {
                key: i,
                text: msg.text,
                isStreaming: !msg.complete,
                dark: true,
              }),
        )}
        {aiEvents.length > 0 && createElement(ToolActivity, { events: aiEvents })}
        {progress && createElement(StreamingProgress, { message: progress })}
        {error && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>
      <ChatInput
        onSend={handleSend}
        placeholder="Ask the ADK agent…"
        disabled={isStreaming}
        theme="ai"
      />
    </ChatLayout>
  );
}
