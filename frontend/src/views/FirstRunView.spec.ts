import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { resetTermStore } from '../stores/term'
import FirstRunView from './FirstRunView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

function mountView(roles = ['PLANNER']) {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.user = { id: 1, username: 'planner', email: null, emailVerified: true, displayName: '排课员', enabled: true, roles }
  auth.initialized = true
  return mount(FirstRunView, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function createFetchMock({
  ready = false,
  issue = true,
  versions = [] as unknown[],
}: {
  ready?: boolean
  issue?: boolean
  versions?: unknown[]
} = {}) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
    if (url.includes('/api/master-data/overview')) {
      return response({
        periods: [{ code: 'MON-1', weekday: 1, period: 1, label: '第 1 节' }],
        teachers: [{ code: 'T001', name: '张老师' }],
        studentGroups: [{ code: 'G7-1', name: '七年级 1 班' }],
        subjects: [{ code: 'MATH', name: '数学' }],
        rooms: issue ? [] : [{ code: 'A101', name: '一号教室' }],
      })
    }
    if (url.includes('/api/solve-readiness')) {
      return response({
        ready,
        timeslotCount: 1,
        roomCount: ready ? 1 : 0,
        requirementCount: ready ? 3 : 0,
        issues: issue ? [{ code: 'NO_ACTIVE_ROOMS', message: '尚未配置启用教室，请先新增或导入教室' }] : [],
      })
    }
    if (url.includes('/api/schedule-versions')) return response({ items: versions, page: 0, size: 20, total: versions.length })
    throw new Error(`Unexpected request: ${url}`)
  }))
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('FirstRunView', () => {
  it('shows the four setup steps and sends the planner to import first', async () => {
    createFetchMock()
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.get('[data-testid="first-run-wizard"]').text()).toContain('按这四步完成本学期课表')
    expect(wrapper.get('[data-testid="first-run-step-01"]').text()).toContain('导入学期数据')
    expect(wrapper.get('[data-testid="first-run-step-01"]').text()).toContain('现在处理')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/import')
    expect(wrapper.text()).toContain('已完成 0/4 步')
    wrapper.unmount()
  })

  it('points a ready term at automatic scheduling instead of the setup sequence', async () => {
    createFetchMock({ ready: true, issue: false, versions: [{ id: 17, status: 'CANDIDATE' }] })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('本学期已经可以继续排课')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/workspace')
    expect(wrapper.get('[data-testid="first-run-step-04"]').text()).toContain('现在处理')
    wrapper.unmount()
  })
})
