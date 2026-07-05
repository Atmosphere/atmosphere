<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useAtmosphereChat } from '../composables/useAtmosphereChat'
import ChatMessage from './ChatMessage.vue'
import ChatInput from './ChatInput.vue'
import ConnectionStatus from './ConnectionStatus.vue'
import ToolCard from './ToolCard.vue'

const props = defineProps<{
  endpoint?: string
  // 'ai' (default) for @AiEndpoint / @Agent / @Coordinator endpoints, 'broadcast'
  // for @ManagedService chats. Server picks this in /api/console/info via
  // AtmosphereConsoleInfoEndpoint#detectMode by inspecting the registered
  // handler class — keeps frontend copy honest for samples like
  // spring-boot-mcp-server / spring-boot-otel-chat where there is no AI
  // assistant on the other side, just other connected peers.
  mode?: 'ai' | 'broadcast'
}>()

const { messages, toolCalls, isConnected, isStreaming, connectionState, connectionStatus, send, clearMessages, respondToApproval, stats } = useAtmosphereChat(props.endpoint, props.mode)
const messagesContainer = ref<HTMLElement | null>(null)

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

watch([messages, toolCalls], () => {
  scrollToBottom()
}, { deep: true })

function handleSend(text: string) {
  send(text)
  scrollToBottom()
}
</script>

<template>
  <div class="chat-container" data-testid="chat-layout">
    <div class="chat-toolbar">
      <ConnectionStatus :state="connectionState" :status="connectionStatus" />
      <button class="clear-btn" @click="clearMessages" title="Clear messages">
        Clear
      </button>
    </div>
    <div ref="messagesContainer" class="messages-area" data-testid="message-list">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
        </div>
        <p class="empty-title">{{ mode === 'broadcast' ? 'Start a broadcast' : 'Start a conversation' }}</p>
        <p class="empty-subtitle">
          <!--
            The broadcast copy intentionally avoids the substring "connected"
            because cli-runtime.spec.ts asserts on the Connection status badge
            via getByText('Connected') in strict mode (case-insensitive
            partial match) — duplicating the substring here would resolve to
            two elements and break the spec.
          -->
          {{ mode === 'broadcast'
            ? 'Type a message below — every subscriber on this endpoint will receive it.'
            : 'Type a message below to begin chatting with the AI assistant.' }}
        </p>
      </div>
      <template v-for="(msg, idx) in messages" :key="msg.id">
        <ChatMessage :message="msg" />
        <!-- Show tool cards after user message, before assistant response -->
        <div v-if="msg.role === 'user' && toolCalls.length > 0 && (idx === messages.length - 1 || messages[idx + 1]?.role === 'assistant')" class="tool-section" data-testid="tool-activity">
          <div class="tool-section-label">Agent Collaboration</div>
          <ToolCard
            v-for="tc in toolCalls"
            :key="tc.id"
            :tool="tc"
            @approve="respondToApproval($event, true)"
            @deny="respondToApproval($event, false)"
          />
        </div>
      </template>
      <div v-if="isStreaming" class="streaming-indicator">
        <span class="dot"></span><span class="dot"></span><span class="dot"></span>
      </div>
      <div v-if="stats && !isStreaming" class="session-stats" data-testid="session-stats">
        <span>{{ stats.totalStreamingTexts }} tokens</span>
        <span class="sep">&middot;</span>
        <span>{{ stats.elapsedMs }}ms</span>
        <span class="sep">&middot;</span>
        <span>{{ stats.streamingTextsPerSecond.toFixed(1) }} tok/s</span>
      </div>
    </div>
    <ChatInput :disabled="!isConnected" :is-streaming="isStreaming" @send="handleSend" />
  </div>
</template>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 1.5rem;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-surface);
}

.clear-btn {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  background: none;
  border: 1px solid var(--border-color);
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.clear-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 1.5rem;
  scroll-behavior: smooth;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-tertiary);
  text-align: center;
  padding: 2rem;
}

.empty-icon {
  margin-bottom: 1rem;
  opacity: 0.4;
}

.empty-title {
  font-size: 1.125rem;
  font-weight: 500;
  color: var(--text-secondary);
  margin: 0 0 0.5rem 0;
}

.empty-subtitle {
  font-size: 0.875rem;
  margin: 0;
  max-width: 320px;
  line-height: 1.5;
}

.tool-section {
  margin: 0.5rem 0 1rem 2.75rem;
  max-width: 42rem;
}

.tool-section-label {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 0.5rem;
}

.streaming-indicator {
  display: flex;
  gap: 4px;
  padding: 0.5rem 1rem;
}

.session-stats {
  display: flex;
  gap: 0.4rem;
  padding: 0.375rem 1rem 0.5rem 2.75rem;
  font-size: 0.6875rem;
  color: var(--text-tertiary);
  font-family: 'SF Mono', 'Fira Code', monospace;
  letter-spacing: 0.02em;
}

.session-stats .sep {
  opacity: 0.5;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-tertiary);
  animation: bounce 1.4s infinite both;
}

.dot:nth-child(2) { animation-delay: 0.16s; }
.dot:nth-child(3) { animation-delay: 0.32s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}
</style>
