import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'
import OverviewView from './OverviewView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

function mountView() {
  return mount(OverviewView, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function createFetchMock({ ready = true, issue = false, versions = [] as unknown[] } = {}) {
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
    if (url.includes('/api/master-data/overview')) {
      return response({
        periods: [{ code: 'MON-1', weekday: 1, period: 1, label: '第 1 节' }],
        teachers: [{ code: 'T001', name: '张老师' }],
        studentGroups: [{ code: 'G7-1', name: '七年级 1 班' }],
        subjects: [{ code: 'MATH', name: '数学' }],
        rooms: [{ code: 'A101', name: '一号教室' }],
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
  return calls
}

afterEach(() => {
  resetTermStore()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('OverviewView', () => {
  it('guides an incomplete term to the first blocking action', async () => {
    const calls = createFetchMock({ ready: false, issue: true })
    const wrapper = mountView()
    await flushPromises()

    expect(calls.some(url => url.includes('/api/master-data/overview?termCode=2026-FALL'))).toBe(true)
    expect(calls.some(url => url.includes('/api/solve-readiness?termCode=2026-FALL'))).toBe(true)
    expect(wrapper.text()).toContain('补齐基础数据')
    expect(wrapper.text()).toContain('尚未配置启用教室，请先新增或导入教室')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/master-data')
    expect(wrapper.find('.stage-blocked').exists()).toBe(true)
    wrapper.unmount()
  })

  it('routes a ready term with a candidate to version review', async () => {
    const calls = createFetchMock({ versions: [{ id: 17, status: 'CANDIDATE', publishable: true, revision: 2 }] })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('检查候选版本')
    expect(wrapper.text()).toContain('候选版本')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/versions')
    expect(wrapper.text()).toContain('17')
    expect(calls.some(url => url.includes('/api/schedule-versions?termCode=2026-FALL&page=0&size=20'))).toBe(true)
    wrapper.unmount()
  })

  it('shows the published destination after a version is published', async () => {
    createFetchMock({ versions: [{ id: 21, status: 'PUBLISHED', publishable: true, revision: 3 }] })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('查看已发布课表')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/published')
    expect(wrapper.text()).toContain('正式版本 v21 已发布')
    wrapper.unmount()
  })
})
