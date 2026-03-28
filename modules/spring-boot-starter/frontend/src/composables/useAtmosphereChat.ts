import { ref, onMounted, onUnmounted } from 'vue'
import { Atmosphere } from 'atmosphere.js'
import type { Subscription, AtmosphereResponse } from 'atmosphere.js'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

export interface ToolCall {
  id: string
  name: string
  args: Record<string, unknown>
  result?: string
  done: boolean
}

export function useAtmosphereChat(endpoint: string = '/atmosphere/ai-chat') {
  const messages = ref<ChatMessage[]>([])
  const toolCalls = ref<ToolCall[]>([])
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const connectionState = ref<string>('disconnected')

  let atmosphere: Atmosphere | null = null
  let subscription: Subscription | null = null
  let currentAssistantMessage: ChatMessage | null = null
  let toolCallCounter = 0
  let reactivityTimer: ReturnType<typeof setTimeout> | null = null

  function parseStreamingMessage(body: string) {
    // Messages arrive as JSON objects, possibly length-prefixed by TrackMessageSizeInterceptor.
    // Format can be: plain JSON, newline-separated JSON, or <len>|<json><len>|<json>...
    const trimmed = body.trim()
    if (!trimmed) return

    // Try length-prefixed format first: <digits>|<json>...
    if (/^\d+\|/.test(trimmed)) {
      let remaining = trimmed
      while (remaining.length > 0) {
        const m = remaining.match(/^(\d+)\|/)
        if (!m) break
        const len = parseInt(m[1], 10)
        const start = m[0].length
        const chunk = remaining.substring(start, start + len)
        remaining = remaining.substring(start + len)
        try { handleStreamingEvent(JSON.parse(chunk)) } catch { appendToAssistant(chunk) }
      }
      return
    }

    // Try as single JSON object
    try {
      handleStreamingEvent(JSON.parse(trimmed))
      return
    } catch { /* not single JSON */ }

    // Split by newlines (multiple JSON objects per frame)
    for (const line of trimmed.split('\n').filter(l => l.trim())) {
      try { handleStreamingEvent(JSON.parse(line)) } catch { appendToAssistant(line) }
    }
  }

  function handleStreamingEvent(msg: Record<string, unknown>) {
    const type = (msg.type as string) || (msg.event as string)

    switch (type) {
      case 'streaming-text':
      case 'text-delta':
        appendToAssistant(
          typeof msg.data === 'string'
            ? msg.data
            : typeof msg.data === 'object' && msg.data !== null
              ? (msg.data as Record<string, unknown>).text as string || ''
              : ''
        )
        break
      case 'complete':
        finalizeAssistant()
        break
      case 'error':
        appendToAssistant(`\n\n**Error:** ${msg.data || 'Unknown error'}`)
        finalizeAssistant()
        break
      case 'progress':
        // Ignored for chat display
        break
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

  function appendToAssistant(text: string) {
    if (!text) return
    console.log('[APPEND]', text.length, 'chars:', text.substring(0, 50))
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

  function finalizeAssistant() {
    // If content looks like JSON (structured output from responseAs), format it
    if (currentAssistantMessage) {
      const raw = currentAssistantMessage.content
      const content = raw.trim()
        .replace(/^```json\s*\n?/, '').replace(/\n?```\s*$/, '').trim()
      if (content.startsWith('{') && content.endsWith('}')) {
        try {
          const entity = JSON.parse(content) as Record<string, unknown>
          console.log('[FINALIZE] parsed OK, keys:', Object.keys(entity))
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
  }

  async function connect() {
    atmosphere = new Atmosphere({ logLevel: 'debug' })

    subscription = await atmosphere.subscribe<string>(
      {
        url: endpoint,
        transport: 'websocket',
        fallbackTransport: 'long-polling',
        reconnect: true,
        reconnectInterval: 3000,
        maxReconnectOnClose: 10,
        trackMessageLength: true,
        enableProtocol: true,
      },
      {
        open: () => {
          isConnected.value = true
          connectionState.value = 'connected'
        },
        close: () => {
          isConnected.value = false
          connectionState.value = 'disconnected'
          finalizeAssistant()
        },
        message: (response: AtmosphereResponse<string>) => {
          if (response.responseBody) {
            parseStreamingMessage(response.responseBody as string)
          }
        },
        error: () => {
          connectionState.value = 'error'
        },
        reconnect: () => {
          connectionState.value = 'reconnecting'
        },
        reopen: () => {
          isConnected.value = true
          connectionState.value = 'connected'
        },
      }
    )
  }

  function send(text: string) {
    if (!subscription || !text.trim()) return

    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text.trim(),
      timestamp: Date.now(),
    }
    messages.value = [...messages.value, userMessage]
    toolCalls.value = []
    isStreaming.value = true

    subscription.push(text.trim())
  }

  function clearMessages() {
    messages.value = []
    toolCalls.value = []
    currentAssistantMessage = null
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    if (atmosphere) {
      atmosphere.closeAll()
    }
  })

  return {
    messages,
    toolCalls,
    isConnected,
    isStreaming,
    connectionState,
    send,
    clearMessages,
  }
}
