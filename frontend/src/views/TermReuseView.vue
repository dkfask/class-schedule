<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface CopyPlan {
  sourceTermCode: string
  targetTermCode: string
  targetExists: boolean
  missingMappings: string[]
  canCopy: boolean
  counts: Record<string, number>
  willCopy: string[]
  willNotCopy: string[]
  needsConfirmation: string[]
}

interface CopyResult {
  status: string
  targetTermCode: string
  copied: Record<string, number>
  needsRecheck: boolean
}

const term = useTermStore()
const loading = ref(false)
const copying = ref(false)
const error = ref('')
const message = ref('')
const plan = ref<CopyPlan | null>(null)
const result = ref<CopyResult | null>(null)
const confirmed = ref(false)
const form = ref({
  sourceTermCode: '',
  targetTermCode: '',
  targetName: '',
  startDate: '',
  endDate: '',
  teacherMappings: '',
  studentGroupMappings: '',
  subjectMappings: '',
  roomMappings: '',
})

const mappingFields = [
  { key: 'teacherMappings', label: '教师映射', hint: '每行一个：来源编码 = 目标编码' },
  { key: 'studentGroupMappings', label: '班级映射', hint: '例如：G7-01 = G7-01' },
  { key: 'subjectMappings', label: '课程映射', hint: '课程编码变化时填写' },
  { key: 'roomMappings', label: '教室映射', hint: '教室编码变化时填写' },
] as const

const sourceOptions = computed(() => term.terms.value.filter(item => item.code !== form.value.targetTermCode))

function parseMappings(value: string) {
  return value.split(/\r?\n/).reduce<Record<string, string>>((mapping, line) => {
    const trimmed = line.trim()
    if (!trimmed) return mapping
    const parts = trimmed.split(/\s*(?:=|->|→)\s*/)
    if (parts.length >= 2 && parts[0].trim() && parts[1].trim()) mapping[parts[0].trim()] = parts.slice(1).join('=').trim()
    return mapping
  }, {})
}

function requestBody() {
  return {
    sourceTermCode: form.value.sourceTermCode,
    targetTermCode: form.value.targetTermCode.trim(),
    targetName: form.value.targetName.trim() || undefined,
    startDate: form.value.startDate || undefined,
    endDate: form.value.endDate || undefined,
    teacherMappings: parseMappings(form.value.teacherMappings),
    studentGroupMappings: parseMappings(form.value.studentGroupMappings),
    subjectMappings: parseMappings(form.value.subjectMappings),
    roomMappings: parseMappings(form.value.roomMappings),
  }
}

function canPreview() {
  return Boolean(form.value.sourceTermCode && form.value.targetTermCode.trim() && form.value.sourceTermCode !== form.value.targetTermCode.trim())
}

async function previewCopy() {
  if (loading.value || !canPreview()) return
  loading.value = true
  error.value = ''
  message.value = ''
  result.value = null
  confirmed.value = false
  try {
    plan.value = await http<CopyPlan>('/api/terms/copy/preview', jsonRequest('POST', requestBody()))
  } catch (reason) {
    plan.value = null
    error.value = reason instanceof Error ? reason.message : '学期复用预览失败'
  } finally { loading.value = false }
}

async function copyTerm() {
  if (copying.value || !plan.value?.canCopy || !confirmed.value) return
  copying.value = true
  error.value = ''
  message.value = ''
  try {
    result.value = await http<CopyResult>('/api/terms/copy', jsonRequest('POST', requestBody()))
    message.value = `学期 ${result.value.targetTermCode} 已创建，正在进入重新检查流程`
    plan.value = null
    confirmed.value = false
    await term.loadTerms(true)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '学期复用失败' } finally { copying.value = false }
}

function selectDefaultSource() {
  if (!form.value.sourceTermCode || !term.terms.value.some(item => item.code === form.value.sourceTermCode)) form.value.sourceTermCode = term.terms.value[0]?.code ?? ''
}

watch(() => term.terms.value, selectDefaultSource, { deep: true })
onMounted(async () => {
  await term.loadTerms()
  selectDefaultSource()
})
</script>

