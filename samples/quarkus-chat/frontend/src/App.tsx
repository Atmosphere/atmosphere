import { useState, useEffect, useMemo } from 'react';
import { useAtmosphere } from 'atmosphere.js/react';
import { ChatLayout, MessageList, ChatInput } from 'atmosphere.js/chat';
import type { ChatMessage } from 'atmosphere.js/chat';

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);

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

  const { data, state, push } = useAtmosphere<ChatMessage>({ request });

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
      title="Atmosphere 4.0 Chat"
      subtitle="Quarkus Extension • ManagedService • WebSocket with Long-Polling Fallback"
      theme="ai"
      state={state}
    >
      <MessageList messages={messages} currentUser={name ?? undefined} theme="ai" />
      <ChatInput
        onSend={handleSend}
        placeholder={name ? 'Type a message…' : 'Enter your name to join…'}
        disabled={state !== 'connected'}
        theme="ai"
      />
    </ChatLayout>
  );
}
