<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface Problem {
  id: number
  termCode?: string
  versionId?: number | null
  solveJobId?: number | null
  importBatchId?: number | null
  title: string
  description: string
  priority: string
  category: string
  status: string
  evidence?: string
  resolution?: string
  reporter?: string
  assignee?: string
  createdAt?: string
  updatedAt?: string
  resolvedAt?: string
}

const route = useRoute()
const term = useTermStore()
const problems = ref<Problem[]>([])
const loading = ref(false)
const saving = ref(false)
const message = ref('')
const error = ref('')
const filterStatus = ref('')
const filterCategory = ref('')
const form = ref({ title: '', description: '', priority: 'MEDIUM', category: 'TO_CONFIRM', evidence: '', versionId: '', solveJobId: '', importBatchId: '' })

const statusLabels: Record<string, string> = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
const priorityLabels: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', URGENT: '紧急' }
const categoryLabels: Record<string, string> = { DATA: '数据问题', RULE: '规则不支持', PRODUCT: '产品缺陷', OPERATION: '操作问题', TO_CONFIRM: '待确认' }

function queryValue(key: string) {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

function applyContext() {
  form.value.title = queryValue('title')
  form.value.versionId = queryValue('versionId')
  form.value.solveJobId = queryValue('solveJobId')
  form.value.importBatchId = queryValue('importBatchId')
}

function formatDate(value?: string) {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function load() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) { problems.value = []; error.value = term.error.value || '暂无可用学期'; return }
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ termCode: term.selectedTermCode.value })
  if (filterStatus.value) params.set('status', filterStatus.value)
  if (filterCategory.value) params.set('category', filterCategory.value)
  try {
    const result = await http<{ items?: Problem[] }>(`/api/problems?${params.toString()}`)
    problems.value = result.items ?? []
  } catch (reason) {
    problems.value = []
    error.value = reason instanceof Error ? reason.message : '问题列表加载失败'
  } finally { loading.value = false }
}

function contextSummary(item: Problem) {
  const parts = [`学期 ${item.termCode ?? '未关联'}`]
  if (item.versionId) parts.push(`版本 v${item.versionId}`)
  if (item.solveJobId) parts.push(`求解任务 #${item.solveJobId}`)
  if (item.importBatchId) parts.push(`导入批次 #${item.importBatchId}`)
  return parts.join(' · ')
}

async function createProblem() {
  if (!form.value.title.trim() || !form.value.description.trim() || saving.value) return
  saving.value = true
  message.value = ''
  try {
    await http('/api/problems', jsonRequest('POST', {
      termCode: term.selectedTermCode.value,
      title: form.value.title.trim(), description: form.value.description.trim(), priority: form.value.priority, category: form.value.category,
      evidence: form.value.evidence.trim() || undefined,
      versionId: form.value.versionId ? Number(form.value.versionId) : undefined,
      solveJobId: form.value.solveJobId ? Number(form.value.solveJobId) : undefined,
      importBatchId: form.value.importBatchId ? Number(form.value.importBatchId) : undefined,
    }))
    message.value = '问题已创建，后续处理会保留在问题记录中'
    form.value = { title: '', description: '', priority: 'MEDIUM', category: 'TO_CONFIRM', evidence: '', versionId: '', solveJobId: '', importBatchId: '' }
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '问题创建失败' } finally { saving.value = false }
}

async function updateProblem(item: Problem, nextStatus?: string) {
  const status = nextStatus ?? item.status
  try {
    await http(`/api/problems/${item.id}`, jsonRequest('PATCH', { status, resolution: item.resolution ?? '' }))
    item.status = status
    message.value = `问题 #${item.id} 已更新为${statusLabels[status] ?? status}`
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '问题更新失败' }
}

watch(() => term.selectedTermCode.value, () => void load())
watch(() => route.query, applyContext, { deep: true })
onMounted(() => { applyContext(); void load() })
</script>

