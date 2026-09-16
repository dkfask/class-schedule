<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { http, jsonRequest } from '../api/http'
import EmptyState from '../components/EmptyState.vue'
import { buildReleaseChecklist } from '../utils/releaseChecklist'
import { describeScore, resolveScore, versionStatusLabel } from '../utils/score'
import { useTermStore } from '../stores/term'
import { useAuthStore } from '../stores/auth'

interface VersionSummary {
  id: number
  status: string
  score?: string
  hardScore?: number | null
  mediumScore?: number | null
  softScore?: number | null
  publishable?: boolean
  parentVersionId?: number
  createdAt?: string
  revision?: number
  updatedAt?: string
  archivedAt?: string
  editLocked?: boolean
  editLockOwner?: string
  ownerApproval?: {
    status?: string
    comment?: string | null
    decidedBy?: string | null
    required?: boolean
  }
}
interface DiffItem { changeType: string; occurrenceKey: string; before?: Record<string, unknown>; after?: Record<string, unknown> }
interface CommandGroup { groupId: string; commandType: string; state: string; reason: string; resultRevision: number }
interface RuleCatalogItem { ruleCode: string; label: string; valueType: 'INTEGER' | 'TEXT'; scopes: string[] }
interface TrialSolveResult { versionId: number; jobId: number; status: string; ruleCode: string }
interface TrialImpactPreview { affectedCount: number; blockingCount: number; summary: string; violations: Array<{ occurrenceKey: string; subjectName?: string; message: string }> }
const term = useTermStore()
const auth = useAuthStore()
const versions = ref<VersionSummary[]>([])
const selectedVersion = ref<number | null>(null)
const diffAgainst = ref<number | null>(null)
const diff = ref<DiffItem[]>([])
const loading = ref(false)
const diffLoading = ref(false)
const mutating = ref(false)
const history = ref<CommandGroup[]>([])
const historyLoading = ref(false)
const message = ref('')
const releaseNote = ref('')
const releaseConfirmed = ref(false)
const approvalComment = ref('')
const onlyChanges = ref(true)
const trialDialogOpen = ref(false)
const trialSubmitting = ref(false)
const trialRuleCatalog = ref<RuleCatalogItem[]>([])
const trialRuleCode = ref('PREFER_ORIGINAL_SLOT')
const trialRuleScopeType = ref('TERM')
const trialRuleScopeCode = ref('')
const trialRuleIntValue = ref(2)
const trialRuleTextValue = ref('')
const trialRuleSeverity = ref('SOFT')
const trialRuleWeight = ref(1)
const trialResult = ref<TrialSolveResult | null>(null)
const trialImpact = ref<TrialImpactPreview | null>(null)
const trialPreviewLoading = ref(false)
const trialRule = computed(() => trialRuleCatalog.value.find(item => item.ruleCode === trialRuleCode.value))
const trialRuleScopes = computed(() => trialRule.value?.scopes ?? [])
const trialRuleUsesText = computed(() => trialRule.value?.valueType === 'TEXT')
const visibleDiff = computed(() => onlyChanges.value ? diff.value.filter(item => item.changeType !== 'UNCHANGED') : diff.value)
const changeCount = computed(() => diff.value.filter(item => item.changeType !== 'UNCHANGED').length)
const selectedSummary = computed(() => versions.value.find(item => item.id === selectedVersion.value))
const reviewOnly = computed(() => auth.canReview && !auth.isPlanner)
const ownerReview = computed(() => auth.isBusinessOwner && !auth.isPlanner)
const reviewRole = computed(() => auth.isPlanner ? 'PLANNER' : ownerReview.value ? 'BUSINESS_OWNER' : 'REVIEWER')
const editable = computed(() => auth.isPlanner && selectedSummary.value != null && ['DRAFT', 'CANDIDATE'].includes(selectedSummary.value.status) && !selectedSummary.value.editLocked)
const canLock = computed(() => auth.isPlanner && selectedSummary.value != null && editable.value && !selectedSummary.value.editLocked)
const canUnlock = computed(() => auth.isPlanner && selectedSummary.value != null && Boolean(selectedSummary.value.editLocked))
const canArchive = computed(() => auth.isPlanner && selectedSummary.value != null && selectedSummary.value.status === 'PUBLISHED' && !selectedSummary.value.archivedAt)
const canFork = computed(() => auth.isPlanner && selectedSummary.value != null && ['CANDIDATE', 'PUBLISHED', 'ARCHIVED'].includes(selectedSummary.value.status))
const canTrialSolve = computed(() => canFork.value && !mutating.value && !trialSubmitting.value)
const ownerApproval = computed(() => selectedSummary.value?.ownerApproval)
const approvalStatus = computed(() => ownerApproval.value?.status ?? 'NONE')
const approvalRequired = computed(() => Boolean(ownerApproval.value?.required))
const canDecideApproval = computed(() => ownerReview.value && selectedSummary.value != null && ['DRAFT', 'CANDIDATE'].includes(selectedSummary.value.status) && selectedSummary.value.publishable === true && !mutating.value)
const canPublish = computed(() => auth.isPlanner && selectedSummary.value != null && selectedSummary.value.status === 'CANDIDATE' && selectedSummary.value.publishable === true && (!approvalRequired.value || approvalStatus.value === 'APPROVED') && !mutating.value)
const releaseReady = computed(() => canPublish.value && releaseConfirmed.value && Boolean(releaseNote.value.trim()))
const selectedScore = computed(() => selectedSummary.value
  ? scoreParts(selectedSummary.value)
  : { hard: null, medium: null, soft: null, valid: false })
