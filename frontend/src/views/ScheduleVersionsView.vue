<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { resolveScore } from '../utils/score'
import { useTermStore } from '../stores/term'

interface VersionSummary {
  id: number
  status: string
  score?: string
  hardScore?: number | null
  mediumScore?: number | null
  softScore?: number | null
  parentVersionId?: number
  createdAt?: string
  revision?: number
  updatedAt?: string
  archivedAt?: string
  editLocked?: boolean
  editLockOwner?: string
}
interface DiffItem { changeType: string; occurrenceKey: string; before?: Record<string, unknown>; after?: Record<string, unknown> }
interface CommandGroup { groupId: string; commandType: string; state: string; reason: string; resultRevision: number }
const term = useTermStore()
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
const onlyChanges = ref(true)
const visibleDiff = computed(() => onlyChanges.value ? diff.value.filter(item => item.changeType !== 'UNCHANGED') : diff.value)
const changeCount = computed(() => diff.value.filter(item => item.changeType !== 'UNCHANGED').length)
const selectedSummary = computed(() => versions.value.find(item => item.id === selectedVersion.value))
const editable = computed(() => selectedSummary.value != null && ['DRAFT', 'CANDIDATE'].includes(selectedSummary.value.status) && !selectedSummary.value.editLocked)
const canLock = computed(() => selectedSummary.value != null && editable.value && !selectedSummary.value.editLocked)
const canUnlock = computed(() => selectedSummary.value != null && Boolean(selectedSummary.value.editLocked))
const canArchive = computed(() => selectedSummary.value != null && selectedSummary.value.status === 'PUBLISHED' && !selectedSummary.value.archivedAt)
const latestApplied = computed(() => history.value.find(item => item.state === 'APPLIED'))
const latestUndone = computed(() => history.value.find(item => item.state === 'UNDONE'))

function scoreParts(version: VersionSummary) {
  return resolveScore(version.score, { hard: version.hardScore, medium: version.mediumScore, soft: version.softScore })
}

async function loadVersions(preserveSelection = false) {
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
  } catch (error) {
    message.value = error instanceof Error ? error.message : '操作失败'
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

function undoCommand(groupId: string) {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/adjustments/commands/${groupId}/undo`, commandRequest(newIdempotencyKey('undo'))))
}

function redoCommand(groupId: string) {
  return mutate(() => http(`/api/schedule-versions/${selectedVersion.value}/adjustments/commands/${groupId}/redo`, commandRequest(newIdempotencyKey('redo'))))
}

function selectVersion(version: VersionSummary) {
  selectedVersion.value = version.id
  diffAgainst.value = version.parentVersionId ?? null
  void loadDiff()
  void loadHistory()
}

watch(() => term.selectedTermCode.value, () => void loadVersions())
onMounted(() => void loadVersions())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">VERSIONS / DIFF</p><h1>版本与差异</h1></div><div class="top-actions"><span class="sync-state">● 只读查询</span><div class="avatar">教</div></div></header>
  <section class="version-page">
    <div class="version-list panel">
      <div class="panel-heading"><div><span class="eyebrow">SCHEDULE VERSIONS</span><h2>候选与草稿</h2></div><el-button plain :loading="loading" @click="loadVersions">刷新</el-button></div>
      <div v-if="message" class="inline-message error-message">{{ message }}</div>
      <button v-for="version in versions" :key="version.id" class="version-row" :class="{ selected: selectedVersion === version.id }" @click="selectVersion(version)">
        <strong>版本 v{{ version.id }} · r{{ version.revision ?? 0 }}</strong><span>{{ version.status }} · {{ version.score ?? '未评分' }} · H{{ scoreParts(version).hard ?? '—' }} / M{{ scoreParts(version).medium ?? '—' }} / S{{ scoreParts(version).soft ?? '—' }}</span><small>{{ version.editLocked ? `锁定：${version.editLockOwner ?? '其他用户'}` : version.archivedAt ? '已归档' : version.parentVersionId ? `父版本 v${version.parentVersionId}` : '无父版本' }}</small>
      </button>
      <el-empty v-if="!loading && !versions.length" description="暂无版本" />
    </div>
    <div class="diff-panel panel">
      <div class="panel-heading"><div><span class="eyebrow">STABLE OCCURRENCE DIFF</span><h2>{{ selectedVersion ? `版本 v${selectedVersion}` : '选择版本' }}</h2><small v-if="selectedSummary" class="resource-caption">revision {{ selectedSummary.revision ?? 0 }} · {{ changeCount }} 项变化</small></div><div class="diff-tools"><label><input v-model="onlyChanges" type="checkbox" /> 只看变化</label><select v-model="diffAgainst" @change="loadDiff"><option :value="null">默认父版本</option><option v-for="version in versions.filter(item => item.id !== selectedVersion)" :key="version.id" :value="version.id">版本 v{{ version.id }}</option></select></div></div>
      <div v-if="selectedVersion" class="version-actions">
        <el-button size="small" plain :disabled="!canLock || mutating" :loading="mutating" @click="lockVersion">锁定编辑</el-button>
        <el-button size="small" plain :disabled="!canUnlock || mutating" :loading="mutating" @click="unlockVersion">解锁</el-button>
        <el-button size="small" plain :disabled="!canArchive || mutating" :loading="mutating" @click="archiveVersion">归档</el-button>
      </div>
      <div v-if="selectedVersion && history.length" class="command-history">
        <div class="history-heading"><strong>命令历史</strong><span v-if="historyLoading">加载中…</span></div>
        <div v-for="command in history.slice(0, 5)" :key="command.groupId" class="history-row"><span>{{ command.commandType }} · {{ command.state }}</span><small>{{ command.reason }} · r{{ command.resultRevision }}</small></div>
        <div class="history-actions">
          <el-button size="small" plain :disabled="!latestApplied || !editable || mutating" :loading="mutating" @click="latestApplied && undoCommand(latestApplied.groupId)">撤销</el-button>
          <el-button size="small" plain :disabled="!latestUndone || !editable || mutating" :loading="mutating" @click="latestUndone && redoCommand(latestUndone.groupId)">重做</el-button>
        </div>
      </div>
      <div v-loading="diffLoading" v-if="visibleDiff.length" class="diff-list"><article v-for="item in visibleDiff" :key="`${item.occurrenceKey}-${item.changeType}`" class="diff-row"><span class="diff-type">{{ item.changeType }}</span><strong>{{ item.occurrenceKey }}</strong><small>{{ item.before?.subjectName ?? item.after?.subjectName ?? '活动' }} · {{ item.before?.timeslotCode ?? '—' }} → {{ item.after?.timeslotCode ?? '—' }} · {{ item.before?.roomCode ?? '—' }} → {{ item.after?.roomCode ?? '—' }}</small></article></div><el-empty v-else description="没有差异或尚未选择版本" />
    </div>
  </section>
</template>
