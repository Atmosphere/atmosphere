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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { SendOptions } from '../../streaming/types';
import {
  useStreamingRN,
  type StreamingSendResult,
  type UseStreamingRNOptions,
  type UseStreamingRNResult,
} from './useStreamingRN';

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

export interface UseChatRNOptions extends UseStreamingRNOptions {
  initialMessages?: ChatMessage[];
  sendOptions?: SendOptions;
  generateId?: () => string;
}

export interface UseChatRNResult {
  messages: ChatMessage[];
  input: string;
  setInput: (input: string) => void;
  /**
   * Append a user message and send it. Returns what happened to it so the
   * chat UI can mark the bubble queued or failed — the same contract as
   * {@link useStreamingRN}'s `send`, not a narrowing of it.
   */
  append: (message: ChatInputMessage, options?: SendOptions) => StreamingSendResult;
  /**
   * Submit the current `input`. Returns the send outcome, or `null` when
   * there was nothing to submit.
   */
  handleSubmit: () => StreamingSendResult | null;
  /** Re-send the last user message. Returns `null` when there is none. */
  reload: (options?: SendOptions) => StreamingSendResult | null;
  stop: () => void;
  reset: () => void;
  isLoading: boolean;
  /** @see UseStreamingRNResult#canSend */
  canSend: boolean;
  error: string | null;
  progress: string | null;
  metadata: Record<string, unknown>;
  stats: UseStreamingRNResult['stats'];
  routing: UseStreamingRNResult['routing'];
  aiEvents: UseStreamingRNResult['aiEvents'];
  connectionStatus: UseStreamingRNResult['connectionStatus'];
}

let nextId = 0;
const defaultGenerateId = () => `chat-${Date.now()}-${nextId++}`;

export function useChatRN(options: UseChatRNOptions): UseChatRNResult {
  const {
    initialMessages = [],
    sendOptions,
    generateId = defaultGenerateId,
    ...streamingOptions
  } = options;
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [input, setInput] = useState('');
  const activeAssistantIdRef = useRef<string | null>(null);
  const streaming = useStreamingRN(streamingOptions);

  useEffect(() => {
    const assistantId = activeAssistantIdRef.current;
    if (!assistantId || !streaming.fullText) return;
    setMessages((previous) => previous.map((message) =>
      message.id === assistantId
        ? { ...message, content: streaming.fullText, status: 'streaming' }
        : message));
  }, [streaming.fullText]);

  useEffect(() => {
    const assistantId = activeAssistantIdRef.current;
    if (!assistantId || streaming.isStreaming || (!streaming.fullText && !streaming.error)) return;
    setMessages((previous) => previous.map((message) =>
      message.id === assistantId && (message.status === 'submitted' || message.status === 'streaming')
        ? { ...message, status: streaming.error ? 'error' : 'complete' }
        : message));
    activeAssistantIdRef.current = null;
  }, [streaming.error, streaming.fullText, streaming.isStreaming]);

  const normalizeMessage = useCallback((message: ChatInputMessage): ChatMessage => {
    if (typeof message === 'string') {
      return { id: generateId(), role: 'user', content: message, status: 'complete' };
    }
    return 'id' in message ? message : { ...message, id: generateId() };
  }, [generateId]);

  const append = useCallback((message: ChatInputMessage, optionsOverride?: SendOptions) => {
    const userMessage = normalizeMessage(message);
    if (userMessage.role !== 'user') {
      throw new Error('useChatRN.append currently sends user messages only');
    }
    const assistantMessage: ChatMessage = {
      id: generateId(),
      role: 'assistant',
      content: '',
      status: 'submitted',
    };
    activeAssistantIdRef.current = assistantMessage.id;
    streaming.reset();
    setMessages((previous) => [...previous, userMessage, assistantMessage]);
    return streaming.send(userMessage.content, optionsOverride ?? sendOptions);
  }, [generateId, normalizeMessage, sendOptions, streaming]);

  const handleSubmit = useCallback((): StreamingSendResult | null => {
    const prompt = input.trim();
    if (!prompt) return null;
    setInput('');
    return append(prompt);
  }, [append, input]);

  const reload = useCallback((optionsOverride?: SendOptions): StreamingSendResult | null => {
    const lastUserMessage = [...messages].reverse().find((message) => message.role === 'user');
    if (!lastUserMessage) return null;
    setMessages((previous) => previous.slice(0, previous.findIndex((m) => m.id === lastUserMessage.id)));
    return append(lastUserMessage, optionsOverride);
  }, [append, messages]);

  const stop = useCallback(() => {
    streaming.close();
    activeAssistantIdRef.current = null;
  }, [streaming]);

  const reset = useCallback(() => {
    activeAssistantIdRef.current = null;
    streaming.reset();
    setMessages(initialMessages);
  }, [initialMessages, streaming]);

  return useMemo(() => ({
    messages,
    input,
    setInput,
    append,
    handleSubmit,
    reload,
    stop,
    reset,
    isLoading: streaming.isStreaming,
    canSend: streaming.canSend,
    error: streaming.error,
    progress: streaming.progress,
    metadata: streaming.metadata,
    stats: streaming.stats,
    routing: streaming.routing,
    aiEvents: streaming.aiEvents,
    connectionStatus: streaming.connectionStatus,
  }), [append, handleSubmit, input, messages, reload, reset, stop, streaming]);
}
