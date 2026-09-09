<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  canCancelSolve,
  countAssignedOccurrences,
  getPeriods,
  getQualityPercent,
  getResourceOptions,
  getSlotItems,
  getStatusLabel,
  getWeekdays,
  resolveBoardOccurrences,
  type WorkspaceOccurrence,
  type WorkspaceOptions,
  type WorkspaceViewType,
} from '../utils/workspace'
import { http } from '../api/http'
import { aiDiagnostics, type AiDiagnosis } from '../api/ai'
import { parseScore } from '../utils/score'
import { useTermStore } from '../stores/term'

type Occurrence = WorkspaceOccurrence
type ScheduleOptions = WorkspaceOptions
type PendingGroupBy = 'reason' | 'teacher' | 'class' | 'subject'

type PreviewViolation = {
  code: string
  message: string
  resourceCode: string
}

interface AdjustmentPreview {
  allowed: boolean
  hardViolations: PreviewViolation[]
  affectedAssignmentIds: number[]
  lockedConflict: boolean
  versionId: number
  current: { timeslotCode?: string; roomCode?: string }
  target: { timeslotCode?: string; roomCode?: string }
}

interface SolveDetails {
  jobId: number
  versionId: number
  jobStatus: string
  versionStatus: string
  progress: number
  score?: string
  hardScore?: number | null
  mediumScore?: number | null
  softScore?: number | null
  scoreValid?: boolean
  errorCode?: string
  errorMessage?: string
  attempt: number
  submittedAt?: string
  startedAt?: string
  heartbeatAt?: string
  finishedAt?: string
  cancelRequested?: boolean
  deadlineAt?: string
}

interface WorkspaceState {
  jobId?: number | null
  versionId?: number | null
}

interface SolveReadiness {
  termCode: string
  ready: boolean
  timeslotCount: number
  roomCount: number
  requirementCount: number
  issues: Array<{ code: string; message: string }>
}

const term = useTermStore()
const router = useRouter()
const loading = ref(false)
const cancelling = ref(false)
const jobId = ref<number | null>(null)
const versionId = ref<number | null>(null)
const versionRevision = ref(0)
const versionEditLocked = ref(false)
const versionLockOwner = ref('')
const versionArchived = ref(false)
const commandHistory = ref<Array<{ groupId: string; commandType: string; state: string; reason: string; resultRevision: number; commands: Array<{ occurrenceId: number; sequence: number }> }>>([])
const historyLoading = ref(false)
const aiLoading = ref(false)
const aiResult = ref<AiDiagnosis | null>(null)
const aiError = ref('')

async function runAiDiagnostics() {
  if (!versionId.value || aiLoading.value) return
  aiLoading.value = true
  aiError.value = ''
  aiResult.value = null
  try {
    aiResult.value = await aiDiagnostics(versionId.value)
  } catch (error) {
    aiError.value = error instanceof Error ? error.message : '诊断生成失败'
  } finally {
    aiLoading.value = false
  }
}

watch(() => versionId.value, () => {
  aiResult.value = null
  aiError.value = ''
})
const jobStatus = ref('待开始')
const versionStatus = ref('')
const progress = ref(0)
const attempt = ref(0)
const jobErrorCode = ref('')
const jobSubmittedAt = ref('')
const jobStartedAt = ref('')
const jobHeartbeatAt = ref('')
const jobFinishedAt = ref('')
const jobDeadline = ref('')
const score = ref<string | null>(null)
const hardScore = ref<number | null>(null)
const mediumScore = ref<number | null>(null)
const softScore = ref<number | null>(null)
const errorMessage = ref('')
const readiness = ref<SolveReadiness | null>(null)
const readinessLoading = ref(false)
const occurrences = ref<Occurrence[]>([])
const filteredOccurrences = ref<Occurrence[]>([])
const viewType = ref<WorkspaceViewType>('CLASS')
const resourceCode = ref('')
const options = ref<ScheduleOptions>({
  timeslots: [],
  rooms: [],
  studentGroups: [],
  teachers: []
})
const selectedOccurrence = ref<Occurrence | null>(null)
const adjustmentOpen = ref(false)
const adjustmentForm = ref({ timeslotCode: '', roomCode: '', reason: '' })
const preview = ref<AdjustmentPreview | null>(null)
const previewLoading = ref(false)
const confirmingAdjustment = ref(false)
const lockingAssignment = ref(false)
const exchangeCandidates = ref<Array<{ occurrenceId: number; occurrenceKey: string; subjectName: string; studentGroupCode: string; teacherCode: string; roomCode: string; timeslotCode: string }>>([])
const exchangeLoading = ref(false)
const dragOccurrence = ref<Occurrence | null>(null)
const selectedExchangeCandidate = ref<{ occurrenceId: number; occurrenceKey: string; subjectName: string; studentGroupCode: string; teacherCode: string; roomCode: string; timeslotCode: string } | null>(null)
const searchQuery = ref('')
const pendingGroupBy = ref<PendingGroupBy>('reason')
const message = ref('')
const termName = ref('')
const masterDataSummary = ref({ teachers: 0, studentGroups: 0, subjects: 0, rooms: 0 })
const backendPublishable = ref(false)
const publishDialogOpen = ref(false)
const publishing = ref(false)
const releaseNote = ref('')
const releaseConfirmed = ref(false)
const solveDialogOpen = ref(false)
const activeView = computed(() => ({ CLASS: '班级课表', TEACHER: '教师课表', ROOM: '教室课表' })[viewType.value])
const assignedCount = computed(() => countAssignedOccurrences(occurrences.value))
const canEditVersion = computed(() => ['DRAFT', 'CANDIDATE'].includes(versionStatus.value) && !versionEditLocked.value && !versionArchived.value)
const latestAppliedCommand = computed(() => commandHistory.value.find(item => item.state === 'APPLIED'))
const latestUndoneCommand = computed(() => commandHistory.value.find(item => item.state === 'UNDONE'))
const publishable = computed(() => backendPublishable.value)
const releaseReady = computed(() => publishable.value && releaseConfirmed.value && Boolean(releaseNote.value.trim()))
const qualityPercent = computed(() => getQualityPercent(occurrences.value))
const selectedResource = computed(() => resourceOptions.value.find(item => item.code === resourceCode.value))
const resourceOptions = computed(() => getResourceOptions(viewType.value, options.value))
const weekdays = computed(() => getWeekdays(options.value.timeslots))
const periods = computed(() => getPeriods(options.value.timeslots))
const boardOccurrences = computed(() => resolveBoardOccurrences(filteredOccurrences.value, versionId.value, occurrences.value))
const pendingOccurrences = computed(() => occurrences.value.filter(item => {
  if (item.timeslotCode && item.roomCode) return false
  const query = searchQuery.value.trim().toLowerCase()
  return !query || [item.subjectCode, item.subjectName, item.teacherCode, item.teacherName, item.studentGroupCode, item.studentGroupName].some(value => value.toLowerCase().includes(query))
}))
function pendingReason(item: Occurrence) {
  if (!item.timeslotCode && !item.roomCode) return '未分配节次与教室'
  if (!item.timeslotCode) return '未分配节次'
  if (!item.roomCode) return '未分配教室'
  return '需要复核'
}
function pendingGroupLabel(item: Occurrence) {
  if (pendingGroupBy.value === 'teacher') return item.teacherName || item.teacherCode || '未指定教师'
  if (pendingGroupBy.value === 'class') return item.studentGroupName || item.studentGroupCode || '未指定班级'
  if (pendingGroupBy.value === 'subject') return item.subjectName || item.subjectCode || '未指定课程'
  return pendingReason(item)
}
const pendingGroups = computed(() => {
  const groups = new Map<string, Occurrence[]>()
  for (const item of pendingOccurrences.value) {
    const label = pendingGroupLabel(item)
    groups.set(label, [...(groups.get(label) ?? []), item])
  }
  return [...groups.entries()].map(([label, items]) => ({ label, items }))
})
const gridStyle = computed(() => ({ gridTemplateColumns: `58px repeat(${Math.max(weekdays.value.length, 1)}, minmax(86px, 1fr))` }))
const canCancel = computed(() => canCancelSolve(jobId.value, jobStatus.value, cancelling.value))
const statusLabel = computed(() => getStatusLabel(jobStatus.value, versionStatus.value))
const hasExistingSolveContext = computed(() => Boolean(jobId.value || versionId.value || occurrences.value.length))
const solveStateInfo = computed(() => {
  if (jobStatus.value === 'FAILED') {
    const known: Record<string, { category: string; detail: string; nextStep: string; retryable: boolean; actionPath?: string }> = {
      SOLVER_DATA_NOT_READY: { category: '输入数据未就绪', detail: '求解器在读取基础数据或教学需求时发现前置条件不足。', nextStep: '检查基础数据和教学计划，修复后重新求解。', retryable: true, actionPath: '/master-data' },
      DEADLINE_EXCEEDED: { category: '执行超时', detail: '任务在截止时间内没有完成，原候选版本仍然保留。', nextStep: '缩小求解范围或调整规则后重新求解。', retryable: true },
      SOLVER_ERROR: { category: '求解器异常', detail: '求解过程发生未分类异常，当前候选版本未被覆盖。', nextStep: '先查看错误详情，确认数据无误后重新求解。', retryable: true },
      STALE_JOB: { category: '任务结果已过期', detail: '任务完成时版本状态已经变化，迟到结果被系统丢弃。', nextStep: '刷新工作台并重新提交求解。', retryable: true },
    }
    const fallback = { category: '任务失败', detail: '系统没有生成可用候选结果，原有版本仍然保留。', nextStep: '查看错误详情后重试。', retryable: true }
    const state = known[jobErrorCode.value] ?? fallback
    return { tone: 'danger', title: '求解失败', code: jobErrorCode.value || '未提供错误码', ...state }
  }
  if (jobStatus.value === 'CANCELLED') return { tone: 'warning', title: '求解已取消', code: '', category: '人工取消', detail: '任务已停止，不会覆盖已有候选版本。', nextStep: '确认数据和规则后可以重新提交。', retryable: true }
  if (jobStatus.value === 'COMPLETED') return { tone: 'success', title: '候选方案已生成', code: '', category: '求解完成', detail: '结果已加载到当前工作台，可以继续诊断、微调或发布。', nextStep: assignedCount.value === occurrences.value.length ? '检查发布清单后确认是否发布。' : '先处理待排任务和冲突，再进入发布检查。', retryable: false }
  if (jobStatus.value === 'RUNNING') return { tone: 'info', title: '正在求解', code: '', category: '执行中', detail: '系统正在计算候选方案，完成后会自动加载结果。', nextStep: '可以等待完成，也可以取消当前任务。', retryable: false }
  if (jobStatus.value === 'QUEUED') return { tone: 'info', title: '等待执行', code: '', category: '排队中', detail: '任务已提交，正在等待求解 Worker 获取执行权。', nextStep: '任务会按队列自动执行。', retryable: false }
  return { tone: 'info', title: '尚未开始', code: '', category: '待开始', detail: '完成数据和规则检查后提交一次求解任务。', nextStep: '确认当前学期后开始自动排课。', retryable: false }
})
const affectedOccurrences = computed(() => {
  const ids = new Set(preview.value?.affectedAssignmentIds ?? [])
  return occurrences.value.filter(item => ids.has(item.occurrenceId))
})
const orderedTimeslotOptions = computed(() => {
  const pinned = selectedOccurrence.value?.pinnedPeriodCode
  const current = selectedOccurrence.value?.timeslotCode
  return [...options.value.timeslots].sort((left, right) => {
    const score = (item: typeof left) => item.code === current ? 0 : item.code === pinned ? 1 : 2
    return score(left) - score(right)
  })
})
const orderedRoomOptions = computed(() => {
  const current = selectedOccurrence.value?.roomCode
  const studentCount = selectedOccurrence.value?.studentCount ?? 0
  return [...options.value.rooms].sort((left, right) => {
    const score = (item: typeof left) => item.code === current ? 0 : item.capacity >= studentCount ? 1 : 2
    return score(left) - score(right)
  })
})

