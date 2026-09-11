import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import AiSettingsView from './AiSettingsView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AiSettingsView', () => {
  it('loads masked admin settings and sends only an explicit replacement key', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/ai-assist/settings' && (init?.method ?? 'GET') === 'GET') {
        return response({ baseUrl: 'https://api.example.com/v1', protocol: 'OPENAI', model: 'deepseek-chat', apiKeyConfigured: true, chatEnabled: true, source: 'ADMIN', updatedAt: null })
      }
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
      if (url === '/api/ai-assist/settings' && init?.method === 'PUT') {
        return response({ baseUrl: 'https://api.example.com/v1', protocol: 'OPENAI', model: 'deepseek-reasoner', apiKeyConfigured: true, chatEnabled: true, source: 'ADMIN', updatedAt: null })
      }
      throw new Error(`Unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AiSettingsView)
    await flushPromises()

    expect(wrapper.text()).toContain('deepseek-chat')
    expect(wrapper.text()).toContain('已安全保存')
    expect(wrapper.text()).not.toContain('secret-value')
    await wrapper.find('input[type="password"]').setValue('secret-value')
    await wrapper.find('input[placeholder="例如：deepseek-chat"]').setValue('deepseek-reasoner')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const updateCall = fetchMock.mock.calls.find(([url, init]) => String(url) === '/api/ai-assist/settings' && init?.method === 'PUT')
    expect(updateCall).toBeTruthy()
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({ protocol: 'OPENAI', model: 'deepseek-reasoner', apiKey: 'secret-value', clearApiKey: false })
    expect(wrapper.text()).toContain('AI 配置已保存并立即生效')
    wrapper.unmount()
  })

  it('surfaces a forbidden response instead of rendering an editable state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: '没有权限访问 AI 配置' }, false)))
    const wrapper = mount(AiSettingsView)
    await flushPromises()
    expect(wrapper.text()).toContain('没有权限访问 AI 配置')
    wrapper.unmount()
  })

  it('supports the Anthropic-compatible MiniMax endpoint', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/ai-assist/settings' && (init?.method ?? 'GET') === 'GET') {
        return response({ baseUrl: '', protocol: 'OPENAI', model: '', apiKeyConfigured: false, chatEnabled: false, source: 'ENVIRONMENT', updatedAt: null })
      }
      if (String(input) === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
      if (String(input) === '/api/ai-assist/settings' && init?.method === 'PUT') {
        return response({ baseUrl: 'https://api.minimax.cn/anthropic', protocol: 'ANTHROPIC', model: 'MiniMax-M2.7', apiKeyConfigured: true, chatEnabled: true, source: 'ADMIN', updatedAt: null })
      }
      throw new Error(`Unexpected request: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AiSettingsView)
    await flushPromises()
    await wrapper.find('select').setValue('ANTHROPIC')
    await wrapper.find('input[type="url"]').setValue('https://api.minimax.cn/anthropic')
    await wrapper.find('input[placeholder="例如：deepseek-chat"]').setValue('MiniMax-M2.7')
    await wrapper.find('input[type="password"]').setValue('secret-value')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const updateCall = fetchMock.mock.calls.find(([url, init]) => String(url) === '/api/ai-assist/settings' && init?.method === 'PUT')
    expect(JSON.parse(String(updateCall?.[1]?.body))).toMatchObject({ baseUrl: 'https://api.minimax.cn/anthropic', protocol: 'ANTHROPIC', model: 'MiniMax-M2.7' })
    expect(wrapper.text()).toContain('Anthropic 兼容')
    wrapper.unmount()
  })
})
