import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routerKey } from 'vue-router'
import WorkspaceView from './WorkspaceView.vue'
import { resetTermStore } from '../stores/term'

const assignment = {
  occurrenceId: 1,
  subjectCode: 'MATH',
  subjectName: '数学',
  teacherCode: 'T001',
  teacherName: '张老师',
  studentGroupCode: 'G7-1',
  studentGroupName: '七年级1班',
  timeslotCode: 'MON-1',
  timeslotLabel: '周一 第1节',
  weekday: 1,
  period: 1,
  roomCode: 'A101',
  roomName: '教学楼 A101',
  source: 'SOLVER',
  locked: false,
  duration: 1,
  activityGroupCode: 'MATH-G7',
  activityType: 'REGULAR',
  studentCount: 46,
  requiredFeatures: ['PROJECTOR'],
  roomCapacity: 50,
  pinnedPeriodCode: 'MON-1',
}

const version = { id: 42, status: 'CANDIDATE', score: '0hard/0soft', publishable: true, assignments: [assignment] }
const options = {
  timeslots: [
    { code: 'MON-1', label: '周一 第1节', weekday: 1, period: 1 },
    { code: 'TUE-1', label: '周二 第1节', weekday: 2, period: 1 },
  ],
  rooms: [{ code: 'A101', name: '教学楼 A101', capacity: 50, roomType: '普通教室' }],
  studentGroups: [{ code: 'G7-1', name: '七年级1班' }],
  teachers: [{ code: 'T001', name: '张老师' }],
}

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

function createFetchMock(previewResponses: unknown[] = []) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init })
    if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' })
    if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
    if (url.endsWith('/api/master-data/overview')) return response({ terms: [{ name: '2026 秋季学期' }], teachers: [], studentGroups: [], subjects: [], rooms: [] })
    if (url.includes('/api/solve-readiness')) return response({ termCode: '2026-FALL', ready: true, timeslotCount: 4, roomCount: 2, requirementCount: 3, issues: [] })
    if (url.endsWith('/api/solve-jobs') && init?.method === 'POST') return response({ jobId: 9, versionId: 43, status: 'QUEUED' })
    if (url.endsWith('/api/imports/confirm') && init?.method === 'POST') return response({ batchId: 9, status: 'IMPORTED', importedRows: 5 })
    if (url.endsWith('/api/imports/preview') && init?.method === 'POST') return response({ batchId: 10, status: 'VALIDATED', issues: [] })
    if (url.endsWith('/api/solve-jobs/9')) return response({ jobId: 9, versionId: 42, jobStatus: 'COMPLETED', versionStatus: 'CANDIDATE', progress: 100, score: '0hard/0soft', hardScore: 0, mediumScore: 0, softScore: 0, scoreValid: true, attempt: 1, submittedAt: '2026-09-09T08:59:00+08:00', startedAt: '2026-09-09T09:00:00+08:00', finishedAt: '2026-09-09T09:01:00+08:00', deadlineAt: '2026-09-09T09:15:00+08:00' })
    if (url.endsWith('/api/schedule-versions/42')) return response(version)
    if (url.endsWith('/api/schedule-versions/43')) return response({ ...version, id: 43, status: 'SOLVING', score: null, publishable: false, assignments: [] })
    if (url.endsWith('/api/schedule-versions/42/options') || url.endsWith('/api/schedule-versions/43/options')) return response(options)
    if (url.endsWith('/api/schedule-versions/42/adjustments/commands')) return response([])
    if (url.includes('/api/schedule-versions/42/filtered')) return response({ ...version, assignments: [assignment] })
    if (url.includes('/api/schedule-versions/43/filtered')) return response({ ...version, id: 43, assignments: [] })
    if (url.includes('/adjustments/preview')) return response(previewResponses.shift() ?? {
      allowed: false,
      hardViolations: [{ code: 'TEACHER_CONFLICT', message: '教师在目标节次已有课程', resourceCode: 'T001' }],
      affectedAssignmentIds: [2], lockedConflict: false, versionId: 42,
      current: { timeslotCode: 'MON-1', roomCode: 'A101' }, target: { timeslotCode: 'TUE-1', roomCode: 'A101' },
    })
    if (url.includes('/adjustments/exchange-candidates')) return response({ allowedWithoutExchange: false, candidates: [{ occurrenceId: 2, occurrenceKey: '2', subjectName: '语文', studentGroupCode: 'G7-1', teacherCode: 'T002', roomCode: 'A102', timeslotCode: 'MON-2' }], hardViolations: [] })
    if (url.includes('/adjustments/exchange') && init?.method === 'POST') return response({ status: 'EXCHANGED', versionId: 42 })
    if (url.endsWith('/adjustments/1/lock') && init?.method === 'POST') return response({ status: 'LOCKED', versionId: 42, occurrenceId: 1, revision: 1 })
    if (url.endsWith('/adjustments/1/lock') && init?.method === 'DELETE') return response({ status: 'UNLOCKED', versionId: 42, occurrenceId: 1, revision: 2 })
    if (url.includes('/adjustments/1') && init?.method === 'POST') return response({ commandId: 77, versionId: 42, occurrenceId: 1, status: 'ADJUSTED' })
    if (url.endsWith('/api/schedule-versions/42/publish')) return response({ status: 'PUBLISHED', versionId: 42 })
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

