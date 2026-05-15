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

import type { AtmosphereRequest } from '../../types';
import type { SendOptions } from '../../streaming/types';
import { Atmosphere } from '../../core/atmosphere';
import type { Readable } from './atmosphere';
import { createStreamingStore } from './streaming';

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

export interface ChatStoreState {
  messages: ChatMessage[];
  input: string;
  isLoading: boolean;
  error: string | null;
  progress: string | null;
  metadata: Record<string, unknown>;
  aiEvents: { event: string; data: Record<string, unknown> }[];
}

export interface CreateChatStoreOptions {
  request: AtmosphereRequest;
  instance?: Atmosphere;
  initialMessages?: ChatMessage[];
  sendOptions?: SendOptions;
  generateId?: () => string;
}

let nextId = 0;
const defaultGenerateId = () => `chat-${Date.now()}-${nextId++}`;

export function createChatStore(options: CreateChatStoreOptions) {
  const {
    request,
    instance,
    initialMessages = [],
    sendOptions,
    generateId = defaultGenerateId,
  } = options;
  const streaming = createStreamingStore(request, instance);
  const subscribers = new Set<(value: ChatStoreState) => void>();
  let activeAssistantId: string | null = null;
  let current: ChatStoreState = {
    messages: initialMessages,
    input: '',
    isLoading: false,
    error: null,
    progress: null,
    metadata: {},
    aiEvents: [],
  };

  const notify = () => { for (const subscriber of subscribers) subscriber(current); };
  const update = (partial: Partial<ChatStoreState>) => {
    current = { ...current, ...partial };
    notify();
  };

  const streamingUnsubscribe = streaming.store.subscribe((state) => {
    if (activeAssistantId) {
      current = {
        ...current,
        messages: current.messages.map((message) => {
          if (message.id !== activeAssistantId) return message;
          return {
            ...message,
            content: state.fullText,
            status: state.error ? 'error' : state.isStreaming ? 'streaming' : 'complete',
          };
        }),
      };
      if (!state.isStreaming && (state.fullText || state.error)) {
        activeAssistantId = null;
      }
    }
    update({
      isLoading: state.isStreaming,
      error: state.error,
      progress: state.progress,
      metadata: state.metadata,
      aiEvents: state.aiEvents,
    });
  });

  const store: Readable<ChatStoreState> = {
    subscribe(run) {
      subscribers.add(run);
      run(current);
      return () => subscribers.delete(run);
    },
  };

  const normalizeMessage = (message: ChatInputMessage): ChatMessage => {
    if (typeof message === 'string') {
      return { id: generateId(), role: 'user', content: message, status: 'complete' };
    }
    return 'id' in message ? message : { ...message, id: generateId() };
  };

  const append = (message: ChatInputMessage, optionsOverride?: SendOptions) => {
    const userMessage = normalizeMessage(message);
    if (userMessage.role !== 'user') {
      throw new Error('createChatStore.append currently sends user messages only');
    }
    const assistantMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: '',
      status: 'submitted',
    };
    activeAssistantId = assistantMessage.id;
    streaming.reset();
    update({ messages: [...current.messages, userMessage, assistantMessage] });
    streaming.send(userMessage.content, optionsOverride ?? sendOptions);
  };

  const setInput = (input: string) => update({ input });
  const handleSubmit = (event?: { preventDefault?: () => void }) => {
    event?.preventDefault?.();
    const prompt = current.input.trim();
    if (!prompt) return;
    setInput('');
    append(prompt);
  };
  const stop = () => {
    streaming.close();
    activeAssistantId = null;
  };
  const reset = () => {
    activeAssistantId = null;
    streaming.reset();
    update({ messages: initialMessages, input: '' });
  };
  const destroy = () => {
    streamingUnsubscribe();
    streaming.close();
    subscribers.clear();
  };

  return { store, append, setInput, handleSubmit, stop, reset, destroy };
}
