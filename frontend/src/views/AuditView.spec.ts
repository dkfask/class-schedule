import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuditView from './AuditView.vue'
import { resetTermStore } from '../stores/term'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

afterEach(() => { resetTermStore(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('AuditView', () => {
  it('loads readable audit events and sends supported filters', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.startsWith('/api/audit?')) return response({ items: [{ id: 1, action: 'PUBLISH', aggregateType: 'SCHEDULE_VERSION', aggregateId: '24', actor: 'planner', outcome: 'SUCCESS', detail: '{"revision":4}', createdAt: '2026-09-08T08:00:00Z' }] })
      return response({})
    }))
    const wrapper = mount(AuditView, { global: { stubs: { 'el-button': { template: '<button><slot /></button>' } } } })
    await flushPromises()
    expect(wrapper.get('[data-testid="audit-page"]').text()).toContain('发布')
    expect(wrapper.text()).toContain('revision: 4')
    const vm = wrapper.vm as any
    vm.versionId = '24'
    vm.action = 'PUBLISH'
    await vm.load()
    expect(calls.at(-1)).toContain('versionId=24')
    expect(calls.at(-1)).toContain('action=PUBLISH')
    wrapper.unmount()
  })
})