function formatTimestamp(value: string) {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

function sourceLabel(source?: string) {
  return ({ SOLVER: '算法生成', MANUAL: '人工调整', IMPORT: '导入结果' } as Record<string, string>)[source ?? ''] ?? source ?? '未标记'
}

function activityTypeLabel(item?: Occurrence | null) {
  return item?.activityTypeSnapshot || item?.activityType || '常规课次'
}

// 六阶段流水线计算（用于步骤明朗化导航）
const currentPipelineStep = computed(() => {
  if (versionStatus.value === 'PUBLISHED') return 6
  if (versionId.value && occurrences.value.length > 0) return 5
  if (loading.value || (jobId.value && ['QUEUED', 'RUNNING'].includes(jobStatus.value)) || readiness.value?.ready) return 4
  if ((readiness.value?.requirementCount ?? 0) > 0) return 3
  if ((readiness.value?.roomCount ?? 0) > 0 && (readiness.value?.timeslotCount ?? 0) > 0) return 2
  return 1
})
let pollTimer: number | undefined
let pollGeneration = 0
const restorePromises = new Map<string, Promise<void>>()

const workspaceStatePrefix = 'class-schedule.workspace:'

function workspaceStateKey(termCode = term.selectedTermCode.value) {
  return `${workspaceStatePrefix}${termCode}`
}

function readWorkspaceState(termCode = term.selectedTermCode.value): WorkspaceState | null {
  if (typeof window === 'undefined' || !termCode) return null
  try {
    const raw = window.localStorage.getItem(workspaceStateKey(termCode))
    if (!raw) return null
    const parsed = JSON.parse(raw) as WorkspaceState
    const validId = (value: unknown) => value === null || value === undefined || (typeof value === 'number' && Number.isInteger(value) && value > 0)
    if (!validId(parsed.jobId) || !validId(parsed.versionId) || (!parsed.jobId && !parsed.versionId)) return null
    return { jobId: parsed.jobId ?? null, versionId: parsed.versionId ?? null }
  } catch {
    return null
  }
}

function persistWorkspaceState(state: WorkspaceState, termCode = term.selectedTermCode.value) {
  if (typeof window === 'undefined' || !termCode) return
  try {
    window.localStorage.setItem(workspaceStateKey(termCode), JSON.stringify({ jobId: state.jobId ?? null, versionId: state.versionId ?? null }))
  } catch {
    // Browser storage can be unavailable or disabled; the current session still works.
  }
}

function clearWorkspaceState(termCode = term.selectedTermCode.value) {
  if (typeof window === 'undefined' || !termCode) return
  try {
    window.localStorage.removeItem(workspaceStateKey(termCode))
  } catch {
    // Browser storage can be unavailable or disabled; the current session still works.
  }
}

function applySolveDetails(result: SolveDetails) {
  jobId.value = result.jobId
  versionId.value = result.versionId
  jobStatus.value = result.jobStatus
  versionStatus.value = result.versionStatus
  progress.value = result.progress
  attempt.value = result.attempt
  jobErrorCode.value = result.errorCode ?? ''
  jobSubmittedAt.value = result.submittedAt ?? ''
  jobStartedAt.value = result.startedAt ?? ''
  jobHeartbeatAt.value = result.heartbeatAt ?? ''
  jobFinishedAt.value = result.finishedAt ?? ''
  jobDeadline.value = result.deadlineAt ?? ''
  score.value = result.score === '等待结果' ? null : result.score ?? null
  hardScore.value = result.hardScore ?? parseScore(result.score).hard
  mediumScore.value = result.mediumScore ?? parseScore(result.score).medium
  softScore.value = result.softScore ?? parseScore(result.score).soft
  errorMessage.value = result.errorMessage ?? ''
  persistWorkspaceState({ jobId: result.jobId, versionId: result.versionId })
}

function clearPollTimer() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  return http<T>(url, init)
}

function setSolveError(text: string) {
  errorMessage.value = text
  message.value = text
}

async function loadReadiness() {
  if (!term.hasValidTerm.value) {
    readiness.value = null
    return null
  }
  readinessLoading.value = true
  try {
    readiness.value = await requestJson<SolveReadiness>(`/api/solve-readiness?termCode=${encodeURIComponent(term.selectedTermCode.value)}`)
    return readiness.value
  } catch (error) {
    readiness.value = null
    setSolveError(error instanceof Error ? `排课前置检查失败：${error.message}` : '排课前置检查失败，请重试')
    return null
  } finally {
    readinessLoading.value = false
  }
}

