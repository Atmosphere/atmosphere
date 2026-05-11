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

import { defineComponent, h, type PropType, type CSSProperties } from 'vue';
import type { ConnectionPhase, ConnectionStatusSnapshot } from '../../resilience';

export const DEFAULT_LABELS: Record<ConnectionPhase, string> = {
  idle: 'Idle',
  connecting: 'Connecting…',
  open: 'Connected',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
  lost: 'Connection lost',
};

export const DEFAULT_COLORS: Record<ConnectionPhase, string> = {
  idle: '#9CA3AF',
  connecting: '#F59E0B',
  open: '#10B981',
  reconnecting: '#F59E0B',
  closed: '#6B7280',
  lost: '#EF4444',
};

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

const KEYFRAMES = `@keyframes atmosphere-pulse {
  0%   { box-shadow: 0 0 0 0 rgba(245, 158, 11, 0.55); }
  70%  { box-shadow: 0 0 0 6px rgba(245, 158, 11, 0); }
  100% { box-shadow: 0 0 0 0 rgba(245, 158, 11, 0); }
}`;

/**
 * Vue 3 functional component that renders a compact connection-status pill.
 *
 * ```vue
 * <script setup lang="ts">
 * import { useStreaming, ConnectionStatusBadge } from 'atmosphere.js/vue';
 * const { connectionStatus } = useStreaming({ url: '/ai', transport: 'websocket' });
 * </script>
 *
 * <template>
 *   <ConnectionStatusBadge :status="connectionStatus" />
 * </template>
 * ```
 */
export const ConnectionStatusBadge = defineComponent({
  name: 'ConnectionStatusBadge',
  props: {
    status: {
      type: Object as PropType<ConnectionStatusSnapshot>,
      required: true,
    },
    labels: {
      type: Object as PropType<Partial<Record<ConnectionPhase, string>>>,
      default: () => ({}),
    },
    colors: {
      type: Object as PropType<Partial<Record<ConnectionPhase, string>>>,
      default: () => ({}),
    },
    showTransport: { type: Boolean, default: true },
  },
  setup(props) {
    return () => {
      const phase = props.status.phase;
      const label = (props.labels?.[phase] ?? DEFAULT_LABELS[phase]) +
        (props.showTransport && phase !== 'idle' ? ` · ${props.status.transport}` : '') +
        (props.showTransport && props.status.viaFallback && phase === 'open' ? ' (fallback)' : '');
      const color = props.colors?.[phase] ?? DEFAULT_COLORS[phase];
      const animated = phase === 'reconnecting' || phase === 'connecting';

      const dotStyle: CSSProperties = {
        display: 'inline-block',
        width: '0.5rem',
        height: '0.5rem',
        borderRadius: '50%',
        background: color,
        animation: animated ? 'atmosphere-pulse 1.2s infinite' : undefined,
      };

      return h(
        'span',
        {
          'data-testid': 'atmosphere-connection-status',
          'data-phase': phase,
          'data-event': props.status.lastEvent ?? '',
          'data-transport': props.status.transport,
          style: containerStyle,
          title: props.status.lastError ? `Last error: ${props.status.lastError.message}` : undefined,
        },
        [
          h('style', { innerHTML: KEYFRAMES }),
          h('span', {
            'data-testid': 'atmosphere-connection-status-dot',
            style: dotStyle,
          }),
          h('span', { 'data-testid': 'atmosphere-connection-status-label' }, label),
        ],
      );
    };
  },
});
