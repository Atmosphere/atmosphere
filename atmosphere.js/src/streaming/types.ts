/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Wire protocol message types sent by the server-side
 * {@code DefaultStreamingSession}.
 */
export type StreamingMessageType = 'streaming-text' | 'complete' | 'error' | 'progress' | 'metadata';

/**
 * Aggregated statistics for a streaming session, computed client-side
 * from coalesced cache events.
 */
export interface SessionStats {
  totalStreamingTexts: number;
  elapsedMs: number;
  status: 'streaming' | 'complete' | 'error';
  streamingTextsPerSecond: number;
}

/**
 * Routing information extracted from {@code routing.*} metadata keys
 * sent by the server's cost/latency routing layer.
 */
export interface RoutingInfo {
  model?: string;
  cost?: number;
  latency?: number;
}

/**
 * Optional hints sent alongside a prompt to influence server-side
 * cost/latency routing decisions.
 */
export interface SendOptions {
  maxCost?: number;
  maxLatencyMs?: number;
}

/**
 * A single streaming wire-protocol message as sent by the Java
 * {@code DefaultStreamingSession}.
 *
 * Example (legacy):
 * ```json
 * {"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}
 * ```
 *
 * Example (AiEvent):
 * ```json
 * {"event":"tool-start","data":{"toolName":"weather","arguments":{"city":"Montreal"}},"sessionId":"abc-123","seq":1}
 * ```
 */
export interface StreamingMessage {
  type: StreamingMessageType;
  /** For legacy messages: string data. For AiEvent messages: structured data object. */
  data?: string;
  key?: string;
  value?: unknown;
  sessionId: string;
  seq: number;
  /** AiEvent type name (e.g., "text-delta", "tool-start"). Present only in AiEvent format. */
  event?: string;
}

/**
 * A structured AI event from the AiEvent wire protocol.
 * Emitted by {@code StreamingSession.emit(AiEvent)} on the server.
 */
export interface AiEventMessage {
  /** Event type (e.g., "text-delta", "tool-start", "agent-step"). */
  event: string;
  /** Structured event payload. */
  data: Record<string, unknown>;
  sessionId: string;
  seq: number;
}

/**
 * Parsed governance denial. Emitted to {@link StreamingHandlers.onPolicyDenied}
 * when the server's governance plane denies the turn (scope breach, MS-schema
 * rule hit, kill switch armed, etc.). Distinguished from generic transport
 * errors so UIs can render governance denials with appropriate framing
 * (friendly redirect vs. connection-failed banner).
 */
export interface PolicyDenial {
  /** Kind of denial — {@code policy} for {@code GovernancePolicy}, {@code guardrail} for {@code AiGuardrail}. */
  kind: 'policy' | 'guardrail';
  /** Policy or guardrail identity, when known. */
  policyName?: string;
  /** Human-readable denial reason. Safe to render in UI. */
  reason: string;
  /** The raw server error string, kept for debug overlays. */
  raw: string;
}

/**
 * Event handlers for a streaming session.
 */
export interface StreamingHandlers {
  /** Called for each streaming text fragment received from the LLM. */
  onStreamingText?: (text: string, seq: number) => void;
  /** Called when the server signals progress (e.g. "Thinking…"). */
  onProgress?: (message: string, seq: number) => void;
  /** Called when the stream completes, with an optional summary. */
  onComplete?: (summary?: string) => void;
  /** Called on error. */
  onError?: (error: string) => void;
  /**
   * Called when the server denied the turn via governance (scope breach,
   * MS-schema rule hit, kill switch armed) or a guardrail (PII, cost ceiling).
   * When this fires, {@link #onError} also fires with the same raw string so
   * code that cares only about error display keeps working; new code should
   * check for {@link PolicyDenial} first and fall back to {@link #onError}
   * only for transport-level failures.
   */
  onPolicyDenied?: (denial: PolicyDenial) => void;
  /** Called on metadata events (model name, streaming text count, etc.). */
  onMetadata?: (key: string, value: unknown) => void;
  /** Called for structured AiEvent messages (tool calls, agent steps, entities, etc.). */
  onAiEvent?: (event: string, data: Record<string, unknown>) => void;
  /** Called when the session completes or errors, with aggregated stats and routing info. */
  onSessionComplete?: (stats: SessionStats, routing: RoutingInfo) => void;
}

/**
 * A handle to an active streaming session.
 */
export interface StreamingHandle {
  /** The session ID assigned by the server. */
  readonly sessionId: string | null;
  /** Send a prompt/message to the server to start or continue streaming. */
  send(message: string | object, options?: SendOptions): void;
  /** Close the streaming session. */
  close(): Promise<void>;
}
