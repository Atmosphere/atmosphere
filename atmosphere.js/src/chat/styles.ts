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

import type { ChatTheme } from './types';
import type { CSSProperties } from 'react';

const STATUS_COLORS: Record<string, string> = {
  connected: '#28a745',
  reconnecting: '#ffc107',
  error: '#dc3545',
};

export function containerStyle(dark?: boolean): CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    height: '100vh',
    maxWidth: 800,
    margin: '0 auto',
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
    background: dark ? '#1a1a2e' : '#f5f6fa',
    color: dark ? '#e0e0e0' : '#333',
  };
}

export function headerStyle(theme: ChatTheme): CSSProperties {
  return {
    background: `linear-gradient(135deg, ${theme.gradient[0]}, ${theme.gradient[1]})`,
    color: '#fff',
    padding: '20px 24px',
    textAlign: 'center',
  };
}

export const titleStyle: CSSProperties = {
  margin: 0,
  fontSize: 22,
  fontWeight: 700,
};

export const subtitleStyle: CSSProperties = {
  margin: '4px 0 0',
  fontSize: 13,
  opacity: 0.85,
};

export function statusBarStyle(): CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    padding: '6px 0',
    fontSize: 12,
  };
}

export function statusDotStyle(state: string): CSSProperties {
  return {
    width: 8,
    height: 8,
    borderRadius: '50%',
    background: STATUS_COLORS[state] ?? '#adb5bd',
  };
}

export function messageAreaStyle(dark?: boolean): CSSProperties {
  return {
    flex: 1,
    overflowY: 'auto',
    padding: '12px 16px',
    display: 'flex',
    flexDirection: 'column',
    gap: 8,
    background: dark ? '#16213e' : '#f5f6fa',
  };
}

export function bubbleStyle(isOwn: boolean, theme: ChatTheme): CSSProperties {
  if (theme.dark) {
    return isOwn
      ? {
          alignSelf: 'flex-end',
          background: `linear-gradient(135deg, ${theme.gradient[0]}, ${theme.gradient[1]})`,
          color: '#fff',
          padding: '10px 14px',
          borderRadius: '16px 16px 4px 16px',
          maxWidth: '85%',
          wordBreak: 'break-word',
        }
      : {
          alignSelf: 'flex-start',
          background: '#16213e',
          border: '1px solid #2a2a4a',
          color: '#e0e0e0',
          padding: '10px 14px',
          borderRadius: '16px 16px 16px 4px',
          maxWidth: '85%',
          wordBreak: 'break-word',
        };
  }
  return isOwn
    ? {
        alignSelf: 'flex-end',
        background: '#e7f3ff',
        borderLeft: `3px solid ${theme.accent}`,
        padding: '10px 14px',
        borderRadius: '12px 12px 4px 12px',
        maxWidth: '85%',
        wordBreak: 'break-word',
      }
    : {
        alignSelf: 'flex-start',
        background: '#fff',
        padding: '10px 14px',
        borderRadius: '12px 12px 12px 4px',
        maxWidth: '85%',
        boxShadow: '0 1px 2px rgba(0,0,0,.06)',
        wordBreak: 'break-word',
      };
}

export const authorStyle: CSSProperties = {
  fontWeight: 600,
  fontSize: 13,
  marginBottom: 2,
};

export const timeStyle: CSSProperties = {
  fontSize: 11,
  opacity: 0.5,
  marginLeft: 8,
};

export function inputBarStyle(dark?: boolean): CSSProperties {
  return {
    display: 'flex',
    gap: 8,
    padding: '12px 16px',
    borderTop: `1px solid ${dark ? '#2a2a4a' : '#e9ecef'}`,
    background: dark ? '#1a1a2e' : '#fff',
  };
}

export function inputStyle(theme: ChatTheme): CSSProperties {
  return {
    flex: 1,
    padding: '10px 14px',
    border: `1px solid ${theme.dark ? '#2a2a4a' : '#e9ecef'}`,
    borderRadius: 20,
    fontSize: 14,
    outline: 'none',
    background: theme.dark ? '#16213e' : '#fff',
    color: theme.dark ? '#e0e0e0' : '#333',
  };
}

export function sendButtonStyle(theme: ChatTheme): CSSProperties {
  return {
    padding: '10px 20px',
    background: `linear-gradient(135deg, ${theme.gradient[0]}, ${theme.gradient[1]})`,
    color: '#fff',
    border: 'none',
    borderRadius: 20,
    fontWeight: 600,
    cursor: 'pointer',
    fontSize: 14,
  };
}

export const systemStyle: CSSProperties = {
  textAlign: 'center',
  color: '#28a745',
  fontSize: 13,
  padding: '6px 12px',
  background: '#d4edda',
  borderRadius: 8,
  alignSelf: 'center',
};

export const welcomeStyle: CSSProperties = {
  textAlign: 'center',
  padding: 40,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 12,
};

export function joinInputStyle(theme: ChatTheme): CSSProperties {
  return {
    padding: '12px 16px',
    border: `2px solid ${theme.accent}`,
    borderRadius: 8,
    fontSize: 16,
    width: 240,
    textAlign: 'center',
    outline: 'none',
  };
}

export function joinButtonStyle(theme: ChatTheme): CSSProperties {
  return {
    ...sendButtonStyle(theme),
    padding: '12px 32px',
    fontSize: 16,
    borderRadius: 8,
  };
}

export const cursorStyle: CSSProperties = {
  display: 'inline-block',
  width: 8,
  height: 18,
  background: '#667eea',
  marginLeft: 2,
  verticalAlign: 'text-bottom',
  animation: 'blink 1s step-end infinite',
};

export const progressStyle: CSSProperties = {
  textAlign: 'center',
  color: '#ffc107',
  fontSize: 13,
  padding: '6px 12px',
  alignSelf: 'center',
};

export const errorStyle: CSSProperties = {
  textAlign: 'center',
  color: '#dc3545',
  fontSize: 13,
  padding: '6px 12px',
  background: 'rgba(220,53,69,.1)',
  borderRadius: 8,
  alignSelf: 'center',
};
