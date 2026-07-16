import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * One parsed AG-UI SSE event: the `event: TYPE` name plus its `data:` JSON.
 */
export interface AgUiEvent {
  type: string
  data: Record<string, unknown>
}

/**
 * Incremental parser for AG-UI's named-event SSE framing
 * (AgUiHandler#SseWriter): `event: <TYPE>\ndata: <json>\n\n`. Feed it raw
 * chunks; it returns completed events and buffers partial lines. Pure state
 * machine so the framing rules are unit-testable without a live stream.
 */
export class AgUiSseParser {
  private buffer = ''
  private eventType: string | null = null

  push(chunk: string): AgUiEvent[] {
    this.buffer += chunk
    const lines = this.buffer.split('\n')
    this.buffer = lines.pop() ?? ''
    const events: AgUiEvent[] = []
    for (const line of lines) {
      if (line.startsWith('event:')) {
        this.eventType = line.slice(6).trim()
      } else if (line.startsWith('data:') && this.eventType) {
        try {
          events.push({ type: this.eventType, data: JSON.parse(line.slice(5)) })
        } catch {
          // Malformed data line — drop the frame, keep the stream alive.
        }
        this.eventType = null
      }
      // Blank separator lines need no handling: the event name resets after
      // each data line, matching the server's strict event/data pairing.
    }
    return events
  }
}

/**
 * Map one AG-UI event onto the Console's normalized chat-event vocabulary
 * (the inverse of the server's AgUiEventMapper). Stateful: AG-UI correlates
 * tool results by toolCallId while the Console's renderer correlates by
 * tool name, so the adapter keeps the id→name map for the current run.
 * Returns the Console events to emit (possibly none).
 */
export class AgUiEventTranslator {
  private toolNames = new Map<string, string>()

  translate(event: AgUiEvent): Array<Record<string, unknown>> {
    const d = event.data
    switch (event.type) {
      case 'TEXT_MESSAGE_CONTENT': {
        const delta = d.delta
        return typeof delta === 'string' && delta
          ? [{ type: 'streaming-text', data: delta }]
          : []
      }
      case 'TOOL_CALL_START': {
        const id = String(d.toolCallId ?? '')
        const name = String(d.name ?? '')
        if (!name) return []
        if (id) this.toolNames.set(id, name)
        return [{ event: 'tool-start', data: { toolName: name, arguments: {} } }]
      }
      case 'TOOL_CALL_RESULT': {
        const id = String(d.toolCallId ?? '')
        const name = this.toolNames.get(id) ?? ''
        const result = typeof d.result === 'string' ? d.result : JSON.stringify(d.result ?? '')
        return [{ event: 'tool-result', data: { toolName: name, result } }]
      }
      case 'RUN_FINISHED':
        this.toolNames.clear()
        return [{ type: 'complete' }]
      case 'RUN_ERROR': {
        this.toolNames.clear()
        return [{ type: 'error', data: String(d.message ?? 'AG-UI run error') }]
      }
      case 'ACTIVITY_DELTA':
        return [{ type: 'progress', data: d }]
      default:
        // RUN_STARTED, TEXT_MESSAGE_START/END, TOOL_CALL_ARGS/END,
        // STEP_STARTED/FINISHED, STATE_* — no Console-renderable mapping yet
        // (the steps timeline is Phase-1 panel work); dropping them is the
        // same behavior the bespoke sample applied to unknown types.
        return []
    }
  }
}

/**
 * AG-UI (CopilotKit-compatible) chat transport: one POST per user turn to
 * /atmosphere/agent/{name}/agui, streaming named SSE events back.
 * "Connected" means the endpoint is reachable — AG-UI defines no side-effect-
 * free probe, so reachability (any HTTP response) is the strongest truthful
 * signal available before the first run.
 */
export class AgUiChatTransport implements ChatTransport {
  readonly name = 'ag-ui' as const

  private readonly lifecycle
  private readonly threadId = crypto.randomUUID()
  private abort: AbortController | null = null
  private connected = false

  constructor(
    private readonly options: ChatTransportOptions,
    private readonly handlers: ChatTransportHandlers,
  ) {
    this.lifecycle = options.status.wrap<string>({})
  }

  async connect(): Promise<void> {
    // Reachability probe: AG-UI defines no side-effect-free request on its
    // endpoint (a HEAD there 405s, which the browser red-logs as a resource
    // error — probe noise the console forbids), so probe the origin root
    // instead. Any HTTP response proves the server answers; only a
    // network-level failure rejects.
    await fetch('/', { method: 'HEAD' })
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
    this.abort?.abort()
    this.abort = new AbortController()
    void this.run(text, this.abort.signal)
  }

  sendControl(): void {
    // AG-UI has no client→server control channel mid-run — documented no-op.
  }

  private async run(text: string, signal: AbortSignal): Promise<void> {
    let finished = false
    try {
      const res = await fetch(this.options.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          threadId: this.threadId,
          runId: crypto.randomUUID(),
          messages: [{ role: 'user', content: text }],
        }),
        signal,
      })
      if (!res.ok || !res.body) {
        throw new Error(`AG-UI run failed: HTTP ${res.status}`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      const parser = new AgUiSseParser()
      const translator = new AgUiEventTranslator()
      try {
        for (;;) {
          const chunk = await reader.read()
          if (chunk.done) break
          for (const event of parser.push(decoder.decode(chunk.value, { stream: true }))) {
            for (const msg of translator.translate(event)) {
              if (msg.type === 'complete' || msg.type === 'error') finished = true
              this.handlers.onEvent(msg)
            }
          }
        }
      } finally {
        reader.releaseLock()
      }
    } catch (e) {
      if (signal.aborted) return
      finished = true
      this.handlers.onEvent({ type: 'error', data: String(e) })
    }
    if (!finished && !signal.aborted) {
      // Stream ended without RUN_FINISHED/RUN_ERROR — close the bubble so the
      // UI never sticks in "streaming" (terminal-path completeness).
      this.handlers.onEvent({ type: 'complete' })
    }
  }
}