const releaseChecklist = computed(() => buildReleaseChecklist({
  status: selectedSummary.value?.status,
  publishable: selectedSummary.value?.publishable,
  hard: selectedScore.value.hard,
  medium: selectedScore.value.medium,
  soft: selectedScore.value.soft,
  scoreValid: selectedScore.value.valid,
  releaseNote: releaseNote.value,
  releaseConfirmed: releaseConfirmed.value,
  reviewOnly: reviewOnly.value,
  reviewerRole: reviewRole.value,
  approvalStatus: approvalStatus.value,
  approvalRequired: approvalRequired.value,
  approvalComment: ownerApproval.value?.comment ?? approvalComment.value,
}))
const latestApplied = computed(() => history.value.find(item => item.state === 'APPLIED'))
const latestUndone = computed(() => history.value.find(item => item.state === 'UNDONE'))

function scoreParts(version: VersionSummary) {
  return resolveScore(version.score, { hard: version.hardScore, medium: version.mediumScore, soft: version.softScore })
}

async function loadVersions(preserveSelection = false) {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    versions.value = []
    selectedVersion.value = null
    diff.value = []
    history.value = []
    return
  }
  loading.value = true
  message.value = ''
  const previousId = selectedVersion.value
  try {
    const data = await http<{ items?: VersionSummary[] }>(`/api/schedule-versions?termCode=${encodeURIComponent(term.selectedTermCode.value)}&page=0&size=50`)
    versions.value = data.items ?? []
    selectedVersion.value = preserveSelection && versions.value.some(item => item.id === previousId) ? previousId : versions.value[0]?.id ?? null
    diffAgainst.value = versions.value.find(item => item.id === selectedVersion.value)?.parentVersionId ?? null
    if (selectedVersion.value) {
      await loadDiff()
      await loadHistory()
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : '版本列表加载失败'
  } finally {
    loading.value = false
  }
}

async function loadDiff() {
  if (!selectedVersion.value) return
  diffLoading.value = true
  try {
    const query = diffAgainst.value ? `?againstVersionId=${diffAgainst.value}` : ''
    diff.value = await http<DiffItem[]>(`/api/schedule-versions/${selectedVersion.value}/diff${query}`)
  } catch (error) {
    diff.value = []
    message.value = error instanceof Error ? error.message : '版本差异加载失败'
  } finally {
    diffLoading.value = false
  }
}

async function loadHistory() {
  if (!selectedVersion.value) return
  historyLoading.value = true
  try {
    history.value = await http<CommandGroup[]>(`/api/schedule-versions/${selectedVersion.value}/adjustments/commands`)
  } catch (error) {
    history.value = []
    message.value = error instanceof Error ? error.message : '命令历史加载失败'
  } finally {
    historyLoading.value = false
  }
}

async function mutate(action: () => Promise<unknown>) {
  mutating.value = true
  message.value = ''
  try {
    await action()
    await loadVersions(true)
    if (selectedVersion.value) await loadHistory()
    return true
  } catch (error) {
    message.value = error instanceof Error ? error.message : '操作失败'
    return false
  } finally {
    mutating.value = false
  }
}

