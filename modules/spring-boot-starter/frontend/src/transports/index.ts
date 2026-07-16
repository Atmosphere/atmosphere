import { AtmosphereChatTransport } from './atmosphere'
import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions, ConsoleTransportName } from './types'

export type { ChatTransport, ChatTransportHandlers, ChatTransportOptions, ConsoleTransportName } from './types'
export { AtmosphereChatTransport, parseAtmosphereFrames } from './atmosphere'

/**
 * Build the chat transport for the wire protocol /api/console/info
 * reported. Async so foreign adapters can be dynamic-imported as their
 * own chunks — the default Atmosphere console bundle must not pay for
 * protobuf/Connect (grpc), JSON-RPC/SSE (a2a) or AG-UI parsing it never
 * uses.
 *
 * Foreign names whose adapter has not shipped yet fail loudly instead of
 * silently downgrading to the Atmosphere transport: pointing the WS
 * transport at a Connect/SSE endpoint produces an endless reconnect loop
 * that looks like a sample bug, which is far harder to diagnose than an
 * explicit "adapter not available" error (Runtime Truth — the console
 * must not pretend to speak a protocol it can't).
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
    case 'grpc':
      throw new Error(
        `Console transport '${name}' adapter is not available in this build`)
    case 'atmosphere':
    default:
      return new AtmosphereChatTransport(options, handlers)
  }
}
