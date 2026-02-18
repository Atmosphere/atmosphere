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

/**
 * Wire protocol message types sent by the server-side
 * {@code DefaultStreamingSession}.
 */
export type StreamingMessageType = 'token' | 'complete' | 'error' | 'progress' | 'metadata';

/**
 * A single streaming wire-protocol message as sent by the Java
 * {@code DefaultStreamingSession}.
 *
 * Example:
 * ```json
 * {"type":"token","data":"Hello","sessionId":"abc-123","seq":1}
 * ```
 */
export interface StreamingMessage {
  type: StreamingMessageType;
  data?: string;
  key?: string;
  value?: unknown;
  sessionId: string;
  seq: number;
}

/**
 * Event handlers for a streaming session.
 */
export interface StreamingHandlers {
  /** Called for each token received from the LLM. */
  onToken?: (token: string, seq: number) => void;
  /** Called when the server signals progress (e.g. "Thinking…"). */
  onProgress?: (message: string, seq: number) => void;
  /** Called when the stream completes, with an optional summary. */
  onComplete?: (summary?: string) => void;
  /** Called on error. */
  onError?: (error: string) => void;
  /** Called on metadata events (model name, token count, etc.). */
  onMetadata?: (key: string, value: unknown) => void;
}

/**
 * A handle to an active streaming session.
 */
export interface StreamingHandle {
  /** The session ID assigned by the server. */
  readonly sessionId: string | null;
  /** Send a prompt/message to the server to start or continue streaming. */
  send(message: string | object): void;
  /** Close the streaming session. */
  close(): Promise<void>;
}
