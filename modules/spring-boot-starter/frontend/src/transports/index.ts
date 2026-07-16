import { AtmosphereChatTransport } from './atmosphere'
import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions, ConsoleTransportName } from './types'

export type { ChatTransport, ChatTransportHandlers, ChatTransportOptions, ConsoleTransportName } from './types'
export { AtmosphereChatTransport, parseAtmosphereFrames } from './atmosphere'

/**
 * Build the chat transport for the wire protocol /api/console/info
 * reported. Async so foreign adapters are dynamic-imported as their own
 * chunks — the default Atmosphere console bundle doesn't pay for the
 * Connect-JSON (grpc), JSON-RPC/SSE (a2a) or AG-UI parsing it never uses.
 * The server validates the name against exactly this set
 * (detectTransport), so every reachable case has a real adapter; if the
 * sets ever drift, failing loudly here beats silently downgrading to the
 * WS transport, which would reconnect-loop against a foreign endpoint.
 */
export async function createChatTransport(
  name: ConsoleTransportName,
  options: ChatTransportOptions,
  handlers: ChatTransportHandlers,
): Promise<ChatTransport> {
  switch (name) {
    case 'a2a': {
      const { A2aChatTransport } = await import('./a2a')
      return new A2aChatTransport(options, handlers)
    }
    case 'ag-ui': {
      const { AgUiChatTransport } = await import('./agui')
      return new AgUiChatTransport(options, handlers)
    }
    case 'grpc': {
      const { GrpcChatTransport } = await import('./grpc')
      return new GrpcChatTransport(options, handlers)
    }
    case 'atmosphere':
    default:
      return new AtmosphereChatTransport(options, handlers)
  }
}
