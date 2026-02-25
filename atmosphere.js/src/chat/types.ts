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
 * Standard chat message shape used by all Atmosphere chat samples.
 */
export interface ChatMessage {
  author: string;
  message: string;
  time?: number;
}

/**
 * Theme configuration for chat UI components.
 */
export interface ChatTheme {
  /** Header gradient stops [from, to]. */
  gradient: [string, string];
  /** Accent color for focus rings, own-message highlights. */
  accent: string;
  /** Use dark theme (dark background, light text). */
  dark?: boolean;
}

/**
 * Built-in theme presets.
 */
export const themes: Record<string, ChatTheme> = {
  default: { gradient: ['#4a5a8a', '#5c3d7a'], accent: '#4a5a8a' },
  ai: { gradient: ['#4a5a8a', '#5c3d7a'], accent: '#4a5a8a', dark: true },
  langchain4j: { gradient: ['#7a3a8a', '#4a3a7a'], accent: '#7a3a8a', dark: true },
  embabel: { gradient: ['#2a7a8a', '#1a5a7a'], accent: '#2a7a8a', dark: true },
  mcp: { gradient: ['#4a5a8a', '#5c3d7a'], accent: '#4a5a8a' },
};
