<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface Template { id: number; code: string; version: number; name: string; description?: string; maintainedBy?: string; changeNote?: string }
interface Preview { added?: unknown[]; modified?: unknown[]; retired?: unknown[]; unmatchedScopes?: string[]; canApply: boolean; readiness?: { ready: boolean; issues?: Array<{ message: string }> } }

const term = useTermStore()
const templates = ref<Template[]>([])
const selectedId = ref<number | null>(null)
const preview = ref<Preview | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const confirmApply = ref(false)
const form = ref({ code: 'SCHOOL-DEFAULT', name: '学校默认规则', description: '', maintainedBy: '', changeNote: '' })

const selectedTemplate = computed(() => templates.value.find(item => item.id === selectedId.value))

async function load() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([term.loadTerms(), loadTemplates()])
    if (selectedId.value && term.hasValidTerm.value) await loadPreview()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '规则模板加载失败' } finally { loading.value = false }
}

async function loadTemplates() {
  templates.value = await http<Template[]>('/api/rule-templates')
  if (!selectedId.value) selectedId.value = templates.value[0]?.id ?? null
}

async function createTemplate() {
  if (saving.value || !form.value.code.trim() || !form.value.name.trim() || !term.hasValidTerm.value) return
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    const created = await http<{ id: number }>('/api/rule-templates', jsonRequest('POST', { sourceTermCode: term.selectedTermCode.value, ...form.value }))
    selectedId.value = created.id
    message.value = '规则模板已保存'
    await loadTemplates()
    await loadPreview()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '规则模板保存失败' } finally { saving.value = false }
}

async function loadPreview() {
  if (!selectedId.value || !term.hasValidTerm.value) { preview.value = null; return }
  preview.value = await http<Preview>(`/api/rule-templates/${selectedId.value}/preview?termCode=${encodeURIComponent(term.selectedTermCode.value)}`)
  confirmApply.value = false
}

async function applyTemplate() {
  if (!selectedId.value || !preview.value?.canApply || !confirmApply.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await http(`/api/rule-templates/${selectedId.value}/apply`, jsonRequest('POST', { termCode: term.selectedTermCode.value }))
    message.value = '规则模板已应用，当前学期仍需重新确认候选方案'
    await loadPreview()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '规则模板应用失败' } finally { saving.value = false }
}

watch(() => term.selectedTermCode.value, () => { if (selectedId.value) void loadPreview() })
watch(selectedId, () => { void loadPreview() })
onMounted(() => void load())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">RULE TEMPLATES / DELIVERY</p><h1>规则模板与交付方案</h1></div><div class="top-actions"><span class="sync-state">● 变更可预览</span><div class="avatar">规</div></div></header>
  <section class="template-page" data-testid="rule-templates-page">
    <div class="template-grid">
      <section class="panel canvas-card template-list"><div class="panel-heading"><div><span class="eyebrow">SAVED BASELINES</span><h2>学校规则模板</h2></div><button class="quiet-button" :disabled="loading" @click="load">刷新</button></div><div v-if="!templates.length && !loading" class="empty">还没有保存的规则模板</div><button v-for="item in templates" :key="item.id" class="template-row" :class="{ selected: item.id === selectedId }" @click="selectedId = item.id"><strong>{{ item.name }} · v{{ item.version }}</strong><small>{{ item.code }} · {{ item.maintainedBy || '未填写维护人' }}</small><span>{{ item.description || '没有模板说明' }}</span></button></section>
      <section class="panel canvas-card template-editor"><div class="panel-heading"><div><span class="eyebrow">NEW BASELINE</span><h2>保存当前学期规则</h2><small>模板只保存规则配置，不会绕过目标学期的数据健康检查。</small></div></div><form class="editor-form" @submit.prevent="createTemplate"><label><span>模板编码</span><input v-model="form.code" required maxlength="64" /></label><label><span>模板名称</span><input v-model="form.name" required maxlength="128" /></label><label><span>维护人</span><input v-model="form.maintainedBy" maxlength="128" placeholder="实施团队或负责人" /></label><label class="wide"><span>说明</span><textarea v-model="form.description" rows="2" maxlength="2000" /></label><label class="wide"><span>变更说明</span><textarea v-model="form.changeNote" rows="2" maxlength="2000" /></label><button class="primary-button" type="submit" :disabled="saving || !term.hasValidTerm.value">保存为新版本</button></form></section>
    </div>
    <section class="panel canvas-card preview-panel"><div class="panel-heading"><div><span class="eyebrow">IMPACT PREVIEW</span><h2>{{ selectedTemplate ? `${selectedTemplate.name} → ${term.selectedTermCode.value}` : '选择模板预览' }}</h2><small>应用前展示将新增、修改和失效的规则；目标学期未通过准备度检查时不可应用。</small></div><button class="quiet-button" :disabled="!selectedId || loading" @click="loadPreview">重新计算</button></div><div v-if="error" class="inline-message error-message">{{ error }}</div><div v-if="message" class="inline-message success-message">{{ message }}</div><div v-if="preview" class="preview-content"><div class="impact-summary"><div><strong>{{ preview.added?.length ?? 0 }}</strong><span>新增</span></div><div><strong>{{ preview.modified?.length ?? 0 }}</strong><span>修改</span></div><div><strong>{{ preview.retired?.length ?? 0 }}</strong><span>失效</span></div><div><strong>{{ preview.unmatchedScopes?.length ?? 0 }}</strong><span>未匹配</span></div></div><div v-if="preview.unmatchedScopes?.length" class="impact-warning"><strong>需要先处理资源映射</strong><span>{{ preview.unmatchedScopes.join('、') }}</span></div><div v-if="preview.readiness && !preview.readiness.ready" class="impact-warning"><strong>目标学期尚未准备好</strong><span>{{ preview.readiness.issues?.map(item => item.message).join('、') }}</span></div><div v-if="preview.canApply" class="apply-box"><label><input v-model="confirmApply" type="checkbox" /> 我已确认上述规则变更，并接受应用后重新检查候选方案</label><button class="primary-button" :disabled="!confirmApply || saving" @click="applyTemplate">应用到当前学期</button></div><div v-else class="blocked-note">完成未匹配资源和数据准备后，才可以应用此模板。</div></div><div v-else class="empty">选择一个模板后查看影响范围</div></section>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.template-page { display: grid; gap: 14px; }