<template>
  <header class="topbar">
    <div><p class="eyebrow">TERM REUSE / SETUP</p><h1>学期复用</h1></div>
    <div class="top-actions"><span class="sync-state">● 复制前可预览</span><div class="avatar">复</div></div>
  </header>

  <section class="reuse-page" data-testid="term-reuse-page">
    <section class="panel canvas-card setup-panel">
      <div class="panel-heading"><div><span class="eyebrow">NEW TERM BASELINE</span><h2>从既有学期开始</h2><small>复制的是可复用配置和教学计划草稿，已发布课表、历史审计和已完成任务不会被带入新学期。</small></div></div>
      <div class="setup-grid">
        <label><span>来源学期</span><select v-model="form.sourceTermCode"><option v-for="item in sourceOptions" :key="item.code" :value="item.code">{{ item.name }} · {{ item.code }}</option></select></label>
        <label><span>目标学期编码</span><input v-model="form.targetTermCode" required maxlength="64" placeholder="例如：2027-SPRING" /></label>
        <label><span>目标学期名称</span><input v-model="form.targetName" maxlength="128" placeholder="例如：2027 春季学期" /></label>
        <label><span>开始日期</span><input v-model="form.startDate" type="date" /></label>
        <label><span>结束日期</span><input v-model="form.endDate" type="date" /></label>
      </div>
    </section>

    <section class="panel canvas-card mapping-panel">
      <div class="panel-heading"><div><span class="eyebrow">RESOURCE MAPPING</span><h2>资源映射</h2><small>编码不变时可以留空；编码变更时按行填写 `来源 = 目标`，预览会检查目标资源是否存在。</small></div></div>
      <div class="mapping-grid">
        <label v-for="field in mappingFields" :key="field.key"><span>{{ field.label }}</span><textarea v-model="form[field.key]" rows="3" :placeholder="field.hint" /></label>
      </div>
      <div class="form-actions"><button class="primary-button" :disabled="loading || !canPreview()" @click="previewCopy">{{ loading ? '正在计算…' : '生成复制预览' }}</button><span>来源与目标不能相同，目标学期必须是全新编码。</span></div>
    </section>

    <section class="panel canvas-card preview-panel">
      <div class="panel-heading"><div><span class="eyebrow">COPY IMPACT PREVIEW</span><h2>{{ plan ? `${plan.sourceTermCode} → ${plan.targetTermCode}` : '等待生成预览' }}</h2><small>确认前检查复制范围、资源缺口和后续动作。</small></div></div>
      <div v-if="error" class="inline-message error-message">{{ error }}</div>
      <div v-if="message" class="inline-message success-message">{{ message }}</div>
      <div v-if="plan" class="preview-content">
        <div class="impact-summary"><div v-for="entry in Object.entries(plan.counts)" :key="entry[0]"><strong>{{ entry[1] }}</strong><span>{{ ({ periods: '节次模板', requirements: '教学需求', activityGroups: '活动组', rules: '规则实例' } as Record<string, string>)[entry[0]] ?? entry[0] }}</span></div></div>
        <div class="preview-columns"><div><h3>将复制</h3><ul><li v-for="item in plan.willCopy" :key="item">{{ item }}</li></ul></div><div><h3>不会复制</h3><ul><li v-for="item in plan.willNotCopy" :key="item">{{ item }}</li></ul></div></div>
        <div v-if="plan.targetExists" class="impact-warning"><strong>目标学期已存在</strong><span>为避免覆盖既有数据，本次复制被阻止。</span></div>
        <div v-if="plan.missingMappings.length" class="impact-warning"><strong>需要先处理资源映射</strong><ul><li v-for="item in plan.missingMappings" :key="item">{{ item }}</li></ul></div>
        <div v-if="plan.needsConfirmation.length" class="notice-box"><strong>复制后的必做事项</strong><span v-for="item in plan.needsConfirmation" :key="item">{{ item }}</span></div>
        <div v-if="plan.canCopy" class="confirm-box"><label><input v-model="confirmed" type="checkbox" /> 我已确认复制范围，并接受新学期需重新执行数据健康检查和发布门禁</label><button class="primary-button" :disabled="copying || !confirmed" @click="copyTerm">{{ copying ? '正在复制…' : '确认创建新学期' }}</button></div>
        <div v-else class="blocked-note">补齐资源映射并确保目标编码未被使用后，才可以创建新学期。</div>
      </div>
      <div v-else-if="!loading" class="empty">填写目标学期并生成预览，系统会先检查影响范围。</div>
    </section>

    <section v-if="result" class="result-strip"><strong>已创建 {{ result.targetTermCode }}</strong><span>复制 {{ Object.values(result.copied).reduce((sum, value) => sum + value, 0) }} 项配置</span><span v-if="result.needsRecheck">请转到学期总览完成数据健康检查和发布门禁复核。</span></section>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.reuse-page { display: grid; gap: 14px; }
