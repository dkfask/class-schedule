import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import ScheduleVersionsView from './ScheduleVersionsView.vue'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

afterEach(() => { clearCsrfToken(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function createFetchMock(initialVersions: unknown[]) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  let versions = initialVersions
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    if (url.endsWith('/api/auth/csrf')) return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
    if (url.endsWith('/api/schedule-versions') || url.includes('/api/schedule-versions?termCode')) {
      return response({ items: versions, page: 0, size: 50, total: versions.length })
    }
    if (url.includes('/diff')) return response([])
    if (url.endsWith('/adjustments/commands')) {
      return response([{ groupId: '11111111-1111-1111-1111-111111111111', commandType: 'ADJUST', state: 'APPLIED', reason: '调课', resultRevision: 1 }])
    }
    if (url.includes('/lock') && init?.method === 'POST') return response({ status: 'LOCKED', versionId: 2 })
    if (url.includes('/lock') && init?.method === 'DELETE') return response({ status: 'UNLOCKED', versionId: 2 })
    if (url.includes('/archive')) return response({ status: 'ARCHIVED', versionId: 3 })
    if (url.includes('/undo')) return response({ status: 'UNDONE', versionId: 2 })
    if (url.includes('/redo')) return response({ status: 'REDONE', versionId: 2 })
    throw new Error(`Unexpected request: ${url}`)
  }))
  return { calls }
}

function mountView() {
  return mount(ScheduleVersionsView, { global: { stubs: { 'el-button': { template: '<button><slot /></button>' }, 'el-empty': { template: '<div />' } } } })
}

describe('ScheduleVersionsView', () => {
  it('loads versions and stable-key diff entries', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); calls.push(url)
      if (url.includes('/diff')) return response([{ changeType: 'MOVED', occurrenceKey: '1-0', before: { subjectName: '数学', timeslotCode: 'MON-1', roomCode: 'A101' }, after: { subjectName: '数学', timeslotCode: 'TUE-1', roomCode: 'A102' } }])
      if (url.endsWith('/adjustments/commands')) return response([])
      return response({ items: [{ id: 2, status: 'DRAFT', score: '0hard/0soft', parentVersionId: 1, revision: 1 }], page: 0, size: 50, total: 1 })
    }))
    const wrapper = mountView()
    await flushPromises()
    expect(calls.some(url => url.includes('/api/schedule-versions?termCode=2026-FALL'))).toBe(true)
    expect(calls.some(url => url.includes('/api/schedule-versions/2/diff?againstVersionId=1'))).toBe(true)
    expect(wrapper.text()).toContain('MOVED')
    expect(wrapper.text()).toContain('1-0')
    expect(wrapper.text()).toContain('H0 / M0 / S0')
    wrapper.unmount()
  })

  it('locks and unlocks a draft version through lifecycle APIs', async () => {
    const { calls } = createFetchMock([{ id: 2, status: 'DRAFT', score: null, parentVersionId: 1, revision: 1 }])
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.lockVersion()
    const lockCall = calls.find(call => call.url.endsWith('/api/schedule-versions/2/lock') && call.init?.method === 'POST')
    expect(lockCall).toBeTruthy()
    expect(JSON.parse(String(lockCall?.init?.body))).toEqual({ reason: '版本页锁定', expectedRevision: 1 })
    expect((lockCall?.init?.headers as Headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
    await vm.unlockVersion()
    const unlockCall = calls.find(call => call.url.endsWith('/api/schedule-versions/2/lock') && call.init?.method === 'DELETE')
    expect(unlockCall).toBeTruthy()
    expect((unlockCall?.init?.headers as Headers).get('If-Match')).toBe('1')
    wrapper.unmount()
  })

  it('archives a published version', async () => {
    const { calls } = createFetchMock([{ id: 3, status: 'PUBLISHED', score: '0hard/0soft', parentVersionId: 1, revision: 2 }])
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.archiveVersion()
    expect(calls.some(call => call.url.endsWith('/api/schedule-versions/3/archive') && call.init?.method === 'POST')).toBe(true)
    wrapper.unmount()
  })

  it('loads command history and undoes the latest applied group', async () => {
    const { calls } = createFetchMock([{ id: 2, status: 'DRAFT', score: null, parentVersionId: 1, revision: 1 }])
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.history).toHaveLength(1)
    expect(vm.latestApplied?.groupId).toBe('11111111-1111-1111-1111-111111111111')
    await vm.undoCommand('11111111-1111-1111-1111-111111111111')
    const undoCall = calls.find(call => call.url.includes('/adjustments/commands/11111111-1111-1111-1111-111111111111/undo'))
    expect(undoCall).toBeTruthy()
    expect((undoCall?.init?.headers as Headers).get('Idempotency-Key')).toBeTruthy()
    expect((undoCall?.init?.headers as Headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
    wrapper.unmount()
  })

  it('redoes the latest undone group', async () => {
    const { calls } = createFetchMock([{ id: 2, status: 'DRAFT', score: null, parentVersionId: 1, revision: 1 }])
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url.endsWith('/api/auth/csrf')) return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
    if (url.endsWith('/api/schedule-versions') || url.includes('/api/schedule-versions?termCode')) {
        return response({ items: [{ id: 2, status: 'DRAFT', score: null, parentVersionId: 1, revision: 1 }], page: 0, size: 50, total: 1 })
      }
      if (url.includes('/diff')) return response([])
      if (url.endsWith('/adjustments/commands')) {
        return response([{ groupId: '22222222-2222-2222-2222-222222222222', commandType: 'EXCHANGE', state: 'UNDONE', reason: '交换', resultRevision: 2 }])
      }
      if (url.includes('/redo')) return response({ status: 'REDONE', versionId: 2 })
      throw new Error(`Unexpected request: ${url}`)
    }))
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.latestUndone?.groupId).toBe('22222222-2222-2222-2222-222222222222')
    await vm.redoCommand('22222222-2222-2222-2222-222222222222')
    expect(calls.some(call => call.url.includes('/adjustments/commands/22222222-2222-2222-2222-222222222222/redo'))).toBe(true)
    wrapper.unmount()
  })

  it('disables editing actions for published versions', async () => {
    const { calls } = createFetchMock([{ id: 3, status: 'PUBLISHED', score: '0hard/0soft', parentVersionId: 1, revision: 2 }])
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.canLock).toBe(false)
    expect(vm.canArchive).toBe(true)
    wrapper.unmount()
  })
})
