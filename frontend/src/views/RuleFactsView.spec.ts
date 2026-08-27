import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RuleFactsView from './RuleFactsView.vue'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('RuleFactsView', () => {
  it('writes teacher availability through the rule facts API', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      if (init?.method === 'POST' && url === '/api/rule-facts/availability/TEACHER') return response({ status: 'UPDATED' })
      if (url.includes('/api/rule-facts/availability')) return response([{ resourceType: 'TEACHER', resourceCode: 'T001', periodCode: 'MON-1', available: false }])
      if (url.includes('/api/rule-facts/features')) return response([])
      if (url.includes('/api/rule-facts/room-features')) return response([])
      if (url.includes('/api/rule-facts/requirement-features')) return response([])
      if (url.includes('/api/rule-facts/activity-groups')) return response([])
      return response({ status: 'UPDATED' })
    }))
    const wrapper = mount(RuleFactsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, 'el-button': { template: '<button><slot /></button>' }, 'el-empty': { template: '<div />' } } } })
    const vm = wrapper.vm as any
    await vm.saveAvailability()
    await flushPromises()
    const saveCall = calls.find(call => call.url === '/api/rule-facts/availability/TEACHER')
    expect(saveCall).toBeTruthy()
    expect(JSON.parse(String(saveCall?.init?.body))).toEqual({ resourceCode: 'T001', termCode: '2026-FALL', periodCode: 'MON-1', available: false })
    expect(vm.message).toBe('规则事实已保存')
    wrapper.unmount()
  })

  it('shows rule fact API failures without reporting success', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      return response({ message: '资源不存在' }, false)
    }))
    const wrapper = mount(RuleFactsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    const vm = wrapper.vm as any
    await vm.saveAvailability()
    await flushPromises()
    expect(vm.message).toBe('')
    expect(vm.error).toContain('资源不存在')
    expect(vm.error).toContain('资源可用性')
    expect(vm.saving).toBe(false)
    wrapper.unmount()
  })

  it('keeps successful sections and identifies a failed section', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      if (url.includes('/api/rule-facts/availability')) return response([{ resourceType: 'TEACHER', resourceCode: 'T001', periodCode: 'MON-1', available: false }])
      if (url.includes('/api/rule-facts/features')) return response({ message: '特征服务不可用' }, false)
      if (url.includes('/api/rule-facts/room-features')) return response([])
      if (url.includes('/api/rule-facts/requirement-features')) return response([])
      if (url.includes('/api/rule-facts/activity-groups')) return response([])
      if (url.endsWith('/api/schedule-rules/catalog')) return response([])
      if (url.includes('/api/schedule-rules?termCode')) return response([])
      return response({})
    }))
    const wrapper = mount(RuleFactsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, 'el-button': { template: '<button><slot /></button>' }, 'el-empty': { template: '<div />' } } } })
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.availability).toHaveLength(1)
    expect(vm.features).toHaveLength(0)
    expect(vm.error).toContain('特征目录')
    expect(vm.error).toContain('特征服务不可用')
    wrapper.unmount()
  })

  it('clears a previous partial-load error after a successful refresh', async () => {
    let failFeatures = true
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      if (url.includes('/api/rule-facts/features') && failFeatures) return response({ message: '暂时失败' }, false)
      if (url.includes('/api/rule-facts/availability')) return response([])
      if (url.includes('/api/rule-facts/features')) return response([])
      if (url.includes('/api/rule-facts/room-features')) return response([])
      if (url.includes('/api/rule-facts/requirement-features')) return response([])
      if (url.includes('/api/rule-facts/activity-groups')) return response([])
      if (url.endsWith('/api/schedule-rules/catalog')) return response([])
      if (url.includes('/api/schedule-rules?termCode')) return response([])
      return response({})
    }))
    const wrapper = mount(RuleFactsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, 'el-button': { template: '<button><slot /></button>' }, 'el-empty': { template: '<div />' } } } })
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.error).toContain('特征目录')
    failFeatures = false
    await vm.loadAll()
    await flushPromises()
    expect(vm.error).toBe('')
    wrapper.unmount()
  })

  it('loads configured availability on mount and refreshes after saving', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      if (url.includes('/api/rule-facts/availability') && (!init || init.method === undefined || init.method === 'GET')) {
        return response([{ resourceType: 'TEACHER', resourceCode: 'T001', periodCode: 'MON-1', available: false }])
      }
      return response({ status: 'UPDATED' })
    }))
    const wrapper = mount(RuleFactsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' }, 'el-button': { template: '<button><slot /></button>' }, 'el-empty': { template: '<div />' } } } })
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.availability).toHaveLength(1)
    expect(vm.availability[0]).toMatchObject({ resourceType: 'TEACHER', resourceCode: 'T001', periodCode: 'MON-1', available: false })
    const listCallsBefore = calls.filter(call => call.url.includes('/api/rule-facts/availability') && !call.init?.method).length
    await vm.saveAvailability()
    await flushPromises()
    const listCallsAfter = calls.filter(call => call.url.includes('/api/rule-facts/availability') && !call.init?.method).length
    expect(listCallsAfter).toBeGreaterThan(listCallsBefore)
    wrapper.unmount()
  })
})
