import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import RuleTemplatesView from './RuleTemplatesView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('RuleTemplatesView', () => {
  it('previews template impact and requires confirmation before applying', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/rule-templates') return response([{ id: 4, code: 'SCHOOL-DEFAULT', version: 1, name: '学校默认规则', maintainedBy: '实施团队' }])
      if (url === '/api/rule-templates/4/preview?termCode=2026-FALL') return response({ added: [{ ruleCode: 'X' }], modified: [], retired: [], unmatchedScopes: [], canApply: true, readiness: { ready: true, issues: [] } })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })
      if (url === '/api/rule-templates/4/apply') return response({ status: 'APPLIED' })
      throw new Error(`Unexpected request: ${url}`)
    }))

    const wrapper = mount(RuleTemplatesView)
    await flushPromises()
    expect(wrapper.get('[data-testid="rule-templates-page"]').text()).toContain('新增')
    expect(wrapper.text()).toContain('学校默认规则')
    const vm = wrapper.vm as any
    expect(vm.confirmApply).toBe(false)
    await vm.applyTemplate()
    expect(calls.some(call => call.url === '/api/rule-templates/4/apply')).toBe(false)
    vm.confirmApply = true
    await vm.applyTemplate()
    await flushPromises()
    const applyCall = calls.find(call => call.url === '/api/rule-templates/4/apply')
    expect(JSON.parse(String(applyCall?.init?.body))).toEqual({ termCode: '2026-FALL' })
    wrapper.unmount()
  })
})
