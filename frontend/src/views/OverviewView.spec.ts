import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { resetTermStore } from '../stores/term'
import OverviewView from './OverviewView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

function mountView(roles = ['PLANNER']) {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.user = { id: 1, username: 'planner', email: null, emailVerified: true, displayName: '排课员', enabled: true, roles }
  auth.initialized = true
  return mount(OverviewView, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
}

function createFetchMock({
  ready = true,
  issue = false,
  versions = [] as unknown[],
  issues,
  roomCount,
  requirementCount,
}: {
  ready?: boolean
  issue?: boolean
  versions?: unknown[]
  issues?: Array<{ code: string; message: string }>
  roomCount?: number
  requirementCount?: number
} = {}) {
  const resolvedIssues = issues ?? (issue ? [{ code: 'NO_ACTIVE_ROOMS', message: '尚未配置启用教室，请先新增或导入教室' }] : [])
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
        roomCount: roomCount ?? (ready ? 1 : 0),
        requirementCount: requirementCount ?? (ready ? 3 : 0),
        issues: resolvedIssues,
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
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/setup')
    expect(wrapper.find('.stage-blocked').exists()).toBe(true)
    expect(wrapper.get('[data-testid="first-run-guide"]').text()).toContain('按这四步完成本学期课表')
    expect(wrapper.get('[data-testid="first-run-guide"]').text()).toContain('导入学期数据')
    expect(wrapper.get('[data-testid="first-run-guide"] .primary-action').attributes('href')).toBe('/setup')
    wrapper.unmount()
  })

  it('routes a ready term with a candidate to version review', async () => {
    const calls = createFetchMock({ versions: [{ id: 17, status: 'CANDIDATE', publishable: true, revision: 2 }] })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('检查候选版本')
    expect(wrapper.text()).toContain('候选版本')
    expect(wrapper.find('[data-testid="first-run-guide"]').exists()).toBe(false)
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

  it('sends a data-complete term with a home-room blocker to the teaching plan', async () => {
    createFetchMock({
      ready: false,
      roomCount: 1,
      requirementCount: 3,
      issues: [{ code: 'HOME_ROOM_NOT_CONFIGURED', message: '有 2 条行政班教学需求缺少有效的绑定教室' }],
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('处理排课前置问题')
    expect(wrapper.get('[data-testid="first-run-guide"] .primary-action').attributes('href')).toBe('/setup')
    expect(wrapper.find('.panel-action-row .primary-action').attributes('href')).toBe('/teaching-plan')
    expect(wrapper.find('.blocker-row').attributes('href')).toBe('/teaching-plan')
    wrapper.unmount()
  })

  it('hides the first-run wizard from reviewers', async () => {
    createFetchMock({ ready: false, issue: true })
    const wrapper = mountView(['REVIEWER'])
    await flushPromises()
    expect(wrapper.find('[data-testid="first-run-guide"]').exists()).toBe(false)
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/versions')
    expect(wrapper.text()).toContain('等待排课员补齐数据')
    expect(wrapper.find('.stage-action').attributes('href')).toBe('/versions')
    wrapper.unmount()
  })

  it('lets reviewers inspect a candidate without offering publish', async () => {
    createFetchMock({ versions: [{ id: 17, status: 'CANDIDATE', publishable: true, revision: 2 }] })
    const wrapper = mountView(['REVIEWER'])
    await flushPromises()
    expect(wrapper.text()).toContain('审阅候选课表')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/versions')
    expect(wrapper.text()).not.toContain('检查并发布版本')
    wrapper.unmount()
  })

  it('sends business owners to an approval checklist instead of publish language', async () => {
    createFetchMock({ versions: [{ id: 17, status: 'CANDIDATE', publishable: true, revision: 2, ownerApproval: { status: 'PENDING', required: true } }] })
    const wrapper = mountView(['BUSINESS_OWNER'])
    await flushPromises()
    expect(wrapper.text()).toContain('审阅候选课表')
    expect(wrapper.text()).toContain('打开审批清单')
    expect(wrapper.find('.primary-action').attributes('href')).toBe('/versions')
    expect(wrapper.text()).toContain('批准或退回')
    expect(wrapper.text()).not.toContain('检查并发布版本')
    expect(wrapper.text()).not.toContain('进入自动排课')
    wrapper.unmount()
  })
})