async function loadMasterData() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    masterDataSummary.value = { teachers: 0, studentGroups: 0, subjects: 0, rooms: 0 }
    termName.value = term.error.value || '暂无可用学期'
    return
  }
  termName.value = term.terms.value.find(item => item.code === term.selectedTermCode.value)?.name ?? ''
  try {
    const data = await requestJson<{ terms?: Array<{ code: string; name: string }>; teachers?: unknown[]; studentGroups?: unknown[]; subjects?: unknown[]; rooms?: unknown[] }>('/api/master-data/overview')
    const currentTerm = data.terms?.find(item => item.code === term.selectedTermCode.value) ?? data.terms?.[0]
    termName.value = currentTerm?.name ?? termName.value
    masterDataSummary.value = {
      teachers: data.teachers?.length ?? 0,
      studentGroups: data.studentGroups?.length ?? 0,
      subjects: data.subjects?.length ?? 0,
      rooms: data.rooms?.length ?? 0
    }
  } catch {
    setSolveError('基础数据暂时无法读取，无法安全开始排课')
  }
  await loadReadiness()
  await restoreWorkspaceState()
}

async function restoreWorkspaceState(termCode = term.selectedTermCode.value) {
  if (!term.hasValidTerm.value || !termCode) return
  const existing = restorePromises.get(termCode)
  if (existing) return existing

  const promise = (async () => {
    const state = readWorkspaceState(termCode)
    if (!state) return
    try {
      if (state.jobId) {
        const result = await requestJson<SolveDetails>(`/api/solve-jobs/${state.jobId}`)
        if (termCode !== term.selectedTermCode.value) return
        applySolveDetails(result)
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(result.jobStatus)) {
          loading.value = false
          if (result.jobStatus === 'COMPLETED') await loadVersion(result.versionId)
          return
        }
        clearPollTimer()
        pollGeneration += 1
        const generation = pollGeneration
        loading.value = true
        void poll(result.jobId, generation)
        return
      }
      if (state.versionId && termCode === term.selectedTermCode.value) await loadVersion(state.versionId)
    } catch {
      if (termCode !== term.selectedTermCode.value) return
      clearWorkspaceState(termCode)
      clearPollTimer()
      jobId.value = null
      versionId.value = null
      jobStatus.value = '待开始'
      versionStatus.value = ''
      progress.value = 0
      attempt.value = 0
      jobErrorCode.value = ''
      jobSubmittedAt.value = ''
      jobStartedAt.value = ''
      jobHeartbeatAt.value = ''
      jobFinishedAt.value = ''
      jobDeadline.value = ''
      score.value = null
      hardScore.value = null
      mediumScore.value = null
      softScore.value = null
      occurrences.value = []
      filteredOccurrences.value = []
      options.value = { timeslots: [], rooms: [], studentGroups: [], teachers: [] }
      backendPublishable.value = false
      loading.value = false
    }
  })()
  restorePromises.set(termCode, promise)
  try {
    await promise
  } finally {
    if (restorePromises.get(termCode) === promise) restorePromises.delete(termCode)
  }
}

async function loadVersion(id = versionId.value) {
  if (!id) return
  const version = await requestJson<{ id: number; status: string; score?: string; hardScore?: number | null; mediumScore?: number | null; softScore?: number | null; publishable: boolean; assignments: Occurrence[]; revision?: number; editLocked?: boolean; editLockOwner?: string; archivedAt?: string }>(`/api/schedule-versions/${id}`)
  versionId.value = version.id
  versionStatus.value = version.status
  versionRevision.value = version.revision ?? 0
  versionEditLocked.value = Boolean(version.editLocked)
  versionLockOwner.value = version.editLockOwner ?? ''
  versionArchived.value = Boolean(version.archivedAt) || version.status === 'ARCHIVED'
  score.value = version.score ?? null
  hardScore.value = version.hardScore ?? parseScore(version.score).hard
  mediumScore.value = version.mediumScore ?? parseScore(version.score).medium
  softScore.value = version.softScore ?? parseScore(version.score).soft
  backendPublishable.value = version.publishable
  occurrences.value = version.assignments ?? []
  if (selectedOccurrence.value) {
    selectedOccurrence.value = occurrences.value.find(item => item.occurrenceId === selectedOccurrence.value?.occurrenceId) ?? null
  }
  await loadOptions(id)
  await loadFiltered()
  await loadCommandHistory()
}

async function loadCommandHistory() {
  if (!versionId.value) return
  historyLoading.value = true
  try {
    commandHistory.value = await requestJson<typeof commandHistory.value>(`/api/schedule-versions/${versionId.value}/adjustments/commands`)
  } catch (error) {
    commandHistory.value = []
    message.value = error instanceof Error ? error.message : '命令历史加载失败'
  } finally {
    historyLoading.value = false
  }
}

function mutationHeaders(key: string) {
  return { 'Content-Type': 'application/json', 'Idempotency-Key': key }
}

function newIdempotencyKey(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function loadOptions(id = versionId.value) {
  if (!id) return
  options.value = await requestJson<ScheduleOptions>(`/api/schedule-versions/${id}/options`)
  if (!resourceOptions.value.some(item => item.code === resourceCode.value)) {
    resourceCode.value = resourceOptions.value[0]?.code ?? ''
  }
}

async function loadFiltered() {
  if (!versionId.value) {
    filteredOccurrences.value = occurrences.value
    return
  }
  const query = resourceCode.value ? `&resourceCode=${encodeURIComponent(resourceCode.value)}` : ''
  const result = await requestJson<{ assignments: Occurrence[] }>(`/api/schedule-versions/${versionId.value}/filtered?view=${viewType.value}${query}`)
  filteredOccurrences.value = result.assignments ?? []
}

async function selectView(type: WorkspaceViewType) {
  viewType.value = type
  if (!resourceOptions.value.some(item => item.code === resourceCode.value)) {
    resourceCode.value = resourceOptions.value[0]?.code ?? ''
  }
  try {
    await loadFiltered()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '课表筛选失败'
  }
}

async function selectResource(code: string) {
  resourceCode.value = code
  try {
    await loadFiltered()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '课表筛选失败'
  }
}

function slotItems(weekday: number, period: number) {
  return getSlotItems(boardOccurrences.value, weekday, period)
}

function startDrag(item: Occurrence, event: DragEvent) {
  if (!canEditVersion.value) return
  dragOccurrence.value = item
  event.dataTransfer?.setData('text/plain', String(item.occurrenceId))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

async function dropOnSlot(weekday: number, period: number) {
  if (!dragOccurrence.value || !versionId.value) return
  const target = options.value.timeslots.find(item => item.weekday === weekday && item.period === period)
  if (!target) return
  openAdjustment(dragOccurrence.value)
  adjustmentForm.value.timeslotCode = target.code
  dragOccurrence.value = null
  try {
    await loadExchangeCandidates()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '交换候选加载失败'
  }
}

async function loadExchangeCandidates() {
  if (!versionId.value || !selectedOccurrence.value) return
  exchangeLoading.value = true
  try {
    const result = await requestJson<{ allowedWithoutExchange: boolean; candidates: typeof exchangeCandidates.value }>(`/api/schedule-versions/${versionId.value}/adjustments/exchange-candidates`, {
      method: 'POST', headers: mutationHeaders(newIdempotencyKey('exchange-candidates')),
      body: JSON.stringify({ occurrenceId: selectedOccurrence.value.occurrenceId, timeslotCode: adjustmentForm.value.timeslotCode, roomCode: adjustmentForm.value.roomCode }),
    })
    exchangeCandidates.value = result.candidates ?? []
    if (result.allowedWithoutExchange) exchangeCandidates.value = []
  } finally {
    exchangeLoading.value = false
  }
}

async function confirmExchange() {
  if (!versionId.value || !selectedOccurrence.value || !selectedExchangeCandidate.value || !adjustmentForm.value.reason.trim()) return
  confirmingAdjustment.value = true
  try {
    await requestJson(`/api/schedule-versions/${versionId.value}/adjustments/exchange`, {
      method: 'POST', headers: mutationHeaders(newIdempotencyKey('exchange')),
      body: JSON.stringify({ occurrenceId: selectedOccurrence.value.occurrenceId, swapOccurrenceId: selectedExchangeCandidate.value.occurrenceId, reason: adjustmentForm.value.reason, expectedRevision: versionRevision.value }),
    })
    adjustmentOpen.value = false
    exchangeCandidates.value = []
    selectedExchangeCandidate.value = null
    await loadVersion()
  } catch (error) {
    const typed = error as Error & { code?: string }
    if (typed.code === 'VERSION_REVISION_CONFLICT') {
      message.value = '版本已被其他操作更新，已重新加载最新课表'
      await loadVersion()
    } else message.value = error instanceof Error ? error.message : '交换确认失败'
  } finally {
    confirmingAdjustment.value = false
  }
}

function openAdjustment(item: Occurrence) {
  if (!canEditVersion.value) {
    message.value = versionEditLocked.value ? `版本已由 ${versionLockOwner.value || '其他用户'} 锁定` : '当前版本只读，不能调整课程'
    return
  }
  selectedOccurrence.value = item
  adjustmentForm.value = {
    timeslotCode: item.timeslotCode ?? options.value.timeslots[0]?.code ?? '',
    roomCode: item.roomCode ?? options.value.rooms[0]?.code ?? '',
    reason: ''
  }
  preview.value = null
  exchangeCandidates.value = []
  selectedExchangeCandidate.value = null
  adjustmentOpen.value = true
}

async function lockSelectedAssignment() {
  if (!versionId.value || !selectedOccurrence.value || selectedOccurrence.value.locked || !adjustmentForm.value.reason.trim()) return
  lockingAssignment.value = true
  try {
    const result = await requestJson<{ revision: number }>(`/api/schedule-versions/${versionId.value}/adjustments/${selectedOccurrence.value.occurrenceId}/lock`, {
      method: 'POST',
      headers: mutationHeaders(newIdempotencyKey('assignment-lock')),
      body: JSON.stringify({ reason: adjustmentForm.value.reason.trim(), expectedRevision: versionRevision.value }),
    })
    message.value = '课次已锁定，重新求解时需要重新确认该课次'
    versionRevision.value = result.revision
    await loadVersion()
  } catch (error) {
    const typed = error as Error & { code?: string }
    message.value = typed.code === 'VERSION_REVISION_CONFLICT' ? '版本已被其他操作更新，已重新加载最新课表' : error instanceof Error ? error.message : '锁定课次失败'
    if (typed.code === 'VERSION_REVISION_CONFLICT') await loadVersion()
  } finally {
    lockingAssignment.value = false
  }
}

async function unlockSelectedAssignment() {
  if (!versionId.value || !selectedOccurrence.value || !selectedOccurrence.value.locked) return
  lockingAssignment.value = true
  try {
    const result = await requestJson<{ revision: number }>(`/api/schedule-versions/${versionId.value}/adjustments/${selectedOccurrence.value.occurrenceId}/lock`, {
      method: 'DELETE',
      headers: { 'If-Match': String(versionRevision.value) },
    })
    message.value = '课次已解锁，可以重新调整'
    versionRevision.value = result.revision
    await loadVersion()
  } catch (error) {
    const typed = error as Error & { code?: string }
    message.value = typed.code === 'VERSION_REVISION_CONFLICT' ? '版本已被其他操作更新，已重新加载最新课表' : error instanceof Error ? error.message : '解锁课次失败'
    if (typed.code === 'VERSION_REVISION_CONFLICT') await loadVersion()
  } finally {
    lockingAssignment.value = false
  }
}

function closeAdjustment() {
  if (previewLoading.value || confirmingAdjustment.value) return false
  adjustmentOpen.value = false
  selectedOccurrence.value = null
  preview.value = null
  return true
}

function handleDrawerClose(done: () => void) {
  if (closeAdjustment()) done()
}

async function previewAdjustment() {
  if (!versionId.value || !selectedOccurrence.value || !adjustmentForm.value.timeslotCode || !adjustmentForm.value.roomCode) return
  previewLoading.value = true
  message.value = ''
  try {
    preview.value = await requestJson<AdjustmentPreview>(`/api/schedule-versions/${versionId.value}/adjustments/preview`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ occurrenceId: selectedOccurrence.value.occurrenceId, ...adjustmentForm.value, reason: undefined })
    })
    if (!preview.value.allowed) await loadExchangeCandidates()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '调整预览失败'
  } finally {
    previewLoading.value = false
  }
}

