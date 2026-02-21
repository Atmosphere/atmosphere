import { type CSSProperties, useCallback, useEffect, useMemo, useState } from 'react';
import { useAtmosphere } from 'atmosphere.js/react';
import { ChatLayout, MessageList, ChatInput } from 'atmosphere.js/chat';
import type { ChatMessage } from 'atmosphere.js/chat';

type Tab = 'chat' | 'rooms' | 'observability';

// --- Room Protocol types ---

interface RoomJoin {
  type: 'join';
  room: string;
  memberId: string;
  metadata: { joinedAt: number };
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
}

interface RoomError {
  type: 'error';
  data: string;
}

type IncomingMessage = JoinAck | Presence | RoomMessage | RoomError;

// --- Styles ---

const tabBarStyle: CSSProperties = {
  display: 'flex',
  background: '#f0f0f0',
  borderBottom: '1px solid #e9ecef',
};

const tabStyle = (active: boolean): CSSProperties => ({
  flex: 1,
  padding: 12,
  textAlign: 'center',
  cursor: 'pointer',
  fontWeight: 500,
  fontSize: 13,
  color: active ? '#667eea' : '#666',
  borderBottom: `3px solid ${active ? '#667eea' : 'transparent'}`,
  background: active ? 'white' : 'transparent',
});

const roomCardStyle: CSSProperties = {
  background: 'white',
  padding: '16px 20px',
  marginBottom: 10,
  borderRadius: 12,
  boxShadow: '0 2px 4px rgba(0,0,0,0.05)',
};

const metricCardStyle: CSSProperties = {
  background: 'white',
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

function sendJoin(push: (msg: string) => void, room: string, memberId: string) {
  const msg: RoomJoin = {
    type: 'join',
    room,
    memberId,
    metadata: { joinedAt: Date.now() },
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
        <h3 style={{ margin: 0, fontSize: 16 }}>🏠 Active Rooms</h3>
        <button
          onClick={fetchRooms}
          style={{ padding: '4px 12px', borderRadius: 6, border: '1px solid #ddd', background: 'white', cursor: 'pointer', fontSize: 12 }}
        >
          Refresh
        </button>
      </div>
      {loading && rooms.length === 0 && <p style={{ color: '#999' }}>Loading rooms…</p>}
      {error && <p style={{ color: '#e74c3c' }}>Error: {error}</p>}
      {rooms.length === 0 && !loading && !error && (
        <p style={{ color: '#999' }}>No active rooms</p>
      )}
      {rooms.map((room) => (
        <div key={room.name} style={roomCardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong style={{ fontSize: 15 }}>{room.name}</strong>
            <span style={{ fontSize: 12, color: '#999' }}>
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
        <h3 style={{ margin: 0, fontSize: 16 }}>📊 Observability</h3>
        <button
          onClick={fetchData}
          style={{ padding: '4px 12px', borderRadius: 6, border: '1px solid #ddd', background: 'white', cursor: 'pointer', fontSize: 12 }}
        >
          Refresh
        </button>
      </div>
      {loading && !health && <p style={{ color: '#999' }}>Loading…</p>}
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
                <p style={{ margin: '4px 0 0', fontSize: 12, color: '#999' }}>{metric.description}</p>
              )}
              <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 12 }}>
                {metric.measurements.map((m) => (
                  <div key={m.statistic} style={{ fontSize: 13 }}>
                    <span style={{ color: '#999' }}>{m.statistic}: </span>
                    <span style={{ fontWeight: 600, color: '#333' }}>{m.value}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </>
      )}

      {!loading && metrics.length === 0 && health && (
        <p style={{ color: '#999', fontSize: 13 }}>No Atmosphere metrics found. Metrics appear after the first connection.</p>
      )}
    </div>
  );
}

// --- Main App ---

const ROOM = 'lobby';

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [name, setName] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('chat');

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

  const { data, state, push } = useAtmosphere<unknown>({ request });

  useEffect(() => {
    if (!data) return;
    try {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;

      // Handle Room Protocol messages
      if (isRoomProtocol(parsed)) {
        switch (parsed.type) {
          case 'join_ack':
            setMessages((prev) => [
              ...prev,
              {
                author: 'system',
                message: `Joined room. Members: ${parsed.members.map((m: { id: string }) => m.id).join(', ')}`,
                time: Date.now(),
              },
            ]);
            break;
          case 'presence':
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

  const handleSend = (text: string) => {
    if (!name) {
      setName(text);
      sendJoin(push, ROOM, text);
    } else {
      sendBroadcast(push, ROOM, text);
      // Add our own message locally — server excludes sender from broadcast
      setMessages((prev) => [
        ...prev,
        { author: name, message: text, time: Date.now() },
      ]);
    }
  };

  return (
    <ChatLayout
      title="🚀 Atmosphere 4.0 Chat"
      subtitle="Spring Boot • Room Protocol • Presence • Message History • Health Check"
      theme="default"
      state={state}
    >
      <div style={tabBarStyle}>
        <div style={tabStyle(activeTab === 'chat')} onClick={() => setActiveTab('chat')}>
          💬 Chat
        </div>
        <div style={tabStyle(activeTab === 'rooms')} onClick={() => setActiveTab('rooms')}>
          🏠 Rooms
        </div>
        <div style={tabStyle(activeTab === 'observability')} onClick={() => setActiveTab('observability')}>
          📊 Observability
        </div>
      </div>

      {activeTab === 'chat' && (
        <>
          <MessageList messages={messages} currentUser={name ?? undefined} theme="default" />
          <ChatInput
            onSend={handleSend}
            placeholder={name ? 'Type a message…' : 'Enter your name to join…'}
            disabled={state !== 'connected'}
            theme="default"
          />
        </>
      )}

      {activeTab === 'rooms' && <RoomsPanel />}
      {activeTab === 'observability' && <ObservabilityPanel />}
    </ChatLayout>
  );
}
