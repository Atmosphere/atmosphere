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
import type { ReactElement } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import type { ViewStyle, TextStyle } from 'react-native';
import type { ConnectionPhase, ConnectionStatusSnapshot } from '../../resilience';

/** Default phase labels. Override via the `labels` prop to localize. */
export const DEFAULT_LABELS_RN: Record<ConnectionPhase, string> = {
  idle: 'Idle',
  connecting: 'Connecting…',
  open: 'Connected',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
  lost: 'Connection lost',
};

/** Default dot colors. Override via the `colors` prop. */
export const DEFAULT_COLORS_RN: Record<ConnectionPhase, string> = {
  idle: '#9CA3AF',
  connecting: '#F59E0B',
  open: '#10B981',
  reconnecting: '#F59E0B',
  closed: '#6B7280',
  lost: '#EF4444',
};

export interface ConnectionStatusBadgeRNProps {
  /** Reactive status snapshot from {@link useStreamingRN} or {@link useAtmosphereRN}. */
  status: ConnectionStatusSnapshot;
  /** Override phase labels (e.g. for localization). */
  labels?: Partial<Record<ConnectionPhase, string>>;
  /** Override dot colors. */
  colors?: Partial<Record<ConnectionPhase, string>>;
  /** When true (default), append ` · <transport>` and a fallback marker. */
  showTransport?: boolean;
  /** Extra container style applied after defaults. */
  style?: ViewStyle;
  /** Extra label text style applied after defaults. */
  textStyle?: TextStyle;
  /** Optional testID for end-to-end testing. */
  testID?: string;
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
    backgroundColor: 'rgba(0,0,0,0.05)',
    alignSelf: 'flex-start' as const,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  label: {
    fontSize: 13,
    color: '#111827',
  },
});

/**
 * React Native equivalent of {@link ConnectionStatusBadge}. Renders a
 * colored dot + label inside a rounded pill, using only React Native
 * primitives (no DOM).
 *
 * ```tsx
 * const { connectionStatus } = useStreamingRN({ request });
 * return <ConnectionStatusBadgeRN status={connectionStatus} />;
 * ```
 */
export function ConnectionStatusBadgeRN(props: ConnectionStatusBadgeRNProps): ReactElement {
  const { status, labels, colors, showTransport = true, style, textStyle, testID } = props;
  const phase = status.phase;
  const label = (labels?.[phase] ?? DEFAULT_LABELS_RN[phase]) +
    (showTransport && phase !== 'idle' ? ` · ${status.transport}` : '') +
    (showTransport && status.viaFallback && phase === 'open' ? ' (fallback)' : '');
  const color = colors?.[phase] ?? DEFAULT_COLORS_RN[phase];

  return createElement(
    View,
    {
      testID: testID ?? 'atmosphere-connection-status',
      style: { ...styles.container, ...(style ?? {}) },
    },
    createElement(View, {
      testID: 'atmosphere-connection-status-dot',
      style: { ...styles.dot, backgroundColor: color },
    }),
    createElement(
      Text,
      {
        testID: 'atmosphere-connection-status-label',
        style: { ...styles.label, ...(textStyle ?? {}) },
      },
      label,
    ),
  );
}
