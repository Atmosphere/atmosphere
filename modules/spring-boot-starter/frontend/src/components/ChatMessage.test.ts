// @vitest-environment jsdom
import { describe, it, expect, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessage from './ChatMessage.vue'
import { loadMath } from '../lib/markdown'
import type { ChatMessage as Message } from '../composables/useAtmosphereChat'

const PROMPT = 'Write the quadratic formula in LaTeX, using $$ delimiters. '
  + 'Do not use a code block; put the $$...$$ on its own line.'

function bubble(message: Partial<Message>) {
  return mount(ChatMessage, {
    props: { message: { id: 'm', role: 'user', content: '', timestamp: 0, ...message } as Message },
  })
}

/**
 * Math is rendered in replies only. A prompt that names TeX delimiters (two
 * `$$` in one line of prose) was itself rendered as display math, so the
 * user's bubble showed `delimiters.Donotuseacodeblock;putthe`.
 */
describe('ChatMessage math rendering', () => {
  beforeAll(async () => {
    await loadMath()
  })

  it('shows the user\'s own prompt as typed, never as math', () => {
    const content = bubble({ role: 'user', content: PROMPT }).get('.message-content')
    expect(content.find('.math').exists()).toBe(false)
    expect(content.find('math').exists()).toBe(false)
    expect(content.text()).toBe(PROMPT)
  })

  it('shows another room member\'s prompt as typed', () => {
    const content = bubble({ role: 'user', content: 'Is $$x^2$$ positive?', author: 'Member 1a2b3c4d' })
      .get('.message-content')
    expect(content.find('math').exists()).toBe(false)
    expect(content.text()).toBe('Is $$x^2$$ positive?')
  })

  it('shows a broadcast room\'s message as typed, though it sits on the reply side', () => {
    const content = bubble({ role: 'assistant', content: 'alice: costs $$5 vs $$7', typed: true })
      .get('.message-content')
    expect(content.find('math').exists()).toBe(false)
    expect(content.text()).toBe('alice: costs $$5 vs $$7')
  })

  it('still renders markdown in a user bubble (the queued marker is italic)', () => {
    const content = bubble({ role: 'user', content: 'hello *(queued)*' }).get('.message-content')
    expect(content.find('em').text()).toBe('(queued)')
  })

  it('renders math in an assistant reply', () => {
    const content = bubble({ role: 'assistant', content: '$$x = \\frac{-b}{2a}$$' }).get('.message-content')
    expect(content.find('.math-display math mfrac').exists()).toBe(true)
  })
})
