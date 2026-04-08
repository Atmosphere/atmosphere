import { useState, useEffect, useRef, useMemo, createElement } from 'react';
import { useStreaming } from 'atmosphere.js/react';
import type { AiEvent } from 'atmosphere.js/react';
import { ChatLayout, ChatInput, StreamingMessage, StreamingProgress, StreamingError } from 'atmosphere.js/chat';

/* ── Agent definitions ── */

const AGENTS: Record<string, { label: string; icon: string; color: string; bg: string; backend: string }> = {
  web_search:       { label: 'Research Agent',  icon: '\uD83D\uDD0D', color: '#f59e0b', bg: 'rgba(245,158,11,0.10)', backend: 'A2A /atmosphere/a2a/research' },
  analyze_strategy: { label: 'Strategy Agent',  icon: '\uD83C\uDFAF', color: '#10b981', bg: 'rgba(16,185,129,0.10)', backend: 'A2A /atmosphere/a2a/strategy' },
  financial_model:  { label: 'Finance Agent',   icon: '\uD83D\uDCB0', color: '#8b5cf6', bg: 'rgba(139,92,246,0.10)', backend: 'A2A /atmosphere/a2a/finance' },
  write_report:     { label: 'Writer Agent',    icon: '\u270D\uFE0F', color: '#ef4444', bg: 'rgba(239,68,68,0.10)',  backend: 'A2A /atmosphere/a2a/writer' },
};

const CEO = { label: 'CEO', icon: '\uD83D\uDC54', color: '#3b82f6', backend: 'Gemini via Google ADK' };

/* Map agent names from agent-step events to tool names used by the status bar */
const AGENT_TO_TOOL: Record<string, string> = {
  'research-agent': 'web_search',
  'strategy-agent': 'analyze_strategy',
  'finance-agent':  'financial_model',
  'writer-agent':   'write_report',
};

/* ── Types ── */

interface UserMessage { role: 'user'; text: string }
interface AssistantMessage { role: 'assistant'; text: string; complete: boolean }
type ChatMsg = UserMessage | AssistantMessage;

interface ToolCallState {
  name: string;
  args: Record<string, unknown>;
  result?: string;
  done: boolean;
}

/* ── WebTransport discovery ── */

async function fetchWebTransportInfo(): Promise<{port?: number; certificateHash?: string}> {
  try {
    const res = await fetch('/api/webtransport-info');
    if (res.ok) return res.json();
  } catch { /* server may not have WebTransport enabled */ }
  return {};
}

/* ── Component ── */