.setup-panel, .mapping-panel, .preview-panel { padding: 20px; }
.setup-panel h2, .mapping-panel h2, .preview-panel h2 { margin: 5px 0 0; color: #173b36; font-size: 18px; }
.panel-heading small { display: block; max-width: 760px; margin-top: 6px; color: #6a7b75; font-size: 11px; line-height: 1.5; }
.setup-grid, .mapping-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.setup-grid label, .mapping-grid label { display: grid; gap: 5px; color: #50605c; font-size: 11px; }
.setup-grid input, .setup-grid select, .mapping-grid textarea { min-width: 0; border: 1px solid #dce4e0; border-radius: 6px; padding: 9px 10px; color: #191c1d; background: #fff; font: inherit; font-size: 12px; }
.mapping-grid textarea { resize: vertical; line-height: 1.5; }
.form-actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; color: #789087; font-size: 11px; }
.primary-button, .quiet-button { border-radius: 6px; padding: 9px 13px; cursor: pointer; font: inherit; font-size: 12px; }
.primary-button { border: 1px solid #173b36; background: #173b36; color: #fff; }
.primary-button:disabled { cursor: not-allowed; opacity: .55; }
.preview-content { margin-top: 18px; }
.impact-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.impact-summary div { display: grid; gap: 4px; padding: 12px; border: 1px solid #e4ece7; border-radius: 7px; background: #f8fbf9; }
.impact-summary strong { color: #173b36; font-size: 22px; }
.impact-summary span { color: #789087; font-size: 10px; }
.preview-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 14px; }
.preview-columns > div { padding: 12px; border: 1px solid #e4ece7; border-radius: 7px; background: #fbfdfb; }
.preview-columns h3 { margin: 0 0 8px; color: #173b36; font-size: 12px; }
.preview-columns ul, .impact-warning ul { padding-left: 18px; margin: 0; color: #5f7069; font-size: 11px; line-height: 1.7; }
.impact-warning, .blocked-note { display: grid; gap: 5px; margin-top: 12px; padding: 11px 12px; border: 1px solid #f1d2aa; border-radius: 7px; background: #fffaf1; color: #805c27; font-size: 11px; line-height: 1.45; }
.notice-box { display: grid; gap: 5px; margin-top: 12px; padding: 11px 12px; border: 1px solid #c8dbe0; border-radius: 7px; background: #f4fafb; color: #3c6268; font-size: 11px; }
.confirm-box { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; padding-top: 14px; border-top: 1px solid #e8efeb; color: #50605c; font-size: 11px; }
.confirm-box label { display: flex; align-items: center; gap: 6px; }
.empty { padding: 32px 12px; color: #789087; font-size: 12px; text-align: center; }
.inline-message { margin-top: 12px; padding: 10px 12px; border-radius: 7px; font-size: 12px; }
.success-message { border: 1px solid #bbdec6; background: #effaf2; color: #28623d; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
.result-strip { display: flex; align-items: center; gap: 16px; padding: 12px 16px; border-left: 3px solid #2a7560; background: #f4faf6; color: #50685d; font-size: 11px; }
.result-strip strong { color: #173b36; font-size: 13px; }
@media (max-width: 760px) { .setup-grid, .mapping-grid, .preview-columns { grid-template-columns: 1fr; } .form-actions, .confirm-box, .result-strip { align-items: stretch; flex-direction: column; } .impact-summary { grid-template-columns: repeat(2, 1fr); } }
</style>