function revisionHeaders() {
  return { 'If-Match': String(selectedSummary.value?.revision ?? 0) }
}

function commandRequest(key: string): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: '{}',
  }
}

function newIdempotencyKey(action: string) {
  return `${action}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function openTrialSolve() {
  if (!selectedVersion.value || !canTrialSolve.value) return
  trialResult.value = null
  trialImpact.value = null
  try {
    trialRuleCatalog.value = await http<RuleCatalogItem[]>('/api/schedule-rules/catalog')
    if (!trialRuleCatalog.value.some(item => item.ruleCode === trialRuleCode.value)) {
      trialRuleCode.value = trialRuleCatalog.value[0]?.ruleCode ?? ''
    }
    if (!trialRuleScopes.value.includes(trialRuleScopeType.value)) {
      trialRuleScopeType.value = trialRuleScopes.value[0] ?? 'TERM'
    }
    trialDialogOpen.value = true
  } catch (error) {
    message.value = error instanceof Error ? error.message : '规则目录加载失败'
  }
}

function trialRulePayload() {
  return {
    termCode: term.selectedTermCode.value,
    ruleCode: trialRuleCode.value,
    scopeType: trialRuleScopeType.value,
    scopeCode: trialRuleScopeType.value === 'TERM' ? null : trialRuleScopeCode.value || null,
    intValue: trialRuleUsesText.value ? null : trialRuleIntValue.value,
    textValue: trialRuleUsesText.value ? trialRuleTextValue.value : null,
    severity: trialRuleSeverity.value,
    weight: trialRuleWeight.value,
  }
}

async function previewTrialSolve() {
  const source = selectedVersion.value
  if (!source || !trialRule.value || trialPreviewLoading.value) return
  trialPreviewLoading.value = true
  message.value = ''
  try {
    trialImpact.value = await http<TrialImpactPreview>(
      `/api/schedule-versions/${source}/trial-solve/impact-preview`,
      jsonRequest('POST', trialRulePayload()),
    )
  } catch (error) {
    message.value = error instanceof Error ? error.message : '影响预估失败'
    trialImpact.value = null
  } finally {
    trialPreviewLoading.value = false
  }
}

async function submitTrialSolve() {
  const source = selectedVersion.value
  const selectedRule = trialRule.value
  if (!source || !selectedRule || !canTrialSolve.value) return
  trialSubmitting.value = true
  message.value = ''
  try {
    trialResult.value = await http<TrialSolveResult>(`/api/schedule-versions/${source}/trial-solve`, {
      ...jsonRequest('POST', {
        idempotencyKey: newIdempotencyKey('trial-solve'),
        rule: {
          termCode: term.selectedTermCode.value,
          ruleCode: trialRuleCode.value,
          scopeType: trialRuleScopeType.value,
          scopeCode: trialRuleScopeType.value === 'TERM' ? null : trialRuleScopeCode.value || null,
          intValue: trialRuleUsesText.value ? null : trialRuleIntValue.value,
          textValue: trialRuleUsesText.value ? trialRuleTextValue.value : null,
          severity: trialRuleSeverity.value,
          weight: trialRuleWeight.value,
        },
      }),
    })
    trialDialogOpen.value = false
    await loadVersions()
    message.value = `试排已提交：新草稿 v${trialResult.value.versionId}，任务 #${trialResult.value.jobId} 正在排队`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '试排提交失败'
  } finally {
    trialSubmitting.value = false
  }
}

function normalizeTrialScope() {
  if (!trialRuleScopes.value.includes(trialRuleScopeType.value)) trialRuleScopeType.value = trialRuleScopes.value[0] ?? 'TERM'
  if (trialRuleScopeType.value === 'TERM') trialRuleScopeCode.value = ''
  trialImpact.value = null
}

function lockVersion() {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/lock`, jsonRequest('POST', {
    reason: '版本页锁定', expectedRevision: selectedSummary.value?.revision ?? 0,
  })))
}

function unlockVersion() {
  return mutate(() => http<void>(`/api/schedule-versions/${selectedVersion.value}/lock`, {
    method: 'DELETE', headers: revisionHeaders(),
  }))
}

function archiveVersion() {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/archive`, {
    method: 'POST', headers: revisionHeaders(),
  }))
}

