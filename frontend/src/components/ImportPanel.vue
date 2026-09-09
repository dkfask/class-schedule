<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { downloadBlob, http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

export interface ImportIssue {
  sheet: string
  row: number
  column: string
  code: string
  message: string
}

interface ImportSheetStat {
  sheet: string
  rows: number
  created: number
  updated: number
  deactivated: number
}

export interface ImportPreview {
  batchId: number
  status: string
  sha256?: string
  sheets?: string[]
  issues: ImportIssue[]
  templateType?: string
  templateVersion?: string
  schemaHash?: string
  sheetStats?: ImportSheetStat[]
}

interface ImportResult {
  batchId: number
  status: string
  importedRows?: number
  issueCount?: number
  message?: string
  sheetStats?: ImportSheetStat[]
}

const term = useTermStore()
const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFileName = ref('')
const preview = ref<ImportPreview | null>(null)
const confirmation = ref<ImportResult | null>(null)
const previewLoading = ref(false)
const confirmLoading = ref(false)
const templateDownloaded = ref(false)
const message = ref('')
const messageType = ref<'success' | 'error' | 'info'>('info')

const termLabel = computed(() => {
  const selected = term.terms.value.find(item => item.code === term.selectedTermCode.value)
  return selected ? `${selected.name} · ${selected.code}` : term.selectedTermCode.value
})
const canImport = computed(() => term.ready.value && term.hasValidTerm.value)
const canConfirm = computed(() => canImport.value && preview.value?.status === 'VALIDATED' && !previewLoading.value)
const currentImportStep = computed(() => {
  if (confirmation.value?.status === 'IMPORTED') return 6
  if (preview.value && preview.value.status !== 'VALIDATED') return 5
  if (preview.value?.status === 'VALIDATED') return 6
  if (selectedFileName.value || previewLoading.value) return 3
  return 2
})
const importSteps = computed(() => [
  { number: '01', label: '下载模板', optional: true, state: templateDownloaded.value ? 'done' : 'pending' },
  ...['选择文件', '结构检查', '数据预览', '修复问题', '确认导入'].map((label, index) => {
    const number = index + 2
    return { number: String(number).padStart(2, '0'), label, state: currentImportStep.value > number ? 'done' : currentImportStep.value === number ? 'active' : 'pending' }
  }),
])

const issueCategory = (code: string) => {
  if (['UNKNOWN_TEMPLATE', 'UNKNOWN_SHEET', 'DUPLICATE_SHEET', 'INVALID_SHEET_ORDER', 'MISSING_SHEET', 'INVALID_HEADER', 'EXTRA_COLUMN'].includes(code)) return '模板结构'
  if (code.includes('REFERENCE') || code.includes('CODE') || code.includes('DUPLICATE')) return '编码与引用'
  if (code.includes('PERIOD') || code.includes('TIME') || code.includes('DURATION') || code === 'TERM_MISMATCH') return '课时与时间'
  if (code.includes('FILE') || code.includes('WORKBOOK') || code.includes('CELL') || code.includes('ROW')) return '文件与内容'
  return '业务数据'
}
const issueGroups = computed(() => {
  const groups = new Map<string, number>()
  for (const issue of preview.value?.issues ?? []) groups.set(issueCategory(issue.code), (groups.get(issueCategory(issue.code)) ?? 0) + 1)
  return [...groups.entries()].map(([label, count]) => ({ label, count }))
})

function summarizeStats(stats?: ImportSheetStat[]) {
  return (stats ?? []).reduce((summary, stat) => ({
    rows: summary.rows + stat.rows,
    created: summary.created + stat.created,
    updated: summary.updated + stat.updated,
    deactivated: summary.deactivated + stat.deactivated,
  }), { rows: 0, created: 0, updated: 0, deactivated: 0 })
}
const previewTotals = computed(() => summarizeStats(preview.value?.sheetStats))
const confirmationTotals = computed(() => summarizeStats(confirmation.value?.sheetStats))

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
    const result = await http<ImportPreview>(`/api/imports/preview${term.selectedTermCode.value ? `?termCode=${encodeURIComponent(term.selectedTermCode.value)}` : ''}`, { method: 'POST', body })
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
    templateDownloaded.value = true
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
  releaseStep: currentImportStep,
  selectedFileName,
})
</script>

