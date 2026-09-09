import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import NotificationsView from './NotificationsView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('NotificationsView', () => {
  it('shows pending workflow events and marks one as done', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const item = { id: 3, kind: 'ACTION_REQUIRED', title: '求解失败，需要处理', message: '任务 #8 执行失败', status: 'PENDING', termCode: '2026-FALL', mandatory: true, createdAt: '2026-09-09T01:00:00Z', aggregateType: 'SOLVE_JOB', aggregateId: '8' }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/notifications') return response({ items: [item], pendingCount: 1 })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })
      if (url === '/api/notifications/3' && init?.method === 'PATCH') return response({ id: 3, status: 'DONE' })
      throw new Error(`Unexpected request: ${url}`)
    }))

    const wrapper = mount(NotificationsView)
    await flushPromises()
    expect(wrapper.get('[data-testid="notifications-page"]').text()).toContain('求解失败，需要处理')
    const vm = wrapper.vm as any
    await vm.update(item, 'DONE')
    await flushPromises()
    expect(item.status).toBe('DONE')
    const updateCall = calls.find(call => call.url === '/api/notifications/3')
    expect(JSON.parse(String(updateCall?.init?.body))).toEqual({ status: 'DONE' })
    wrapper.unmount()
  })
})