async function decideApproval(decision: 'APPROVED' | 'REJECTED') {
  const versionId = selectedVersion.value
  if (!versionId || !canDecideApproval.value) return
  if (decision === 'REJECTED' && !approvalComment.value.trim()) {
    message.value = '退回时请写明原因，便于排课员处理'
    return
  }
  const succeeded = await mutate(() => http(`/api/schedule-versions/${versionId}/owner-approval`, {
    method: 'POST',
    headers: { ...revisionHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ decision, comment: approvalComment.value.trim() || null }),
  }))
  if (succeeded) {
    message.value = decision === 'APPROVED' ? `版本 v${versionId} 已批准，待排课员面向全校` : `版本 v${versionId} 已退回排课员`
    approvalComment.value = ''
  }
}

async function publishVersion() {
  const versionId = selectedVersion.value
  if (!versionId || !releaseReady.value) return
  const succeeded = await mutate(() => http(`/api/schedule-versions/${versionId}/publish`, {
    method: 'POST', headers: { ...revisionHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ releaseNote: releaseNote.value.trim() }),
  }))
  if (succeeded) message.value = `版本 v${versionId} 已发布`
}

function forkVersion() {
  const source = selectedVersion.value
  if (!source) return
  void ElMessageBox.prompt('新草稿名称', `复制版本 v${source} 为新草稿`, {
    confirmButtonText: '创建草稿',
    cancelButtonText: '取消',
    inputValue: `v${source} 副本`,
  })
    .then(({ value }) => {
      const name = (value ?? '').trim() || `v${source} 副本`
      return mutate(() => http(`/api/schedule-versions/${source}/fork`, jsonRequest('POST', { name })))
    })
    .catch(() => undefined)
}

function downloadValidation(format: 'xlsx' | 'pdf') {
  if (!selectedVersion.value) return
  window.open(`/api/schedule-versions/${selectedVersion.value}/validation/export.${format}`, '_blank')
}

function undoCommand(groupId: string) {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/adjustments/commands/${groupId}/undo`, commandRequest(newIdempotencyKey('undo'))))
}

function redoCommand(groupId: string) {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/adjustments/commands/${groupId}/redo`, commandRequest(newIdempotencyKey('redo'))))
}

function selectVersion(version: VersionSummary) {
  selectedVersion.value = version.id
  diffAgainst.value = version.parentVersionId ?? null
  releaseNote.value = ''
  releaseConfirmed.value = false
  approvalComment.value = selectedSummary.value?.ownerApproval?.comment ?? ''
  void loadDiff()
  void loadHistory()
}