<template>
  <section class="import-panel canvas-card" data-testid="import-panel">
    <div class="import-panel-heading">
      <div>
        <p class="eyebrow">IMPORT / MASTER DATA</p>
        <h2>批量导入基础数据</h2>
        <p class="import-caption">必填：教师、班级、课程、教学需求。可选：教室、资源可用性、特征目录、特征绑定和活动组。</p>
        <p class="import-caption">可选 Sheet 可以省略或留空，系统会保留已有配置；仅填写的数据行会被导入。</p>
      </div>
      <span class="import-term">{{ term.ready.value ? (term.hasValidTerm.value ? termLabel : '暂无可用学期') : '正在加载学期…' }}</span>
    </div>

    <ol class="import-steps" data-testid="import-steps" aria-label="数据导入步骤">
      <li v-for="step in importSteps" :key="step.number" :class="`step-${step.state}`">
        <span class="step-number">{{ step.number }}</span>
        <span class="step-label">{{ step.label }}<small v-if="step.optional">可选</small></span>
        <span v-if="step.state === 'done'" class="step-check" aria-label="已完成">✓</span>
      </li>
    </ol>

    <!-- 上传区域 -->
    <div class="upload-section">
      <div class="dropzone-card" @click="openFilePicker">
        <div class="upload-icon">⇧</div>
        <p class="upload-title">点击选择文件 或 将 Excel 模板拖拽到此处</p>
        <p class="upload-subtitle">支持 .xlsx 格式的标准校务基础数据导入模板</p>
        <div class="import-actions">
          <el-button type="primary" :loading="previewLoading" :disabled="!canImport" @click.stop="openFilePicker">
            选择 Excel 文件
          </el-button>
          <el-button plain @click.stop="downloadTemplate">下载导入模板</el-button>
        </div>
      </div>
      <input ref="fileInput" class="hidden-file" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="handleFileChange" />
    </div>

    <div v-if="!term.ready.value || term.loading.value" class="import-state info-state">正在加载可用学期…</div>
    <div v-else-if="!canImport" class="import-state error-state">{{ term.error.value || '暂无可用学期，暂不能进行导入' }}</div>
    <div v-else-if="selectedFileName" class="selected-file"><span>当前文件</span><strong>{{ selectedFileName }}</strong></div>

    <div v-if="previewLoading" class="import-state info-state" data-testid="preview-loading">正在预检文件，请稍候…</div>
    <div v-if="message" class="import-state" :class="`${messageType}-state`" data-testid="import-message">{{ message }}</div>

    <div v-if="preview" class="preview-summary" data-testid="import-preview">
      <div class="preview-summary-heading">
        <div>
          <span class="eyebrow">PREVIEW RESULT</span>
          <h3>{{ preview.status === 'VALIDATED' ? '预检通过' : '预检发现问题' }}</h3>
        </div>
        <el-tag :type="preview.status === 'VALIDATED' ? 'success' : 'danger'" effect="plain" round>
          {{ preview.status }}
        </el-tag>
      </div>
      <p v-if="preview.sheets?.length" class="preview-meta">包含 Sheet：{{ preview.sheets.join('、') }}</p>
      <p class="preview-meta">批次 #{{ preview.batchId }} · {{ preview.issues.length ? `共 ${preview.issues.length} 个问题` : '未发现数据问题' }}</p>

      <div v-if="preview.sheetStats?.length" class="preview-impact" data-testid="import-impact">
        <div><span>本次数据行</span><strong>{{ previewTotals.rows }}</strong></div>
        <div><span>预计新增</span><strong>{{ previewTotals.created }}</strong></div>
        <div><span>预计更新</span><strong>{{ previewTotals.updated }}</strong></div>
        <div><span>预计停用</span><strong>{{ previewTotals.deactivated }}</strong></div>
      </div>

      <div v-if="preview.issues.length" class="import-issues full-issues" data-testid="import-issues">
        <div class="issue-heading"><strong>请修正以下阻塞问题后重新选择文件</strong><div class="issue-groups"><span v-for="group in issueGroups" :key="group.label">{{ group.label }} {{ group.count }}</span></div></div>
        <div v-for="(issue, index) in preview.issues" :key="`${issue.sheet}-${issue.row}-${issue.column}-${issue.code}-${index}`" class="issue-row">
          <span>{{ issue.sheet || '工作簿' }} / {{ issue.row || '—' }} / {{ issue.column || '—' }}</span>
          <strong>{{ issue.code }}</strong>
          <span><em class="issue-category">{{ issueCategory(issue.code) }} · 阻塞</em>{{ issue.message }}</span>
        </div>
      </div>

      <div v-if="preview.status === 'VALIDATED'" class="import-confirm">
        <span>数据预检通过，已生成临时批次，尚未写入正式业务数据</span>
        <el-button type="primary" :loading="confirmLoading" :disabled="!canConfirm" @click="confirmImport">
          确认导入
        </el-button>
      </div>
    </div>

    <div v-if="confirmation && confirmation.status === 'IMPORTED'" class="import-state success-state" data-testid="import-success">
      已完成批次 #{{ confirmation.batchId }} 的导入，共写入 {{ confirmation.importedRows ?? confirmationTotals.rows }} 行；新增 {{ confirmationTotals.created }}，更新 {{ confirmationTotals.updated }}，停用 {{ confirmationTotals.deactivated }}。请返回工作台检查当前学期排课条件后再开始求解。
      <el-button link type="primary" @click="router.push('/workspace')">返回排课工作台</el-button>
    </div>
  </section>
