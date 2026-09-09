import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import RetrospectiveView from './RetrospectiveView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('RetrospectiveView', () => {
  it('renders separated delivery metrics and saves implementation notes', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const payload = { termCode: '2026-FALL', metrics: { importAttempts: 2, importFailures: 1, solveAttempts: 3, solveFailures: 1, manualAdjustments: 4, publishedVersions: 1, problemReports: 2, supportInterventions: 3, elapsedMinutes: 95 }, notes: { ruleAdaptation: '', legacyIssues: '', schoolFeedback: '', supportInterventionCount: 0 } }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/retrospectives?termCode=2026-FALL') return response(payload)
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })
      if (url === '/api/retrospectives' && init?.method === 'PATCH') return response({ ...payload, notes: { ...payload.notes, ruleAdaptation: '调整教师每日上限', supportInterventionCount: 2 } })
      throw new Error(`Unexpected request: ${url}`)
    }))
    const wrapper = mount(RetrospectiveView)
    await flushPromises()
    expect(wrapper.get('[data-testid="retrospective-page"]').text()).toContain('人工调整')
    expect(wrapper.text()).toContain('95 分钟')
    expect(calls.filter(call => call.url === '/api/retrospectives?termCode=2026-FALL')).toHaveLength(1)
    const vm = wrapper.vm as any
    vm.form.ruleAdaptation = '调整教师每日上限'
    vm.form.supportInterventionCount = 2
    await vm.save()
    await flushPromises()
    const saveCall = calls.find(call => call.url === '/api/retrospectives' && call.init?.method === 'PATCH')
    expect(JSON.parse(String(saveCall?.init?.body))).toMatchObject({ termCode: '2026-FALL', ruleAdaptation: '调整教师每日上限', supportInterventionCount: 2 })
    wrapper.unmount()
  })
})
