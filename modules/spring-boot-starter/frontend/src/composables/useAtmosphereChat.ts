import { ref, onMounted, onUnmounted } from 'vue'
import { Atmosphere } from 'atmosphere.js'
import type { Subscription, AtmosphereResponse } from 'atmosphere.js'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

export function useAtmosphereChat(endpoint: string = '/atmosphere/ai-chat') {
  const messages = ref<ChatMessage[]>([])
  const isConnected = ref(false)
  const isStreaming = ref(false)
  const connectionState = ref<string>('disconnected')

  let atmosphere: Atmosphere | null = null
  let subscription: Subscription | null = null
  let currentAssistantMessage: ChatMessage | null = null

  function parseStreamingMessage(body: string) {
    // Messages can be batched with TrackMessageSizeInterceptor framing.
    // Each message is a JSON object. Try to parse each one.
    const lines = body.split('\n').filter(l => l.trim())
    for (const line of lines) {
      try {
        const msg = JSON.parse(line)
        handleStreamingEvent(msg)
      } catch {
        // If not JSON, treat as raw text chunk
        appendToAssistant(line)
      }
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
      default:
        // Unknown type — try to use data as text
        if (typeof msg.data === 'string' && msg.data) {
          appendToAssistant(msg.data)
        }
    }
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
      // Trigger reactivity
      messages.value = [...messages.value]
    }
  }

  function finalizeAssistant() {
    currentAssistantMessage = null
    isStreaming.value = false
  }

  async function connect() {
    atmosphere = new Atmosphere({ logLevel: 'warn' })

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
    isStreaming.value = true

    subscription.push(text.trim())
  }

  function clearMessages() {
    messages.value = []
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
    isConnected,
    isStreaming,
    connectionState,
    send,
    clearMessages,
  }
}
