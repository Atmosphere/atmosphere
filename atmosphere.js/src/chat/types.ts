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
  default: { gradient: ['#667eea', '#764ba2'], accent: '#667eea' },
  ai: { gradient: ['#667eea', '#764ba2'], accent: '#667eea', dark: true },
  langchain4j: { gradient: ['#e040fb', '#7c4dff'], accent: '#e040fb', dark: true },
  embabel: { gradient: ['#00b4d8', '#0077b6'], accent: '#00b4d8', dark: true },
  mcp: { gradient: ['#667eea', '#764ba2'], accent: '#667eea' },
};