<template>
  <header class="topbar">
    <div><p class="eyebrow">PROBLEMS / FOLLOW-UP</p><h1>问题与反馈中心</h1></div>
    <div class="top-actions"><span class="sync-state">● 可追踪处理</span><div class="avatar">问</div></div>
  </header>

  <section class="problem-page panel canvas-card" data-testid="problems-page">
    <div class="panel-heading"><div><span class="eyebrow">ISSUE INBOX</span><h2>问题清单</h2><small>从导入、求解、冲突和发布记录创建问题，并保留处理结论与验证依据。</small></div><button class="quiet-button" :disabled="loading" @click="load">刷新</button></div>
    <div v-if="message" class="inline-message success-message">{{ message }}</div>
    <div v-if="error" class="inline-message error-message">{{ error }}</div>

    <form class="problem-create" @submit.prevent="createProblem">
      <div class="form-heading"><div><span class="eyebrow">NEW PROBLEM</span><h3>创建问题</h3></div><span>当前学期 {{ term.selectedTermCode.value || '—' }}</span></div>
      <div class="form-grid"><label class="wide"><span>标题</span><input v-model="form.title" required maxlength="200" placeholder="例如：七年级数学有两节课无法安排" /></label><label><span>分类</span><select v-model="form.category"><option value="DATA">数据问题</option><option value="RULE">规则不支持</option><option value="PRODUCT">产品缺陷</option><option value="OPERATION">操作问题</option><option value="TO_CONFIRM">待确认</option></select></label><label><span>优先级</span><select v-model="form.priority"><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label><label class="wide"><span>问题描述</span><textarea v-model="form.description" required maxlength="10000" rows="3" placeholder="描述实际影响、复现条件和期望结果" /></label><label class="wide"><span>证据或验证依据</span><textarea v-model="form.evidence" maxlength="10000" rows="2" placeholder="可记录错误码、冲突对象、截图位置或后续验证方法" /></label></div>
      <div class="context-fields"><label><span>版本 ID（可选）</span><input v-model="form.versionId" inputmode="numeric" /></label><label><span>求解任务 ID（可选）</span><input v-model="form.solveJobId" inputmode="numeric" /></label><label><span>导入批次 ID（可选）</span><input v-model="form.importBatchId" inputmode="numeric" /></label><button class="primary-button" type="submit" :disabled="saving || !form.title.trim() || !form.description.trim()">创建问题</button></div>
    </form>

    <div class="problem-toolbar"><div><strong>待跟进问题</strong><span>{{ problems.length }} 条</span></div><div class="problem-filters"><select v-model="filterStatus" @change="load"><option value="">全部状态</option><option value="OPEN">待处理</option><option value="IN_PROGRESS">处理中</option><option value="RESOLVED">已解决</option><option value="CLOSED">已关闭</option></select><select v-model="filterCategory" @change="load"><option value="">全部分类</option><option value="DATA">数据问题</option><option value="RULE">规则不支持</option><option value="PRODUCT">产品缺陷</option><option value="OPERATION">操作问题</option><option value="TO_CONFIRM">待确认</option></select></div></div>
    <div v-if="loading" class="problem-empty">正在加载问题…</div>
    <div v-else-if="!problems.length" class="problem-empty">当前学期还没有问题记录</div>
    <div v-else class="problem-list"><article v-for="item in problems" :key="item.id" class="problem-card" :class="`priority-${item.priority.toLowerCase()}`"><div class="problem-card-header"><div><span class="problem-id">#{{ item.id }}</span><strong>{{ item.title }}</strong></div><div class="problem-tags"><span class="problem-tag">{{ categoryLabels[item.category] ?? item.category }}</span><span class="problem-tag priority-tag">{{ priorityLabels[item.priority] ?? item.priority }}</span></div></div><p>{{ item.description }}</p><small class="problem-context">{{ contextSummary(item) }} · {{ item.reporter || '未知提交人' }} · {{ formatDate(item.createdAt) }}</small><div v-if="item.evidence" class="problem-evidence">证据：{{ item.evidence }}</div><div class="problem-card-footer"><label><span>处理状态</span><select :value="item.status" @change="updateProblem(item, ($event.target as HTMLSelectElement).value)"><option value="OPEN">待处理</option><option value="IN_PROGRESS">处理中</option><option value="RESOLVED">已解决</option><option value="CLOSED">已关闭</option></select></label><label class="resolution-field"><span>处理结论</span><input v-model="item.resolution" placeholder="补充处理结论" @keyup.enter="updateProblem(item)" /></label><button class="quiet-button" @click="updateProblem(item)">保存</button></div></article></div>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.problem-page { padding: 20px; }
