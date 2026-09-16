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

    const wrapper = mount(NotificationsView, { global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.get('[data-testid="notifications-page"]').text()).toContain('求解失败，需要处理')
    expect(wrapper.find('a[href="/workspace"]').exists()).toBe(true)
    const vm = wrapper.vm as any
    await vm.update(item, 'DONE')
    await flushPromises()
    expect(item.status).toBe('DONE')
    const updateCall = calls.find(call => call.url === '/api/notifications/3')
    expect(JSON.parse(String(updateCall?.init?.body))).toEqual({ status: 'DONE' })
    wrapper.unmount()
  })

  it('points an empty inbox back to the term overview', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/notifications') return response({ items: [], pendingCount: 0 })
      throw new Error(`Unexpected request: ${url}`)
    }))
    const wrapper = mount(NotificationsView, { global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.get('[data-testid="empty-state"]').text()).toContain('还没有需要处理的通知')
    expect(wrapper.find('a[href="/overview"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('sends owners to the approval checklist and planners to publish after approval', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/notifications') {
        return response({
          items: [
            { id: 8, kind: 'ACTION_REQUIRED', title: '候选课表待您批准', message: '版本 v12 等待审阅', status: 'PENDING', termCode: '2026-FALL', mandatory: true, createdAt: '2026-09-16T01:00:00Z', aggregateType: 'SCHEDULE_VERSION', aggregateId: '12' },
            { id: 9, kind: 'RELEASE', title: '业务负责人已批准候选课表', message: '版本 v12 已批准', status: 'PENDING', termCode: '2026-FALL', mandatory: false, createdAt: '2026-09-16T02:00:00Z', aggregateType: 'SCHEDULE_VERSION', aggregateId: '12' },
          ],
          pendingCount: 2,
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    const wrapper = mount(NotificationsView, { global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('打开审批清单')
    expect(wrapper.text()).toContain('检查并发布')
    expect(wrapper.findAll('a[href="/versions"]')).toHaveLength(2)
    wrapper.unmount()
  })
})
