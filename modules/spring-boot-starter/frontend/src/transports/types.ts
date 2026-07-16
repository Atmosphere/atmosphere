import type { ConnectionStatus } from 'atmosphere.js'

/**
 * Wire transports the console can speak, as reported by
 * /api/console/info's `transport` field (validated server-side in
 * AtmosphereConsoleInfoEndpoint#detectTransport — unknown values are
 * reported as 'atmosphere', so this union is exhaustive on the wire).
 */
export type ConsoleTransportName = 'atmosphere' | 'grpc' | 'a2a' | 'ag-ui'

/**
 * Callbacks a transport drives. They carry the Console's *normalized*
 * chat-event vocabulary — the `{type|event, data}` shapes
 * useAtmosphereChat#handleStreamingEvent renders (streaming-text /
 * text-delta / complete / error / tool-start / tool-result /
 * approval-required / plan-update / entity-* ...). Foreign transports
 * (grpc / a2a / ag-ui) translate their own wire frames into this same
 * vocabulary so the renderer stays transport-agnostic.
 */
export interface ChatTransportHandlers {
  onOpen(): void
  onClose(): void
  onError(): void
  onReconnect(): void
  onReopen(): void
  onClientTimeout(): void
  onFailureToReconnect(): void
  /** One parsed, Console-normalized chat event. */
  onEvent(msg: Record<string, unknown>): void
  /** Non-JSON payload fragment — rendered as raw assistant text. */
  onRawText(text: string): void
}

export interface ChatTransportOptions {
  endpoint: string
  /**
   * Live dialect getter — broadcast endpoints (@ManagedService rooms)
   * expect a JSON {author, message, time} envelope while AI endpoints
   * take the raw prompt string. A getter (not a boolean) because the
   * mode can resolve asynchronously from /api/console/info.
   */
  isBroadcast(): boolean
  /**
   * Shared resilience tracker driving the ConnectionStatus pill. The
   * Atmosphere transport instruments it via status.wrap(); foreign
   * adapters mark lifecycle transitions on it directly.
   */
  status: ConnectionStatus
  maxReconnectOnClose: number
}

/**
 * A chat wire transport. Owns the connection to the sample's endpoint
 * and translates its wire protocol into ChatTransportHandlers calls.
 * The composable (useAtmosphereChat) owns all rendering state.
 */
export interface ChatTransport {
  readonly name: ConsoleTransportName
  connect(): Promise<void>
  disconnect(): void
  /** Send a user chat message in the transport's dialect. */
  send(text: string): void
  /**
   * Push a raw control frame (e.g. /__approval/<id>/approve). Transports
   * without a control channel treat this as a no-op.
   */
  sendControl(frame: string): void
}
