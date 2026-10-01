// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import type { SubscriptionHandlers } from 'atmosphere.js'

/**
 * The Console frees a prompt the server refused only when the atmosphere.js
 * send error reaches useAtmosphereChat intact: its `name`
 * ('AtmosphereSendError') tells an undelivered message from a broken
 * connection. The composable's own tests mock the transport away, so this pins
 * the pass-through in AtmosphereChatTransport itself.
 */
const captured: { handlers: SubscriptionHandlers<string> | null } = { handlers: null }

vi.mock('atmosphere.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('atmosphere.js')>()
  class FakeAtmosphere {
    async subscribe(_request: unknown, handlers: SubscriptionHandlers<string>) {
      captured.handlers = handlers
      return { push: vi.fn(), close: vi.fn(async () => {}), state: 'connected' }
    }
  }
  return { ...actual, Atmosphere: FakeAtmosphere }
})

const { ConnectionStatus } = await import('atmosphere.js')
const { AtmosphereChatTransport } = await import('./atmosphere')

describe('AtmosphereChatTransport error pass-through', () => {
  it('hands the atmosphere.js send error to onError unchanged, name included', async () => {
    const onError = vi.fn()
    const transport = new AtmosphereChatTransport(
      {
        endpoint: '/atmosphere/ai-chat',
        isBroadcast: () => false,
        status: new ConnectionStatus({ initialTransport: 'websocket' }),
        maxReconnectOnClose: 10,
      },
      {
        onOpen() {}, onClose() {}, onError, onReconnect() {},
        onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
        onEvent() {}, onRawText() {},
      },
    )
    await transport.connect()
    expect(captured.handlers?.error).toBeTypeOf('function')

    const sendError = new Error('long-polling POST send failed with status 503 after 3 attempts')
    sendError.name = 'AtmosphereSendError'
    captured.handlers!.error!(sendError)

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0][0]).toBe(sendError)
    expect(onError.mock.calls[0][0].name).toBe('AtmosphereSendError')
  })
})