export function App() {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([]);
  const [agentActivity, setAgentActivity] = useState<Record<string, string>>({});
  const endRef = useRef<HTMLDivElement>(null);
  const [wtInfo, setWtInfo] = useState<{port?: number; certificateHash?: string}>({});
  const [wtLoaded, setWtLoaded] = useState(false);

  useEffect(() => {
    fetchWebTransportInfo().then((info) => {
      setWtInfo(info);
      setWtLoaded(true);
    });
  }, []);

  const request = useMemo(() => ({
    url: `${window.location.protocol}//${window.location.host}/atmosphere/agent/ceo`,
    transport: 'webtransport' as const,
    fallbackTransport: 'websocket' as const,
    ...(wtInfo.enabled && wtInfo.port ? { webTransportUrl: `https://${window.location.hostname}:${wtInfo.port}/atmosphere/agent/ceo` } : {}),
    ...(wtInfo.certificateHash ? { serverCertificateHashes: [wtInfo.certificateHash] } : {}),
    reconnect: true,
    maxReconnectOnClose: 10,
    reconnectInterval: 5000,
    trackMessageLength: true,
    enableProtocol: false,
    contentType: 'application/json',
  }), [wtInfo]);

  const { fullText, isStreaming, progress, aiEvents, error, send, reset } =
    useStreaming({ request, enabled: wtLoaded });

  // Track tool calls from AI events
  useEffect(() => {
    if (aiEvents.length === 0) return;
    setToolCalls(prev => {
      const updated = [...prev];
      for (const ev of aiEvents) {
        const name = (ev.data.toolName ?? ev.data.name) as string | undefined;
        if (ev.event === 'tool-start' && name) {
          const args = (ev.data.arguments ?? ev.data.args ?? {}) as Record<string, unknown>;
          // Deduplicate: only add if no entry with this name exists at all
          if (!updated.find(t => t.name === name)) {
            updated.push({ name, args, done: false });
          }
        } else if (ev.event === 'tool-result' && name) {
          const tc = updated.find(t => t.name === name && !t.done);
          if (tc) {
            tc.result = (ev.data.result ?? ev.data.text) as string;
            tc.done = true;
          }
        }
      }
      return updated;
    });

    // Track agent-step events for real-time activity status
    for (const ev of aiEvents) {
      if (ev.event === 'agent-step') {
        const agentName = ev.data.agent as string | undefined;
        const stepName = ev.data.stepName as string | undefined;
        if (agentName && stepName) {
          const toolKey = AGENT_TO_TOOL[agentName];
          if (toolKey) {
            setAgentActivity(prev => ({ ...prev, [toolKey]: stepName }));
          }
        }
      }
    }
  }, [aiEvents]);

  // Update assistant message from streaming
  useEffect(() => {
    if (!fullText) return;
    setMessages(prev => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant' && !last.complete) {
        return [...prev.slice(0, -1), { role: 'assistant', text: fullText, complete: false }];
      }
      return [...prev, { role: 'assistant', text: fullText, complete: false }];
    });
  }, [fullText]);

  // Mark complete
  useEffect(() => {
    if (!isStreaming && fullText) {
      setMessages(prev => {
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
  }, [messages, fullText, toolCalls]);

  const handleSend = (text: string) => {
    setMessages(prev => [...prev, { role: 'user', text }]);
    setToolCalls([]);
    setAgentActivity({});
    reset();
    send(text);
  };

  // Determine active agents
  const activeAgents = new Set(toolCalls.map(t => t.name));
  const completedAgents = new Set(toolCalls.filter(t => t.done).map(t => t.name));

  return (
    <ChatLayout
      title="Atmosphere A2A Multi-Agent Team"
      subtitle="5 independent A2A agents collaborating via JSON-RPC"
      theme="ai"
    >
      <div style={{
        flex: 1,
        overflowY: 'auto',
        padding: '12px 16px',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        background: 'linear-gradient(180deg, #0a0e1a 0%, #111827 100%)',
      }}>
        {/* Welcome state */}
        {messages.length === 0 && !isStreaming && (
          <div style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            flex: 1,
            gap: 24,
            padding: 40,
          }}>
            <div style={{ fontSize: 48 }}>{CEO.icon}</div>
            <div style={{
              fontSize: 20,
              fontWeight: 600,
              color: '#e5e7eb',
              textAlign: 'center',
            }}>
              AI Startup Advisory Team
            </div>
            <div style={{
              fontSize: 14,
              color: '#9ca3af',
              textAlign: 'center',
              maxWidth: 500,
              lineHeight: 1.6,
            }}>
              Ask me to analyze any market, product idea, or business opportunity.
              My team of specialist agents will research, strategize, model finances,
              and deliver a comprehensive briefing — all in real-time.
            </div>
            {/* Agent cards */}
            <div style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: 12,
              maxWidth: 500,
              width: '100%',
            }}>
              {Object.entries(AGENTS).map(([key, agent]) => (
                <div key={key} style={{
                  background: agent.bg,
                  border: `1px solid ${agent.color}33`,
                  borderRadius: 12,
                  padding: '12px 16px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                }}>
                  <span style={{ fontSize: 20 }}>{agent.icon}</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <span style={{ fontSize: 13, color: agent.color, fontWeight: 600 }}>
                      {agent.label}
                    </span>
                    <span style={{ fontSize: 10, color: '#6b7280' }}>
                      {agent.backend}
                    </span>
                  </div>
                </div>
              ))}
            </div>
            <div style={{
              fontSize: 12,
              color: '#6b7280',
              textAlign: 'center',
              fontStyle: 'italic',
            }}>
              Try: "Analyze the market for AI-powered developer tools in 2026"
            </div>
          </div>
        )}

        {/* User messages */}
        {messages.filter(m => m.role === 'user').map((msg, i) =>
          createElement('div', {
            key: `user-${i}`,
            style: {
              alignSelf: 'flex-end',
              background: 'linear-gradient(135deg, #3b82f6, #6366f1)',
              color: '#fff',
              padding: '10px 16px',
              borderRadius: '16px 16px 4px 16px',
              maxWidth: '80%',
              wordBreak: 'break-word' as const,
              fontSize: 14,
              animation: 'slideIn 0.3s ease',
            },
          }, msg.text),
        )}

        {/* Agent Collaboration section — tool cards appear FIRST */}
        {toolCalls.length > 0 && createElement('div', {
          style: {
            fontSize: 11,
            color: '#9ca3af',
            textTransform: 'uppercase' as const,
            letterSpacing: 1.5,
            fontWeight: 600,
            marginTop: 8,
            marginBottom: 4,
          },
        }, 'Agent Collaboration')}

        {/* Tool call cards — the agent collaboration visualization */}
        {toolCalls.map((tc, i) => {
          const agent = AGENTS[tc.name] ?? { label: tc.name, icon: '\uD83E\uDD16', color: '#9ca3af', bg: 'rgba(156,163,175,0.10)' };
          return createElement('div', {
            key: `tool-${i}`,
            style: {
              background: agent.bg,
              border: `1px solid ${agent.color}33`,
              borderLeft: `3px solid ${agent.color}`,
              borderRadius: '4px 12px 12px 4px',
              padding: '12px 16px',
              animation: 'slideIn 0.4s ease',
              maxWidth: '90%',
            },
          },
            /* Agent header */
            createElement('div', {
              style: {
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 8,
              },
            },
              createElement('div', {
                style: {
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  fontSize: 13,
                  fontWeight: 600,
                  color: agent.color,
                },
              },
                createElement('span', null, `${agent.icon} ${agent.label}`),
                'backend' in agent && createElement('span', {
                  style: {
                    fontSize: 9,
                    color: '#6b7280',
                    background: 'rgba(255,255,255,0.06)',
                    padding: '1px 6px',
                    borderRadius: 4,
                    marginLeft: 6,
                    fontWeight: 400,
                  },
                }, (agent as { backend: string }).backend),
              ),
              createElement('div', {
                style: {
                  fontSize: 11,
                  color: tc.done ? '#10b981' : agent.color,
                  fontWeight: 500,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 4,
                },
              }, tc.done ? '\u2713 Done' : '\u25CF Working...'),
            ),
            /* Tool args */
            createElement('div', {
              style: {
                fontSize: 11,
                color: '#9ca3af',
                fontFamily: 'monospace',
                marginBottom: tc.result ? 8 : 0,
                wordBreak: 'break-all' as const,
              },
            }, Object.entries(tc.args).map(([k, v]) =>
              createElement('div', { key: k },
                createElement('span', { style: { color: '#6b7280' } }, `${k}: `),
                createElement('span', { style: { color: '#d1d5db' } }, String(v)),
              )
            )),
            /* Tool result — render as markdown so journal tables display correctly */
            tc.result && createElement('div', {
              style: {
                fontSize: 12,
                color: '#d1d5db',
                background: 'rgba(0,0,0,0.25)',
                borderRadius: 8,
                padding: '8px 12px',
                maxHeight: 200,
                overflowY: 'auto' as const,
                lineHeight: 1.5,
              },
            }, createElement(StreamingMessage, {
              text: tc.result,
              isStreaming: false,
              dark: true,
              markdown: true,
            })),
          );
        })}

        {/* CEO Synthesis — assistant messages appear AFTER agent cards */}
        {messages.filter(m => m.role === 'assistant').map((msg, i) =>
          createElement('div', { key: `ceo-${i}`, style: { animation: 'slideIn 0.3s ease' } },
            /* CEO Synthesis header */
            toolCalls.length > 0 && createElement('div', {
              style: {
                fontSize: 11,
                color: '#9ca3af',
                textTransform: 'uppercase' as const,
                letterSpacing: 1.5,
                fontWeight: 600,
                marginTop: 12,
                marginBottom: 8,
              },
            }, 'CEO Synthesis'),
            /* CEO label */
            createElement('div', {
              style: {
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                marginBottom: 6,
                fontSize: 12,
                color: CEO.color,
                fontWeight: 600,
              },
            },
              createElement('span', null, `${CEO.icon} ${CEO.label}`),
              createElement('span', {
                style: { fontSize: 10, color: '#6b7280', fontWeight: 400, marginLeft: 6 },
              }, `via ${CEO.backend}`),
            ),
            createElement(StreamingMessage, {
              text: (msg as AssistantMessage).text,
              isStreaming: !(msg as AssistantMessage).complete,
              dark: true,
            }),
          ),
        )}

        {progress && createElement(StreamingProgress, { message: progress })}
        {error && !error.includes('handshake') && createElement(StreamingError, { message: error })}
        <div ref={endRef} />
      </div>

      {/* Agent status bar */}
      {(isStreaming || toolCalls.length > 0) && (
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          padding: '8px 16px',
          background: '#111827',
          borderTop: '1px solid #1f2937',
          fontSize: 12,
        }}>
          {Object.entries(AGENTS).map(([key, agent]) => {
            const isActive = activeAgents.has(key) && !completedAgents.has(key);
            const isDone = completedAgents.has(key);
            return createElement('div', {
              key,
              style: {
                display: 'flex',
                alignItems: 'center',
                gap: 4,
                color: isDone ? '#10b981' : isActive ? agent.color : '#4b5563',
                transition: 'color 0.3s',
              },
            },
              createElement('span', {
                style: {
                  width: 6,
                  height: 6,
                  borderRadius: '50%',
                  background: isDone ? '#10b981' : isActive ? agent.color : '#374151',
                  display: 'inline-block',
                  animation: isActive ? 'pulse 1.5s ease infinite' : 'none',
                },
              }),
              `${agent.icon} ${isDone ? '\u2713' : isActive ? (agentActivity[key] ?? '...') : '\u2013'}`,
            );
          })}
        </div>
      )}

      <ChatInput
        onSend={handleSend}
        placeholder="Ask about any market, product idea, or business opportunity..."
        disabled={isStreaming}
        theme="ai"
      />
    </ChatLayout>
  );
}
