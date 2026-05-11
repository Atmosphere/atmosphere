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

import { createElement } from 'react';
import type { CSSProperties } from 'react';
import type { ConnectionPhase, ConnectionStatusSnapshot } from '../../resilience';

/**
 * Default human-readable labels for each phase. Override via the `labels`
 * prop to localize or rebrand.
 */
export const DEFAULT_LABELS: Record<ConnectionPhase, string> = {
  idle: 'Idle',
  connecting: 'Connecting…',
  open: 'Connected',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
  lost: 'Connection lost',
};

/**
 * Default dot colors for each phase. Override via the `colors` prop.
 */
export const DEFAULT_COLORS: Record<ConnectionPhase, string> = {
  idle: '#9CA3AF',         // gray
  connecting: '#F59E0B',   // amber
  open: '#10B981',         // emerald
  reconnecting: '#F59E0B', // amber
  closed: '#6B7280',       // slate
  lost: '#EF4444',         // red
};

export interface ConnectionStatusBadgeProps {
  /** Reactive status snapshot from {@link useAtmosphere} or {@link useStreaming}. */
  status: ConnectionStatusSnapshot;
  /** Override phase labels (e.g. for localization). */
  labels?: Partial<Record<ConnectionPhase, string>>;
  /** Override dot colors. */
  colors?: Partial<Record<ConnectionPhase, string>>;
  /**
   * When true (default), append " · <transport>" and a fallback marker
   * to the label so operators can see which transport is in use.
   */
  showTransport?: boolean;
  /** Extra container style. */
  style?: CSSProperties;
  /** Optional className for CSS-based styling. */
  className?: string;
}

const containerStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '0.4rem',
  fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  fontSize: '0.875rem',
  lineHeight: '1.25',
  padding: '0.25rem 0.5rem',
  borderRadius: '999px',
  background: 'rgba(0, 0, 0, 0.04)',
  color: '#111827',
};

function dotStyle(color: string, animated: boolean): CSSProperties {
  return {
    display: 'inline-block',
    width: '0.5rem',
    height: '0.5rem',
    borderRadius: '50%',
    background: color,
    boxShadow: animated ? `0 0 0 0 ${color}` : undefined,
    animation: animated ? 'atmosphere-pulse 1.2s infinite' : undefined,
  };
}

const KEYFRAMES = `@keyframes atmosphere-pulse {
  0%   { box-shadow: 0 0 0 0 rgba(245, 158, 11, 0.55); }
  70%  { box-shadow: 0 0 0 6px rgba(245, 158, 11, 0); }
  100% { box-shadow: 0 0 0 0 rgba(245, 158, 11, 0); }
}`;

/**
 * Compact connection-status pill. Shows a colored dot + label and
 * (optionally) the active transport. Pulses while reconnecting.
 *
 * ```tsx
 * const { connectionStatus } = useAtmosphere({ request });
 * return <ConnectionStatusBadge status={connectionStatus} />;
 * ```
 */
export function ConnectionStatusBadge(props: ConnectionStatusBadgeProps) {
  const { status, labels, colors, showTransport = true, style, className } = props;
  const phase = status.phase;
  const label = (labels?.[phase] ?? DEFAULT_LABELS[phase]) +
    (showTransport && phase !== 'idle' ? ` · ${status.transport}` : '') +
    (showTransport && status.viaFallback && phase === 'open' ? ' (fallback)' : '');
  const color = colors?.[phase] ?? DEFAULT_COLORS[phase];
  const animated = phase === 'reconnecting' || phase === 'connecting';

  return createElement(
    'span',
    {
      'data-testid': 'atmosphere-connection-status',
      'data-phase': phase,
      'data-event': status.lastEvent ?? '',
      'data-transport': status.transport,
      style: { ...containerStyle, ...style },
      className,
      title: status.lastError ? `Last error: ${status.lastError.message}` : undefined,
    },
    // Inline keyframes once so the badge is self-contained.
    createElement('style', { dangerouslySetInnerHTML: { __html: KEYFRAMES } }),
    createElement('span', {
      'data-testid': 'atmosphere-connection-status-dot',
      style: dotStyle(color, animated),
    }),
    createElement('span', { 'data-testid': 'atmosphere-connection-status-label' }, label),
  );
}
