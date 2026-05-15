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

import { ref, watch, type Ref } from 'vue';
import type { AtmosphereRequest } from '../../types';
import type { SendOptions } from '../../streaming/types';
import { useStreaming, type VueStreamingLifecycle } from './useStreaming';
import { Atmosphere } from '../../core/atmosphere';

export type ChatRole = 'system' | 'user' | 'assistant' | 'tool';
export type ChatMessageStatus = 'submitted' | 'streaming' | 'complete' | 'error';

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  status?: ChatMessageStatus;
  metadata?: Record<string, unknown>;
}

export type ChatInputMessage = string | Omit<ChatMessage, 'id'> | ChatMessage;

export interface UseChatOptions extends VueStreamingLifecycle {
  request: AtmosphereRequest;
  instance?: Atmosphere;
  initialMessages?: ChatMessage[];
  sendOptions?: SendOptions;
  generateId?: () => string;
}

let nextId = 0;
const defaultGenerateId = () => `chat-${Date.now()}-${nextId++}`;

export function useChat(options: UseChatOptions) {
  const {
    request,
    instance,
    initialMessages = [],
    sendOptions,
    generateId = defaultGenerateId,
    ...lifecycle
  } = options;

  const messages: Ref<ChatMessage[]> = ref(initialMessages);
  const input = ref('');
  const activeAssistantId: Ref<string | null> = ref(null);
  const streaming = useStreaming(request, instance, lifecycle);

  const normalizeMessage = (message: ChatInputMessage): ChatMessage => {
    if (typeof message === 'string') {
      return { id: generateId(), role: 'user', content: message, status: 'complete' };
    }
    return 'id' in message ? message : { ...message, id: generateId() };
  };

  const append = (message: ChatInputMessage, optionsOverride?: SendOptions) => {
    const userMessage = normalizeMessage(message);
    if (userMessage.role !== 'user') {
      throw new Error('useChat.append currently sends user messages only');
    }
    const assistantMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: '',
      status: 'submitted',
    };
    activeAssistantId.value = assistantMessage.id;
    streaming.reset();
    messages.value = [...messages.value, userMessage, assistantMessage];
    streaming.send(userMessage.content, optionsOverride ?? sendOptions);
  };

  const syncAssistant = () => {
    const assistantId = activeAssistantId.value;
    if (!assistantId) return;
    messages.value = messages.value.map((message) => {
      if (message.id !== assistantId) return message;
      if (streaming.error.value) return { ...message, status: 'error' };
      return {
        ...message,
        content: streaming.fullText.value,
        status: streaming.isStreaming.value ? 'streaming' : 'complete',
      };
    });
    if (!streaming.isStreaming.value && (streaming.fullText.value || streaming.error.value)) {
      activeAssistantId.value = null;
    }
  };

  const stop = () => {
    streaming.close();
    activeAssistantId.value = null;
  };

  const reset = () => {
    activeAssistantId.value = null;
    streaming.reset();
    messages.value = initialMessages;
  };

  const reload = (optionsOverride?: SendOptions) => {
    const lastUserMessage = [...messages.value].reverse().find((message) => message.role === 'user');
    if (!lastUserMessage) return;
    messages.value = messages.value.slice(0, messages.value.findIndex((m) => m.id === lastUserMessage.id));
    append(lastUserMessage, optionsOverride);
  };

  const handleSubmit = (event?: { preventDefault?: () => void }) => {
    event?.preventDefault?.();
    const prompt = input.value.trim();
    if (!prompt) return;
    input.value = '';
    append(prompt);
  };

  watch([streaming.fullText, streaming.isStreaming, streaming.error], syncAssistant);

  return {
    messages,
    input,
    append,
    handleSubmit,
    reload,
    stop,
    reset,
    isLoading: streaming.isStreaming,
    error: streaming.error,
    progress: streaming.progress,
    metadata: streaming.metadata,
    stats: streaming.stats,
    routing: streaming.routing,
    aiEvents: streaming.aiEvents,
    connectionState: streaming.connectionState,
    isReconnecting: streaming.isReconnecting,
    connectionStatus: streaming.connectionStatus,
  };
}
