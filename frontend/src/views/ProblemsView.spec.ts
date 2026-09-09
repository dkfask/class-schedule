import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import ProblemsView from './ProblemsView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ProblemsView', () => {
  it('loads the current term, creates a linked problem, and updates its status', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const problem = {
      id: 7,
      termCode: '2026-FALL',
      title: '教室容量不足',
      description: '七年级数学无法安排',
      priority: 'HIGH',
      category: 'DATA',
      status: 'OPEN',
      evidence: 'room capacity',
      reporter: 'planner',
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/problems?termCode=2026-FALL') return response({ items: [problem] })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })
      if (url === '/api/problems' && init?.method === 'POST') return response({ id: 8, status: 'OPEN' })
      if (url === '/api/problems/7' && init?.method === 'PATCH') return response({ id: 7, status: 'IN_PROGRESS' })
      throw new Error(`Unexpected request: ${url}`)
    }))

    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/problems', component: ProblemsView }] })
    await router.push('/problems?title=来自审计&versionId=24')
    await router.isReady()
    const wrapper = mount(ProblemsView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('[data-testid="problems-page"]').text()).toContain('教室容量不足')
    expect(wrapper.text()).toContain('数据问题')

    const vm = wrapper.vm as any
    vm.form.title = '求解后仍有未安排课程'
    vm.form.description = '需要支持人员确认规则'
    vm.form.versionId = '24'
    vm.form.category = 'RULE'
    await vm.createProblem()
    await flushPromises()

    const createCall = calls.find(call => call.url === '/api/problems' && call.init?.method === 'POST')
    expect(createCall).toBeDefined()
    expect(JSON.parse(String(createCall?.init?.body))).toMatchObject({
      termCode: '2026-FALL',
      title: '求解后仍有未安排课程',
      category: 'RULE',
      versionId: 24,
    })

    await vm.updateProblem(problem, 'IN_PROGRESS')
    await flushPromises()
    const updateCall = calls.find(call => call.url === '/api/problems/7' && call.init?.method === 'PATCH')
    expect(JSON.parse(String(updateCall?.init?.body))).toMatchObject({ status: 'IN_PROGRESS' })
    expect(problem.status).toBe('IN_PROGRESS')
    wrapper.unmount()
  })
})