.problem-page h2, .problem-page h3 { margin: 5px 0 0; color: #202A35; }
.problem-page h2 { font-size: 18px; }
.problem-page h3 { font-size: 15px; }
.panel-heading small { display: block; margin-top: 6px; color: #6a7b75; font-size: 11px; }
.problem-create { margin: 20px 0; padding: 16px; border: 1px solid #dfeae3; border-radius: 9px; background: #f7fbf8; }
.form-heading, .problem-toolbar, .problem-card-header, .problem-card-footer { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
.form-heading > span, .problem-toolbar > div > span { color: #566474; font-size: 11px; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) 140px 140px; gap: 10px; margin-top: 14px; }
.form-grid label, .context-fields label, .problem-card-footer label { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.form-grid .wide { grid-column: 1 / -1; }
.form-grid input, .form-grid textarea, .form-grid select, .context-fields input, .problem-card-footer input, .problem-card-footer select, .problem-filters select { min-width: 0; border: 1px solid #D9DEE3; border-radius: 6px; padding: 8px 9px; background: #fff; color: #191c1d; font: inherit; font-size: 12px; }
.form-grid textarea { resize: vertical; line-height: 1.45; }
.context-fields { display: grid; grid-template-columns: repeat(3, minmax(100px, 1fr)) auto; gap: 10px; align-items: end; margin-top: 10px; }
.problem-toolbar { align-items: center; margin: 18px 0 10px; padding-bottom: 10px; border-bottom: 1px solid #e8efeb; color: #36594a; }
.problem-toolbar > div:first-child { display: flex; gap: 8px; align-items: center; }
.problem-filters { display: flex; gap: 7px; }
.problem-list { display: grid; gap: 10px; }
.problem-card { padding: 14px; border: 1px solid #EEF1F3; border-left: 3px solid #76a589; border-radius: 8px; background: #fff; }
.problem-card.priority-high { border-left-color: #d7964d; }
.problem-card.priority-urgent { border-left-color: #ca6a5e; background: #fffafa; }
.problem-card-header strong { margin-left: 7px; color: #202A35; font-size: 13px; }
.problem-id { color: #8a9b92; font-size: 10px; }
.problem-tags { display: flex; gap: 5px; flex-wrap: wrap; justify-content: flex-end; }
.problem-tag { padding: 3px 7px; border-radius: 999px; color: #47715b; background: #e8f4ec; font-size: 10px; }
.priority-tag { color: #8e641f; background: #FBF4E7; }
.problem-card p { margin: 10px 0 7px; color: #50685d; font-size: 12px; line-height: 1.55; white-space: pre-wrap; }
.problem-context { color: #8a9a91; font-size: 10px; }
.problem-evidence { margin-top: 8px; padding: 7px 9px; color: #657d70; background: #F5F6F8; font-size: 11px; line-height: 1.45; }
.problem-card-footer { align-items: end; margin-top: 12px; padding-top: 10px; border-top: 1px solid #eef3ef; }
.problem-card-footer label:first-child { flex: 0 0 150px; }
.resolution-field { flex: 1; }
.primary-button, .quiet-button { border-radius: 6px; padding: 8px 12px; cursor: pointer; font-size: 12px; white-space: nowrap; }
.primary-button { border: 1px solid #202A35; background: #202A35; color: #fff; }
.quiet-button { border: 1px solid #D9DEE3; background: #fff; color: #566474; }
.primary-button:disabled, .quiet-button:disabled { cursor: not-allowed; opacity: .55; }
.problem-empty { padding: 32px 16px; border: 1px dashed #cddbd5; border-radius: 8px; color: #566474; text-align: center; font-size: 12px; }
.inline-message { margin-bottom: 14px; padding: 10px 14px; border-radius: 8px; font-size: 12px; }
.success-message { border: 1px solid #bbdec6; background: #effaf2; color: #28623d; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
@media (max-width: 850px) { .form-grid, .context-fields { grid-template-columns: 1fr 1fr; } .form-grid .wide { grid-column: 1 / -1; } .context-fields .primary-button { grid-column: 1 / -1; } }
@media (max-width: 620px) { .problem-page { padding: 14px; } .form-grid, .context-fields { grid-template-columns: 1fr; } .problem-card-header, .problem-card-footer, .problem-toolbar { display: grid; } .problem-card-footer label:first-child { flex: auto; } .problem-filters { width: 100%; } .problem-filters select { flex: 1; } }
</style>
