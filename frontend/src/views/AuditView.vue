<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { http } from '../api/http'
import { useTermStore } from '../stores/term'

interface AuditEvent {
  id: number
  action: string
  aggregateType: string
  aggregateId?: string
  actor: string
  actorKind?: string
  outcome?: string
  correlationId?: string
  detail?: string
  createdAt?: string
  termCode?: string
}

const term = useTermStore()
const events = ref<AuditEvent[]>([])
const actor = ref('')
const action = ref('')
const versionId = ref('')
const from = ref('')
const to = ref('')
const loading = ref(false)
const error = ref('')
const hasMore = ref(false)

const actionOptions = [
  ['CREATE', '新建'],
  ['UPDATE', '更新'],
  ['DEACTIVATE', '停用'],
  ['ACTIVATE', '启用'],
  ['SOLVE_FAILED', '求解失败'],
  ['SOLVE_DEADLINE', '求解超时'],
  ['PUBLISH', '发布'],
  ['ADJUST', '调整'],
  ['LOCK', '锁定'],
  ['UNLOCK', '解锁'],
  ['ARCHIVE', '归档'],
] as const

function actionLabel(value: string) {
  return actionOptions.find(item => item[0] === value)?.[1] ?? value
}

function aggregateLabel(value: string) {
  return ({
    SCHEDULE_VERSION: '课表版本',
    SOLVE_JOB: '求解任务',
    IMPORT_BATCH: '导入批次',
    TEACHING_REQUIREMENT: '教学需求',
    RULE_FACT: '规则事实',
  } as Record<string, string>)[value] ?? value
}

function outcomeLabel(value?: string) {
  return ({ SUCCESS: '成功', FAILED: '失败', REJECTED: '已拒绝' } as Record<string, string>)[value ?? ''] ?? value ?? '已记录'
}

function formatDate(value?: string) {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function detailSummary(value?: string) {
  if (!value) return '没有附加摘要'
  try {
    const parsed = JSON.parse(value) as Record<string, unknown>
    return Object.entries(parsed)
      .map(([key, item]) => `${key}: ${typeof item === 'object' ? JSON.stringify(item) : String(item)}`)
      .join(' · ')
  } catch {
    return value
  }
}

function problemHref(event: AuditEvent) {
  const params = new URLSearchParams({ title: `${actionLabel(event.action)}：${aggregateLabel(event.aggregateType)}${event.aggregateId ? ` ${event.aggregateId}` : ''}` })
  if (event.aggregateType === 'SCHEDULE_VERSION' && event.aggregateId) params.set('versionId', event.aggregateId)
  if (event.aggregateType === 'SOLVE_JOB' && event.aggregateId) params.set('solveJobId', event.aggregateId)
  if (event.aggregateType === 'IMPORT_BATCH' && event.aggregateId) params.set('importBatchId', event.aggregateId)
  return `/problems?${params.toString()}`
}

async function load() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    events.value = []
    error.value = term.error.value || '暂无可用学期'
    return
  }
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ termCode: term.selectedTermCode.value })
  if (versionId.value.trim()) params.set('versionId', versionId.value.trim())
  if (actor.value.trim()) params.set('actor', actor.value.trim())
  if (action.value) params.set('action', action.value)
  if (from.value) params.set('from', from.value)
  if (to.value) params.set('to', to.value)
  try {
    const result = await http<{ items?: AuditEvent[]; hasMore?: boolean }>(`/api/audit?${params.toString()}`)
    events.value = result.items ?? []
    hasMore.value = Boolean(result.hasMore)
  } catch (reason) {
    events.value = []
    error.value = reason instanceof Error ? reason.message : '审计记录加载失败'
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  actor.value = ''
  action.value = ''
  versionId.value = ''
  from.value = ''
  to.value = ''
  void load()
}

