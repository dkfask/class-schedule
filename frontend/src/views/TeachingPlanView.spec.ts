import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TeachingPlanView from './TeachingPlanView.vue'
import { resetTermStore } from '../stores/term'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

function mountTeachingPlan() {
  return mount(TeachingPlanView, {
    global: {
      directives: { loading: () => undefined },
      stubs: {
        'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        'el-table': { template: '<div class="el-table"><slot /></div>' },
        'el-table-column': { template: '<div />' },
        'el-pagination': { template: '<div />' },
        'el-empty': { template: '<div />' },
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        'el-dialog': { template: '<div><slot /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<label><slot /></label>' },
        'el-input': { props: ['modelValue'], template: '<input :data-testid="$attrs[\'data-testid\']" />' },
        'el-input-number': { template: '<input />' },
        'el-select': { template: '<select><slot /></select>' },
        'el-option': { template: '<option><slot /></option>' },
        'el-tag': { template: '<span><slot /></span>' },
      },
    },
  })
}

afterEach(() => { resetTermStore(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('TeachingPlanView', () => {
  it('loads requirements for the selected term', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.includes('/api/master-data/teaching-requirements')) return response({ items: [{ id: 1, code: 'REQ-1', termCode: '2026-FALL', studentGroupCode: 'G7-1', subjectCode: 'MATH', teacherCode: 'T001', weeklyPeriods: 1, durationPeriods: 1, studentCount: 0, requiredFeatures: '', pinnedPeriodCode: null, active: true }], page: 0, size: 20, total: 1 })
      return response({})
    }))
    const wrapper = mountTeachingPlan()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(calls.some(url => url.includes('/api/master-data/teaching-requirements?termCode=2026-FALL'))).toBe(true)
    expect(vm.items).toHaveLength(1)
    expect(vm.items[0].code).toBe('REQ-1')
    expect(wrapper.find('.teaching-plan-table-wrap').exists()).toBe(true)
    expect(wrapper.find('.teaching-plan-table-wrap .el-table').exists()).toBe(true)
    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('guides an empty teaching plan to term import', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.includes('/api/master-data/teaching-requirements')) return response({ items: [], page: 0, size: 20, total: 0 })
      return response({})
    }))
    const wrapper = mountTeachingPlan()
    await flushPromises()
    expect(wrapper.get('[data-testid="empty-state"]').text()).toContain('还没有教学需求')
    expect(wrapper.get('[data-testid="empty-state"] a').attributes('href')).toBe('/import')
    wrapper.unmount()
  })

  it('creates a requirement with the selected term', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
      if (url.endsWith('/api/master-data/teaching-requirements') && init?.method === 'POST') return response({ id: 9, status: 'CREATED' })
      if (url.includes('/api/master-data/teaching-requirements')) return response({ items: [], page: 0, size: 20, total: 0 })
      return response({})
    }))
    const wrapper = mountTeachingPlan()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.form = { code: 'REQ-NEW', studentGroupCode: 'G7-1', subjectCode: 'MATH', teacherCode: 'T001', weeklyPeriods: 2, durationPeriods: 1, studentCount: 40, requiredFeatures: '', pinnedPeriodCode: '', roomAssignmentMode: 'HOME', preferredPeriodCodes: 'MON-1;TUE-2' }
    await vm.save()
    await flushPromises()
    const createCall = calls.find(call => call.url.endsWith('/api/master-data/teaching-requirements') && call.init?.method === 'POST')
    expect(createCall).toBeTruthy()
    expect(JSON.parse(String(createCall?.init?.body))).toMatchObject({ code: 'REQ-NEW', termCode: '2026-FALL', weeklyPeriods: 2, preferredPeriodCodes: 'MON-1;TUE-2' })
    wrapper.unmount()
  })

  it('shows preferred period codes in the teaching plan form', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.includes('/api/master-data/teaching-requirements')) {
        return response({ items: [{ id: 1, code: 'REQ-1', termCode: '2026-FALL', studentGroupCode: 'G7-1', subjectCode: 'MATH', teacherCode: 'T001', weeklyPeriods: 2, durationPeriods: 1, studentCount: 0, requiredFeatures: '', pinnedPeriodCode: null, roomAssignmentMode: 'HOME', preferredPeriodCodes: 'MON-1;TUE-2', active: true }], page: 0, size: 20, total: 1 })
      }
      return response({})
    }))
    const wrapper = mountTeachingPlan()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openEdit(vm.items[0])
    expect(vm.form.preferredPeriodCodes).toBe('MON-1;TUE-2')
    expect(wrapper.find('[data-testid="preferred-period-codes"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