watch(() => term.selectedTermCode.value, () => void loadVersions())
onMounted(() => void loadVersions())
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">{{ auth.isPlanner ? '检查与发布' : ownerReview ? '课表审阅' : '检查清单' }}</p>
      <h1>{{ auth.isPlanner ? '课表发布前检查' : ownerReview ? '确认是否面向全校' : '课表检查清单' }}</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● {{ auth.isPlanner ? '发布后保持只读' : ownerReview ? '审批后仍由排课员发布' : '审阅后仍由排课员发布' }}</span>
      <div class="avatar">教</div>
    </div>
  </header>

  <section class="version-page">
    <!-- 左侧版本列表 -->
    <div class="version-list panel canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">本学期版本</span>
          <h2>候选、草稿与已发布</h2>
        </div>
        <el-button plain size="small" :loading="loading" @click="loadVersions">刷新</el-button>
      </div>

      <div v-if="reviewOnly" class="review-banner">{{ ownerReview ? '当前为业务负责人审批视图：请核对课次是否排齐、是否还有冲突，以及学校是否接受未满足的偏好。您可以批准或退回，但不能修改课表或代替排课员发布。' : '当前为只读审核视图：可以查看候选课表、差异、调整记录和检查清单，但不能修改或发布课表。' }}</div>

      <div v-if="message" class="inline-message error-message">{{ message }}</div>

      <button
        v-for="version in versions"
        :key="version.id"
        class="version-row"
        :class="{ selected: selectedVersion === version.id }"
        @click="selectVersion(version)"
      >
        <div class="version-item-header">
          <strong>版本 v{{ version.id }}</strong>
          <span class="version-status-tag" :class="version.status.toLowerCase()">{{ versionStatusLabel(version.status) }}</span>
        </div>
        <span class="version-score-line">
          {{ describeScore(scoreParts(version)) }}
        </span>
        <small class="version-meta">
          {{ version.editLocked ? `🔒 锁定：${version.editLockOwner ?? '其他用户'}` : version.archivedAt ? '已归档' : version.parentVersionId ? `父版本 v${version.parentVersionId}` : '无父版本' }}
        </small>
      </button>

      <EmptyState
        v-if="!loading && !versions.length"
        title="还没有排课版本"
        :description="auth.isPlanner ? '完成本学期数据准备后，从自动排课生成第一份候选课表。' : '排课员提交自动排课后，这里会出现可供审阅的候选课表。'"
        :action-label="auth.isPlanner ? '去自动排课' : '返回学期总览'"
        :to="auth.isPlanner ? '/workspace' : '/overview'"
      />
    </div>

    <!-- 右侧差异与生命周期操作 -->
    <div class="diff-panel panel canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">版本对比</span>
          <h2>{{ selectedVersion ? `版本 v${selectedVersion}` : '选择版本' }}</h2>
          <small v-if="selectedSummary" class="resource-caption">
            第 {{ selectedSummary.revision ?? 0 }} 次调整 · {{ changeCount }} 项变化
          </small>
        </div>
        <div class="diff-tools">
          <label class="diff-checkbox"><input v-model="onlyChanges" type="checkbox" /> 仅看变更项</label>
          <select v-model="diffAgainst" class="styled-diff-select" @change="loadDiff">
            <option :value="null">默认对比父版本</option>
            <option
              v-for="version in versions.filter(item => item.id !== selectedVersion)"
              :key="version.id"
              :value="version.id"
            >
              对比版本 v{{ version.id }}
            </option>
          </select>
        </div>
      </div>

      <!-- 操作按钮条 -->
      <div v-if="selectedVersion && !reviewOnly" class="version-actions">
        <el-button size="small" plain :disabled="!canLock || mutating" :loading="mutating" @click="lockVersion">锁定编辑</el-button>
        <el-button size="small" plain :disabled="!canUnlock || mutating" :loading="mutating" @click="unlockVersion">解锁</el-button>
        <el-button size="small" plain :disabled="!canArchive || mutating" :loading="mutating" @click="archiveVersion">归档</el-button>
        <el-button size="small" type="primary" plain :disabled="!canTrialSolve" :loading="trialSubmitting" data-testid="trial-solve-open" @click="openTrialSolve">试排新条件</el-button>
        <el-button size="small" plain :disabled="!canFork || mutating" :loading="mutating" @click="forkVersion">复制为新草稿 (Fork)</el-button>
        <el-button size="small" plain data-testid="validation-export-xlsx" @click="downloadValidation('xlsx')">校验 Excel</el-button>
        <el-button size="small" plain data-testid="validation-export-pdf" @click="downloadValidation('pdf')">校验 PDF</el-button>
      </div>

      <div v-if="selectedVersion" class="release-panel" data-testid="release-checklist">
        <div class="release-heading">
          <div>
            <span class="eyebrow">{{ auth.isPlanner ? '发布前检查' : ownerReview ? '面向全校前审阅' : '发布前检查' }}</span>
            <h3>{{ auth.isPlanner ? '发布检查清单' : ownerReview ? '审阅清单' : '检查清单' }}</h3>
            <small>{{ releaseChecklist.why }}</small>
          </div>
          <span class="release-status" :class="releaseChecklist.blocking.length ? 'release-blocked' : 'release-ready'">
            {{ releaseChecklist.statusLabel }}
          </span>
        </div>
        <div v-if="releaseChecklist.blocking.length" class="release-group" data-testid="release-blocking">
          <h4>阻塞项 · {{ releaseChecklist.blocking.length }}</h4>
          <ul class="release-checks">
            <li v-for="item in releaseChecklist.blocking" :key="item.id" class="check-blocked">
              <span>!</span>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.detail }}</small>
                <RouterLink v-if="item.actionPath" class="release-action" :to="item.actionPath">{{ item.actionLabel }}</RouterLink>
              </div>
            </li>
          </ul>
        </div>
        <div v-if="releaseChecklist.warnings.length" class="release-group" data-testid="release-warnings">
          <h4>警告项 · {{ releaseChecklist.warnings.length }}</h4>
          <ul class="release-checks">
            <li v-for="item in releaseChecklist.warnings" :key="item.id" class="check-warn">
              <span>!</span>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.detail }}</small>
              </div>
            </li>
          </ul>
        </div>
        <div v-if="releaseChecklist.passed.length" class="release-group" data-testid="release-passed">
          <h4>已通过项 · {{ releaseChecklist.passed.length }}</h4>
          <ul class="release-checks">
            <li v-for="item in releaseChecklist.passed" :key="item.id" class="check-ok">
              <span>✓</span>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.detail }}</small>
                <RouterLink v-if="item.actionPath" class="release-action" :to="item.actionPath">{{ item.actionLabel }}</RouterLink>
              </div>
            </li>
          </ul>
        </div>
        <label v-if="ownerReview && selectedSummary?.status === 'CANDIDATE'" class="release-note-field">
          <span>{{ approvalStatus === 'REJECTED' ? '退回原因' : '审批说明' }}</span>
          <textarea v-model="approvalComment" rows="2" :placeholder="approvalStatus === 'APPROVED' ? '已记录的批准说明' : '批准可写备注；退回时必须写明原因'" />
        </label>
        <div v-if="ownerReview && selectedSummary?.status === 'CANDIDATE'" class="approval-actions" data-testid="owner-approval-actions">
          <el-button class="release-button" type="primary" :disabled="!canDecideApproval" :loading="mutating" @click="decideApproval('APPROVED')">批准面向全校</el-button>
          <el-button class="release-button" plain :disabled="!canDecideApproval" :loading="mutating" @click="decideApproval('REJECTED')">退回排课员</el-button>
        </div>
        <label v-if="!reviewOnly" class="release-note-field">
          <span>版本说明</span>
          <textarea v-model="releaseNote" rows="2" placeholder="说明本次发布的适用范围、特殊安排或业务确认结论" />
        </label>
        <label v-if="!reviewOnly" class="release-confirm"><input v-model="releaseConfirmed" type="checkbox" /> <span>我已确认当前版本的适用学期、课次数量和发布结果</span></label>
        <el-button v-if="!reviewOnly" class="release-button" type="primary" :disabled="!releaseReady" :loading="mutating" @click="publishVersion">
          {{ selectedSummary?.status === 'PUBLISHED' ? '版本已发布' : '发布当前版本' }}
        </el-button>
      </div>

      <!-- 命令历史 -->
      <div v-if="selectedVersion" class="command-history">
        <div class="history-heading">
          <strong>{{ reviewOnly ? '调整记录' : '命令历史' }}</strong>
          <span v-if="historyLoading">加载中…</span>
          <span v-else-if="!history.length">暂无命令记录</span>
        </div>
        <template v-if="history.length">
          <div v-for="command in history.slice(0, 5)" :key="command.groupId" class="history-row">
            <span>{{ command.commandType }} · {{ command.state }}</span>
            <small>{{ command.reason }} · r{{ command.resultRevision }}</small>
          </div>
          <div class="history-actions">
            <el-button size="small" plain :disabled="!latestApplied || !editable || mutating" :loading="mutating" @click="latestApplied && undoCommand(latestApplied.groupId)">撤销</el-button>
            <el-button size="small" plain :disabled="!latestUndone || !editable || mutating" :loading="mutating" @click="latestUndone && redoCommand(latestUndone.groupId)">重做</el-button>
          </div>
        </template>
      </div>

      <!-- 差异列表 -->
      <div v-loading="diffLoading" v-if="visibleDiff.length" class="diff-list">
        <article
          v-for="item in visibleDiff"
          :key="`${item.occurrenceKey}-${item.changeType}`"
          class="diff-row"
        >
          <span class="diff-type" :class="item.changeType.toLowerCase()">{{ item.changeType }}</span>
          <strong>{{ item.occurrenceKey }}</strong>
          <small>
            {{ item.before?.subjectName ?? item.after?.subjectName ?? '教学任务' }} · {{ item.before?.timeslotCode ?? '—' }} → {{ item.after?.timeslotCode ?? '—' }} · {{ item.before?.roomCode ?? '—' }} → {{ item.after?.roomCode ?? '—' }}
          </small>
        </article>
      </div>
      <el-empty v-else description="没有检测到差异或尚未选择版本" />
    </div>
  </section>

  <div v-if="trialDialogOpen" class="trial-modal" role="dialog" aria-modal="true" data-testid="trial-solve-dialog">
    <div class="trial-dialog-panel">
      <div class="trial-dialog-header">
        <div>
          <span class="eyebrow">试排草稿</span>
          <h2>带新条件试排</h2>
          <small>原版本 v{{ selectedVersion }} 保持不动；系统会复制一份草稿并重新排课，不影响当前课表。</small>
        </div>
        <button class="trial-close" type="button" aria-label="关闭试排窗口" @click="trialDialogOpen = false">×</button>
      </div>
      <div class="trial-form">
        <label>
          <span>规则类型</span>
          <select v-model="trialRuleCode" class="styled-diff-select" @change="normalizeTrialScope">
            <option v-for="item in trialRuleCatalog" :key="item.ruleCode" :value="item.ruleCode">{{ item.label }}</option>
          </select>
        </label>
        <label>
          <span>作用域</span>
          <select v-model="trialRuleScopeType" class="styled-diff-select" @change="normalizeTrialScope">
            <option v-for="scope in trialRuleScopes" :key="scope" :value="scope">{{ scope }}</option>
          </select>
        </label>
        <label v-if="trialRuleScopeType !== 'TERM'">
          <span>资源编码</span>
          <input v-model="trialRuleScopeCode" class="trial-input" placeholder="输入教师/班级/课程编码" />
        </label>
        <label v-if="!trialRuleUsesText">
          <span>整数参数</span>
          <input v-model.number="trialRuleIntValue" class="trial-input" type="number" min="1" />
        </label>
        <label v-else>
          <span>文本参数</span>
          <input v-model="trialRuleTextValue" class="trial-input" placeholder="输入文本策略" />
        </label>
        <label>
          <span>约束级别</span>
          <select v-model="trialRuleSeverity" class="styled-diff-select">
            <option value="HARD">必须满足（会挡住发布）</option>
            <option value="MEDIUM">尽量安排</option>
            <option value="SOFT">偏好，不挡住发布</option>
          </select>
        </label>
        <label>
          <span>规则权重</span>
          <input v-model.number="trialRuleWeight" class="trial-input" type="number" min="1" />
        </label>
      </div>
      <div v-if="trialImpact" class="trial-impact" data-testid="trial-impact-preview">
        <div class="trial-impact-summary">
          <strong>{{ trialImpact.summary }}</strong>
          <span>{{ trialImpact.affectedCount }} 个课次可能变化 · {{ trialImpact.blockingCount ? `有 ${trialImpact.blockingCount} 处会挡住发布` : '不会挡住发布' }}</span>
        </div>
        <div v-if="trialImpact.violations.length" class="trial-impact-list">
          <span v-for="item in trialImpact.violations.slice(0, 5)" :key="`${item.occurrenceKey}-${item.message}`">
            {{ item.subjectName || '教学任务' }} · {{ item.occurrenceKey }} · {{ item.message }}
          </span>
          <small v-if="trialImpact.violations.length > 5">仅展示前 5 项，完整结果将在试排草稿的版本差异中查看。</small>
        </div>
      </div>
      <div class="trial-dialog-footer">
        <button class="quiet-button" type="button" :disabled="trialSubmitting || trialPreviewLoading" @click="trialDialogOpen = false">取消</button>
        <button class="quiet-button" type="button" :disabled="trialPreviewLoading || !trialRuleCode" @click="previewTrialSolve">{{ trialPreviewLoading ? '计算中…' : '计算影响' }}</button>
        <button class="primary-button" type="button" :disabled="trialSubmitting || !trialImpact || !trialRuleCode" @click="submitTrialSolve">{{ trialSubmitting ? '提交中…' : '创建草稿并试排' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.canvas-card {
  background: #ffffff;
  border: 1px solid rgba(23, 59, 54, 0.1);
  border-radius: 12px;
  box-shadow: 0 4px 16px -2px rgba(23, 59, 54, 0.03);
  overflow: hidden;
}
.version-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.version-status-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 4px;
}
.version-status-tag.published { background: #dcfce7; color: #15803d; }
.review-banner { margin: 0 18px 14px; padding: 10px 12px; border-left: 3px solid #5c8b9a; background: #f1f8fa; color: #3f6570; font-size: 11px; }
.version-status-tag.candidate { background: #e0f2fe; color: #0369a1; }
.version-status-tag.draft { background: #fef9c3; color: #a16207; }
.version-status-tag.archived { background: #f3f4f6; color: #6b7280; }

.diff-checkbox {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: #3b574c;
  cursor: pointer;
}
.styled-diff-select {
  border: 1px solid #D9DEE3;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 11px;
  background: #ffffff;
  outline: none;
}
.diff-type.moved { color: #d97706; font-weight: 700; }
.diff-type.added { color: #16a34a; font-weight: 700; }
.diff-type.unchanged { color: #9ca3af; }
.release-panel { margin: 0 18px 18px; padding: 16px; border: 1px solid #dfeae3; background: #f9fcfa; }
.release-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.release-heading h3 { margin: 1px 0 0; color: #2d5544; font-size: 14px; }
.release-heading small { display: block; margin-top: 4px; color: #6d8075; font-size: 11px; line-height: 1.45; max-width: 36rem; }
.release-status { padding: 4px 8px; font-size: 10px; font-weight: 650; }
.release-ready { color: #2c7c52; background: #e4f4ea; }
.release-blocked { color: #9a662d; background: #fff2db; }
.release-group { margin-top: 14px; }
.release-group h4 { margin: 0 0 8px; color: #3f5d52; font-size: 11px; font-weight: 700; }
.release-checks { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.release-checks li { display: grid; grid-template-columns: 19px minmax(0, 1fr); gap: 8px; align-items: start; }
.release-checks li > span { display: grid; place-items: center; width: 19px; height: 19px; border-radius: 50%; font-size: 10px; }
.release-checks strong, .release-checks small { display: block; }
.release-checks strong { color: #566474; font-size: 11px; }
.release-checks small { margin-top: 3px; color: #85998e; font-size: 10px; line-height: 1.45; }
.release-action { display: inline-block; margin-top: 6px; color: #2d6a56; font-size: 11px; font-weight: 650; text-decoration: none; }
.check-ok > span { color: #2a8658; background: #e0f3e7; }
.check-blocked > span { color: #9a662d; background: #FBF4E7; }
.check-warn > span { color: #a15c0a; background: #FBF4E7; }
.release-button { width: 100%; }
.release-note-field { display: grid; gap: 5px; margin: 12px 0 8px; color: #566474; font-size: 11px; }
.release-note-field textarea { resize: vertical; border: 1px solid #D9DEE3; border-radius: 6px; padding: 8px; color: #202A35; font: inherit; line-height: 1.5; }
.release-note-field textarea:focus { outline: 2px solid rgba(77, 138, 120, .25); border-color: #4d8a78; }
.release-confirm { display: flex; gap: 6px; align-items: flex-start; margin: 8px 0 12px; color: #64786e; font-size: 11px; line-height: 1.45; }
.release-confirm input { margin-top: 1px; }
.trial-modal { position: fixed; inset: 0; z-index: 20; display: grid; place-items: center; padding: 20px; background: rgba(16, 34, 31, .35); }
.trial-dialog-panel { width: min(560px, 100%); padding: 20px; background: #ffffff; border: 1px solid #dfeae3; border-radius: 10px; box-shadow: 0 18px 48px rgba(18, 48, 44, .2); }
.trial-dialog-header { display: flex; justify-content: space-between; gap: 14px; }
.trial-dialog-header h2 { margin: 3px 0 5px; color: #2d5544; font-size: 18px; }
.trial-dialog-header small { color: #6d8075; font-size: 11px; line-height: 1.5; }
.trial-close { border: 0; background: transparent; color: #64786e; font-size: 22px; line-height: 1; cursor: pointer; }
.trial-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.trial-form label { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.trial-input { min-width: 0; border: 1px solid #d9dee3; border-radius: 6px; padding: 7px 9px; background: #f9fafb; color: #202a35; font: inherit; font-size: 12px; }
.trial-input:focus { outline: 2px solid rgba(77, 138, 120, .25); border-color: #4d8a78; background: #ffffff; }
.trial-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.trial-impact { margin-top: 16px; padding: 11px 12px; border: 1px solid #d9e8e0; border-left: 3px solid #4d8a78; border-radius: 7px; background: #f4faf7; }
.trial-impact-summary { display: grid; gap: 3px; color: #2d5544; font-size: 12px; }
.trial-impact-summary span { color: #6d8075; font-size: 11px; }
.trial-impact-list { display: grid; gap: 4px; margin-top: 9px; color: #566474; font-size: 11px; line-height: 1.4; }
.trial-impact-list small { color: #85998e; }
@media (max-width: 560px) { .trial-form { grid-template-columns: 1fr; } .trial-dialog-panel { padding: 16px; } }
</style>
