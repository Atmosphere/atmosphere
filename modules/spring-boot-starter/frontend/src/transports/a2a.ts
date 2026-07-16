import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * One decoded unit of an A2A SSE `data:` payload, normalized toward the
 * Console's chat-event vocabulary.
 */
export type A2aFrame =
  | { kind: 'text'; text: string }
  | { kind: 'done' }
  | { kind: 'error'; message: string }

/**
 * Decode one A2A SSE `data:` payload. The server (A2aHandler#writeStreamChunk)
 * emits v1.0.0 JSON-RPC envelopes wrapping a StreamResponse:
 *
 *   data: {"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"artifact":
 *          {"artifactId":"<uuid>","parts":[{"text":"<token>"}]}}}}
 *   data: [DONE]
 *
 * NOTE: the extraction path is result.artifactUpdate.artifact.parts[].text —
 * the sample frontend historically read `parsed.artifact.parts[0].text`
 * (one level too shallow) and rendered nothing. This adapter is built against
 * the server's actual envelope; the vitest suite pins it.
 */
export function parseA2aData(payload: string): A2aFrame[] {
  const trimmed = payload.trim()
  if (!trimmed) return []
  if (trimmed === '[DONE]') return [{ kind: 'done' }]

  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    // Not JSON — surface verbatim rather than dropping a frame silently.
    return [{ kind: 'text', text: trimmed }]
  }

  const error = parsed.error as Record<string, unknown> | undefined
  if (error) {
    return [{ kind: 'error', message: String(error.message ?? 'A2A error') }]
  }

  const result = parsed.result as Record<string, unknown> | undefined
  const artifactUpdate = result?.artifactUpdate as Record<string, unknown> | undefined
  const artifact = artifactUpdate?.artifact as Record<string, unknown> | undefined
  const parts = artifact?.parts as Array<Record<string, unknown>> | undefined
  if (!Array.isArray(parts)) return []

  const frames: A2aFrame[] = []
  for (const part of parts) {
    if (typeof part?.text === 'string' && part.text) {
      frames.push({ kind: 'text', text: part.text })
    }
  }
  return frames
}

/**
 * Extract the reply text from a unary `message/send` Task result:
 * result.artifacts[0].parts[0].text (the A2A sync-fallback contract).
 */
export function extractTaskText(result: unknown): string | null {
  const task = result as Record<string, unknown> | null
  const artifacts = task?.artifacts as Array<Record<string, unknown>> | undefined
  const parts = artifacts?.[0]?.parts as Array<Record<string, unknown>> | undefined
  const text = parts?.[0]?.text
  return typeof text === 'string' && text ? text : null
}

/**
 * A2A (Agent-to-Agent) chat transport: JSON-RPC 2.0 over HTTP POST with
 * SSE streaming (`message/stream`) and a unary `message/send` fallback.
 * "Connected" is runtime truth here — the agent card must answer before the
 * transport reports open, since A2A has no persistent connection to probe.
 */
export class A2aChatTransport implements ChatTransport {
  readonly name = 'a2a' as const

  // Wrapped (empty) subscription handlers reusing ConnectionStatus's
  // lifecycle tracking for the status pill — its mark* methods are private,
  // wrap() is the supported seam.
  private readonly lifecycle
  private abort: AbortController | null = null
  private rpcId = 0
  private connected = false

  constructor(
    private readonly options: ChatTransportOptions,
    private readonly handlers: ChatTransportHandlers,
  ) {
    this.lifecycle = options.status.wrap<string>({})
  }

  async connect(): Promise<void> {
    // Same probe the A2A protocol defines for discovery; the legacy alias is
    // what the shipped sample sends and A2aMethod canonicalizes it.
    await this.rpc('agent/authenticatedExtendedCard', {})
    this.connected = true
    this.lifecycle.open?.(undefined as never)
    this.handlers.onOpen()
  }

  disconnect(): void {
    this.abort?.abort()
    this.abort = null
    if (this.connected) {
      this.connected = false
      this.lifecycle.close?.(undefined as never)
      this.handlers.onClose()
    }
  }

  send(text: string): void {
    if (!this.connected) return
    // One in-flight exchange at a time — a new send supersedes the previous
    // stream (the UI disables input while streaming; this is the guard for
    // programmatic callers).
    this.abort?.abort()
    this.abort = new AbortController()
    void this.exchange(text, this.abort.signal)
  }

  sendControl(): void {
    // A2A has no client→server control channel mid-stream (approvals flow
    // over the Atmosphere transport only) — documented no-op.
  }

  private async exchange(text: string, signal: AbortSignal): Promise<void> {
    try {
      await this.stream(text, signal)
    } catch (e) {
      if (signal.aborted) return
      // Streaming path failed (network, non-SSE response) — the A2A contract
      // defines message/send as the sync fallback.
      try {
        const result = await this.rpc('message/send', this.messageParams(text), signal)
        const reply = extractTaskText(result)
        if (reply) {
          this.handlers.onEvent({ type: 'streaming-text', data: reply })
          this.handlers.onEvent({ type: 'complete' })
        } else {
          this.handlers.onEvent({ type: 'error', data: 'Empty A2A task result' })
        }
      } catch (fallbackError) {
        if (signal.aborted) return
        this.handlers.onEvent({ type: 'error', data: String(fallbackError) })
      }
    }
  }

  private async stream(text: string, signal: AbortSignal): Promise<void> {
    const res = await fetch(this.options.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: ++this.rpcId,
        method: 'message/stream',
        params: this.messageParams(text),
      }),
      signal,
    })
    if (!res.ok || !res.body) {
      throw new Error(`A2A stream failed: HTTP ${res.status}`)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let done = false
    try {
      for (;;) {
        const chunk = await reader.read()
        if (chunk.done) break
        buffer += decoder.decode(chunk.value, { stream: true })
        // SSE events are newline-framed; data lines carry the payload.
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          for (const frame of parseA2aData(line.slice(5))) {
            if (frame.kind === 'done') {
              done = true
              this.handlers.onEvent({ type: 'complete' })
            } else if (frame.kind === 'error') {
              done = true
              this.handlers.onEvent({ type: 'error', data: frame.message })
            } else {
              this.handlers.onEvent({ type: 'streaming-text', data: frame.text })
            }
          }
        }
      }
    } finally {
      reader.releaseLock()
    }
    if (!done && !signal.aborted) {
      // Stream ended without [DONE] — close the bubble anyway so the UI
      // never sticks in "streaming" (terminal-path completeness).
      this.handlers.onEvent({ type: 'complete' })
    }
  }

  private messageParams(text: string): Record<string, unknown> {
    return {
      message: {
        role: 'user',
        parts: [{ type: 'text', text }],
        messageId: `m-${this.rpcId + 1}`,
      },
      // Duplicated flat form some skills read (matches the shipped sample).
      arguments: { message: text },
    }
  }

  private async rpc(
    method: string,
    params: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<unknown> {
    const res = await fetch(this.options.endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++this.rpcId, method, params }),
      signal,
    })
    if (!res.ok) {
      throw new Error(`A2A rpc ${method} failed: HTTP ${res.status}`)
    }
    const json = await res.json() as Record<string, unknown>
    const error = json.error as Record<string, unknown> | undefined
    if (error) {
      throw new Error(String(error.message ?? `A2A rpc ${method} error`))
    }
    return json.result
  }
}
