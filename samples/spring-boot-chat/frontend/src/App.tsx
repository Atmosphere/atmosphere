import { type CSSProperties, useCallback, useEffect, useMemo, useState } from 'react';
import {
  useAtmosphere,
  useOfflineQueue,
  useMessageHistory,
  useOptimistic,
  ConnectionStatusBadge,
} from 'atmosphere.js/react';
import { ChatLayout, MessageList, ChatInput } from 'atmosphere.js/chat';
import type { ChatMessage as BaseChatMessage } from 'atmosphere.js/chat';

// Sample extension: tag locally-echoed user bubbles with the optimistic
// tracker id so the renderer can flip them from "sending…" to "delivered"
// once `useOptimistic` resolves the round-trip.
interface ChatMessage extends BaseChatMessage {
  optimisticId?: string;
}

type Tab = 'chat' | 'rooms' | 'observability';

// --- Room Protocol types ---

interface RoomJoin {
  type: 'join';
  room: string;
  memberId: string;
  metadata: { joinedAt: number };
  /** History cursor for reconnect dedupe. Omitted on first join. */
  sinceId?: number;
}

interface RoomBroadcast {
  type: 'broadcast';
  room: string;
  data: string;
}

interface RoomLeave {
  type: 'leave';
  room: string;
}

interface JoinAck {
  type: 'join_ack';
  members: string[];
}

interface Presence {
  type: 'presence';
  memberId: string;
  action: 'join' | 'leave';
}

interface RoomMessage {
  type: 'message';
  from: string;
  data: string;
  /** Server-assigned monotonic id. Used to drive history-sync on reconnect. */
  id?: number;
}

interface RoomError {
  type: 'error';
  data: string;
}

type IncomingMessage = JoinAck | Presence | RoomMessage | RoomError;

// --- Styles ---

const tabBarStyle: CSSProperties = {
  display: 'flex',
  background: '#1a1d23',
  borderBottom: '1px solid #2d3039',
};

const tabStyle = (active: boolean): CSSProperties => ({
  flex: 1,
  padding: 12,
  textAlign: 'center',
  cursor: 'pointer',
  fontWeight: 500,
  fontSize: 13,
  color: active ? '#3b82f6' : '#9ca0a8',
  borderBottom: `3px solid ${active ? '#3b82f6' : 'transparent'}`,
  background: active ? '#252830' : 'transparent',
});

const roomCardStyle: CSSProperties = {
  background: '#1a1d23',
  padding: '16px 20px',
  marginBottom: 10,
  borderRadius: 12,
  boxShadow: '0 2px 4px rgba(0,0,0,0.05)',
};

const metricCardStyle: CSSProperties = {
  background: '#252830',
  padding: '16px 20px',
  marginBottom: 10,
  borderRadius: 12,
  boxShadow: '0 2px 4px rgba(0,0,0,0.05)',
};

const panelStyle: CSSProperties = {
  flex: 1,
  overflow: 'auto',
  padding: 16,
  background: '#f8f9fa',
};

// --- Room Protocol helpers ---

function sendJoin(
  push: (msg: string) => void,
  room: string,
  memberId: string,
  sinceId?: number,
) {
  const msg: RoomJoin = {
    type: 'join',
    room,
    memberId,
    metadata: { joinedAt: Date.now() },
    // sinceId > 0 → server replays only entries newer than this cursor.
    ...(sinceId && sinceId > 0 ? { sinceId } : {}),
  };
  push(JSON.stringify(msg));
}

function sendBroadcast(push: (msg: string) => void, room: string, data: string) {
  const msg: RoomBroadcast = { type: 'broadcast', room, data };
  push(JSON.stringify(msg));
}

function isLegacyMessage(obj: unknown): obj is ChatMessage {
  return (
    typeof obj === 'object' &&
    obj !== null &&
    'author' in obj &&
    'message' in obj
  );
}