watch(() => term.selectedTermCode.value, () => void load())
onMounted(() => void load())
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">AUDIT / TRACE</p>
      <h1>操作与审计</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● 只读记录</span>
      <div class="avatar">审</div>
    </div>
  </header>

  <section class="audit-page panel canvas-card" data-testid="audit-page">
    <div class="panel-heading">
      <div>
        <span class="eyebrow">ACTIVITY TRAIL</span>
        <h2>业务操作记录</h2>
        <small>用于还原导入、求解、调整和发布过程；记录不可在此页面修改。</small>
      </div>
      <el-button plain :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="audit-filters">
      <label><span>当前学期</span><input :value="term.selectedTermCode.value" disabled /></label>
      <label><span>版本号</span><input v-model="versionId" inputmode="numeric" placeholder="如 24" /></label>
      <label><span>操作人</span><input v-model="actor" placeholder="用户名或服务名" /></label>
      <label><span>操作类型</span><select v-model="action"><option value="">全部操作</option><option v-for="item in actionOptions" :key="item[0]" :value="item[0]">{{ item[1] }}</option></select></label>
      <label><span>开始日期</span><input v-model="from" type="date" /></label>
      <label><span>结束日期</span><input v-model="to" type="date" /></label>
      <div class="filter-actions"><button class="primary-button" :disabled="loading" @click="load">查询</button><button class="quiet-button" :disabled="loading" @click="clearFilters">清空</button></div>
    </div>

    <div v-if="error" class="inline-message error-message">{{ error }}</div>
    <div v-if="loading" class="audit-empty">正在读取操作记录…</div>
    <div v-else-if="!events.length" class="audit-empty">当前筛选条件下暂无操作记录</div>
    <div v-else class="audit-list">
      <article v-for="event in events" :key="event.id" class="audit-row">
        <div class="audit-row-main">
          <div class="audit-title"><span class="action-pill" :class="(event.outcome ?? 'SUCCESS').toLowerCase()">{{ actionLabel(event.action) }}</span><strong>{{ aggregateLabel(event.aggregateType) }}{{ event.aggregateId ? ` · ${event.aggregateId}` : '' }}</strong></div>
          <p>{{ detailSummary(event.detail) }}</p>
        </div>
          <div class="audit-meta"><span>{{ event.actor || '系统' }}</span><span>{{ outcomeLabel(event.outcome) }}</span><time>{{ formatDate(event.createdAt) }}</time><a class="problem-link" :href="problemHref(event)">创建问题</a></div>
      </article>
    </div>
    <div v-if="hasMore" class="audit-footnote">已显示最近 100 条记录，请缩小筛选范围后继续定位。</div>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.audit-page { padding: 20px; }
.audit-page h2 { margin: 5px 0 0; font-size: 18px; }
.audit-page .panel-heading small { display: block; margin-top: 6px; color: #6a7b75; font-size: 11px; }
.audit-filters { display: grid; grid-template-columns: repeat(6, minmax(110px, 1fr)) auto; gap: 10px; align-items: end; margin: 20px 0; padding: 14px; background: #f6faf8; border: 1px solid #e8f0eb; border-radius: 8px; }
.audit-filters label { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.audit-filters input, .audit-filters select { min-width: 0; border: 1px solid #D9DEE3; border-radius: 6px; padding: 8px 9px; background: #fff; color: #191c1d; font-size: 12px; }
.filter-actions { display: flex; gap: 6px; }
.primary-button, .quiet-button { border-radius: 6px; padding: 8px 12px; cursor: pointer; font-size: 12px; white-space: nowrap; }
.primary-button { border: 1px solid #202A35; background: #202A35; color: #fff; }
.quiet-button { border: 1px solid #D9DEE3; background: #fff; color: #566474; }
.primary-button:disabled, .quiet-button:disabled { cursor: not-allowed; opacity: .55; }
.audit-list { display: grid; border-top: 1px solid #e8efeb; }
.audit-row { display: flex; justify-content: space-between; gap: 20px; padding: 14px 4px; border-bottom: 1px solid #EEF1F3; }
.audit-title { display: flex; align-items: center; gap: 8px; color: #202A35; font-size: 13px; }
.audit-row p { margin: 7px 0 0 2px; color: #566474; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.action-pill { border-radius: 9999px; padding: 3px 8px; background: #e6f2ec; color: #28704d; font-size: 10px; font-weight: 700; white-space: nowrap; }
.action-pill.failed, .action-pill.rejected { background: #fee2e2; color: #b91c1c; }
.audit-meta { display: grid; flex: 0 0 180px; gap: 4px; justify-items: end; color: #62736d; font-size: 11px; text-align: right; }
.audit-meta time { color: #8795A5; }
.problem-link { color: #B85C45; font-size: 10px; text-decoration: none; }
.problem-link:hover { text-decoration: underline; }
.audit-empty { padding: 34px 16px; border: 1px dashed #cddbd5; border-radius: 8px; color: #566474; text-align: center; font-size: 12px; }
.audit-footnote { margin-top: 12px; color: #8795A5; font-size: 11px; text-align: center; }
.inline-message { margin-bottom: 14px; padding: 10px 14px; border-radius: 8px; font-size: 12px; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
@media (max-width: 1050px) { .audit-filters { grid-template-columns: repeat(3, minmax(130px, 1fr)); } }
@media (max-width: 640px) { .audit-page { padding: 14px; } .audit-filters { grid-template-columns: 1fr 1fr; } .filter-actions { grid-column: 1 / -1; } .audit-row { display: grid; gap: 10px; } .audit-meta { display: flex; flex-wrap: wrap; justify-items: start; flex: auto; text-align: left; } }
</style>
