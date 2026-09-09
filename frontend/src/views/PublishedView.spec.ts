import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PublishedView from './PublishedView.vue'
import { resetTermStore } from '../stores/term'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

function mountView() {
  return mount(PublishedView, {
    global: {
      stubs: {
        'el-button': { template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>', props: ['disabled', 'loading'] },
        'el-empty': { template: '<div><slot /></div>' },
      },
    },
  })
}

afterEach(() => { resetTermStore(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('PublishedView', () => {
  it('renders legacy and modern scores from the raw score fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.includes('/api/schedule-versions')) return response({ items: [
        { id: 24, status: 'PUBLISHED', score: '0hard/0soft', revision: 1 },
        { id: 25, status: 'PUBLISHED', score: '-1hard/-2medium/-3soft', revision: 2 },
      ] })
      return response({})
    }))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('H0 / M0 / S0')
    expect(wrapper.text()).toContain('H-1 / M-2 / S-3')
    wrapper.unmount()
  })

  it('opens the selected version export and print URLs', async () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (String(input).includes('/api/schedule-versions')) return response({ items: [{ id: 24, status: 'PUBLISHED', score: '0hard/0soft' }] })
      return response({})
    }))
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.download('xlsx')
    vm.download('pdf')
    vm.print()
    expect(open).toHaveBeenNthCalledWith(1, '/api/schedule-versions/24/exports/xlsx?view=CLASS', '_blank')
    expect(open).toHaveBeenNthCalledWith(2, '/api/schedule-versions/24/exports/pdf?view=CLASS', '_blank')
    expect(open).toHaveBeenNthCalledWith(3, '/api/schedule-versions/24/print?view=CLASS', '_blank')
    wrapper.unmount()
  })

  it('loads a read-only board and switches the published resource view', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url.includes('/api/master-data/overview')) return response({
        periods: [{ code: 'MON-1', label: '周一 第1节', weekday: 1, period: 1 }],
        rooms: [{ code: 'A101', name: '教学楼 A101', capacity: 50 }],
        studentGroups: [{ code: 'G7-1', name: '七年级1班' }],
        teachers: [{ code: 'T001', name: '张老师' }],
      })
      if (url.includes('/api/schedule-versions/24/filtered?view=CLASS')) return response({ assignments: [{ occurrenceId: 1, subjectName: '数学', teacherName: '张老师', studentGroupName: '七年级1班', roomName: '教学楼 A101', weekday: 1, period: 1 }] })
      if (url.includes('/api/schedule-versions/24/filtered?view=TEACHER')) return response({ assignments: [{ occurrenceId: 1, subjectName: '数学', teacherName: '张老师', studentGroupName: '七年级1班', roomName: '教学楼 A101', weekday: 1, period: 1 }] })
      if (url.includes('/api/schedule-versions')) return response({ items: [{ id: 24, status: 'PUBLISHED', score: '0hard/0soft' }] })
      return response({})
    }))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.get('[data-testid="published-schedule-view"]').text()).toContain('数学')
    await (wrapper.get('[data-testid="published-view-teacher"]').trigger('click'))
    await flushPromises()
    expect(calls.some(url => url.includes('/api/schedule-versions/24/filtered?view=TEACHER&resourceCode=T001'))).toBe(true)
    expect(wrapper.get('[data-testid="published-view-teacher"]').attributes('aria-pressed')).toBe('true')
    wrapper.unmount()
  })
})
