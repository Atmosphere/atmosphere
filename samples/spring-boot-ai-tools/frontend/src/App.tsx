import { useState, useEffect, useRef, useMemo, createElement } from 'react';
import { useStreaming } from 'atmosphere.js/react';
import type { RoutingInfo } from 'atmosphere.js';
import { ChatLayout, ChatInput, StreamingMessage, StreamingProgress, StreamingError } from 'atmosphere.js/chat';

interface UserMessage {
  role: 'user';
  text: string;
}

interface AssistantMessage {
  role: 'assistant';
  text: string;
  complete: boolean;
  costInfo?: CostInfo;
}

interface CostInfo {
  model?: string;
  cost?: number;
  tokens?: number;
  latency?: number;
}

type Message = UserMessage | AssistantMessage;

function formatCost(cost: number): string {
  if (cost < 0.01) return `$${cost.toFixed(6)}`;
  return `$${cost.toFixed(4)}`;
}

function CostBadge({ info }: { info: CostInfo }) {
  const parts: string[] = [];
  if (info.tokens != null) parts.push(`~${info.tokens} tokens`);
  if (info.model) parts.push(info.model);
  if (info.cost != null) parts.push(formatCost(info.cost));
  if (info.latency != null) parts.push(`${info.latency}ms`);
  if (parts.length === 0) return null;

  return createElement('div', {
    style: {
      fontSize: 11,
      color: '#888',
      marginTop: 4,
      paddingLeft: 2,
      fontFamily: 'monospace',
    },
  }, parts.join(' \u00b7 '));
}

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const endRef = useRef<HTMLDivElement>(null);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/langchain4j-tools/lobby`,
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

  const { fullText, isStreaming, progress, metadata, routing, error, send, reset } =
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

  // When streaming completes, mark the message as complete and snapshot cost info
  useEffect(() => {
    if (!isStreaming && fullText) {
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        if (last && last.role === 'assistant' && !last.complete) {
          const costInfo = buildCostInfo(routing, metadata);
          return [...prev.slice(0, -1), { ...last, complete: true, costInfo }];
        }
        return prev;
      });
    }
  }, [isStreaming, fullText, routing, metadata]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, fullText]);

  const handleSend = (text: string) => {
    setMessages((prev) => [...prev, { role: 'user', text }]);
    reset();
    send(text);
  };

  const modelBadge = metadata.model
    ? createElement('div', {
        style: {
          fontSize: 11,
          background: 'rgba(255,255,255,0.15)',
          padding: '3px 10px',
          borderRadius: 10,
          marginTop: 6,
          display: 'inline-block',
        },
      }, String(metadata.model))
    : null;

  return (
    <ChatLayout
      title={<><img src="/logo.png" alt="" style={{ height: '1.2em', verticalAlign: 'middle', marginRight: 8 }} />Atmosphere @AiTool Pipeline</>}
      subtitle="Tool calling with cost metering"
      theme="aitool"
      headerExtra={modelBadge}
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
                  background: 'linear-gradient(135deg, #e040fb, #7c4dff)',
                  color: '#fff',
                  padding: '10px 14px',
                  borderRadius: '16px 16px 4px 16px',
                  maxWidth: '85%',
                  wordBreak: 'break-word',
                },
              }, msg.text)
            : createElement('div', { key: i }, [
                createElement(StreamingMessage, {
                  key: 'msg',
                  text: msg.text,
                  isStreaming: !msg.complete,
                  dark: true,
                }),
                msg.complete && msg.costInfo
                  ? createElement(CostBadge, { key: 'cost', info: msg.costInfo })
                  : null,
              ]),
        )}
        {progress && createElement(StreamingProgress, { message: progress })}
        {error && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>
      <ChatInput
        onSend={handleSend}
        placeholder="Ask about time, weather, or try the tool pipeline…"
        disabled={isStreaming}
        theme="aitool"
      />
    </ChatLayout>
  );
}

function buildCostInfo(routing: RoutingInfo, metadata: Record<string, unknown>): CostInfo | undefined {
  // routing.tokens is not a standard RoutingInfo field, so read it from metadata
  const tokens = metadata['routing.tokens'];
  if (!routing.model && !routing.cost && !routing.latency) return undefined;
  return {
    model: routing.model ? String(routing.model) : undefined,
    cost: typeof routing.cost === 'number' ? routing.cost : undefined,
    tokens: typeof tokens === 'number' ? tokens : undefined,
    latency: typeof routing.latency === 'number' ? routing.latency : undefined,
  };
}