async function confirmAdjustment() {
  if (!versionId.value || !selectedOccurrence.value || !preview.value?.allowed || !adjustmentForm.value.reason.trim()) return
  confirmingAdjustment.value = true
  try {
    const result = await requestJson<{ commandId: number; revision: number }>(`/api/schedule-versions/${versionId.value}/adjustments/${selectedOccurrence.value.occurrenceId}`, {
      method: 'POST', headers: mutationHeaders(newIdempotencyKey('adjustment')), body: JSON.stringify({ ...adjustmentForm.value, expectedRevision: versionRevision.value })
    })
    message.value = `调整已保存，命令 #${result.commandId}`
    adjustmentOpen.value = false
    selectedOccurrence.value = null
    preview.value = null
    backendPublishable.value = false
    await loadVersion()
  } catch (error) {
    const typed = error as Error & { code?: string }
    if (typed.code === 'VERSION_REVISION_CONFLICT') {
      message.value = '版本已被其他操作更新，已重新加载最新课表'
      await loadVersion()
    } else message.value = error instanceof Error ? error.message : '调整确认失败，请重新预览'
  } finally {
    confirmingAdjustment.value = false
  }
}

async function undoLatest() {
  if (!versionId.value || !latestAppliedCommand.value || !canEditVersion.value) return
  try {
    await requestJson(`/api/schedule-versions/${versionId.value}/adjustments/commands/${latestAppliedCommand.value.groupId}/undo`, {
      method: 'POST', headers: mutationHeaders(newIdempotencyKey('undo')), body: JSON.stringify({})
    })
    message.value = '最近一次调整已撤销'
    await loadVersion()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '撤销失败'
  }
}

async function redoLatest() {
  if (!versionId.value || !latestUndoneCommand.value || !canEditVersion.value) return
  try {
    await requestJson(`/api/schedule-versions/${versionId.value}/adjustments/commands/${latestUndoneCommand.value.groupId}/redo`, {
      method: 'POST', headers: mutationHeaders(newIdempotencyKey('redo')), body: JSON.stringify({})
    })
    message.value = '最近一次调整已重做'
    await loadVersion()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '重做失败'
  }
}

async function startSolve() {
  if (!term.hasValidTerm.value) {
    setSolveError(term.error.value || '暂无可用学期，无法开始排课')
    return
  }
  const checked = readiness.value ?? await loadReadiness()
  if (!checked) return
  if (!checked.ready) {
    jobStatus.value = '数据未就绪'
    setSolveError(checked.issues.map(issue => issue.message).join('；') || '当前学期排课基础数据未就绪')
    return
  }
  if (hasExistingSolveContext.value) {
    solveDialogOpen.value = true
    return
  }
  await submitSolve()
}

async function confirmResolve() {
  solveDialogOpen.value = false
  await submitSolve()
}

async function submitSolve() {
  pollGeneration += 1
  const generation = pollGeneration
  clearPollTimer()
  loading.value = true
  jobStatus.value = 'QUEUED'
  versionStatus.value = 'SOLVING'
  progress.value = 0
  attempt.value = 0
  jobErrorCode.value = ''
  jobSubmittedAt.value = ''
  jobStartedAt.value = ''
  jobHeartbeatAt.value = ''
  jobFinishedAt.value = ''
  score.value = null
  hardScore.value = null
  mediumScore.value = null
  softScore.value = null
  occurrences.value = []
  filteredOccurrences.value = []
  options.value = { timeslots: [], rooms: [], studentGroups: [], teachers: [] }
  resourceCode.value = ''
  selectedOccurrence.value = null
  exchangeCandidates.value = []
  selectedExchangeCandidate.value = null
  dragOccurrence.value = null
  searchQuery.value = ''
  pendingGroupBy.value = 'reason'
  backendPublishable.value = false
  errorMessage.value = ''
  message.value = ''
  clearWorkspaceState()
  try {
    const created = await requestJson<{ jobId: number; versionId: number }>('/api/solve-jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idempotencyKey: `workspace-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, termCode: term.selectedTermCode.value }),
    })
    jobId.value = created.jobId
    versionId.value = created.versionId
    persistWorkspaceState({ jobId: created.jobId, versionId: created.versionId })
    void poll(created.jobId, generation)
  } catch (error) {
    loading.value = false
    jobStatus.value = 'FAILED'
    setSolveError(error instanceof Error ? error.message : '无法提交求解任务，请检查后端服务')
  }
}

async function poll(id: number, generation = pollGeneration) {
  if (generation !== pollGeneration) return
  try {
    const result = await requestJson<SolveDetails>(`/api/solve-jobs/${id}`)
    if (generation !== pollGeneration) return
    applySolveDetails(result)
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(result.jobStatus)) {
      clearPollTimer()
      loading.value = false
      if (result.jobStatus === 'COMPLETED') {
        try {
          await loadVersion(result.versionId)
        } catch (error) {
          if (generation !== pollGeneration) return
          setSolveError(error instanceof Error ? `求解已完成，但课表结果加载失败：${error.message}` : '求解已完成，但课表结果加载失败')
        }
      }
      return
    }
    pollTimer = window.setTimeout(() => void poll(id, generation), 700)
  } catch (error) {
    if (generation !== pollGeneration) return
    clearPollTimer()
    loading.value = false
    jobStatus.value = '状态读取失败'
    setSolveError(error instanceof Error ? error.message : '无法读取求解状态')
  }
}

