import { useState, useEffect, useMemo } from 'react';
import { useAtmosphere, ConnectionStatusBadge } from 'atmosphere.js/react';
import { ChatLayout, MessageList, ChatInput } from 'atmosphere.js/chat';
import type { ChatMessage } from 'atmosphere.js/chat';

async function fetchWebTransportInfo(): Promise<{enabled?: boolean; port?: number; certificateHash?: string}> {
  try {
    const res = await fetch('/api/webtransport-info');
    if (res.ok) return res.json();
  } catch { /* WebTransport not available — fall through to WebSocket */ }
  return {};
}

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);
  const [wtInfo, setWtInfo] = useState<{enabled?: boolean; port?: number; certificateHash?: string}>({});
  const [wtLoaded, setWtLoaded] = useState(false);

  useEffect(() => {
    fetchWebTransportInfo().then((info) => { setWtInfo(info); setWtLoaded(true); });
  }, []);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/chat`,
      transport: 'webtransport' as const,
      fallbackTransport: 'websocket' as const,
      ...(wtInfo.enabled && wtInfo.port ? { webTransportUrl: `https://${window.location.hostname}:${wtInfo.port}/atmosphere/chat` } : {}),
      ...(wtInfo.certificateHash ? { serverCertificateHashes: [wtInfo.certificateHash] } : {}),
      reconnect: true,
      reconnectInterval: 5000,
      maxReconnectOnClose: 10,
      trackMessageLength: true,
      contentType: 'application/json',
    }),
    [wtInfo],
  );

  const { data, state, push, connectionStatus } = useAtmosphere<ChatMessage>({
    request,
    enabled: wtLoaded,
    onReopen: () => console.info('[atmosphere] reopened'),
    onTransportFailure: (reason) =>
      console.warn('[atmosphere] transport failed, falling back:', reason),
    onFailureToReconnect: () =>
      console.error('[atmosphere] reconnect attempts exhausted'),
  });

  useEffect(() => {
    if (!data) return;
    try {
      const msg: ChatMessage =
        typeof data === 'string' ? JSON.parse(data) : data;
      if (!msg.author) return;
      setMessages((prev) => [...prev, msg]);
    } catch {
      // ignore parse errors
    }
  }, [data, name]);

  const handleSend = (text: string) => {
    if (!name) {
      setName(text);
      push(JSON.stringify({ author: text, message: `${text} has joined!` }));
    } else {
      push(JSON.stringify({ author: name, message: text }));
    }
  };

  return (
    <ChatLayout
      title="Atmosphere 4.0 — MCP + Chat"
      subtitle="MCP Server at /atmosphere/mcp • Chat at /atmosphere/chat — AI agents can list users, broadcast messages, ban users via MCP tools"
      theme="mcp"
      state={state}
      headerExtra={<ConnectionStatusBadge status={connectionStatus} />}
    >
      <MessageList messages={messages} currentUser={name ?? undefined} theme="mcp" />
      <ChatInput
        onSend={handleSend}
        placeholder={name ? 'Type a message…' : 'Enter your name to join…'}
        disabled={state !== 'connected'}
        theme="mcp"
      />
    </ChatLayout>
  );
}
