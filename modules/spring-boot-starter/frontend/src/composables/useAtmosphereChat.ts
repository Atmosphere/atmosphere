import { ref, computed, watch, onMounted, onUnmounted, type Ref } from 'vue'
import { ConnectionStatus, roomMemberId } from 'atmosphere.js'
import { useOfflineQueue } from 'atmosphere.js/vue'
import { recordPlanUpdate, resetLivePlan } from '../lib/workspaceStore'
import { mergeRoutingMetadata, normalizeMetadataFrame, type RoutingMetadata } from '../lib/routingMetadata'
import { mergeGovernanceFeedback, type GovernanceFeedback } from '../lib/governanceFeedback'
import { createChatTransport } from '../transports'
import type { ChatTransport, ConsoleTransportName } from '../transports'
import type { ConnectionStatusSnapshot } from 'atmosphere.js'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  /** Who sent a user message this tab did not: another member of the room. */
  author?: string
}

export interface PendingApproval {
  approvalId: string
  message: string
  expiresIn: number
  requestedAt: number
}

export interface ToolCall {
  id: string
  name: string
  args: Record<string, unknown>
  result?: string
  done: boolean
  approval?: PendingApproval
}

export interface SessionStats {
  totalStreamingTexts: number
  elapsedMs: number
  streamingTextsPerSecond: number
}

