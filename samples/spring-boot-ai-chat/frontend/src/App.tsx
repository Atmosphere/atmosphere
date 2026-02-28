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

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const endRef = useRef<HTMLDivElement>(null);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/ai-chat`,
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

  const { fullText, isStreaming, progress, metadata, stats, routing, error, send, reset } =
    useStreaming({ request });

  // When streaming text updates, update/append the assistant message
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

  // When streaming completes, mark the message as complete
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
    send(text, { maxCost: 0.10, maxLatencyMs: 5000 });
  };

  const displayModel = routing.model ?? (metadata.model as string | undefined);

  const badgeStyle = {
    fontSize: 11,
    background: 'rgba(255,255,255,0.15)',
    padding: '3px 10px',
    borderRadius: 10,
    display: 'inline-block',
  };

  const headerBadges = createElement('div', {
    style: { display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' as const },
  },
    displayModel
      ? createElement('div', { style: badgeStyle }, displayModel)
      : null,
    routing.cost !== undefined
      ? createElement('div', { style: badgeStyle }, `$${routing.cost.toFixed(2)}`)
      : null,
    routing.latency !== undefined
      ? createElement('div', { style: badgeStyle }, `${routing.latency}ms`)
      : null,
  );

  return (
    <ChatLayout
      title={<><img src="/logo.png" alt="" style={{ height: '1.2em', verticalAlign: 'middle', marginRight: 8 }} />Atmosphere AI Chat</>}
      subtitle="Real-time streaming via WebSocket"
      theme="ai"
      headerExtra={headerBadges}
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
        placeholder="Ask me anything…"
        disabled={isStreaming}
        theme="ai"
      />
    </ChatLayout>
  );
}
