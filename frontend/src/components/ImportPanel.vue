<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { downloadBlob, http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

export interface ImportIssue {
  sheet: string
  row: number
  column: string
  code: string
  message: string
}

export interface ImportPreview {
  batchId: number
  status: string
  sha256?: string
  sheets?: string[]
  issues: ImportIssue[]
}

interface ImportResult {
  batchId: number
  status: string
  importedRows?: number
  issueCount?: number
  message?: string
}

const term = useTermStore()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFileName = ref('')
const preview = ref<ImportPreview | null>(null)
const confirmation = ref<ImportResult | null>(null)
const previewLoading = ref(false)
const confirmLoading = ref(false)
const message = ref('')
const messageType = ref<'success' | 'error' | 'info'>('info')

const termLabel = computed(() => {
  const selected = term.terms.value.find(item => item.code === term.selectedTermCode.value)
  return selected ? `${selected.name} · ${selected.code}` : term.selectedTermCode.value
})
const canImport = computed(() => term.ready.value && term.hasValidTerm.value)
const canConfirm = computed(() => canImport.value && preview.value?.status === 'VALIDATED' && !previewLoading.value)

function setMessage(text: string, type: 'success' | 'error' | 'info' = 'info') {
  message.value = text
  messageType.value = type
}

function resetFileInput() {
  if (fileInput.value) fileInput.value.value = ''
}

function openFilePicker() {
  resetFileInput()
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  resetFileInput()
  if (file) await previewFile(file)
}

async function previewFile(file: File) {
  if (!canImport.value) {
    setMessage(term.error.value || '暂无可用学期，无法导入数据', 'error')
    return
  }
  selectedFileName.value = file.name
  preview.value = null
  confirmation.value = null
  setMessage('正在预检文件…', 'info')
  previewLoading.value = true
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await http<ImportPreview>('/api/imports/preview', { method: 'POST', body })
    preview.value = { ...result, issues: result.issues ?? [] }
    if (result.status === 'VALIDATED') {
      setMessage('数据预检通过，可以确认导入', 'success')
    } else {
      setMessage(`发现 ${result.issues?.length ?? 0} 个数据问题，请修正后重新选择文件`, 'error')
    }
  } catch (error) {
    preview.value = null
    setMessage(error instanceof Error ? error.message : '导入预检失败，请重试', 'error')
  } finally {
    previewLoading.value = false
  }
}

async function downloadTemplate() {
  try {
    const blob = await downloadBlob('/api/imports/templates/master-data.xlsx')
    const href = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = href
    anchor.download = 'master-data-v1.xlsx'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(href)
    setMessage('模板下载已开始', 'success')
  } catch (error) {
    setMessage(error instanceof Error ? error.message : '模板下载失败，请重试', 'error')
  }
}

async function confirmImport() {
  if (!canConfirm.value || !preview.value) return
  confirmLoading.value = true
  confirmation.value = null
  try {
    const result = await http<ImportResult>('/api/imports/confirm', {
      ...jsonRequest('POST', { batchId: preview.value.batchId }),
    })
    confirmation.value = result
    if (result.status === 'IMPORTED') {
      preview.value = null
      setMessage(`导入成功，共写入 ${result.importedRows ?? 0} 行`, 'success')
    } else {
      setMessage(result.message ?? '导入未提交，请检查批次状态', 'error')
    }
  } catch (error) {
    setMessage(error instanceof Error ? error.message : '确认导入失败，请重试', 'error')
  } finally {
    confirmLoading.value = false
  }
}

onMounted(() => {
  void term.loadTerms()
})

defineExpose({
  canImport,
  confirmation,
  confirmImport,
  confirmLoading,
  downloadTemplate,
  handleFileChange,
  message,
  openFilePicker,
  preview,
  previewFile,
  previewLoading,
  selectedFileName,
})
</script>