</template>

<style scoped>
.canvas-card {
  background: #ffffff;
  border: 1px solid rgba(23, 59, 54, 0.1);
  border-radius: 12px;
  box-shadow: 0 4px 16px -2px rgba(23, 59, 54, 0.03);
}
.import-panel { max-width: 920px; margin: 28px auto 0; overflow: hidden; }
.import-panel-heading { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; padding: 24px 28px 20px; border-bottom: 1px solid #edf2ef; }
.import-panel-heading h2 { margin: 0; font-size: 20px; color: #173b36; font-weight: 700; }
.import-caption { color: #6a7b74; font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
.import-caption + .import-caption { margin-top: 2px; }
.import-term { color: #1e7048; background: #e6f7ef; padding: 6px 12px; font-size: 11.5px; border-radius: 9999px; font-weight: 600; white-space: nowrap; }

.import-steps { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0; margin: 0; padding: 0 28px; list-style: none; border-bottom: 1px solid #edf2ef; }
.import-steps li { position: relative; display: flex; align-items: center; gap: 9px; min-height: 52px; color: #a0b1a8; font-size: 11px; }
.import-steps li:not(:last-child)::after { content: ''; position: absolute; top: 25px; left: 82px; right: 18px; height: 1px; background: #e4ece7; }
.step-number { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid #d3e0d9; border-radius: 50%; color: #91a69b; font-size: 9px; z-index: 1; background: #fff; }
.step-label { position: relative; z-index: 1; padding-right: 7px; background: #fff; }
.step-label small { display: block; color: #a0b1a8; font-size: 9px; font-weight: 400; line-height: 1.2; }
.step-active { color: #2c694e !important; font-weight: 650; }
.step-active .step-number { border-color: #66a881; color: #2c694e; background: #eef8f2; }
.step-done { color: #4a8064 !important; }
.step-done .step-number { border-color: #77b38f; background: #e8f5ed; color: #2d8559; }
.step-check { margin-left: auto; margin-right: 22px; color: #2d8559; font-size: 12px; }

.upload-section { padding: 24px 28px 20px; }
.dropzone-card {
  border: 2px dashed #cfdbd5;
  background: #fafcfb;
  border-radius: 12px;
  padding: 36px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  cursor: pointer;
  transition: all 0.25s ease;
}
.dropzone-card:hover {
  border-color: #2c694e;
  background: #f2f8f4;
  transform: translateY(-1px);
}
.upload-icon {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #e1f0e8;
  color: #173b36;
  font-size: 20px;
  font-weight: 700;
  display: grid;
  place-items: center;
  margin-bottom: 12px;
}
.upload-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #191c1d;
}
.upload-subtitle {
  margin: 4px 0 16px;
  font-size: 12px;
  color: #7b948a;
}
.import-actions { display: flex; gap: 12px; }

.selected-file { display: flex; align-items: baseline; gap: 12px; margin: 0 28px 14px; padding: 10px 14px; background: #f7faf8; border: 1px solid #e1ebe5; border-radius: 8px; color: #789087; font-size: 11.5px; }
.selected-file strong { color: #173b36; overflow-wrap: anywhere; }
.import-state { margin: 0 28px 16px; padding: 12px 14px; border-radius: 8px; font-size: 12px; line-height: 1.5; }
.info-state { background: #f0f7f2; border: 1px solid #d4eae0; color: #2c694e; }
.success-state { background: #edf8f1; border: 1px solid #c9ebd8; color: #1b5a45; }
.error-state { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; }

.preview-summary { margin: 0 28px 24px; border-top: 1px solid #edf2ef; padding-top: 20px; }
.preview-summary-heading { display: flex; justify-content: space-between; align-items: center; }
.preview-summary h3 { margin: 0; color: #173b36; font-size: 16px; font-weight: 700; }
.preview-meta { color: #7b948a; font-size: 11.5px; margin: 6px 0 0; }
.preview-impact { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 14px; }
.preview-impact div { display: grid; gap: 4px; padding: 10px; background: #f7faf8; border: 1px solid #e2ece6; border-radius: 7px; }
.preview-impact span { color: #789087; font-size: 10px; }
.preview-impact strong { color: #173b36; font-size: 16px; }
.full-issues { margin: 16px 0 12px; max-height: 360px; overflow: auto; border-radius: 8px; padding: 12px; }
.issue-heading { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.issue-groups { display: flex; flex-wrap: wrap; gap: 5px; justify-content: flex-end; }
.issue-groups span, .issue-category { display: inline-block; color: #9a5a17; background: #fff1db; border-radius: 4px; padding: 2px 5px; font-size: 10px; font-style: normal; white-space: nowrap; }
.issue-row { display: grid; grid-template-columns: minmax(110px, .8fr) 150px minmax(0, 1.6fr); gap: 10px; padding: 7px 0; border-top: 1px solid #f0dfcb; font-size: 11.5px; }
.import-confirm {
  margin-top: 16px;
  padding: 14px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #166534;
  font-size: 12.5px;
}
@media (max-width: 640px) {
  .import-panel-heading { display: block; }
  .import-term { display: inline-block; margin-top: 14px; }
  .import-steps { padding: 0 16px; }
  .import-steps li { gap: 6px; }
  .import-steps li:not(:last-child)::after { left: 69px; right: 8px; }
  .step-label { font-size: 10px; }
  .step-check { margin-right: 8px; }
  .issue-row { grid-template-columns: 1fr; gap: 3px; }
  .preview-impact { grid-template-columns: repeat(2, 1fr); }
  .issue-heading { display: grid; }
  .issue-groups { justify-content: flex-start; }
}
</style>
