import { useState, useRef, useEffect, useCallback, createElement } from 'react';
import { ChatLayout, ChatInput, StreamingMessage, StreamingProgress, StreamingError } from 'atmosphere.js/chat';

interface AgUiEvent {
  type: string;
  [key: string]: unknown;
}

interface ToolCall {
  id: string;
  name: string;
  args: string;
  result?: string;
  done: boolean;
}

interface Step {
  id: string;
  name: string;
  done: boolean;
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls: ToolCall[];
  steps: Step[];
  isStreaming: boolean;
  error?: string;
}

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isRunning, setIsRunning] = useState(false);
  const [threadId] = useState(() => crypto.randomUUID());
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleEvent = useCallback((event: AgUiEvent) => {
    setMessages(prev => {
      const updated = [...prev];
      const lastIdx = updated.length - 1;
      const last = updated[lastIdx];
      if (!last || last.role !== 'assistant') return prev;

      const msg = { ...last, toolCalls: [...last.toolCalls], steps: [...last.steps] };

      switch (event.type) {
        case 'TEXT_MESSAGE_CONTENT':
          msg.content += (event.delta as string) || '';
          break;
        case 'TEXT_MESSAGE_END':
          break;
        case 'TOOL_CALL_START':
          msg.toolCalls.push({
            id: event.toolCallId as string,
            name: event.name as string,
            args: '',
            done: false,
          });
          break;
        case 'TOOL_CALL_ARGS': {
          const tc = msg.toolCalls.find(t => t.id === event.toolCallId);
          if (tc) tc.args += (event.delta as string) || '';
          break;
        }
        case 'TOOL_CALL_RESULT': {
          const tc2 = msg.toolCalls.find(t => t.id === event.toolCallId);
          if (tc2) tc2.result = event.result as string;
          break;
        }
        case 'TOOL_CALL_END': {
          const tc3 = msg.toolCalls.find(t => t.id === event.toolCallId);
          if (tc3) tc3.done = true;
          break;
        }
        case 'STEP_STARTED':
          msg.steps.push({ id: event.stepId as string, name: event.name as string, done: false });
          break;
        case 'STEP_FINISHED': {
          const step = msg.steps.find(s => s.id === event.stepId);
          if (step) step.done = true;
          break;
        }
        case 'RUN_FINISHED':
          msg.isStreaming = false;
          break;
        case 'RUN_ERROR':
          msg.isStreaming = false;
          msg.error = (event.message as string) || 'Unknown error';
          break;
        default:
          break;
      }

      updated[lastIdx] = msg;
      return updated;
    });
  }, []);

  const sendMessage = useCallback(async (text: string) => {
    if (!text.trim() || isRunning) return;

    const userMsg: ChatMessage = { role: 'user', content: text, toolCalls: [], steps: [], isStreaming: false };
    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setIsRunning(true);

    const assistantMsg: ChatMessage = {
      role: 'assistant', content: '', toolCalls: [], steps: [], isStreaming: true,
    };
    setMessages(prev => [...prev, assistantMsg]);

    try {
      const response = await fetch('/agui', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          threadId,
          runId: crypto.randomUUID(),
          messages: [{ role: 'user', content: text }],
        }),
      });

      if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEventType = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event: ')) {
            currentEventType = line.slice(7).trim();
          } else if (line.startsWith('data: ') && currentEventType) {
            try {
              const data = JSON.parse(line.slice(6)) as AgUiEvent;
              data.type = currentEventType;
              handleEvent(data);
            } catch { /* skip */ }
            currentEventType = '';
          }
        }
      }
    } catch (err: unknown) {
      if ((err as Error).name !== 'AbortError') {
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'assistant') {
            updated[updated.length - 1] = { ...last, isStreaming: false, error: String(err) };
          }
          return updated;
        });
      }
    } finally {
      setIsRunning(false);
    }
  }, [isRunning, threadId, handleEvent]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage(input);
    }
  };

  return (
    <ChatLayout
      title="Atmosphere AG-UI Chat"
      subtitle="CopilotKit-compatible SSE streaming"
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
        {messages.length === 0 && (
          <div style={{
            textAlign: 'center',
            color: 'rgba(255,255,255,0.4)',
            marginTop: '25vh',
            fontSize: 15,
          }}>
            <div style={{ fontSize: 28, marginBottom: 10 }}>AG-UI Protocol Demo</div>
            <div>Send a message to see streaming events, tool calls, and agent steps.</div>
            <div style={{ marginTop: 6, fontSize: 13 }}>
              Try: "What's the weather?" or "What time is it?" or just say hello.
            </div>
          </div>
        )}

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
                  wordBreak: 'break-word' as const,
                },
              }, msg.content)
            : createElement('div', { key: i, style: { display: 'flex', flexDirection: 'column' as const, gap: 8, maxWidth: '85%' } },
                // Steps
                msg.steps.length > 0 && createElement('div', { style: { display: 'flex', flexDirection: 'column' as const, gap: 3 } },
                  ...msg.steps.map(step =>
                    createElement('div', {
                      key: step.id,
                      style: { fontSize: 12, color: step.done ? '#6ee7b7' : '#fbbf24', display: 'flex', alignItems: 'center', gap: 6 },
                    }, step.done ? '\u2713' : '\u25CB', ' ', step.name)
                  ),
                ),
                // Tool Calls
                msg.toolCalls.length > 0 && createElement('div', { style: { display: 'flex', flexDirection: 'column' as const, gap: 6 } },
                  ...msg.toolCalls.map(tc =>
                    createElement('div', {
                      key: tc.id,
                      style: {
                        background: 'rgba(99,102,241,0.15)',
                        border: '1px solid rgba(99,102,241,0.3)',
                        borderRadius: 8, padding: '8px 12px', fontSize: 13,
                      },
                    },
                      createElement('div', { style: { fontWeight: 600, color: '#a5b4fc', marginBottom: 4 } },
                        tc.done ? '\u2713 ' : '\u25CB ', tc.name),
                      tc.args && createElement('div', {
                        style: { fontFamily: 'monospace', fontSize: 11, color: 'rgba(255,255,255,0.5)', marginBottom: tc.result ? 4 : 0 },
                      }, tc.args),
                      tc.result && createElement('div', {
                        style: { fontFamily: 'monospace', fontSize: 11, color: '#6ee7b7', background: 'rgba(0,0,0,0.2)', padding: '4px 8px', borderRadius: 4 },
                      }, tc.result),
                    )
                  ),
                ),
                // Text
                (msg.content || msg.isStreaming) && createElement(StreamingMessage, {
                  text: msg.content || ' ',
                  isStreaming: msg.isStreaming,
                  dark: true,
                }),
                // Error
                msg.error && createElement(StreamingError, { message: msg.error }),
              ),
        )}
        <div ref={endRef} />
      </div>
      <ChatInput
        onSend={sendMessage}
        placeholder={isRunning ? 'Agent is responding...' : 'Ask me anything...'}
        disabled={isRunning}
        theme="ai"
      />
    </ChatLayout>
  );
}