function mountWorkspace() {
  return mount(WorkspaceView, { global: { provide: { [routerKey]: { push: vi.fn() } }, stubs: {
    'el-button': { template: '<button><slot /></button>' }, 'el-select': { template: '<select><slot /></select>' }, 'el-option': { template: '<option><slot /></option>' }, 'el-drawer': { template: '<div><slot /></div>' }, 'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' }, 'el-form': { template: '<form><slot /></form>' }, 'el-form-item': { template: '<label><slot /></label>' }, 'el-input': { template: '<textarea />' }, 'el-tag': { template: '<span><slot /></span>' },
  } } })
}

afterEach(() => { resetTermStore(); window.localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('WorkspaceView API workflow', () => {
  it('loads a version, switches to teacher view, and filters by stable code', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); await vm.selectView('TEACHER')
    expect(calls.some(call => call.url.endsWith('/api/schedule-versions/42/options'))).toBe(true)
    expect(calls.some(call => call.url.includes('/filtered?view=TEACHER&resourceCode=T001'))).toBe(true)
    expect(vm.viewType).toBe('TEACHER'); expect(vm.resourceCode).toBe('T001'); wrapper.unmount()
  })

  it('sends only target placement in preview and blocks confirmation on conflicts', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); vm.openAdjustment(assignment); await vm.previewAdjustment()
    const previewCall = calls.find(call => call.url.includes('/adjustments/preview'))
    expect(JSON.parse(String(previewCall?.init?.body))).toEqual({ occurrenceId: 1, timeslotCode: 'MON-1', roomCode: 'A101' }); expect(vm.preview.allowed).toBe(false)
    const requestCount = calls.length; vm.adjustmentForm.reason = '冲突确认测试'; await vm.confirmAdjustment(); expect(calls).toHaveLength(requestCount); wrapper.unmount()
  })

  it('confirms an allowed preview with reason and reloads the version', async () => {
    const { calls } = createFetchMock([{ allowed: true, hardViolations: [], affectedAssignmentIds: [], lockedConflict: false, versionId: 42, current: { timeslotCode: 'MON-1', roomCode: 'A101' }, target: { timeslotCode: 'TUE-1', roomCode: 'A101' } }])
    const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); vm.openAdjustment(assignment); vm.adjustmentForm.timeslotCode = 'TUE-1'; vm.adjustmentForm.reason = '调课验收'; await vm.previewAdjustment(); await vm.confirmAdjustment()
    const adjustCall = calls.find(call => call.url.endsWith('/api/schedule-versions/42/adjustments/1'))
    expect(JSON.parse(String(adjustCall?.init?.body))).toEqual({ timeslotCode: 'TUE-1', roomCode: 'A101', reason: '调课验收', expectedRevision: 0 }); expect(calls.filter(call => call.url.endsWith('/api/schedule-versions/42')).length).toBeGreaterThanOrEqual(2); expect(vm.backendPublishable).toBe(true); wrapper.unmount()
  })

  it('requires a release note and confirmation before publishing', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); expect(vm.publishable).toBe(true); vm.openPublishDialog(); await vm.publishVersion()
    expect(calls.some(call => call.url.endsWith('/api/schedule-versions/42/publish'))).toBe(false)
    vm.releaseNote = '2026 fall release'; vm.releaseConfirmed = true; await vm.publishVersion()
    const publishCall = calls.find(call => call.url.endsWith('/api/schedule-versions/42/publish'))
    expect(JSON.parse(String(publishCall?.init?.body))).toEqual({ releaseNote: '2026 fall release' }); expect(vm.jobStatus).toBe('PUBLISHED'); wrapper.unmount()
  })

  it('clears stale schedule data before submitting a new solve', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadReadiness(); await vm.loadVersion(42); expect(vm.occurrences).toHaveLength(1); await vm.startSolve(); expect(vm.solveDialogOpen).toBe(true); expect(vm.occurrences).toHaveLength(1); expect(calls.filter(call => call.url.endsWith('/api/solve-jobs') && call.init?.method === 'POST')).toHaveLength(0); await vm.confirmResolve(); expect(vm.occurrences).toEqual([]); expect(vm.filteredOccurrences).toEqual([]); expect(vm.options.timeslots).toEqual([]); expect(calls.some(call => call.url.endsWith('/api/solve-jobs'))).toBe(true); wrapper.unmount()
  })

  it('translates a failed solve into a recovery path with task timestamps', async () => {
    const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    vm.applySolveDetails({ jobId: 21, versionId: 44, jobStatus: 'FAILED', versionStatus: 'FAILED', progress: 62, score: null, errorCode: 'DEADLINE_EXCEEDED', errorMessage: '求解任务超过截止时间', attempt: 2, startedAt: '2026-09-09T09:00:00+08:00', heartbeatAt: '2026-09-09T09:14:00+08:00', finishedAt: '2026-09-09T09:15:00+08:00', deadlineAt: '2026-09-09T09:15:00+08:00' })
    await flushPromises()
    expect(vm.solveStateInfo.title).toBe('求解失败')
    expect(vm.solveStateInfo.category).toBe('执行超时')
    expect(vm.solveStateInfo.retryable).toBe(true)
    expect(wrapper.find('[data-testid="solve-status"]').text()).toContain('缩小求解范围或调整规则后重新求解')
    expect(wrapper.find('[data-testid="solve-status"]').text()).toContain('失败码 DEADLINE_EXCEEDED')
    wrapper.unmount()
  })

  it('shows the business context of a selected assignment before adjustment', async () => {
    createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); vm.openAdjustment(assignment); await flushPromises()
    expect(wrapper.find('[data-testid="adjustment-drawer"]').text()).toContain('MATH-G7')
    expect(wrapper.find('[data-testid="adjustment-drawer"]').text()).toContain('固定节次')
    expect(wrapper.find('[data-testid="adjustment-drawer"]').text()).toContain('教室容量50')
    wrapper.unmount()
  })

  it('locks and unlocks a selected course with the current revision', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); vm.openAdjustment(assignment); vm.adjustmentForm.reason = '校务确认'; await vm.lockSelectedAssignment()
    const lockCall = calls.find(call => call.url.endsWith('/adjustments/1/lock') && call.init?.method === 'POST')
    expect(JSON.parse(String(lockCall?.init?.body))).toEqual({ reason: '校务确认', expectedRevision: 0 })
    vm.selectedOccurrence = { ...assignment, locked: true }; vm.versionRevision = 1; await vm.unlockSelectedAssignment()
    const unlockCall = calls.find(call => call.url.endsWith('/adjustments/1/lock') && call.init?.method === 'DELETE')
    expect((unlockCall?.init?.headers as Headers).get('If-Match')).toBe('1')
    wrapper.unmount()
  })

  it('restores the persisted completed workspace after reload', async () => {
    window.localStorage.setItem('class-schedule.workspace:2026-FALL', JSON.stringify({ jobId: 9, versionId: 42 }))
    const { calls } = createFetchMock()
    const wrapper = mountWorkspace()
    await flushPromises()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(calls.some(call => call.url.endsWith('/api/solve-jobs/9'))).toBe(true)
    expect(vm.jobId).toBe(9)
    expect(vm.versionId).toBe(42)
    expect(vm.jobStatus).toBe('COMPLETED')
    expect(vm.occurrences).toHaveLength(1)
    wrapper.unmount()
  })

  it('blocks solve submission when the current term is not ready', async () => {
    const { calls } = createFetchMock(); const readinessCall = calls
    const originalFetch = globalThis.fetch
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/solve-readiness')) return response({ termCode: '2026-FALL', ready: false, timeslotCount: 4, roomCount: 0, requirementCount: 3, issues: [{ code: 'NO_ACTIVE_ROOMS', message: '尚未配置启用教室，请先新增或导入教室' }] })
      return (originalFetch as any)(input, init)
    }))
    const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadReadiness(); await vm.startSolve()
    expect(vm.jobStatus).toBe('数据未就绪')
    expect(vm.errorMessage).toContain('尚未配置启用教室')
    expect(readinessCall.some((call: any) => call.url.endsWith('/api/solve-jobs'))).toBe(false)
    wrapper.unmount()
  })

  it('filters pending tasks by the search query', async () => {
    const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    vm.occurrences = [{ ...assignment, timeslotCode: undefined, roomCode: undefined }, { ...assignment, occurrenceId: 2, subjectName: '语文', subjectCode: 'CHN', timeslotCode: undefined, roomCode: undefined }]; vm.searchQuery = '语文'; await flushPromises(); expect(vm.pendingOccurrences.map((item: any) => item.subjectCode)).toEqual(['CHN']); wrapper.unmount()
  })

  it('groups pending tasks by an actionable reason or resource', async () => {
    const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    vm.occurrences = [
      { ...assignment, timeslotCode: undefined, roomCode: undefined },
      { ...assignment, occurrenceId: 2, teacherName: '李老师', teacherCode: 'T002', timeslotCode: undefined, roomCode: 'A101' },
    ]
    await flushPromises()
    expect(vm.pendingGroups.map((group: any) => group.label)).toEqual(['未分配节次与教室', '未分配节次'])
    vm.pendingGroupBy = 'teacher'; await flushPromises()
    expect(vm.pendingGroups.map((group: any) => group.label)).toEqual(['张老师', '李老师'])
    wrapper.unmount()
  })

  it('loads and confirms a one-hop exchange candidate', async () => {
    const { calls } = createFetchMock(); const wrapper = mountWorkspace(); await flushPromises(); const vm = wrapper.vm as any
    await vm.loadVersion(42); vm.openAdjustment(assignment); await vm.previewAdjustment(); expect(vm.exchangeCandidates).toHaveLength(1); vm.selectedExchangeCandidate = vm.exchangeCandidates[0]; vm.adjustmentForm.reason = '交换验收'; await vm.confirmExchange()
    const exchangeCall = calls.find(call => call.url.endsWith('/api/schedule-versions/42/adjustments/exchange'))
    expect(JSON.parse(String(exchangeCall?.init?.body))).toEqual({ occurrenceId: 1, swapOccurrenceId: 2, reason: '交换验收', expectedRevision: 0 }); wrapper.unmount()
  })
})