function isRoomProtocol(obj: unknown): obj is IncomingMessage {
  return typeof obj === 'object' && obj !== null && 'type' in obj;
}

// --- Rooms Panel ---

interface RoomInfo {
  name: string;
  memberCount: number;
  members: { id: string; joinedAt?: number }[];
}

function RoomsPanel() {
  const [rooms, setRooms] = useState<RoomInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchRooms = useCallback(async () => {
    try {
      setLoading(true);
      const res = await fetch('/api/rooms');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setRooms(Array.isArray(data) ? data : []);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to fetch rooms');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchRooms();
    const interval = setInterval(fetchRooms, 5000);
    return () => clearInterval(interval);
  }, [fetchRooms]);

  return (
    <div style={panelStyle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0, fontSize: 16 }}>Active Rooms</h3>
        <button
          onClick={fetchRooms}
          style={{ padding: '4px 12px', borderRadius: 6, border: '1px solid #2d3039', background: '#252830', cursor: 'pointer', fontSize: 12 }}
        >
          Refresh
        </button>
      </div>
      {loading && rooms.length === 0 && <p style={{ color: '#6b7280' }}>Loading rooms…</p>}
      {error && <p style={{ color: '#e74c3c' }}>Error: {error}</p>}
      {rooms.length === 0 && !loading && !error && (
        <p style={{ color: '#6b7280' }}>No active rooms</p>
      )}
      {rooms.map((room) => (
        <div key={room.name} style={roomCardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong style={{ fontSize: 15 }}>{room.name}</strong>
            <span style={{ fontSize: 12, color: '#6b7280' }}>
              {room.memberCount ?? room.members?.length ?? 0} member{(room.memberCount ?? room.members?.length ?? 0) !== 1 ? 's' : ''}
            </span>
          </div>
          {room.members && room.members.length > 0 && (
            <div style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {room.members.map((m) => (
                <span
                  key={m.id}
                  style={{
                    display: 'inline-block',
                    padding: '2px 10px',
                    borderRadius: 12,
                    background: '#eef2ff',
                    color: '#667eea',
                    fontSize: 12,
                    fontWeight: 500,
                  }}
                >
                  {m.id}
                </span>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

// --- Observability Panel ---

interface HealthResponse {
  status: string;
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}

interface MetricResponse {
  name: string;
  description?: string;
  measurements: { statistic: string; value: number }[];
}

function ObservabilityPanel() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [metrics, setMetrics] = useState<MetricResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);

      const healthRes = await fetch('/actuator/health');
      if (healthRes.ok) {
        setHealth(await healthRes.json());
      }

      // Fetch the metrics index to discover atmosphere.* metrics
      const metricsIndexRes = await fetch('/actuator/metrics');
      if (metricsIndexRes.ok) {
        const index = await metricsIndexRes.json();
        const names: string[] = (index.names ?? []).filter((n: string) =>
          n.startsWith('atmosphere.'),
        );

        const metricResults = await Promise.all(
          names.map(async (name: string) => {
            try {
              const res = await fetch(`/actuator/metrics/${name}`);
              if (res.ok) return (await res.json()) as MetricResponse;
            } catch {
              // skip individual metric errors
            }
            return null;
          }),
        );

        setMetrics(metricResults.filter((m): m is MetricResponse => m !== null));
      }

      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to fetch observability data');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 10000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const healthColor = health?.status === 'UP' ? '#27ae60' : '#e74c3c';

  return (
    <div style={panelStyle}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0, fontSize: 16 }}>Observability</h3>
        <button
          onClick={fetchData}
          style={{ padding: '4px 12px', borderRadius: 6, border: '1px solid #2d3039', background: '#252830', cursor: 'pointer', fontSize: 12 }}
        >
          Refresh
        </button>
      </div>
      {loading && !health && <p style={{ color: '#6b7280' }}>Loading…</p>}
      {error && <p style={{ color: '#e74c3c' }}>Error: {error}</p>}

      {health && (
        <div style={metricCardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong>Health Status</strong>
            <span style={{ color: healthColor, fontWeight: 600 }}>{health.status}</span>
          </div>
          {health.components && (
            <div style={{ marginTop: 8 }}>
              {Object.entries(health.components).map(([name, comp]) => (
                <div key={name} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, padding: '2px 0', color: '#555' }}>
                  <span>{name}</span>
                  <span style={{ color: comp.status === 'UP' ? '#27ae60' : '#e74c3c' }}>{comp.status}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {metrics.length > 0 && (
        <>
          <h4 style={{ margin: '16px 0 8px', fontSize: 14, color: '#555' }}>Atmosphere Metrics</h4>
          {metrics.map((metric) => (
            <div key={metric.name} style={metricCardStyle}>
              <strong style={{ fontSize: 13 }}>{metric.name}</strong>
              {metric.description && (
                <p style={{ margin: '4px 0 0', fontSize: 12, color: '#6b7280' }}>{metric.description}</p>
              )}
              <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 12 }}>
                {metric.measurements.map((m) => (
                  <div key={m.statistic} style={{ fontSize: 13 }}>
                    <span style={{ color: '#6b7280' }}>{m.statistic}: </span>
                    <span style={{ fontWeight: 600, color: '#333' }}>{m.value}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </>
      )}

      {!loading && metrics.length === 0 && health && (
        <p style={{ color: '#6b7280', fontSize: 13 }}>No Atmosphere metrics found. Metrics appear after the first connection.</p>
      )}
    </div>
  );
}

// --- Main App ---

const ROOM = 'lobby';

/** Fetch WebTransport server config (port + cert hash) from the server. */
async function fetchWebTransportInfo(): Promise<{port?: number; certificateHash?: string}> {
  try {
    const res = await fetch('/api/webtransport-info');
    if (res.ok) return res.json();
  } catch { /* server may not have WebTransport enabled */ }
  return {};
}

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('chat');
  const [wtInfo, setWtInfo] = useState<{port?: number; certificateHash?: string}>({});
  const [wtLoaded, setWtLoaded] = useState(false);

  // Presence: members currently in the room, derived from the Room Protocol
  // 'join_ack' / 'presence' events on the SAME atmosphere subscription as
  // the chat. We deliberately do NOT use `usePresence` / `useRoom` here
  // because those hooks open their own subscription via `AtmosphereRooms`,
  // which would double the WebSocket against /atmosphere/chat. Sharing the
  // subscription with `useAtmosphere` keeps the sample a single-connection
  // demo while still showcasing real-time presence.
  const [presentMembers, setPresentMembers] = useState<Set<string>>(() => new Set());

  // Optimistic UI: render outbound user messages as "sending…" the moment
  // the user hits Enter, then flip them to "delivered" once the round-trip
  // settles. Atmosphere's room broadcast excludes the sender (no server
  // echo on the wire to correlate against), so we use the `confirmAfterMs`
  // fallback: anything that hasn't been rolled back within 600ms is
  // implicitly delivered. `useOptimistic` wraps the same `OfflineQueue`
  // tracking primitive `useOfflineQueue` uses, so the ids are namespaced
  // correctly and there is no chance of cross-pollination.
  const optimistic = useOptimistic<{ text: string }>({ confirmAfterMs: 600 });

  // Offline queue: messages typed while disconnected are enqueued here and
  // drained automatically on reconnect. The transport reads this instance
  // from `request.offlineQueue` and calls `queue.drain(...)` on `open`.
  const offline = useOfflineQueue<string>({ maxSize: 50 });

  // History sync: track the server-assigned message id so the next join
  // after a reconnect can carry sinceId and the server replays only the
  // messages we missed (instead of duplicating everything in the cache).
  // localStorage persists the cursor across reloads so a hard refresh
  // mid-conversation does not silently drop history coherence.
  const history = useMessageHistory({
    storage: typeof window !== 'undefined' ? window.localStorage : undefined,
    storageKey: `atmosphere:chat:${ROOM}:lastSeenId`,
  });

  useEffect(() => {
    fetchWebTransportInfo().then((info) => {
      setWtInfo(info);
      setWtLoaded(true);
    });
  }, []);

  const request = useMemo(
    () => ({
      url: `${window.location.protocol}//${window.location.host}/atmosphere/chat`,
      // Default to WebSocket — WebTransport broadcast across clients is still experimental.
      // To test WebTransport, change transport to 'webtransport' below.
      transport: 'websocket' as const,
      fallbackTransport: 'long-polling' as const,
      reconnect: true,
      reconnectInterval: 5000,
      maxReconnectOnClose: 10,
      trackMessageLength: true,
      contentType: 'application/json',
      offlineQueue: offline.queue,
    }),
    // The queue identity is stable across renders (useOfflineQueue uses a
    // ref), so depending on `offline.queue` does not re-trigger subscribe.
    [wtInfo, offline.queue],
  );

  const { data, state, push, connectionStatus } = useAtmosphere<unknown>({
    request,
    enabled: wtLoaded,
    onReopen: () => {
      console.info('[atmosphere] reopened, sinceId=', history.lastSeenId);
      // Re-join carrying the last-seen cursor so the server replays only
      // the messages we missed during the disconnect. Without this, the
      // server's BroadcasterCache replays everything and the UI shows
      // duplicates. The name we joined under is captured below; for a
      // fresh-name first connection sinceId is implicitly 0 (omitted).
      if (name) {
        sendJoin(push, ROOM, name, history.lastSeenId);
      }
    },
    onTransportFailure: (reason) =>
      console.warn('[atmosphere] transport failed, falling back:', reason),
    onFailureToReconnect: () =>
      console.error('[atmosphere] reconnect attempts exhausted'),
  });

  useEffect(() => {
    if (!data) return;
    try {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;

      // Handle Room Protocol messages
      if (isRoomProtocol(parsed)) {
        switch (parsed.type) {
          case 'join_ack':
            setPresentMembers(new Set(parsed.members));
            setMessages((prev) => [
              ...prev,
              {
                author: 'system',
                message: `Joined room. Members: ${parsed.members.join(', ')}`,
                time: Date.now(),
              },
            ]);
            break;
          case 'presence':
            setPresentMembers((prev) => {
              const next = new Set(prev);
              if (parsed.action === 'join') next.add(parsed.memberId);
              else next.delete(parsed.memberId);
              return next;
            });
            setMessages((prev) => [
              ...prev,
              {
                author: 'system',
                message: `${parsed.memberId} has ${parsed.action === 'join' ? 'joined' : 'left'} the room`,
                time: Date.now(),
              },
            ]);
            break;
          case 'message':
            // Advance the history cursor so the next reconnect's sinceId is fresh.
            history.observe(parsed as { id?: number });
            setMessages((prev) => [
              ...prev,
              { author: parsed.from, message: parsed.data, time: Date.now() },
            ]);
            break;
          case 'error':
            setMessages((prev) => [
              ...prev,
              { author: 'system', message: `Error: ${parsed.data}`, time: Date.now() },
            ]);
            break;
        }
        return;
      }

      // Handle legacy chat messages
      if (isLegacyMessage(parsed)) {
        setMessages((prev) => [...prev, parsed]);
        return;
      }
    } catch {
      // ignore parse errors
    }
  }, [data]);

  // Project the optimistic state map onto the rendered chat list. Bubbles
  // whose corresponding optimistic record is still `'sent'` get a
  // "(sending…)" suffix; once the record flips to `'confirmed'` (after
  // confirmAfterMs) or `'failed'` the suffix is removed or replaced.
  const optimisticById = useMemo(() => {
    const m = new Map<string, 'sent' | 'confirmed' | 'failed'>();
    for (const rec of optimistic.messages) {
      if (rec.state === 'sent' || rec.state === 'confirmed' || rec.state === 'failed') {
        m.set(rec.id, rec.state);
      }
    }
    return m;
  }, [optimistic.messages]);

  const displayMessages = useMemo<ChatMessage[]>(() => {
    return messages.map((msg) => {
      if (!msg.optimisticId) return msg;
      const state = optimisticById.get(msg.optimisticId);
      if (state === 'sent') {
        return { ...msg, message: `${msg.message}  (sending…)` };
      }
      if (state === 'failed') {
        return { ...msg, message: `${msg.message}  (failed to send)` };
      }
      return msg;
    });
  }, [messages, optimisticById]);

  const handleSend = (text: string) => {
    if (!name) {
      setName(text);
      sendJoin(push, ROOM, text);
      return;
    }

    // Connected: send live. Disconnected: enqueue locally — the transport
    // will drain the queue on the next `open` event.
    const isOnline = connectionStatus.phase === 'open';
    let optimisticId: string | undefined;
    if (isOnline) {
      sendBroadcast(push, ROOM, text);
      // Track this outbound message optimistically so the UI can render
      // it as "sending…" until the confirmAfterMs deadline auto-confirms.
      optimisticId = optimistic.send({ text }).id;
    } else {
      const payload: RoomBroadcast = { type: 'broadcast', room: ROOM, data: text };
      offline.enqueue(JSON.stringify(payload));
    }

    // Always echo locally so the UI shows the message immediately.
    setMessages((prev) => [
      ...prev,
      {
        author: name,
        message: isOnline ? text : `${text}  (queued — offline)`,
        time: Date.now(),
        optimisticId,
      },
    ]);
  };

  return (
    <ChatLayout
      title="Atmosphere 4.0 Chat"
      subtitle="Spring Boot • WebTransport/HTTP3 • Room Protocol • Presence • Health Check"
      theme="ai"
      state={state}
      headerExtra={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <ConnectionStatusBadge status={connectionStatus} />
          {presentMembers.size > 0 && (
            <span
              data-testid="presence-count"
              title={Array.from(presentMembers).sort().join(', ')}
              style={{
                fontSize: 12,
                fontWeight: 500,
                color: '#fff',
                background: '#0284c7',
                padding: '2px 8px',
                borderRadius: 10,
              }}
            >
              {presentMembers.size} online
            </span>
          )}
          {offline.size > 0 && (
            <span
              data-testid="offline-queue-size"
              style={{
                fontSize: 12,
                fontWeight: 500,
                color: '#fff',
                background: '#d97706',
                padding: '2px 8px',
                borderRadius: 10,
              }}
              title="Messages typed offline, waiting to drain on reconnect"
            >
              {offline.size} queued
            </span>
          )}
        </div>
      }
    >
      <div style={tabBarStyle}>
        <div style={tabStyle(activeTab === 'chat')} onClick={() => setActiveTab('chat')}>
          Chat
        </div>
        <div style={tabStyle(activeTab === 'rooms')} onClick={() => setActiveTab('rooms')}>
          Rooms
        </div>
        <div style={tabStyle(activeTab === 'observability')} onClick={() => setActiveTab('observability')}>
          Observability
        </div>
      </div>

      {activeTab === 'chat' && (
        <>
          <MessageList messages={displayMessages} currentUser={name ?? undefined} theme="ai" />
          <ChatInput
            onSend={handleSend}
            placeholder={name ? 'Type a message…' : 'Enter your name to join…'}
            disabled={state !== 'connected'}
            theme="ai"
          />
        </>
      )}

      {activeTab === 'rooms' && <RoomsPanel />}
      {activeTab === 'observability' && <ObservabilityPanel />}
    </ChatLayout>
  );
}