export function useAtmosphereChat(endpoint: string = '/atmosphere/ai-chat',
                                  mode?: 'ai' | 'broadcast',
                                  transportName: ConsoleTransportName = 'atmosphere',
                                  webTransport?: { port: number; certificateHash?: string },
                                  room?: string) {
  const messages = ref<ChatMessage[]>([])
  const toolCalls = ref<ToolCall[]>([])
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const connectionState = ref<string>('disconnected')
  const stats = ref<SessionStats | null>(null)
  // Cost/latency routing readout from the server's metadata events —
  // rendered as header chips + in the per-turn stats footer.
  const routing = ref<RoutingMetadata>({})
  // Governance guidance the server injected into THIS turn's system prompt
  // (ai.governance.feedback.* metadata frames). Per-turn: reset on send.
  const governanceFeedback = ref<GovernanceFeedback | null>(null)
  // Live per-agent activity from agent-step events (agent → current step) —
  // drives the fleet activity strip during multi-agent coordinations.
  const agentSteps = ref<Record<string, string>>({})
  // Live room headcount. Fed by two wire shapes: presence frames carrying a
  // server-computed count (classroom), or join_ack/presence member deltas
  // (Room Protocol) tracked in a local set.
  const presenceCount = ref(0)
  let presenceMembers = new Set<string>()

  // Offline queue for the Atmosphere transport: sends while disconnected
  // enqueue (the core auto-enqueues on push when state !== 'connected') and
  // drain automatically on reopen. The queue only fills after a first
  // successful connect — the core drains on *reopen*, not first open — so
  // offline input is gated on everConnected (Runtime Truth: never advertise
  // a queue that would never drain).
  const offline = useOfflineQueue<string>({ maxSize: 50 })
  const everConnected = ref(false)
  const canQueueOffline = computed(() =>
    transportName === 'atmosphere' && everConnected.value)
  // Re-wrap in a local computed: the package's ComputedRef type comes from
  // its own vue instance, which vue-tsc won't unwrap in templates here.
  const offlineSize = computed(() => offline.size.value)
  // User bubbles sent while disconnected carry a "(queued)" suffix until the
  // queue drains (size back to 0), then the suffix is stripped.
  const QUEUED_SUFFIX = ' *(queued)*'
  // A prompt the server never took (atmosphere.js gave up resending it): the
  // bubble says so, and the input is free again to send it once more.
  const UNDELIVERED_SUFFIX = ' *(not delivered — send it again)*'
  const SEND_ERROR_NAME = 'AtmosphereSendError'
  let lastSentUserMessageId: string | null = null
  const queuedBubbleIds = new Set<string>()
  watch(offlineSize, (size) => {
    if (size === 0 && queuedBubbleIds.size > 0) {
      messages.value = messages.value.map(m => queuedBubbleIds.has(m.id)
        ? { ...m, content: m.content.replace(QUEUED_SUFFIX, '') }
        : m)
      queuedBubbleIds.clear()
    }
  })

  // Broadcast-mode endpoints (@ManagedService chat rooms) decode a JSON
  // {author, message, time} envelope, not the raw prompt string the AI
  // pipeline accepts. The caller (App.vue → ChatContainer) already resolved
  // the mode from the console info endpoint before mounting, so prefer the
  // prop — a second unawaited fetch here could lose the race with the first
  // send() and push the wrong dialect, permanently locking isStreaming on a
  // broadcast console. The fetch remains only as a fallback for callers that
  // don't pass the mode.
  const broadcastMode = ref(mode === 'broadcast')
  if (mode === undefined) {
    fetch('/api/console/info')
      .then(r => (r.ok ? r.json() : null))
      .then(info => { broadcastMode.value = info?.mode === 'broadcast' })
      .catch(() => { /* AI dialect stays the default */ })
  }

  // Resilience tracker — surfaces fallback, reconnect-attempts and the
  // terminal "lost" state to the operator UI via ConnectionStatusBadge.
  // The pill names the wire honestly: 'websocket' for the Atmosphere
  // transport (long-polling shows via fallback tracking), the foreign
  // protocol name otherwise (ConnectionTransportName is deliberately wide).
  const status = new ConnectionStatus({
    initialTransport: transportName === 'atmosphere' ? 'websocket' : transportName,
  })
  const connectionStatus: Ref<ConnectionStatusSnapshot> = ref(status.snapshot)
  const unsubscribeStatus = status.onChange((s) => { connectionStatus.value = s })

  let transport: ChatTransport | null = null
  let currentAssistantMessage: ChatMessage | null = null
  // Server-assigned id of the last Room Protocol message seen — the reconnect
  // join carries it as the sinceId history cursor so replays deduplicate.
  let lastRoomMessageId = 0
  let toolCallCounter = 0
  let reactivityTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempts = 0
  const MAX_RECONNECT_ON_CLOSE = 10
  let streamStartedAt = 0
  let streamTokenCount = 0
  // Clear-during-stream guard: when the user clears the conversation while a
  // reply is still streaming, the late deltas belong to the cleared
  // conversation and must not resurrect an assistant bubble. Set on clear,
  // reset when the discarded stream terminates or a new send starts.
  let discardInFlightStream = false
  // The session of the stream being discarded, so a room turn that starts
  // meanwhile (another member's prompt) still renders; null when unknown.
  let discardSessionId: string | null = null
  let lastStreamSessionId: string | null = null

  /** Whether `msg` belongs to the stream a clear discarded. */
  function isDiscarded(msg: Record<string, unknown>): boolean {
    if (!discardInFlightStream) return false
    const sessionId = typeof msg.sessionId === 'string' ? msg.sessionId : null
    return discardSessionId === null || sessionId === null || sessionId === discardSessionId
  }

  // Room prompts (broadcastReply): rendered once per turn, the turn named by
  // its run id (a live echo's session maps to it through the run-id metadata
  // frame; a replayed echo carries it as its session).
  const RUN_ID_KEY = 'X-Atmosphere-Run-Id'
  const MAX_TRACKED_TURNS = 64
  const runOfSession = new Map<string, string>()
  const renderedPromptTurns = new Set<string>()
  function remember(set: Set<string>, value: string) {
    set.add(value)
    if (set.size > MAX_TRACKED_TURNS) set.delete(set.values().next().value as string)
  }

  // This tab's room member ids (roomMemberId of each tracking id it had), kept
  // in sessionStorage so a prompt it sent before a reload, replayed with its
  // run, renders as its own bubble rather than another member's. A duplicated
  // tab starts with a copy of the original's sessionStorage (these ids and the
  // run id atmosphere.js keeps there), so it takes the original's prompts as its own.
  const OWN_MEMBER_IDS_KEY = 'atmosphere.console.roomMemberIds'
  const MAX_OWN_MEMBER_IDS = 8
  function ownMemberIds(): string[] {
    try {
      const raw = sessionStorage.getItem(OWN_MEMBER_IDS_KEY)
      const ids = raw ? JSON.parse(raw) : []
      return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : []
    } catch {
      return []
    }
  }
  /** Record this connection's member id; returns it ('' while unknown). */
  function rememberOwnMemberId(): string {
    const id = roomMemberId(transport?.uuid)
    if (!id) return ''
    const ids = ownMemberIds()
    if (!ids.includes(id)) {
      try {
        sessionStorage.setItem(OWN_MEMBER_IDS_KEY, JSON.stringify([...ids, id].slice(-MAX_OWN_MEMBER_IDS)))
      } catch { /* storage unavailable: own prompts after a reload show as a member's */ }
    }
    return id
  }

  /**
   * Turn whatever shape the server emits for an error into a compact
   * {message, status?, retryDelay?} card. Handles:
   *  - plain string (legacy)
   *  - {message: "..."} (LC4j / Spring AI / Built-in)
   *  - stringified JSON arrays from the OpenAI-compat layer, e.g.
   *    `[{"error":{"code":429,"message":"quota...","status":"RESOURCE_EXHAUSTED",
   *       "details":[{"@type":".../RetryInfo","retryDelay":"5s"}, ...]}}]`
   *    (this is the Gemini 429 case — the whole envelope used to render raw).
   */
  function extractErrorShape(raw: unknown): {
    message: string
    status?: string
    retryDelay?: string
  } {
    // Unwrap one or two levels of JSON-in-string — the pipeline sometimes
    // double-stringifies the upstream error.
    let data: unknown = raw
    for (let i = 0; i < 2 && typeof data === 'string'; i++) {
      const trimmed = data.trim()
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        try { data = JSON.parse(trimmed) } catch { break }
      } else {
        break
      }
    }

    // Flatten single-element arrays
    if (Array.isArray(data) && data.length === 1) {
      data = data[0]
    }

    // Plain string after unwrap
    if (typeof data === 'string') {
      return { message: data }
    }
    if (data == null || typeof data !== 'object') {
      return { message: 'Unknown error' }
    }

    // {error: {message, status, details: [...]}}
    const obj = data as Record<string, unknown>
    const errNode = (obj.error ?? obj) as Record<string, unknown>
    const message = (errNode.message as string)
      ?? (obj.message as string)
      ?? 'Unknown error'
    const status = errNode.status as string | undefined

    // Walk details for google.rpc.RetryInfo
    let retryDelay: string | undefined
    const details = errNode.details as unknown
    if (Array.isArray(details)) {
      for (const d of details) {
        if (d && typeof d === 'object') {
          const dr = d as Record<string, unknown>
          if (typeof dr.retryDelay === 'string') {
            retryDelay = dr.retryDelay as string
            break
          }
        }
      }
    }

    return { message, status, retryDelay }
  }

  /**
   * The line a cancelled run ends with. `data` is the cancelled frame's reason
   * code; a protocol bridge that only carries text (AG-UI, A2A) passes its
   * message, shown as is.
   */
  function cancelledNotice(data: unknown): string {
    switch (data) {
      case 'disconnect': return 'Run cancelled: the client disconnected before it finished.'
      case 'operator': return 'Run cancelled by an operator.'
      case 'sweep': return 'Run cancelled: the session expired.'
      case 'caller': return 'Run cancelled by the caller.'
      case 'timeout': return 'Run cancelled: the reply ran past its time limit.'
      default:
        return typeof data === 'string' && data ? data : 'Run cancelled.'
    }
  }

  function handleStreamingEvent(msg: Record<string, unknown>) {
    const type = (msg.type as string) || (msg.event as string)

    if (!type && typeof msg.message === 'string' && msg.message) {
      // Event-less frame: a broadcast-mode payload ({author?, message, time?})
      // from a @ManagedService/Broadcaster endpoint. Render it as its own
      // bubble instead of dropping it — this is how another client's message
      // (and the sender's own echo) reaches the console in broadcast mode.
      const author = typeof msg.author === 'string' && msg.author ? `${msg.author}: ` : ''
      appendBroadcastMessage(author + (msg.message as string))
      return
    }

    switch (type) {
      case 'streaming-text':
      case 'text-delta':
        if (isDiscarded(msg)) break
        if (typeof msg.sessionId === 'string') lastStreamSessionId = msg.sessionId
        // A reply this client did not send() for — a run it reattached to
        // after a reload, a room reply — streams like one it did: the turn is
        // in flight until its complete/error/cancelled frame.
        isStreaming.value = true
        streamTokenCount += 1
        appendToAssistant(
          typeof msg.data === 'string'
            ? msg.data
            : typeof msg.data === 'object' && msg.data !== null
              ? (msg.data as Record<string, unknown>).text as string || ''
              : ''
        )
        break
      case 'complete': {
        if (isDiscarded(msg)) {
          discardInFlightStream = false
          break
        }
        if (streamStartedAt > 0) {
          const elapsedMs = Math.max(1, Date.now() - streamStartedAt)
          stats.value = {
            totalStreamingTexts: streamTokenCount,
            elapsedMs,
            streamingTextsPerSecond: (streamTokenCount / elapsedMs) * 1000,
          }
        }
        finalizeAssistant()
        break
      }
      case 'error': {
        if (isDiscarded(msg)) {
          discardInFlightStream = false
          break
        }
        const { message, retryDelay, status } = extractErrorShape(msg.data)
        let rendered = `\n\n**Error:** ${message}`
        if (status) {
          rendered += `\n\n*${status}*`
        }
        if (retryDelay) {
          rendered += `  •  retry in ${retryDelay}`
        }
        appendToAssistant(rendered)
        finalizeAssistant()
        break
      }
      case 'cancelled': {
        // The run's one terminal frame when the server cut it short: the
        // answer so far stays, marked as cut off — not an error.
        if (isDiscarded(msg)) {
          discardInFlightStream = false
          break
        }
        appendToAssistant(`\n\n*${cancelledNotice(msg.data)}*`)
        finalizeAssistant()
        break
      }
      case 'prompt': {
        // A room member's prompt (@AiEndpoint(broadcastReply = true)), echoed
        // to the room as the turn's first frame, and replayed with the run to
        // a client that reattaches. `from` is the sender's room member id,
        // never its tracking id. Rendered once per turn: this connection's own
        // prompt is already on screen (the optimistic bubble send() added); a
        // prompt this tab sent before a reload comes back as its own bubble;
        // another member's becomes a user bubble labelled with its member id.
        // The reply that follows opens a bubble of its own.
        const text = typeof msg.text === 'string' ? msg.text : ''
        const from = typeof msg.from === 'string' ? msg.from : ''
        const sessionId = typeof msg.sessionId === 'string' ? msg.sessionId : ''
        const turn = runOfSession.get(sessionId) ?? sessionId
        if (!text || (turn && renderedPromptTurns.has(turn))) break
        if (turn) remember(renderedPromptTurns, turn)
        const current = rememberOwnMemberId()
        if (from && from === current) break
        flushAssistant()
        currentAssistantMessage = null
        const own = !!from && ownMemberIds().includes(from)
        messages.value = [...messages.value, {
          id: crypto.randomUUID(),
          role: 'user',
          content: text,
          timestamp: Date.now(),
          ...(from && !own ? { author: `Member ${from.slice(0, 8)}` } : {}),
        }]
        break
      }
      case 'progress':
        // Ignored for chat display
        break
      case 'metadata': {
        // Cost/latency routing readout (routing.model / routing.cost /
        // routing.latency keys, plus the runtime's bare model) — feeds the
        // header chips and the per-turn stats footer. The Atmosphere wire
        // frame is {type:'metadata', key, value} top-level (see
        // DefaultStreamingSession), normalized here into a keyed record;
        // frames without a top-level key fall back to a data payload map.
        // The same record feeds the per-turn governance guidance panel.
        if (msg.key === RUN_ID_KEY && typeof msg.value === 'string' && typeof msg.sessionId === 'string') {
          // A turn's run id: its prompt replayed on a reattach comes under it.
          runOfSession.set(msg.sessionId, msg.value)
          if (runOfSession.size > MAX_TRACKED_TURNS) {
            runOfSession.delete(runOfSession.keys().next().value as string)
          }
        }
        const frame = normalizeMetadataFrame(msg)
        routing.value = mergeRoutingMetadata(routing.value, frame)
        governanceFeedback.value = mergeGovernanceFeedback(governanceFeedback.value, frame)
        break
      }
      case 'tool-start': {
        const name = ((msg.data as Record<string, unknown>)?.toolName ?? '') as string
        const args = ((msg.data as Record<string, unknown>)?.arguments ?? {}) as Record<string, unknown>
        if (name) {
          const id = `${++toolCallCounter}-${name}`
          toolCalls.value = [...toolCalls.value, { id, name, args, done: false }]
        }
        break
      }
      case 'tool-result': {
        const name = ((msg.data as Record<string, unknown>)?.toolName ?? '') as string
        const result = ((msg.data as Record<string, unknown>)?.result ?? '') as string
        // FIFO match: finds the first unfinished tool call with this name.
        // For true out-of-order correlation, the server should include a correlation ID
        // in tool-start/tool-result events (planned for AgentRuntime SPI).
        let tc = toolCalls.value.find(t => t.name === name && !t.done)
        if (!tc) {
          // Fallback: if no unfinished match by name, match any unfinished call
          tc = toolCalls.value.find(t => !t.done)
        }
        if (tc) {
          tc.result = typeof result === 'string' ? result : JSON.stringify(result)
          tc.done = true
          toolCalls.value = [...toolCalls.value]
        }
        break
      }
      case 'presence': {
        // Two wire shapes: classroom presence frames ship the current room
        // headcount; Room Protocol presence frames are member join/leave
        // deltas tracked in a local set seeded by join_ack.
        const count = msg.count
        if (typeof count === 'number') {
          presenceCount.value = count
        } else if (typeof msg.memberId === 'string' && msg.memberId) {
          if (msg.action === 'leave') {
            presenceMembers.delete(msg.memberId)
          } else {
            presenceMembers.add(msg.memberId)
          }
          presenceCount.value = presenceMembers.size
        }
        break
      }
      case 'join_ack': {
        // Room Protocol join acknowledgement seeds the member set. The wire
        // carries RoomMember objects ({id, metadata}); tolerate bare strings.
        if (Array.isArray(msg.members)) {
          presenceMembers = new Set(msg.members
            .map((m: unknown) => typeof m === 'string'
              ? m
              : (m as { id?: unknown })?.id)
            .filter((id): id is string => typeof id === 'string' && id.length > 0))
          presenceCount.value = presenceMembers.size
        }
        break
      }
      case 'message': {
        // Room Protocol broadcast from another member: {from, data, id}.
        // Rendered as an author-prefixed bubble like broadcast mode.
        const from = typeof msg.from === 'string' && msg.from ? `${msg.from}: ` : ''
        if (typeof msg.data === 'string' && msg.data) {
          appendBroadcastMessage(from + msg.data)
        }
        if (typeof msg.id === 'number') {
          lastRoomMessageId = msg.id
        }
        break
      }
      case 'agent-step': {
        // Multi-agent coordination progress — surfaced as the live fleet
        // activity strip while the coordinator fans out. Wire shape is the
        // serialized AiEvent.AgentStep record: {stepName, description,
        // data: {agent, skill, ...}} — the agent name lives one level down.
        const data = msg.data as Record<string, unknown> | undefined
        const inner = data?.data as Record<string, unknown> | undefined
        const agent = inner?.agent
        const stepName = data?.stepName
        if (typeof agent === 'string' && agent && typeof stepName === 'string') {
          agentSteps.value = { ...agentSteps.value, [agent]: stepName }
        }
        break
      }
      case 'plan-update':
        // The agent's plan changed (write_todos / native plan bridge). Full-
        // list replace semantics — the event carries the whole plan, rendered
        // live by the Workspace tab via the shared workspaceStore.
        recordPlanUpdate(msg.data)
        break
      case 'approval-required': {
        const data = msg.data as Record<string, unknown> | undefined
        if (!data) break
        const approvalId = data.approvalId as string
        const toolName = (data.toolName as string) ?? ''
        const args = (data.arguments as Record<string, unknown>) ?? {}
        const message = (data.message as string) ?? `Approve running ${toolName}?`
        const expiresIn = (data.expiresIn as number) ?? 300
        if (!approvalId) break
        const approval: PendingApproval = {
          approvalId,
          message,
          expiresIn,
          requestedAt: Date.now(),
        }
        // Attach to the most recent unfinished tool call with the same name
        // (tool-start arrives before approval-required in the BuiltIn runtime
        // path). Fall back to creating a new card so framework runtimes that
        // skip tool-start still surface an approve/deny prompt.
        const existing = [...toolCalls.value].reverse().find(t => t.name === toolName && !t.done && !t.approval)
        if (existing) {
          existing.approval = approval
          toolCalls.value = [...toolCalls.value]
        } else {
          const id = `${++toolCallCounter}-${toolName || 'approval'}`
          toolCalls.value = [...toolCalls.value, {
            id, name: toolName || 'approval', args, done: false, approval,
          }]
        }
        break
      }
      case 'entity-start':
        // Structured output starting — suppress raw JSON text
        break
      case 'structured-field':
        // Progressive field parsing — ignore (entity-complete is authoritative)
        break
      case 'entity-complete': {
        // Structured output: replace raw JSON with formatted entity fields.
        // Must replace the object reference (not just mutate .content) for Vue reactivity.
        const data = msg.data as Record<string, unknown>
        const entity = data?.entity as Record<string, unknown>
        const typeName = (data?.typeName ?? 'Entity') as string
        if (entity && currentAssistantMessage) {
          const lines = [`**${typeName}**\n`]
          for (const [key, val] of Object.entries(entity)) {
            const display = typeof val === 'string' && val.length > 200
              ? val.substring(0, 200) + '...'
              : String(val)
            lines.push(`- **${key}:** ${display}`)
          }
          // Cancel any pending throttled update
          if (reactivityTimer) {
            clearTimeout(reactivityTimer)
            reactivityTimer = null
          }
          // Replace the object reference so Vue detects the change
          const updated = { ...currentAssistantMessage, content: lines.join('\n') }
          const idx = messages.value.findIndex(m => m.id === updated.id)
          if (idx >= 0) {
            messages.value[idx] = updated
          }
          currentAssistantMessage = updated
          messages.value = [...messages.value]
        }
        break
      }
      default:
        // Unknown type — try to use data as text
        if (typeof msg.data === 'string' && msg.data) {
          appendToAssistant(msg.data)
        }
    }
  }

  function appendBroadcastMessage(text: string) {
    // A discrete broadcast message: always its own bubble, never merged into
    // an in-flight streaming reply (leaves currentAssistantMessage untouched).
    if (!text) return
    messages.value = [...messages.value, {
      id: crypto.randomUUID(),
      role: 'assistant',
      content: text,
      timestamp: Date.now(),
    }]
  }

  function appendToAssistant(text: string) {
    if (!text) return
    if (!currentAssistantMessage) {
      currentAssistantMessage = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: text,
        timestamp: Date.now(),
      }
      messages.value = [...messages.value, currentAssistantMessage]
    } else {
      currentAssistantMessage.content += text
      // Throttle Vue reactivity — rapid chunks fire hundreds of updates.
      // Replace the message object so Vue detects the change (shallow comparison).
      if (!reactivityTimer) {
        reactivityTimer = setTimeout(() => {
          reactivityTimer = null
          if (currentAssistantMessage) {
            const updated = { ...currentAssistantMessage }
            const idx = messages.value.findIndex(m => m.id === updated.id)
            if (idx >= 0) {
              messages.value[idx] = updated
              currentAssistantMessage = updated
            }
            messages.value = [...messages.value]
          }
        }, 50)
      }
    }
  }

  /**
   * Commit whatever `appendToAssistant` has buffered into the message object
   * but not yet published to Vue.
   *
   * Chunks are accumulated on the message object immediately and published on a
   * 50ms throttle. Anything appended after the last timer fired is therefore
   * live on the object but absent from the DOM, and the throttle callback bails
   * on `if (currentAssistantMessage)` — so once the stream ends and that field
   * is nulled, the pending text is never rendered at all. A deterministic
   * handler that calls `session.send()` three times and completes shows only
   * the first chunk; a real model stream silently loses its last ≤50ms of
   * tokens, which reads as the model trailing off mid-sentence.
   */
  function flushAssistant() {
    if (reactivityTimer) {
      clearTimeout(reactivityTimer)
      reactivityTimer = null
    }
    if (!currentAssistantMessage) return
    const updated = { ...currentAssistantMessage }
    const idx = messages.value.findIndex(m => m.id === updated.id)
    if (idx >= 0) {
      messages.value[idx] = updated
      currentAssistantMessage = updated
    }
    messages.value = [...messages.value]
  }

  function finalizeAssistant() {
    flushAssistant()
    // If content looks like JSON (structured output from responseAs), format it
    if (currentAssistantMessage) {
      const raw = currentAssistantMessage.content
      const content = raw.trim()
        .replace(/^```json\s*\n?/, '').replace(/\n?```\s*$/, '').trim()
      if (content.startsWith('{') && content.endsWith('}')) {
        try {
          const entity = JSON.parse(content) as Record<string, unknown>
          const lines: string[] = []
          for (const [key, val] of Object.entries(entity)) {
            const display = typeof val === 'string' && val.length > 200
              ? val.substring(0, 200) + '...'
              : String(val)
            lines.push(`- **${key}:** ${display}`)
          }
          currentAssistantMessage.content = lines.join('\n')
          messages.value = [...messages.value]
        } catch { /* not valid JSON, keep as-is */ }
      }
    }
    currentAssistantMessage = null
    isStreaming.value = false
    // The prompt was answered (or its connection closed): a later refused send
    // is not this prompt.
    lastSentUserMessageId = null
  }

  async function connect() {
    try {
      transport = await createChatTransport(transportName, {
        endpoint,
        isBroadcast: () => broadcastMode.value,
        status,
        maxReconnectOnClose: MAX_RECONNECT_ON_CLOSE,
        webTransport,
        ...(transportName === 'atmosphere' ? { offlineQueue: offline.queue } : {}),
        ...(room ? {
          rooms: {
            room,
            memberId: `console-${Math.random().toString(16).slice(2, 6)}`,
            sinceId: () => lastRoomMessageId,
          },
        } : {}),
      }, {
        onOpen: () => {
          isConnected.value = true
          everConnected.value = true
          connectionState.value = 'connected'
          reconnectAttempts = 0
        },
        onClose: () => {
          isConnected.value = false
          connectionState.value = 'disconnected'
          finalizeAssistant()
        },
        onError: (error?: Error) => {
          if (error?.name === SEND_ERROR_NAME) {
            // One prompt was refused; the connection itself is still up.
            markLastPromptUndelivered()
            return
          }
          connectionState.value = 'error'
        },
        onReconnect: () => {
          reconnectAttempts += 1
          // Stop advertising "reconnecting" after the client has exhausted
          // its quota of retries — atmosphere.js will silently give up at
          // that point, so surface the terminal state explicitly.
          connectionState.value = reconnectAttempts >= MAX_RECONNECT_ON_CLOSE
            ? 'disconnected'
            : 'reconnecting'
        },
        onReopen: () => {
          isConnected.value = true
          connectionState.value = 'connected'
          reconnectAttempts = 0
        },
        onClientTimeout: () => {
          // Heartbeat watchdog tripped — the transport will reconnect.
          connectionState.value = 'reconnecting'
        },
        onFailureToReconnect: () => {
          // Reconnect quota exhausted. Make this visible in the operator UI.
          isConnected.value = false
          connectionState.value = 'disconnected'
        },
        onEvent: handleStreamingEvent,
        onRawText: appendToAssistant,
      })
      await transport.connect()
      rememberOwnMemberId()
    } catch (e) {
      // Transport construction/connect failed terminally (e.g. the reported
      // transport's adapter isn't in this build). Fail loud, not blank.
      console.error('[console] chat transport failed:', e)
      isConnected.value = false
      connectionState.value = 'error'
    }
  }

  function send(text: string) {
    if (!transport || !text.trim()) return

    // Disconnected sends are only meaningful when the offline queue will
    // catch them (Atmosphere transport, after a first connect).
    const offlineSend = !isConnected.value
    if (offlineSend && !canQueueOffline.value) return

    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text.trim() + (offlineSend ? QUEUED_SUFFIX : ''),
      timestamp: Date.now(),
    }
    if (offlineSend) queuedBubbleIds.add(userMessage.id)
    lastSentUserMessageId = userMessage.id
    messages.value = [...messages.value, userMessage]
    toolCalls.value = []
    agentSteps.value = {}
    governanceFeedback.value = null
    stats.value = null
    streamStartedAt = Date.now()
    streamTokenCount = 0
    // A new prompt starts a stream the user wants to see, even if a discarded
    // one never delivered its terminal event. Its session is not known yet, so
    // a Clear before its first token discards whatever streams next.
    discardInFlightStream = false
    lastStreamSessionId = null

    // Broadcast and Room Protocol sends have no streamed AI reply — the room
    // echo renders as its own bubble ({type:'message'} or the event-less
    // frame). The wire envelope itself is the transport's dialect concern.
    // Offline sends stream nothing until the queue drains on reopen.
    isStreaming.value = !broadcastMode.value && !room && !offlineSend
    rememberOwnMemberId()
    transport.send(text.trim())
  }

  function markLastPromptUndelivered() {
    const id = lastSentUserMessageId
    // A reply already under way means the prompt arrived: the refused send was
    // another frame (an approval), and the stream is left alone.
    if (!id || currentAssistantMessage !== null || toolCalls.value.length > 0) {
      console.warn('[console] a message sent during a reply was not delivered')
      return
    }
    lastSentUserMessageId = null
    messages.value = messages.value.map(m => m.id === id && !m.content.endsWith(UNDELIVERED_SUFFIX)
      ? { ...m, content: m.content + UNDELIVERED_SUFFIX }
      : m)
    // No reply will stream for it: stop waiting so the input is enabled again.
    finalizeAssistant()
  }

  function respondToApproval(approvalId: string, approved: boolean) {
    if (!transport || !approvalId) return
    // Clear approval state locally so the UI collapses the buttons
    // immediately — the server will follow up with tool-result.
    const tc = toolCalls.value.find(t => t.approval?.approvalId === approvalId)
    if (tc) {
      tc.approval = undefined
      toolCalls.value = [...toolCalls.value]
    }
    transport.sendControl(`/__approval/${approvalId}/${approved ? 'approve' : 'deny'}`)
  }

  function clearMessages() {
    // A reply still streaming belongs to the conversation being cleared —
    // without the discard flag its late deltas would resurrect an assistant
    // bubble and the empty state would never return.
    if (isStreaming.value) {
      discardInFlightStream = true
      discardSessionId = lastStreamSessionId
      isStreaming.value = false
    }
    messages.value = []
    toolCalls.value = []
    agentSteps.value = {}
    governanceFeedback.value = null
    stats.value = null
    currentAssistantMessage = null
    // The suffixed bubbles are gone; queued sends themselves stay queued.
    queuedBubbleIds.clear()
    // The Workspace tab's live plan belongs to the conversation being
    // cleared — keeping it would render the previous conversation's plan
    // against the new chat.
    resetLivePlan()
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    unsubscribeStatus()
    transport?.disconnect()
    transport = null
  })

  return {
    messages,
    toolCalls,
    isConnected,
    isStreaming,
    connectionState,
    connectionStatus,
    send,
    clearMessages,
    respondToApproval,
    stats,
    routing,
    governanceFeedback,
    agentSteps,
    presenceCount,
    offlineSize,
    canQueueOffline,
  }
}
