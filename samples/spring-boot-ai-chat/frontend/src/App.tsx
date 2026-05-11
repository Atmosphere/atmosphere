import { useState, useEffect, useRef, useMemo, createElement } from 'react';
import { useStreaming, ConnectionStatusBadge } from 'atmosphere.js/react';
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

async function fetchWebTransportInfo(): Promise<{port?: number; certificateHash?: string}> {
  try {
    const res = await fetch('/api/webtransport-info');
    if (res.ok) return res.json();
  } catch { /* WebTransport not enabled */ }
  return {};
}

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const endRef = useRef<HTMLDivElement>(null);
  const [wtInfo, setWtInfo] = useState<{port?: number; certificateHash?: string}>({});
  const [wtLoaded, setWtLoaded] = useState(false);

  useEffect(() => {
    fetchWebTransportInfo().then((info) => { setWtInfo(info); setWtLoaded(true); });
  }, []);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/ai-chat`,
      transport: 'webtransport' as const,
      fallbackTransport: 'websocket' as const,
      ...(wtInfo.enabled && wtInfo.port ? { webTransportUrl: `https://${window.location.hostname}:${wtInfo.port}/atmosphere/ai-chat` } : {}),
      ...(wtInfo.certificateHash ? { serverCertificateHashes: [wtInfo.certificateHash] } : {}),
      reconnect: true,
      maxReconnectOnClose: 10,
      reconnectInterval: 5000,
      trackMessageLength: true,
      enableProtocol: false,
      contentType: 'application/json',
      // authToken: 'demo-token', // disabled for WebTransport demo
    }),
    [wtInfo],
  );

  const { fullText, isStreaming, progress, metadata, stats, routing, error, send, reset,
          connectionState, isReconnecting, connectionStatus } =
    useStreaming({
      request,
      enabled: wtLoaded,
      // Pair with the server-side @AiEndpoint primitives:
      // - onClose fires when AiStreamingSession.cancelInflight aborts mid-stream
      // - onReconnect fires when the client retries; @AiEndpoint(streamCache=
      //   UUIDBroadcasterCache.class) replays cached frames on the server side
      // - onClientTimeout fires when the heartbeat watchdog expires; pair
      //   with @AiEndpoint(heartbeatSeconds=N) on long-tool flows
      // - onTransportFailure fires when WebTransport handshake fails and the
      //   client falls back to WebSocket — the Badge surfaces this to the user.
      onOpen: () => console.info('[atmosphere] transport open'),
      onClose: () => console.info('[atmosphere] transport closed'),
      onReconnect: () => console.info('[atmosphere] reconnecting…'),
      onReopen: () => console.info('[atmosphere] reopened'),
      onClientTimeout: () => console.warn('[atmosphere] client heartbeat timeout'),
      onTransportFailure: (reason) =>
        console.warn('[atmosphere] transport failed, falling back:', reason),
      onFailureToReconnect: () =>
        console.error('[atmosphere] reconnect attempts exhausted'),
    });

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

  // Connection-state banner: surfaces reconnect / closed / error states
  // produced by the new transport lifecycle hooks. Pairs with the server-side
  // @AiEndpoint disconnect, streamCache, and heartbeat primitives so the user
  // sees what's happening rather than a frozen UI.
  const connectionBanner = (() => {
    if (isReconnecting) {
      return createElement('div', {
        style: { background: '#f59e0b', color: '#1f2937', padding: '6px 12px',
          fontSize: 12, textAlign: 'center' as const, fontWeight: 600 },
      }, 'Reconnecting… cached frames will replay');
    }
    if (connectionState === 'closed') {
      return createElement('div', {
        style: { background: '#ef4444', color: '#fff', padding: '6px 12px',
          fontSize: 12, textAlign: 'center' as const, fontWeight: 600 },
      }, 'Connection closed');
    }
    if (connectionState === 'error') {
      return createElement('div', {
        style: { background: '#dc2626', color: '#fff', padding: '6px 12px',
          fontSize: 12, textAlign: 'center' as const, fontWeight: 600 },
      }, 'Connection error — retrying…');
    }
    return null;
  })();

  return (
    <ChatLayout
      title={<><img src="/logo.png" alt="" style={{ height: '1.2em', verticalAlign: 'middle', marginRight: 8 }} />Atmosphere AI Chat</>}
      subtitle="Real-time streaming via WebSocket"
      theme="ai"
      headerExtra={<>{headerBadges}<ConnectionStatusBadge status={connectionStatus} />{connectionBanner}</>}
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
          <span>{stats.totalStreamingTexts} streaming texts</span>
          <span>&middot;</span>
          <span>{stats.elapsedMs}ms</span>
          <span>&middot;</span>
          <span>{stats.streamingTextsPerSecond.toFixed(1)} texts/s</span>
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
