import { useState, useEffect, useRef, useCallback, createElement } from 'react';
import { ChatLayout, ChatInput, StreamingMessage, StreamingProgress, StreamingError } from 'atmosphere.js/chat';
import type { ChatTheme } from 'atmosphere.js/chat';

const a2aTheme: ChatTheme = {
  gradient: ['#4338ca', '#6d28d9'],
  accent: '#6d28d9',
  dark: true,
};

interface Skill {
  id: string;
  name: string;
  description: string;
  tags: string[];
}

interface UserMessage {
  role: 'user';
  text: string;
}

interface AssistantMessage {
  role: 'assistant';
  text: string;
  complete: boolean;
  taskId?: string;
  statusMessage?: string;
}

type Message = UserMessage | AssistantMessage;

const BASE = '/atmosphere/a2a';

async function rpc<T>(method: string, params?: unknown): Promise<T> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method, params }),
  });
  const json = await res.json();
  if (json.error) throw new Error(json.error.message ?? JSON.stringify(json.error));
  return json.result as T;
}

export function App() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Fetch agent card on mount
  useEffect(() => {
    rpc<{ skills: Skill[] }>('agent/authenticatedExtendedCard')
      .then((card) => setSkills(card.skills ?? []))
      .catch(() => { /* ignore — skills just won't show */ });
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = useCallback(async (text: string) => {
    setMessages((prev) => [...prev, { role: 'user', text }]);
    setError(null);
    setIsStreaming(true);
    setProgress('Thinking...');

    // Cancel any previous stream
    abortRef.current?.abort();
    const abort = new AbortController();
    abortRef.current = abort;

    let accumulated = '';

    try {
      const res = await fetch(BASE, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: Date.now(),
          method: 'message/stream',
          params: {
            message: {
              role: 'user',
              parts: [{ type: 'text', text }],
              messageId: `m-${Date.now()}`,
            },
            arguments: { message: text },
          },
        }),
        signal: abort.signal,
      });

      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }

      const reader = res.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      setProgress(null);

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.slice(6).trim();
          if (data === '[DONE]') continue;

          try {
            const parsed = JSON.parse(data);
            const text = parsed?.artifact?.parts?.[0]?.text;
            if (text) {
              accumulated += text;
              setMessages((prev) => {
                const last = prev[prev.length - 1];
                if (last?.role === 'assistant' && !last.complete) {
                  return [...prev.slice(0, -1), { role: 'assistant', text: accumulated, complete: false }];
                }
                return [...prev, { role: 'assistant', text: accumulated, complete: false }];
              });
            }
          } catch {
            // ignore malformed SSE data
          }
        }
      }

      // Mark complete
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        if (last?.role === 'assistant' && !last.complete) {
          return [...prev.slice(0, -1), { ...last, complete: true }];
        }
        // If no assistant message was created (empty stream), try sync fallback
        return prev;
      });
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;

      // Fallback to synchronous message/send
      try {
        const task = await rpc<{
          id: string;
          status: { state: string; message?: string };
          artifacts?: Array<{ parts: Array<{ text?: string }> }>;
        }>('message/send', {
          message: {
            role: 'user',
            parts: [{ type: 'text', text }],
            messageId: `m-${Date.now()}`,
          },
          arguments: { message: text },
        });

        const responseText = task.artifacts?.[0]?.parts?.[0]?.text ?? 'No response';
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', text: responseText, complete: true, taskId: task.id, statusMessage: task.status.message },
        ]);
      } catch (syncErr) {
        setError(syncErr instanceof Error ? syncErr.message : String(syncErr));
      }
    } finally {
      setIsStreaming(false);
      setProgress(null);
    }
  }, []);

  const skillBadges = skills.length > 0
    ? createElement('div', {
        style: { display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' as const },
      }, ...skills.map((s) =>
        createElement('div', {
          key: s.id,
          style: {
            fontSize: 10,
            background: 'rgba(255,255,255,0.15)',
            padding: '2px 8px',
            borderRadius: 8,
            display: 'inline-block',
          },
        }, s.name),
      ))
    : null;

  return (
    <ChatLayout
      title="Atmosphere A2A Agent"
      subtitle="Agent-to-Agent protocol — real-time AI backend"
      theme={a2aTheme}
      headerExtra={skillBadges}
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
                  background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
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
                msg.complete && msg.statusMessage
                  ? createElement('div', {
                      key: 'status',
                      style: { fontSize: 11, color: '#888', marginTop: 4, paddingLeft: 2, fontFamily: 'monospace' },
                    }, msg.statusMessage)
                  : null,
              ]),
        )}
        {progress && createElement(StreamingProgress, { message: progress })}
        {error && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>
      <ChatInput
        onSend={handleSend}
        placeholder="Ask the agent anything..."
        disabled={isStreaming}
        theme={a2aTheme}
      />
    </ChatLayout>
  );
}
