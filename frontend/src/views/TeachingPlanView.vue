<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface Requirement {
  id: number
  code: string
  termCode: string
  studentGroupCode: string
  subjectCode: string
  teacherCode: string
  weeklyPeriods: number
  durationPeriods: number
  studentCount: number
  requiredFeatures: string
  pinnedPeriodCode: string | null
  active: boolean
}
interface Option { code: string; name: string }
interface Overview {
  terms?: Array<{ code: string; name: string }>
  periods?: Array<{ code: string; label: string }>
  teachers?: Option[]
  studentGroups?: Option[]
  subjects?: Option[]
}

const term = useTermStore()
const items = ref<Requirement[]>([])
const visibleItems = computed(() => items.value.slice(page.value * size.value, page.value * size.value + size.value))
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const page = ref(0)
const size = ref(20)
const total = ref(0)
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const options = ref<Overview>({})
const form = ref({
  code: '',
  studentGroupCode: '',
  subjectCode: '',
  teacherCode: '',
  weeklyPeriods: 1,
  durationPeriods: 1,
  studentCount: 0,
  requiredFeatures: '',
  pinnedPeriodCode: '',
})

const selectedTerm = computed(() => term.terms.value.find(item => item.code === term.selectedTermCode.value))
const termLabel = computed(() => selectedTerm.value ? `${selectedTerm.value.name} · ${selectedTerm.value.code}` : term.selectedTermCode.value)

async function loadOptions() {
  try {
    options.value = await http<Overview>('/api/master-data/overview')
  } catch {
    options.value = {}
  }
}

async function loadItems() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await http<Requirement[] | { items?: Requirement[]; total?: number }>(`/api/master-data/teaching-requirements?termCode=${encodeURIComponent(term.selectedTermCode.value)}&active=true`)
    items.value = Array.isArray(data) ? data : data.items ?? []
    total.value = items.value.length
  } catch (error) {
    items.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '教学需求加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = {
    code: '', studentGroupCode: options.value.studentGroups?.[0]?.code ?? '', subjectCode: options.value.subjects?.[0]?.code ?? '',
    teacherCode: options.value.teachers?.[0]?.code ?? '', weeklyPeriods: 1, durationPeriods: 1, studentCount: 0, requiredFeatures: '', pinnedPeriodCode: '',
  }
  dialogOpen.value = true
}

function openEdit(item: Requirement) {
  editingId.value = item.id
  form.value = {
    code: item.code, studentGroupCode: item.studentGroupCode, subjectCode: item.subjectCode, teacherCode: item.teacherCode,
    weeklyPeriods: item.weeklyPeriods, durationPeriods: item.durationPeriods, studentCount: item.studentCount,
    requiredFeatures: item.requiredFeatures, pinnedPeriodCode: item.pinnedPeriodCode ?? '',
  }
  dialogOpen.value = true
}

async function save() {
  saving.value = true
  try {
    const method = editingId.value ? 'PATCH' : 'POST'
    const url = `/api/master-data/teaching-requirements${editingId.value ? `/${editingId.value}` : ''}`
    await http(url, jsonRequest(method, { ...form.value, termCode: term.selectedTermCode.value }))
    dialogOpen.value = false
    ElMessage.success('已保存教学需求')
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function deactivate(item: Requirement) {
  await ElMessageBox.confirm(`停用教学需求“${item.code}”？`, '确认操作')
  try {
    await http<void>(`/api/master-data/teaching-requirements/${item.id}`, { method: 'DELETE' })
    ElMessage.success('已停用')
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '停用失败')
  }
}

function changePage(nextPage: number) { page.value = nextPage - 1; void loadItems() }
function changePageSize(nextSize: number) { size.value = nextSize; page.value = 0; void loadItems() }

watch(() => term.selectedTermCode.value, () => { page.value = 0; void loadItems() })
onMounted(() => { void loadOptions(); void loadItems() })
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">TEACHING PLAN / REQUIREMENTS</p><h1>教学计划</h1></div><div class="top-actions"><span class="sync-state">● {{ termLabel }}</span><el-button type="primary" :disabled="!options.studentGroups?.length" @click="openCreate">新增教学需求</el-button><div class="avatar">教</div></div></header>
  <section class="data-page panel">
    <div class="data-toolbar"><div><span class="eyebrow">TEACHING REQUIREMENTS</span><h2>教学需求列表</h2></div><el-button plain :loading="loading" @click="loadItems">刷新</el-button></div>
    <div v-if="errorMessage" class="inline-message error-message">{{ errorMessage }}</div>
    <div class="teaching-plan-table-wrap">
      <el-table v-loading="loading" :data="visibleItems" stripe>
        <el-table-column prop="code" label="编码" width="150" />
        <el-table-column prop="studentGroupCode" label="班级" width="110" />
        <el-table-column prop="subjectCode" label="课程" width="110" />
        <el-table-column prop="teacherCode" label="教师" width="110" />
        <el-table-column prop="weeklyPeriods" label="周课时" width="90" />
        <el-table-column prop="durationPeriods" label="时长" width="80" />
        <el-table-column prop="studentCount" label="人数" width="90" />
        <el-table-column label="固定节次" width="120"><template #default="scope">{{ scope.row.pinnedPeriodCode ?? '—' }}</template></el-table-column>
        <el-table-column prop="requiredFeatures" label="特征" min-width="120" />
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="160" fixed="right"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button v-if="scope.row.active" link type="danger" @click="deactivate(scope.row)">停用</el-button></template></el-table-column>
      </el-table>
    </div>
    <el-empty v-if="!loading && !errorMessage && items.length === 0" description="暂无教学需求" />
    <el-pagination v-if="total > 0" class="data-pagination" background layout="total, sizes, prev, pager, next" :current-page="page + 1" :page-size="size" :page-sizes="[10, 20, 50]" :total="total" @current-change="changePage" @size-change="changePageSize" />
  </section>
  <el-dialog v-model="dialogOpen" :title="`${editingId ? '编辑' : '新增'}教学需求`" width="460px">
    <el-form label-width="96px">
      <el-form-item label="编码"><el-input v-model="form.code" maxlength="64" /></el-form-item>
      <el-form-item label="班级"><el-select v-model="form.studentGroupCode"><el-option v-for="item in options.studentGroups ?? []" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" /></el-select></el-form-item>
      <el-form-item label="课程"><el-select v-model="form.subjectCode"><el-option v-for="item in options.subjects ?? []" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" /></el-select></el-form-item>
      <el-form-item label="教师"><el-select v-model="form.teacherCode"><el-option v-for="item in options.teachers ?? []" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" /></el-select></el-form-item>
      <el-form-item label="周课时"><el-input-number v-model="form.weeklyPeriods" :min="1" /></el-form-item>
      <el-form-item label="时长"><el-input-number v-model="form.durationPeriods" :min="1" /></el-form-item>
      <el-form-item label="人数"><el-input-number v-model="form.studentCount" :min="0" /></el-form-item>
      <el-form-item label="特征"><el-input v-model="form.requiredFeatures" placeholder="逗号分隔，如 LAB" /></el-form-item>
      <el-form-item label="固定节次"><el-select v-model="form.pinnedPeriodCode" clearable><el-option v-for="item in options.periods ?? []" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>
