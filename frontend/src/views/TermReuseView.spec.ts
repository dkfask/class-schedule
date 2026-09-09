import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import TermReuseView from './TermReuseView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('TermReuseView', () => {
  it('previews mappings and requires confirmation before copying a term', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const plan = {
      sourceTermCode: '2026-FALL', targetTermCode: '2027-SPRING', targetExists: false,
      missingMappings: [], canCopy: true, counts: { periods: 30, requirements: 3, activityGroups: 1, rules: 4 },
      willCopy: ['作息与节次模板'], willNotCopy: ['已发布课表事实'], needsConfirmation: ['需重新检查'],
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([
        { code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' },
        { code: '2025-FALL', name: '2025 秋季学期', status: 'DRAFT' },
      ])
      if (url === '/api/terms/copy/preview') return response(plan)
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })
      if (url === '/api/terms/copy') return response({ status: 'COPIED', targetTermCode: '2027-SPRING', copied: { periods: 30 }, needsRecheck: true })
      throw new Error(`Unexpected request: ${url}`)
    }))

    const wrapper = mount(TermReuseView)
    await flushPromises()
    const vm = wrapper.vm as any
    vm.form.targetTermCode = '2027-SPRING'
    vm.form.targetName = '2027 春季学期'
    vm.form.teacherMappings = 'T001 = T101'
    await vm.previewCopy()
    await flushPromises()
    expect(wrapper.text()).toContain('已发布课表事实')
    expect(JSON.parse(String(calls.find(call => call.url === '/api/terms/copy/preview')?.init?.body))).toMatchObject({
      sourceTermCode: '2026-FALL', targetTermCode: '2027-SPRING', teacherMappings: { T001: 'T101' },
    })
    await vm.copyTerm()
    expect(calls.some(call => call.url === '/api/terms/copy')).toBe(false)
    vm.confirmed = true
    await vm.copyTerm()
    await flushPromises()
    expect(calls.some(call => call.url === '/api/terms/copy')).toBe(true)
    wrapper.unmount()
  })
})