<template>
  <section class="import-panel panel" data-testid="import-panel">
    <div class="import-panel-heading">
      <div>
        <p class="eyebrow">IMPORT / MASTER DATA</p>
        <h2>批量导入基础数据</h2>
        <p class="import-caption">使用统一模板导入教师、班级、课程、教室和教学需求</p>
      </div>
      <span class="import-term">{{ term.ready ? (term.hasValidTerm ? termLabel : '暂无可用学期') : '正在加载学期…' }}</span>
    </div>

    <div class="import-actions">
      <el-button plain :loading="previewLoading" :disabled="!canImport" @click="openFilePicker">选择 Excel 文件</el-button>
      <el-button plain @click="downloadTemplate">下载导入模板</el-button>
      <input ref="fileInput" class="hidden-file" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="handleFileChange" />
    </div>

    <div v-if="!term.ready || term.loading" class="import-state info-state">正在加载可用学期…</div>
    <div v-else-if="!canImport" class="import-state error-state">{{ term.error || '暂无可用学期，暂不能进行导入' }}</div>
    <div v-else-if="selectedFileName" class="selected-file"><span>当前文件</span><strong>{{ selectedFileName }}</strong></div>

    <div v-if="previewLoading" class="import-state info-state" data-testid="preview-loading">正在预检文件，请稍候…</div>
    <div v-if="message" class="import-state" :class="`${messageType}-state`" data-testid="import-message">{{ message }}</div>

    <div v-if="preview" class="preview-summary" data-testid="import-preview">
      <div class="preview-summary-heading"><div><span class="eyebrow">PREVIEW RESULT</span><h3>{{ preview.status === 'VALIDATED' ? '预检通过' : '预检发现问题' }}</h3></div><el-tag :type="preview.status === 'VALIDATED' ? 'success' : 'danger'">{{ preview.status }}</el-tag></div>
      <p v-if="preview.sheets?.length" class="preview-meta">包含 Sheet：{{ preview.sheets.join('、') }}</p>
      <p class="preview-meta">批次 #{{ preview.batchId }} · {{ preview.issues.length ? `共 ${preview.issues.length} 个问题` : '未发现数据问题' }}</p>
      <div v-if="preview.issues.length" class="import-issues full-issues" data-testid="import-issues">
        <strong>请修正以下全部问题后重新选择文件</strong>
        <div v-for="(issue, index) in preview.issues" :key="`${issue.sheet}-${issue.row}-${issue.column}-${issue.code}-${index}`" class="issue-row">
          <span>{{ issue.sheet || '工作簿' }} / {{ issue.row || '—' }} / {{ issue.column || '—' }}</span>
          <strong>{{ issue.code }}</strong>
          <span>{{ issue.message }}</span>
        </div>
      </div>
      <div v-if="preview.status === 'VALIDATED'" class="import-confirm">
        <span>预检通过，批次尚未写入业务数据</span>
        <el-button type="primary" plain :loading="confirmLoading" :disabled="!canConfirm" @click="confirmImport">确认导入</el-button>
      </div>
    </div>

    <div v-if="confirmation && confirmation.status === 'IMPORTED'" class="import-state success-state" data-testid="import-success">已完成批次 #{{ confirmation.batchId }} 的导入，共写入 {{ confirmation.importedRows ?? 0 }} 行。</div>
  </section>
</template>

<style scoped>
.import-panel { max-width: 920px; margin: 28px auto 0; }
.import-panel-heading { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; padding: 24px 24px 20px; border-bottom: 1px solid #e5ece8; }
.import-panel-heading h2 { margin: 0; font-size: 21px; color: #213b32; }
.import-caption { color: #789087; font-size: 12px; line-height: 1.6; margin: 8px 0 0; }
.import-term { color: #4e7565; background: #edf8f1; padding: 7px 10px; font-size: 11px; white-space: nowrap; }
.import-actions { display: flex; flex-wrap: wrap; gap: 10px; padding: 20px 24px 14px; }
.selected-file { display: flex; align-items: baseline; gap: 12px; margin: 0 24px 14px; padding: 11px 13px; background: #f7faf8; border: 1px solid #e1ebe5; color: #789087; font-size: 11px; }
.selected-file strong { color: #315d4b; overflow-wrap: anywhere; }
.import-state { margin: 0 24px 16px; padding: 11px 13px; font-size: 11px; line-height: 1.5; }
.info-state { background: #f0f7f2; color: #3a7656; }
.success-state { background: #edf8f1; color: #286d4e; }
.error-state { background: #fff1f0; color: #a33b35; }
.preview-summary { margin: 0 24px 24px; border-top: 1px solid #e5ece8; padding-top: 20px; }
.preview-summary-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.preview-summary h3 { margin: 0; color: #315d4b; font-size: 16px; }
.preview-meta { color: #789087; font-size: 11px; margin: 8px 0 0; }
.full-issues { margin: 16px 0 12px; max-height: 360px; overflow: auto; }
.issue-row { display: grid; grid-template-columns: minmax(110px, .8fr) 150px minmax(0, 1.6fr); gap: 10px; padding: 7px 0; border-top: 1px solid #f0dfcb; }
.issue-row strong { font-size: 10px; }
.import-confirm { margin: 0; }
@media (max-width: 640px) {
  .import-panel-heading { display: block; }
  .import-term { display: inline-block; margin-top: 14px; }
  .issue-row { grid-template-columns: 1fr; gap: 3px; }
}
</style>
