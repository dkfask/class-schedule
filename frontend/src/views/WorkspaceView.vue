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
import { parseScore } from '../utils/score'
import { useTermStore } from '../stores/term'

type Occurrence = WorkspaceOccurrence
type ScheduleOptions = WorkspaceOptions

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
  heartbeatAt?: string
  cancelRequested?: boolean
  deadlineAt?: string
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
const jobStatus = ref('待开始')
const versionStatus = ref('')
const progress = ref(0)
const attempt = ref(0)
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
const exchangeCandidates = ref<Array<{ occurrenceId: number; occurrenceKey: string; subjectName: string; studentGroupCode: string; teacherCode: string; roomCode: string; timeslotCode: string }>>([])
const exchangeLoading = ref(false)
const dragOccurrence = ref<Occurrence | null>(null)
const selectedExchangeCandidate = ref<{ occurrenceId: number; occurrenceKey: string; subjectName: string; studentGroupCode: string; teacherCode: string; roomCode: string; timeslotCode: string } | null>(null)
const searchQuery = ref('')
const message = ref('')
const termName = ref('')
const masterDataSummary = ref({ teachers: 0, studentGroups: 0, subjects: 0, rooms: 0 })
const backendPublishable = ref(false)
const activeView = computed(() => ({ CLASS: '班级课表', TEACHER: '教师课表', ROOM: '教室课表' })[viewType.value])
const assignedCount = computed(() => countAssignedOccurrences(occurrences.value))
const canEditVersion = computed(() => ['DRAFT', 'CANDIDATE'].includes(versionStatus.value) && !versionEditLocked.value && !versionArchived.value)
const latestAppliedCommand = computed(() => commandHistory.value.find(item => item.state === 'APPLIED'))
const latestUndoneCommand = computed(() => commandHistory.value.find(item => item.state === 'UNDONE'))
const publishable = computed(() => backendPublishable.value)
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
const gridStyle = computed(() => ({ gridTemplateColumns: `58px repeat(${Math.max(weekdays.value.length, 1)}, minmax(86px, 1fr))` }))
const canCancel = computed(() => canCancelSolve(jobId.value, jobStatus.value, cancelling.value))
const statusLabel = computed(() => getStatusLabel(jobStatus.value, versionStatus.value))
let pollTimer: number | undefined
let pollGeneration = 0

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
  if (!canEditVersion.value || item.locked) {
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
  pollGeneration += 1
  const generation = pollGeneration
  clearPollTimer()
  loading.value = true
  jobStatus.value = 'QUEUED'
  versionStatus.value = 'SOLVING'
  progress.value = 0
  attempt.value = 0
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
  backendPublishable.value = false
  errorMessage.value = ''
  message.value = ''
  try {
    const created = await requestJson<{ jobId: number; versionId: number }>('/api/solve-jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idempotencyKey: `workspace-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, termCode: term.selectedTermCode.value }),
    })
    jobId.value = created.jobId
    versionId.value = created.versionId
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
    jobStatus.value = result.jobStatus
    versionStatus.value = result.versionStatus
    progress.value = result.progress
    attempt.value = result.attempt
    jobDeadline.value = result.deadlineAt ?? ''
    score.value = result.score === '等待结果' ? null : result.score ?? null
    hardScore.value = result.hardScore ?? parseScore(result.score).hard
    mediumScore.value = result.mediumScore ?? parseScore(result.score).medium
    softScore.value = result.softScore ?? parseScore(result.score).soft
    errorMessage.value = result.errorMessage ?? ''
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
  if (!versionId.value || !publishable.value) return
  try {
    const result = await requestJson<{ status: string }>(`/api/schedule-versions/${versionId.value}/publish`, { method: 'POST' })
    if (result.status === 'PUBLISHED') {
      jobStatus.value = 'PUBLISHED'
      versionStatus.value = 'PUBLISHED'
      backendPublishable.value = false
      message.value = '候选版本已发布，当前课表进入只读状态'
      await loadFiltered()
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : '版本尚未满足发布条件'
  }
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
      <p class="eyebrow">SCHEDULE / WORKSPACE</p>
      <h1>排课工作台</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● {{ errorMessage ? '需要处理' : '数据已同步' }}</span>
      <el-button plain @click="router.push('/import')">导入数据</el-button>
      <el-button v-if="canCancel" plain :loading="cancelling" @click="cancelSolve">取消求解</el-button>
      <el-button data-testid="start-solve" type="primary" :loading="loading" :disabled="loading || readinessLoading || readiness === null || !readiness.ready" @click="startSolve">{{ loading ? `正在求解 ${progress}%` : (readinessLoading ? '检查排课条件…' : '开始自动排课') }}</el-button>
      <div class="avatar">教</div>
    </div>
  </header>

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
        <div v-for="item in pendingOccurrences" :key="item.occurrenceId" class="task-item" @click="openAdjustment(item)"><span class="task-color"></span><div><strong>{{ item.subjectName }}</strong><small>{{ item.studentGroupName }} · {{ item.teacherName }}</small></div></div>
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
      <div class="notice"><span>↗</span><div><strong>点击课程进行调整</strong><small>先选择目标节次和教室，后端会显示冲突及受影响课程</small></div></div>
      <div v-if="errorMessage" class="import-issues"><strong>{{ errorMessage }}</strong></div>
      <div v-if="message" class="inline-message">{{ message }}</div>
      <div v-if="versionId && commandHistory.length" class="command-history"><div class="history-heading"><strong>最近调整</strong><span>revision {{ versionRevision }}</span></div><div v-for="command in commandHistory.slice(0, 3)" :key="command.groupId" class="history-row"><span>{{ command.commandType }}</span><small>{{ command.reason }} · {{ command.state }}</small></div><div class="history-actions"><el-button size="small" plain :disabled="!latestAppliedCommand || !canEditVersion" @click="undoLatest">撤销</el-button><el-button size="small" plain :disabled="!latestUndoneCommand || !canEditVersion" @click="redoLatest">重做</el-button></div></div>
      <div class="quality"><div><span>方案完整度</span><strong>{{ qualityPercent }}%</strong></div><div class="quality-track"><i :style="{ width: `${qualityPercent}%` }"></i></div></div>
      <el-button class="publish-btn" type="primary" plain :disabled="!publishable || jobStatus === 'PUBLISHED'" @click="publishVersion">{{ jobStatus === 'PUBLISHED' ? '版本已发布' : '发布候选版本' }}</el-button>
    </aside>
  </section>

  <el-drawer v-model="adjustmentOpen" title="调整课程" size="420px" data-testid="adjustment-drawer" :before-close="handleDrawerClose">
    <template v-if="selectedOccurrence">
      <div class="drawer-lesson"><span class="eyebrow">ASSIGNMENT #{{ selectedOccurrence.occurrenceId }}</span><h2>{{ selectedOccurrence.subjectName }}</h2><p>{{ selectedOccurrence.studentGroupName }} · {{ selectedOccurrence.teacherName }}</p><el-tag v-if="selectedOccurrence.locked" type="warning" effect="plain">已锁定</el-tag></div>
      <el-form label-position="top" class="adjustment-form">
        <el-form-item label="目标节次"><el-select v-model="adjustmentForm.timeslotCode" class="full-width"><el-option v-for="item in options.timeslots" :key="item.code" :label="`${item.label} · ${item.code}`" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="目标教室"><el-select v-model="adjustmentForm.roomCode" class="full-width"><el-option v-for="item in options.rooms" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" /></el-select></el-form-item>
        <el-form-item label="调整原因" required><el-input v-model="adjustmentForm.reason" type="textarea" :rows="3" placeholder="请输入本次调整的业务原因" /></el-form-item>
      </el-form>
      <el-button class="full-width" data-testid="preview-adjustment" :loading="previewLoading" @click="previewAdjustment">预览调整</el-button>
      <div v-if="preview" class="preview-result" :class="preview.allowed ? 'preview-ok' : 'preview-blocked'">
        <strong>{{ preview.allowed ? '可以放置' : '存在硬冲突，不能确认' }}</strong>
        <span v-if="preview.lockedConflict">涉及锁定课程</span>
        <span v-if="preview.affectedAssignmentIds.length">受影响课程：{{ preview.affectedAssignmentIds.join('、') }}</span>
        <span v-for="violation in preview.hardViolations" :key="`${violation.code}-${violation.resourceCode}`">{{ violation.code }}：{{ violation.message }}</span>
      </div>
      <div v-if="exchangeLoading" class="exchange-candidates">正在计算交换候选…</div>
      <div v-if="exchangeCandidates.length" class="exchange-candidates" data-testid="exchange-candidates"><strong>可交换课程</strong><button v-for="candidate in exchangeCandidates" :key="candidate.occurrenceId" :class="{ selected: selectedExchangeCandidate?.occurrenceId === candidate.occurrenceId }" @click="selectedExchangeCandidate = candidate">{{ candidate.subjectName }} · {{ candidate.studentGroupCode }}<small>{{ candidate.teacherCode }} · {{ candidate.timeslotCode }} · {{ candidate.roomCode }}</small></button></div>
      <div class="drawer-actions"><el-button @click="closeAdjustment">取消</el-button><el-button v-if="selectedExchangeCandidate" data-testid="confirm-exchange" type="warning" :loading="confirmingAdjustment" :disabled="!adjustmentForm.reason.trim()" @click="confirmExchange">确认交换</el-button><el-button v-else data-testid="confirm-adjustment" type="primary" :loading="confirmingAdjustment" :disabled="!preview?.allowed || !adjustmentForm.reason.trim()" @click="confirmAdjustment">确认调整</el-button></div>
    </template>
  </el-drawer>
</template>
