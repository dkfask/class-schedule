<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface Metrics { importAttempts: number; importFailures: number; solveAttempts: number; solveFailures: number; manualAdjustments: number; publishedVersions: number; problemReports: number; supportInterventions: number; elapsedMinutes: number }
interface Retrospective { metrics: Metrics; notes: { ruleAdaptation: string; legacyIssues: string; schoolFeedback: string; supportInterventionCount: number } }

const term = useTermStore()
const data = ref<Retrospective | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const form = ref({ ruleAdaptation: '', legacyIssues: '', schoolFeedback: '', supportInterventionCount: 0 })
let loadSequence = 0

async function load() {
  const sequence = ++loadSequence
  await term.loadTerms()
  if (sequence !== loadSequence) return
  if (!term.hasValidTerm.value) { error.value = term.error.value || '暂无可用学期'; return }
  const termCode = term.selectedTermCode.value
  loading.value = true
  error.value = ''
  try {
    const result = await http<Retrospective>(`/api/retrospectives?termCode=${encodeURIComponent(termCode)}`)
    if (sequence !== loadSequence || termCode !== term.selectedTermCode.value) return
    data.value = result
    form.value = { ...form.value, ...result.notes }
  } catch (reason) {
    if (sequence === loadSequence) error.value = reason instanceof Error ? reason.message : '复盘数据加载失败'
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function save() {
  if (saving.value || !term.hasValidTerm.value) return
  saving.value = true
  error.value = ''
  try {
    data.value = await http<Retrospective>('/api/retrospectives', jsonRequest('PATCH', { termCode: term.selectedTermCode.value, ...form.value }))
    form.value = { ...form.value, ...data.value.notes }
    message.value = '复盘记录已保存'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '复盘记录保存失败' } finally { saving.value = false }
}

function number(value: number | undefined) { return value ?? 0 }
function minutes(value: number | undefined) { return `${Math.round(number(value))} 分钟` }

watch(() => term.selectedTermCode.value, () => void load())
onMounted(() => void load())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">RETROSPECTIVE / DELIVERY</p><h1>学期复盘</h1></div><div class="top-actions"><span class="sync-state">● 指标与反馈分开记录</span><div class="avatar">复</div></div></header>
  <section class="retrospective-page" data-testid="retrospective-page">
    <div class="panel canvas-card summary-panel"><div class="panel-heading"><div><span class="eyebrow">TERM REVIEW</span><h2>{{ term.selectedTermCode.value || '当前学期' }} 的交付摘要</h2><small>复盘用于判断数据准备、算法执行和实施支持的真实成本，不把算法分数当作唯一客户价值。</small></div><button class="quiet-button" :disabled="loading" @click="load">刷新</button></div><div v-if="error" class="inline-message error-message">{{ error }}</div><div v-if="message" class="inline-message success-message">{{ message }}</div><div v-if="loading" class="empty">正在加载复盘数据…</div><div v-else-if="data" class="metric-groups"><section><h3>产品与流程</h3><div class="metric-grid"><div><strong>{{ number(data.metrics.importAttempts) }}</strong><span>导入尝试</span><small>失败 {{ number(data.metrics.importFailures) }} 次</small></div><div><strong>{{ number(data.metrics.publishedVersions) }}</strong><span>发布版本</span><small>问题记录 {{ number(data.metrics.problemReports) }} 条</small></div><div><strong>{{ number(data.metrics.manualAdjustments) }}</strong><span>人工调整</span><small>交付历时 {{ minutes(data.metrics.elapsedMinutes) }}</small></div></div></section><section><h3>算法运行</h3><div class="metric-grid"><div><strong>{{ number(data.metrics.solveAttempts) }}</strong><span>求解尝试</span><small>失败 {{ number(data.metrics.solveFailures) }} 次</small></div><div><strong>{{ number(data.metrics.solveFailures) }}</strong><span>失败任务</span><small>需要结合错误码分析</small></div><div><strong>{{ number(data.metrics.supportInterventions) }}</strong><span>支持相关事件</span><small>问题和复盘更新</small></div></div></section></div></div>
    <section class="panel canvas-card notes-panel"><div class="panel-heading"><div><span class="eyebrow">IMPLEMENTATION NOTES</span><h2>规则适配与学校反馈</h2><small>记录下学期可复用的实施经验，并保留需要重新确认的遗留问题。</small></div></div><form class="notes-form" @submit.prevent="save"><label><span>规则适配</span><textarea v-model="form.ruleAdaptation" rows="4" maxlength="10000" placeholder="哪些规则需要配置、实施服务或暂不支持？" /></label><label><span>遗留问题</span><textarea v-model="form.legacyIssues" rows="4" maxlength="10000" placeholder="未解决的数据、流程或发布风险" /></label><label><span>学校反馈</span><textarea v-model="form.schoolFeedback" rows="4" maxlength="10000" placeholder="业务负责人对准备度、课表质量和交付过程的反馈" /></label><div class="notes-footer"><label class="count-field"><span>支持介入次数</span><input v-model.number="form.supportInterventionCount" type="number" min="0" step="1" /></label><button class="primary-button" type="submit" :disabled="saving || !term.hasValidTerm.value">保存复盘</button></div></form></section>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.retrospective-page { display: grid; gap: 14px; }
.summary-panel, .notes-panel { padding: 20px; }
.summary-panel h2, .notes-panel h2 { margin: 5px 0 0; color: #202A35; font-size: 18px; }
.panel-heading small { display: block; margin-top: 6px; color: #6a7b75; font-size: 11px; }
.quiet-button, .primary-button { border-radius: 6px; padding: 8px 12px; cursor: pointer; font: inherit; font-size: 12px; }
.quiet-button { border: 1px solid #D9DEE3; background: #fff; color: #566474; }
.primary-button { border: 1px solid #202A35; background: #202A35; color: #fff; }
.quiet-button:disabled, .primary-button:disabled { cursor: not-allowed; opacity: .55; }
.metric-groups { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 20px; }
.metric-groups h3 { margin: 0 0 8px; color: #36594a; font-size: 13px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.metric-grid div { display: grid; gap: 4px; padding: 12px; border: 1px solid #EEF1F3; border-radius: 7px; background: #f8fbf9; }
.metric-grid strong { color: #202A35; font-size: 22px; }
.metric-grid span { color: #50685d; font-size: 11px; }
.metric-grid small { color: #8a9a91; font-size: 10px; }
.notes-form { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 16px; }
.notes-form label { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.notes-form textarea, .notes-form input { min-width: 0; border: 1px solid #D9DEE3; border-radius: 6px; padding: 9px; color: #191c1d; background: #fff; font: inherit; font-size: 12px; resize: vertical; }
.notes-footer { display: flex; grid-column: 1 / -1; justify-content: space-between; align-items: end; gap: 10px; }
.count-field { max-width: 180px; }
.inline-message { margin-top: 12px; padding: 10px 12px; border-radius: 7px; font-size: 12px; }
.success-message { border: 1px solid #bbdec6; background: #effaf2; color: #28623d; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
.empty { padding: 32px; color: #566474; font-size: 12px; text-align: center; }
@media (max-width: 760px) { .metric-groups { grid-template-columns: 1fr; } .metric-grid { grid-template-columns: repeat(2, 1fr); } .notes-form { grid-template-columns: 1fr; } .notes-footer { grid-column: auto; } }
</style>
