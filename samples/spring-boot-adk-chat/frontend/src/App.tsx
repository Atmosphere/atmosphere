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
      url: `${window.location.protocol}//${window.location.host}/atmosphere/adk-chat`,
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

  const { fullText, isStreaming, progress, error, send, reset } =
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
      title="🤖 Atmosphere + ADK Chat"
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