async function cancelSolve() {
  if (!jobId.value || !canCancel.value) return
  cancelling.value = true
  try {
    const result = await requestJson<{ jobStatus: string }>(`/api/solve-jobs/${jobId.value}/cancel`, { method: 'POST' })
    jobStatus.value = result.jobStatus
    message.value = '已提交取消请求'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '取消请求失败'
  } finally {
    cancelling.value = false
  }
}

async function publishVersion() {
  if (!versionId.value || !releaseReady.value || publishing.value) return
  publishing.value = true
  try {
    const result = await requestJson<{ status: string }>(`/api/schedule-versions/${versionId.value}/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ releaseNote: releaseNote.value.trim() }),
    })
    if (result.status === 'PUBLISHED') {
      jobStatus.value = 'PUBLISHED'
      versionStatus.value = 'PUBLISHED'
      backendPublishable.value = false
      publishDialogOpen.value = false
      releaseNote.value = ''
      releaseConfirmed.value = false
      message.value = '候选版本已发布，当前课表进入只读状态'
      await loadFiltered()
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : '版本尚未满足发布条件'
  } finally {
    publishing.value = false
  }
}

function openPublishDialog() {
  if (!versionId.value || !publishable.value || jobStatus.value === 'PUBLISHED') return
  releaseNote.value = ''
  releaseConfirmed.value = false
  publishDialogOpen.value = true
}

onMounted(() => {
  void loadMasterData()
})

watch(() => term.selectedTermCode.value, () => {
  pollGeneration += 1
  clearPollTimer()
  jobId.value = null
  versionId.value = null
  versionRevision.value = 0
  versionStatus.value = ''
  jobStatus.value = '待开始'
  progress.value = 0
  attempt.value = 0
  jobErrorCode.value = ''
  jobStartedAt.value = ''
  jobHeartbeatAt.value = ''
  jobFinishedAt.value = ''
  jobDeadline.value = ''
  score.value = null
  hardScore.value = null
  mediumScore.value = null
  softScore.value = null
  occurrences.value = []
  filteredOccurrences.value = []
  options.value = { timeslots: [], rooms: [], studentGroups: [], teachers: [] }
  readiness.value = null
  backendPublishable.value = false
  selectedOccurrence.value = null
  publishDialogOpen.value = false
  solveDialogOpen.value = false
  releaseNote.value = ''
  releaseConfirmed.value = false
  void loadMasterData()
})

onBeforeUnmount(() => {
  pollGeneration += 1
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
})
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">SCHEDULE / WORKSPACE PIPELINE</p>
      <h1>排课控制台</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● {{ errorMessage ? '需要处理' : '数据已同步' }}</span>
      <el-button plain @click="router.push('/import')">导入数据</el-button>
      <el-button v-if="canCancel" plain :loading="cancelling" @click="cancelSolve">取消求解</el-button>
      <el-button data-testid="start-solve" type="primary" :loading="loading" :disabled="loading || readinessLoading || readiness === null || !readiness.ready" @click="startSolve">{{ loading ? `正在求解 ${progress}%` : (readinessLoading ? '检查排课条件…' : (hasExistingSolveContext ? '重新求解' : '开始自动排课')) }}</el-button>
      <div class="avatar">教</div>
    </div>
  </header>

  <!-- 六阶段工作流导航流水线 (Pipeline Flow Stepper) -->
  <section class="pipeline-flow-card">
    <div class="pipeline-header">
      <div>
        <span class="pipeline-tag">SCHEDULE PIPELINE</span>
        <h3 class="pipeline-title">全流程排课向导 · 第 {{ currentPipelineStep }} / 6 步</h3>
      </div>
      <div class="pipeline-guide-hint">
        <span v-if="currentPipelineStep === 1">需录入或导入基础教室与时段节次</span>
        <span v-else-if="currentPipelineStep === 2">需补充本学期的教学需求和课时计划</span>
        <span v-else-if="currentPipelineStep === 3">需检查规则强度、作用范围和排课前置条件</span>
        <span v-else-if="currentPipelineStep === 4">已具备排课条件，可随时执行自动排课</span>
        <span v-else-if="currentPipelineStep === 5">候选版本已生成，可进行冲突诊断、微调或交换</span>
        <span v-else>课表已成功发布为正式版，全校只读</span>
      </div>
    </div>

    <div class="pipeline-steps">
      <!-- 步骤 1 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 1,
          completed: currentPipelineStep > 1,
          warning: (readiness?.timeslotCount ?? 0) === 0 || (readiness?.roomCount ?? 0) === 0
        }"
        @click="router.push('/master-data')"
      >
        <div class="step-num">1</div>
        <div class="step-content">
          <strong>基础数据准备</strong>
          <small>{{ readiness?.timeslotCount ?? 0 }}节次 · {{ readiness?.roomCount ?? 0 }}教室</small>
        </div>
        <span class="step-arrow">➔</span>
      </div>

      <!-- 步骤 2 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 2,
          completed: currentPipelineStep > 2,
          warning: (readiness?.requirementCount ?? 0) === 0
        }"
        @click="router.push('/teaching-plan')"
      >
        <div class="step-num">2</div>
        <div class="step-content">
          <strong>教学计划</strong>
          <small>{{ readiness?.requirementCount ?? 0 }}项教学需求 · {{ (readiness?.requirementCount ?? 0) > 0 ? '已录入' : '待完善' }}</small>
        </div>
        <span class="step-arrow">➔</span>
      </div>

      <!-- 步骤 3 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 3,
          completed: currentPipelineStep > 3,
          warning: !readiness?.ready && (readiness?.requirementCount ?? 0) > 0
        }"
        @click="router.push('/rule-facts')"
      >
        <div class="step-num">3</div>
        <div class="step-content">
          <strong>规则中心</strong>
          <small>{{ readiness?.ready ? '规则与前置条件已就绪' : '检查规则与数据约束' }}</small>
        </div>
        <span class="step-arrow">➔</span>
      </div>

      <!-- 步骤 4 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 4,
          completed: currentPipelineStep > 4,
          loading: loading,
          warning: readiness !== null && !readiness.ready
        }"
        @click="startSolve"
      >
        <div class="step-num">4</div>
        <div class="step-content">
          <strong>算法求解</strong>
          <small>{{ loading ? `求解中 ${progress}%` : (readiness?.ready ? '条件已就绪' : '等待前置就绪') }}</small>
        </div>
        <span class="step-arrow">➔</span>
      </div>

      <!-- 步骤 5 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 5,
          completed: currentPipelineStep > 5,
          attention: versionId && hardScore !== 0
        }"
      >
        <div class="step-num">5</div>
        <div class="step-content">
          <strong>冲突诊断与微调</strong>
          <small>{{ versionId ? `v${versionId} · ${score ?? '未评分'}` : '尚未生成候选' }}</small>
        </div>
        <span class="step-arrow">➔</span>
      </div>

      <!-- 步骤 6 -->
      <div
        class="pipeline-step-item"
        :class="{
          active: currentPipelineStep === 6,
          completed: versionStatus === 'PUBLISHED',
          ready: publishable && versionStatus !== 'PUBLISHED'
        }"
        @click="openPublishDialog"
      >
        <div class="step-num">6</div>
        <div class="step-content">
          <strong>校验与正式发布</strong>
          <small>{{ versionStatus === 'PUBLISHED' ? '已正式发布' : (publishable ? '达到发布标准' : '待达标') }}</small>
        </div>
      </div>
    </div>
  </section>

  <section class="summary-row">
    <div class="metric"><span>排课状态</span><strong>{{ statusLabel }}</strong><small>{{ jobId ? `任务 #${jobId} · 尝试 ${attempt}` : '尚未提交求解任务' }}</small></div>
    <div class="metric"><span>教学任务</span><strong>{{ assignedCount }} / {{ occurrences.length || '—' }}</strong><small>已分配 / 总任务</small></div>
    <div class="metric"><span>评分</span><strong :class="hardScore === 0 ? 'good' : ''">{{ score ?? '待计算' }}</strong><small>H {{ hardScore ?? '—' }} · M {{ mediumScore ?? '—' }} · S {{ softScore ?? '—' }} · {{ progress }}% · {{ termName }}<span v-if="jobDeadline"> · 截止 {{ new Date(jobDeadline).toLocaleTimeString() }}</span></small></div>
    <div class="metric metric-action"><span>当前版本</span><strong>{{ versionId ? `版本 v${versionId}` : '未创建版本' }}</strong><small>{{ versionStatus || '等待求解' }}</small></div>
  </section>

  <section class="toolbar">
    <div class="view-tabs">
      <button v-for="view in [{ type: 'CLASS', label: '班级课表' }, { type: 'TEACHER', label: '教师课表' }, { type: 'ROOM', label: '教室课表' }]" :key="view.type" :class="{ selected: viewType === view.type }" :data-testid="`view-${view.type.toLowerCase()}`" @click="selectView(view.type as 'CLASS' | 'TEACHER' | 'ROOM')">{{ view.label }}</button>
    </div>
    <div class="toolbar-tools">
      <el-select v-if="resourceOptions.length" :model-value="resourceCode" size="small" class="resource-select" @update:model-value="selectResource">
        <el-option v-for="item in resourceOptions" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" />
      </el-select>
      <span v-else class="toolbar-empty">完成一次求解后选择资源</span>
    </div>
  </section>

  <section class="board-layout">
    <aside class="task-panel panel">
      <div class="panel-heading"><div><span class="eyebrow">TASK POOL</span><h2>待排任务</h2></div><span class="count">{{ pendingOccurrences.length }}</span></div>
      <input class="search" placeholder="搜索课程、教师或班级" v-model="searchQuery" />
      <div v-if="pendingOccurrences.length" class="task-list">
        <label class="task-group-select"><span>分组</span><select v-model="pendingGroupBy"><option value="reason">未排原因</option><option value="teacher">教师</option><option value="class">班级</option><option value="subject">课程</option></select></label>
        <section v-for="group in pendingGroups" :key="group.label" class="task-group">
          <div class="task-group-heading"><strong>{{ group.label }}</strong><span>{{ group.items.length }}</span></div>
          <div v-for="item in group.items" :key="item.occurrenceId" class="task-item" @click="openAdjustment(item)"><span class="task-color"></span><div><strong>{{ item.subjectName }}</strong><small>{{ item.studentGroupName }} · {{ item.teacherName }}</small></div></div>
        </section>
      </div>
      <div v-else class="empty-state"><span class="empty-icon">✓</span><strong>{{ occurrences.length ? '没有待排任务' : '还没有求解结果' }}</strong><small>{{ occurrences.length ? '所有教学任务都有时间和教室' : '导入教学计划或运行自动排课' }}</small></div>
    </aside>

    <div class="timetable panel">
      <div class="panel-heading"><div><span class="eyebrow">{{ viewType }} VIEW</span><h2>{{ selectedResource?.name ?? activeView }}</h2><small class="resource-caption">{{ selectedResource?.code ?? '未选择资源' }}</small></div><span class="readonly-badge">{{ jobStatus === 'PUBLISHED' ? '已发布只读' : '候选可编辑' }}</span></div>
      <div v-if="!weekdays.length" class="empty-state board-empty"><span class="empty-icon">＋</span><strong>等待课表节次</strong><small>完成求解后按当前学期节次生成课表</small></div>
      <div v-else class="grid" :style="gridStyle">
        <div class="grid-corner">节次</div>
        <div v-for="day in weekdays" :key="day.number" class="day-head">{{ day.label }}</div>
        <template v-for="period in periods" :key="period">
          <div class="period-label">第{{ period }}节</div>
          <div v-for="day in weekdays" :key="`${period}-${day.number}`" class="slot" @dragover.prevent @drop.prevent="dropOnSlot(day.number, period)">
            <button v-for="item in slotItems(day.number, period)" :key="item.occurrenceId" class="lesson-card" :class="{ locked: item.locked }" :data-testid="`assignment-${item.occurrenceId}`" :draggable="!item.locked && jobStatus !== 'PUBLISHED'" :title="item.locked ? '课程已锁定' : '打开调整预览或拖动课程'" @dragstart="startDrag(item, $event)" @click="openAdjustment(item)">
              <strong>{{ item.subjectName }}</strong><small>{{ viewType === 'CLASS' ? item.teacherName : item.studentGroupName }}</small><em>{{ item.roomName ?? '待分配教室' }}<span v-if="item.locked"> · 锁定</span></em>
            </button>
          </div>
        </template>
      </div>
    </div>

    <aside class="detail-panel panel">
      <div class="panel-heading"><div><span class="eyebrow">DETAIL</span><h2>排课提示</h2></div><span class="readonly-badge">{{ canEditVersion ? (versionEditLocked ? '锁定' : '可编辑') : '只读' }}</span></div>
      <div v-if="readiness" class="notice" :class="{ success: readiness.ready, warning: !readiness.ready }"><span>{{ readiness.ready ? '✓' : '!' }}</span><div><strong>{{ readiness.ready ? '排课条件已就绪' : '排课条件未就绪' }}</strong><small>节次 {{ readiness.timeslotCount }} · 启用教室 {{ readiness.roomCount }} · 有效教学需求 {{ readiness.requirementCount }}<span v-if="!readiness.ready">；{{ readiness.issues.map(issue => issue.message).join('；') }}</span></small></div></div>
      <div v-if="jobId || loading || errorMessage" class="solve-status-card" :class="`solve-status-${solveStateInfo.tone}`" data-testid="solve-status">
        <div class="solve-status-heading"><div><span class="eyebrow">SOLVE JOB #{{ jobId ?? '—' }}</span><strong>{{ solveStateInfo.title }}</strong></div><span class="solve-status-category">{{ solveStateInfo.category }}</span></div>
        <p>{{ solveStateInfo.detail }}</p>
        <div class="solve-status-grid">
          <div><span>进度</span><strong>{{ progress }}%</strong></div>
          <div><span>尝试次数</span><strong>{{ attempt || '—' }}</strong></div>
          <div><span>提交时间</span><strong>{{ formatTimestamp(jobSubmittedAt) }}</strong></div>
          <div><span>开始时间</span><strong>{{ formatTimestamp(jobStartedAt) }}</strong></div>
          <div><span>完成时间</span><strong>{{ formatTimestamp(jobFinishedAt) }}</strong></div>
          <div><span>截止时间</span><strong>{{ formatTimestamp(jobDeadline) }}</strong></div>
          <div><span>最近心跳</span><strong>{{ formatTimestamp(jobHeartbeatAt) }}</strong></div>
        </div>
        <div v-if="jobStatus === 'FAILED'" class="solve-failure-detail"><span>失败码 {{ solveStateInfo.code }}</span><small>{{ errorMessage || '后端未提供进一步错误说明' }}</small><a :href="`/problems?termCode=${encodeURIComponent(term.selectedTermCode.value)}&solveJobId=${jobId}&title=${encodeURIComponent(`求解失败：${solveStateInfo.category}`)}`">创建问题记录</a></div>
        <div class="solve-next-step"><span>下一步</span><strong>{{ solveStateInfo.nextStep }}</strong></div>
        <div class="solve-status-actions">
          <el-button v-if="solveStateInfo.actionPath" size="small" plain @click="router.push(solveStateInfo.actionPath)">去检查数据</el-button>
          <el-button v-if="solveStateInfo.retryable && !loading" size="small" plain @click="startSolve">重新求解</el-button>
        </div>
      </div>
      <div class="notice"><span>↗</span><div><strong>点击课程进行调整</strong><small>先选择目标节次和教室，后端会显示冲突及受影响课程</small></div></div>
      <div v-if="errorMessage && jobStatus !== 'FAILED'" class="import-issues"><strong>{{ errorMessage }}</strong></div>
      <div v-if="message" class="inline-message">{{ message }}</div>
      <div v-if="versionId && commandHistory.length" class="command-history"><div class="history-heading"><strong>最近调整</strong><span>revision {{ versionRevision }}</span></div><div v-for="command in commandHistory.slice(0, 3)" :key="command.groupId" class="history-row"><span>{{ command.commandType }}</span><small>{{ command.reason }} · {{ command.state }}</small></div><div class="history-actions"><el-button size="small" plain :disabled="!latestAppliedCommand || !canEditVersion" @click="undoLatest">撤销</el-button><el-button size="small" plain :disabled="!latestUndoneCommand || !canEditVersion" @click="redoLatest">重做</el-button></div></div>
      <div class="quality"><div><span>方案完整度</span><strong>{{ qualityPercent }}%</strong></div><div class="quality-track"><i :style="{ width: `${qualityPercent}%` }"></i></div></div>
      <div v-if="versionId" class="ai-block">
        <el-button size="small" plain :disabled="aiLoading" :loading="aiLoading" @click="runAiDiagnostics">AI 诊断</el-button>
        <div v-if="aiError" class="error-message">{{ aiError }}</div>
        <template v-if="aiResult">
          <div v-for="(finding, index) in aiResult.findings" :key="index" class="ai-finding" :class="finding.severity.toLowerCase()">
            <span class="ai-severity">{{ finding.severity }}</span>
            <div><strong>{{ finding.title }}</strong><small>{{ finding.detail }}</small></div>
          </div>
          <ul v-if="aiResult.suggestions.length" class="ai-suggestions"><li v-for="s in aiResult.suggestions" :key="s">{{ s }}</li></ul>
        </template>
      </div>
      <el-button class="publish-btn" type="primary" plain :disabled="!publishable || jobStatus === 'PUBLISHED'" @click="openPublishDialog">{{ jobStatus === 'PUBLISHED' ? '版本已发布' : '发布候选版本' }}</el-button>
    </aside>
  </section>

  <el-drawer v-model="adjustmentOpen" title="调整课程" size="420px" data-testid="adjustment-drawer" :before-close="handleDrawerClose">
    <template v-if="selectedOccurrence">
      <div class="drawer-lesson"><span class="eyebrow">ASSIGNMENT #{{ selectedOccurrence.occurrenceId }}</span><h2>{{ selectedOccurrence.subjectName }}</h2><p>{{ selectedOccurrence.studentGroupName }} · {{ selectedOccurrence.teacherName }}</p><div class="drawer-facts"><div><span>当前节次</span><strong>{{ selectedOccurrence.timeslotLabel || selectedOccurrence.timeslotCode || '未分配' }}</strong></div><div><span>当前教室</span><strong>{{ selectedOccurrence.roomName || selectedOccurrence.roomCode || '未分配' }}</strong></div><div><span>活动组</span><strong>{{ selectedOccurrence.activityGroupCode || '无' }}</strong></div><div><span>课次类型</span><strong>{{ activityTypeLabel(selectedOccurrence) }}</strong></div><div><span>固定/锁定</span><strong>{{ selectedOccurrence.pinnedPeriodCode ? `固定节次 · ${selectedOccurrence.pinnedPeriodCode}` : (selectedOccurrence.locked ? '已锁定' : '可调整') }}</strong></div><div><span>来源</span><strong>{{ sourceLabel(selectedOccurrence.source) }}</strong></div><div><span>学生人数</span><strong>{{ selectedOccurrence.studentCount ?? '—' }}</strong></div><div><span>教室容量</span><strong>{{ selectedOccurrence.roomCapacity ?? '—' }}</strong></div></div><div v-if="selectedOccurrence.requiredFeatures?.length" class="drawer-requirements"><span>所需教室特征</span><strong>{{ selectedOccurrence.requiredFeatures.join('、') }}</strong></div><el-tag v-if="selectedOccurrence.locked" type="warning" effect="plain">已锁定</el-tag></div>
      <el-form v-if="!selectedOccurrence.locked" label-position="top" class="adjustment-form">
        <el-form-item label="目标节次"><el-select v-model="adjustmentForm.timeslotCode" class="full-width"><el-option v-for="item in orderedTimeslotOptions" :key="item.code" :label="`${item.label} · ${item.code}${item.code === selectedOccurrence.pinnedPeriodCode ? ' · 固定节次' : ''}`" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="目标教室"><el-select v-model="adjustmentForm.roomCode" class="full-width"><el-option v-for="item in orderedRoomOptions" :key="item.code" :label="`${item.name} · ${item.code} · 容量 ${item.capacity}${item.capacity >= (selectedOccurrence.studentCount ?? 0) ? ' · 容量匹配' : ' · 容量不足'}`" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="调整原因" required><el-input v-model="adjustmentForm.reason" type="textarea" :rows="3" placeholder="请输入本次调整的业务原因" /></el-form-item>
      </el-form>
      <el-button v-if="!selectedOccurrence.locked" class="full-width" data-testid="preview-adjustment" :loading="previewLoading" @click="previewAdjustment">预览调整</el-button>
      <div v-if="!selectedOccurrence.locked && preview" class="preview-result" :class="preview.allowed ? 'preview-ok' : 'preview-blocked'">
        <strong>{{ preview.allowed ? '可以放置' : '存在硬冲突，不能确认' }}</strong>
        <span v-if="preview.lockedConflict">涉及锁定课程</span>
        <div v-if="preview.affectedAssignmentIds.length" class="affected-lessons"><span>受影响课次</span><strong v-if="affectedOccurrences.length">{{ affectedOccurrences.map(item => `${item.subjectName} · ${item.studentGroupName}`).join('；') }}</strong><strong v-else>{{ preview.affectedAssignmentIds.join('、') }}</strong></div>
        <span class="preview-location">当前位置：{{ preview.current.timeslotCode || '未分配' }} · {{ preview.current.roomCode || '未分配' }}；目标：{{ preview.target.timeslotCode || '未分配' }} · {{ preview.target.roomCode || '未分配' }}</span>
        <span v-for="violation in preview.hardViolations" :key="`${violation.code}-${violation.resourceCode}`">{{ violation.code }}：{{ violation.message }}</span>
      </div>
      <div v-if="!selectedOccurrence.locked && exchangeLoading" class="exchange-candidates">正在计算交换候选…</div>
      <div v-if="!selectedOccurrence.locked && exchangeCandidates.length" class="exchange-candidates" data-testid="exchange-candidates"><strong>可交换课程</strong><button v-for="candidate in exchangeCandidates" :key="candidate.occurrenceId" :class="{ selected: selectedExchangeCandidate?.occurrenceId === candidate.occurrenceId }" @click="selectedExchangeCandidate = candidate">{{ candidate.subjectName }} · {{ candidate.studentGroupCode }}<small>{{ candidate.teacherCode }} · {{ candidate.timeslotCode }} · {{ candidate.roomCode }}</small></button></div>
      <div class="drawer-actions"><el-button @click="closeAdjustment">取消</el-button><el-button v-if="selectedOccurrence.locked" data-testid="unlock-assignment" type="warning" :loading="lockingAssignment" @click="unlockSelectedAssignment">解锁此课次</el-button><el-button v-else data-testid="lock-assignment" plain :loading="lockingAssignment" :disabled="!adjustmentForm.reason.trim()" @click="lockSelectedAssignment">锁定此课次</el-button><el-button v-if="!selectedOccurrence.locked && selectedExchangeCandidate" data-testid="confirm-exchange" type="warning" :loading="confirmingAdjustment" :disabled="!adjustmentForm.reason.trim()" @click="confirmExchange">确认交换</el-button><el-button v-else-if="!selectedOccurrence.locked" data-testid="confirm-adjustment" type="primary" :loading="confirmingAdjustment" :disabled="!preview?.allowed || !adjustmentForm.reason.trim()" @click="confirmAdjustment">确认调整</el-button></div>
    </template>
  </el-drawer>

  <el-dialog v-model="publishDialogOpen" title="发布前确认" width="460px" data-testid="publish-dialog">
    <div class="release-dialog">
      <div class="release-dialog-summary">
        <strong>版本 v{{ versionId }} · {{ termName || term.selectedTermCode.value }}</strong>
        <span>{{ occurrences.length }} 个教学任务 · 已分配 {{ assignedCount }} 个 · 硬约束 {{ hardScore ?? '—' }}</span>
      </div>
      <ul class="release-dialog-checks">
        <li><span class="check-ok">✓</span><div><strong>任务完整性</strong><small>{{ assignedCount }} / {{ occurrences.length }} 个教学任务已有节次和教室</small></div></li>
        <li><span class="check-ok">✓</span><div><strong>独立校验</strong><small>当前版本已通过后端发布门禁</small></div></li>
        <li v-if="softScore !== null && softScore !== 0"><span class="check-warn">!</span><div><strong>非阻塞提醒</strong><small>仍有软约束评分 {{ softScore }}，不会阻止发布，请在版本说明中注明业务取舍</small></div></li>
      </ul>
      <label class="release-note-field">
        <span>版本说明</span>
        <textarea v-model="releaseNote" rows="3" placeholder="说明适用范围、特殊安排或业务确认结论" />
      </label>
      <label class="release-confirm"><input v-model="releaseConfirmed" type="checkbox" /> <span>我已确认当前学期、课次数量和发布结果</span></label>
    </div>
    <template #footer>
      <el-button @click="publishDialogOpen = false">取消</el-button>
      <el-button type="primary" :loading="publishing" :disabled="!releaseReady" data-testid="confirm-publish" @click="publishVersion">确认发布</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="solveDialogOpen" title="重新求解前确认" width="460px" data-testid="solve-dialog">
    <div class="solve-dialog-content">
      <div class="solve-dialog-summary"><strong>当前版本 v{{ versionId ?? '—' }} · {{ termName || term.selectedTermCode.value }}</strong><span>{{ occurrences.length }} 个教学任务 · 已安排 {{ assignedCount }} 个 · 当前状态 {{ statusLabel }}</span></div>
      <ul class="solve-dialog-effects"><li><span class="effect-keep">保留</span><div><strong>当前候选版本和调整历史</strong><small>原版本不会被覆盖，已完成的人工调整、撤销和重做记录继续可查。</small></div></li><li><span class="effect-reset">新建</span><div><strong>生成独立候选版本</strong><small>本次求解读取当前学期快照和规则配置，完成后作为新的候选结果返回。</small></div></li><li><span class="effect-reset">重置</span><div><strong>当前手工调整不自动带入</strong><small>如需保留个别位置，请在新候选生成后重新锁定或调整，并再次确认发布清单。</small></div></li></ul>
      <p class="solve-dialog-note">重新求解会重新计算所有教学任务，可能改变当前课表的时间和教室安排。</p>
    </div>
    <template #footer><el-button @click="solveDialogOpen = false">取消</el-button><el-button type="primary" data-testid="confirm-resolve" @click="confirmResolve">确认重新求解</el-button></template>
  </el-dialog>
</template>

<style scoped>
.ai-block { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.ai-finding { display: flex; gap: 8px; align-items: flex-start; padding: 8px; border-radius: 8px; background: var(--el-fill-color-light); }
.ai-finding.high { background: var(--el-color-danger-light-9); }
.ai-finding.medium { background: var(--el-color-warning-light-9); }
.ai-severity { font-size: 11px; font-weight: 700; padding: 1px 6px; border-radius: 6px; color: #fff; background: var(--el-color-info); flex-shrink: 0; }
.ai-finding.high .ai-severity { background: var(--el-color-danger); }
.ai-finding.medium .ai-severity { background: var(--el-color-warning); }
.ai-finding.low .ai-severity { background: var(--el-color-info); }
.ai-finding strong { display: block; font-size: 12px; }
.ai-finding small { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.5; display: block; }
.ai-suggestions { margin: 0; padding-left: 18px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
.release-dialog { color: var(--el-text-color-primary); }
.release-dialog-summary { display: grid; gap: 5px; padding: 12px; background: #f6faf7; border: 1px solid #dcebe1; border-radius: 8px; }
.release-dialog-summary strong { color: #173b36; font-size: 13px; }
.release-dialog-summary span { color: #6c8176; font-size: 11px; }
.release-dialog-checks { display: grid; gap: 10px; margin: 16px 0 0; padding: 0; list-style: none; }
.release-dialog-checks li { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 8px; align-items: start; }
.release-dialog-checks li > span { display: grid; place-items: center; width: 19px; height: 19px; border-radius: 50%; font-size: 11px; font-weight: 700; }
.release-dialog-checks .check-ok { color: #217348; background: #e3f5e9; }
.release-dialog-checks .check-warn { color: #a15c0a; background: #fff1d7; }
.release-dialog-checks strong, .release-dialog-checks small { display: block; }
.release-dialog-checks strong { color: #345d49; font-size: 12px; }
.release-dialog-checks small { margin-top: 3px; color: #758a80; font-size: 11px; line-height: 1.45; }
.release-note-field { display: grid; gap: 5px; margin: 16px 0 8px; color: #456552; font-size: 11px; }
.release-note-field textarea { resize: vertical; border: 1px solid #dce8e0; border-radius: 6px; padding: 8px; color: #173b36; font: inherit; line-height: 1.5; }
.release-note-field textarea:focus { outline: 2px solid rgba(77, 138, 120, .25); border-color: #4d8a78; }
.release-confirm { display: flex; gap: 6px; align-items: flex-start; color: #64786e; font-size: 11px; line-height: 1.45; }
.release-confirm input { margin-top: 1px; }
.solve-status-card { display: grid; gap: 9px; margin: 12px 0; padding: 12px; border: 1px solid #dce8e0; border-radius: 8px; background: #f8fbf9; color: #35574a; }
.solve-status-danger { border-color: #efd7d2; background: #fff8f6; }
.solve-status-warning { border-color: #efdfc4; background: #fffbf4; }
.solve-status-success { border-color: #d6eadc; background: #f5fbf7; }
.solve-status-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.solve-status-heading strong { display: block; margin-top: 3px; color: #173b36; font-size: 14px; }
.solve-status-category { flex-shrink: 0; padding: 3px 7px; border-radius: 999px; color: #4f7462; background: #e7f2eb; font-size: 10px; }
.solve-status-danger .solve-status-category { color: #a1483f; background: #fde9e4; }
.solve-status-warning .solve-status-category { color: #9b641b; background: #fff0d2; }
.solve-status-card p { margin: 0; color: #637a6d; font-size: 11px; line-height: 1.5; }
.solve-status-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px 12px; padding: 9px 0; border-top: 1px solid rgba(92, 126, 108, .14); border-bottom: 1px solid rgba(92, 126, 108, .14); }
.solve-status-grid span, .solve-next-step span, .affected-lessons > span, .drawer-facts span, .drawer-requirements span { display: block; color: #84988d; font-size: 10px; }
.solve-status-grid strong { display: block; margin-top: 2px; color: #395d4c; font-size: 11px; font-weight: 600; }
.solve-failure-detail { display: grid; gap: 3px; padding: 8px; border-left: 3px solid #ce6b5b; background: rgba(255, 234, 228, .72); color: #9d443b; font-size: 10px; }
.solve-failure-detail small { color: #8c6058; line-height: 1.45; }
.solve-failure-detail a { color: #a1483f; font-size: 10px; text-decoration: none; }
.solve-failure-detail a:hover { text-decoration: underline; }
.solve-next-step { display: grid; gap: 2px; }
.solve-next-step strong { color: #496f5b; font-size: 11px; font-weight: 600; line-height: 1.45; }
.solve-status-actions { display: flex; gap: 7px; }
.drawer-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; margin-top: 14px; padding: 12px 0; border-top: 1px solid #edf2ef; border-bottom: 1px solid #edf2ef; }
.drawer-facts strong, .drawer-requirements strong { display: block; margin-top: 3px; color: #365b4b; font-size: 11px; line-height: 1.35; }
.drawer-requirements { display: grid; gap: 3px; margin: 11px 0; padding: 9px 10px; background: #f7faf8; border-left: 3px solid #8fbaa0; }
.affected-lessons { display: grid; gap: 3px; padding: 7px 0; }
.affected-lessons strong { color: inherit; font-size: 11px; line-height: 1.45; }
.preview-location { padding-top: 5px; color: #73887d; font-size: 10px; }
.solve-dialog-content { color: #36594a; }
.solve-dialog-summary { display: grid; gap: 5px; padding: 12px; border: 1px solid #dcebe1; border-radius: 8px; background: #f6faf7; }
.solve-dialog-summary strong { color: #173b36; font-size: 13px; }
.solve-dialog-summary span { color: #71857b; font-size: 11px; }
.solve-dialog-effects { display: grid; gap: 12px; margin: 16px 0 0; padding: 0; list-style: none; }
.solve-dialog-effects li { display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 9px; align-items: start; }
.solve-dialog-effects li > span { padding: 3px 0; border-radius: 999px; text-align: center; font-size: 10px; font-weight: 700; }
.effect-keep { color: #27754a; background: #e5f5ea; }
.effect-reset { color: #9a651e; background: #fff0d5; }
.solve-dialog-effects strong, .solve-dialog-effects small { display: block; }
.solve-dialog-effects strong { color: #365d4b; font-size: 12px; }
.solve-dialog-effects small { margin-top: 3px; color: #75897f; font-size: 11px; line-height: 1.45; }
.solve-dialog-note { margin: 16px 0 0; padding: 9px 10px; color: #97652a; background: #fff8e9; font-size: 11px; line-height: 1.5; }
</style>