.template-grid { display: grid; grid-template-columns: minmax(260px, .75fr) minmax(0, 1.25fr); gap: 14px; }
.template-list, .template-editor, .preview-panel { padding: 18px; }
.template-list h2, .template-editor h2, .preview-panel h2 { margin: 5px 0 0; color: #202A35; font-size: 18px; }
.panel-heading small { display: block; margin-top: 6px; color: #6a7b75; font-size: 11px; }
.quiet-button, .primary-button { border-radius: 6px; padding: 8px 12px; cursor: pointer; font: inherit; font-size: 12px; }
.quiet-button { border: 1px solid #D9DEE3; background: #fff; color: #566474; }
.primary-button { border: 1px solid #202A35; background: #202A35; color: #fff; }
.quiet-button:disabled, .primary-button:disabled { cursor: not-allowed; opacity: .55; }
.template-row { display: grid; gap: 4px; width: 100%; padding: 12px; border: 1px solid #EEF1F3; border-radius: 7px; background: #fff; color: #566474; text-align: left; cursor: pointer; }
.template-row + .template-row { margin-top: 8px; }
.template-row.selected { border-color: #79a98b; background: #f4faf6; }
.template-row strong { color: #202A35; font-size: 12px; }
.template-row small, .template-row span { color: #7a8b82; font-size: 10px; }
.editor-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 16px; }
.editor-form label { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.editor-form .wide { grid-column: 1 / -1; }
.editor-form input, .editor-form textarea { min-width: 0; border: 1px solid #D9DEE3; border-radius: 6px; padding: 8px 9px; color: #191c1d; background: #fff; font: inherit; font-size: 12px; }
.editor-form textarea { resize: vertical; }
.editor-form .primary-button { justify-self: start; }
.preview-content { margin-top: 18px; }
.impact-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.impact-summary div { display: grid; gap: 4px; padding: 12px; border: 1px solid #EEF1F3; border-radius: 7px; background: #f8fbf9; }
.impact-summary strong { color: #202A35; font-size: 22px; }
.impact-summary span { color: #566474; font-size: 10px; }
.impact-warning, .blocked-note { display: grid; gap: 5px; margin-top: 12px; padding: 10px 12px; border: 1px solid #f1d2aa; border-radius: 7px; background: #FBF4E7; color: #805c27; font-size: 11px; line-height: 1.45; }
.apply-box { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; padding-top: 14px; border-top: 1px solid #e8efeb; color: #566474; font-size: 11px; }
.apply-box label { display: flex; align-items: center; gap: 6px; }
.empty { padding: 28px 10px; color: #566474; font-size: 12px; text-align: center; }
.inline-message { margin-top: 12px; padding: 10px 12px; border-radius: 7px; font-size: 12px; }
.success-message { border: 1px solid #bbdec6; background: #effaf2; color: #28623d; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
@media (max-width: 760px) { .template-grid { grid-template-columns: 1fr; } .apply-box { align-items: stretch; flex-direction: column; } .impact-summary { grid-template-columns: repeat(2, 1fr); } }
</style>
