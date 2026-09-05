<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, jsonRequest } from '../api/http'

type Resource = 'teachers' | 'student-groups' | 'subjects' | 'rooms'
interface Item { id: number; code: string; name: string; active: boolean; attributes: Record<string, unknown> }
const resources: Array<{ key: Resource; label: string }> = [
  { key: 'teachers', label: '教师' },
  { key: 'student-groups', label: '班级' },
  { key: 'subjects', label: '课程' },
  { key: 'rooms', label: '教室' }
]
const router = useRouter()
const activeResource = ref<Resource>('teachers')
const items = ref<Item[]>([])
const loading = ref(false)
const errorMessage = ref('')
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const page = ref(0)
const size = ref(20)
const total = ref(0)
const form = ref({ code: '', name: '', capacity: 50, studentCount: 0, roomType: '普通教室' })
const resourceLabel = computed(() => resources.find(item => item.key === activeResource.value)?.label ?? '')

async function loadItems() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await http<{ items?: Item[]; total?: number; page?: number; size?: number }>(`/api/master-data/${activeResource.value}?active=false&page=${page.value}&size=${size.value}`)
    items.value = data.items ?? []
    total.value = Number(data.total ?? 0)
    page.value = Number(data.page ?? page.value)
    size.value = Number(data.size ?? size.value)
  } catch (error) {
    items.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '基础数据加载失败'
  } finally {
    loading.value = false
  }
}

function selectResource(resource: Resource) {
  activeResource.value = resource
  page.value = 0
  void loadItems()
}

function changePage(nextPage: number) {
  page.value = nextPage - 1
  void loadItems()
}

function changePageSize(nextSize: number) {
  size.value = nextSize
  page.value = 0
  void loadItems()
}

function openCreate() { editingId.value = null; form.value = { code: '', name: '', capacity: 50, studentCount: 0, roomType: '普通教室' }; dialogOpen.value = true }
function openEdit(item: Item) { editingId.value = item.id; form.value = { code: item.code, name: item.name, capacity: Number(item.attributes.capacity ?? 50), studentCount: Number(item.attributes.studentCount ?? 0), roomType: String(item.attributes.roomType ?? '普通教室') }; dialogOpen.value = true }
async function save() {
  const method = editingId.value ? 'PATCH' : 'POST'
  const url = `/api/master-data/${activeResource.value}${editingId.value ? `/${editingId.value}` : ''}`
  try {
    await http(url, jsonRequest(method, form.value))
    dialogOpen.value = false
    ElMessage.success('已保存')
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}
async function deactivate(item: Item) {
  await ElMessageBox.confirm(`停用${resourceLabel.value}“${item.name}”？`, '确认操作')
  try {
    await http<void>(`/api/master-data/${activeResource.value}/${item.id}`, { method: 'DELETE' })
    ElMessage.success('已停用')
    await loadItems()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '停用失败')
  }
}
async function activate(item: Item) {
  try { await http<void>(`/api/master-data/${activeResource.value}/${item.id}/activate`, { method: 'POST' }); ElMessage.success('已启用'); await loadItems() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '启用失败') }
}
onMounted(() => void loadItems())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">MASTER DATA / ADMIN</p><h1>基础数据</h1></div><div class="top-actions"><span class="sync-state">● 数据已同步</span><el-button plain @click="router.push('/import')">按模板导入</el-button><el-button type="primary" @click="openCreate">新增{{ resourceLabel }}</el-button><div class="avatar">教</div></div></header>
<section class="data-page panel"><div class="data-tabs"><button v-for="resource in resources" :key="resource.key" :class="{ selected: activeResource === resource.key }" @click="selectResource(resource.key)">{{ resource.label }}</button></div><div class="data-toolbar"><div><span class="eyebrow">{{ activeResource.toUpperCase() }}</span><h2>{{ resourceLabel }}列表</h2></div><el-button plain @click="loadItems">刷新</el-button></div><div v-if="errorMessage" class="inline-message error-message">{{ errorMessage }}</div><el-table v-loading="loading" :data="items" stripe><el-table-column prop="code" label="编码" width="180"/><el-table-column prop="name" label="名称"/><el-table-column v-if="activeResource === 'student-groups'" label="人数" width="120"><template #default="scope">{{ scope.row.attributes.studentCount }}</template></el-table-column><el-table-column v-if="activeResource === 'rooms'" label="容量" width="120"><template #default="scope">{{ scope.row.attributes.capacity }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="180"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button><el-button v-if="scope.row.active" link type="danger" @click="deactivate(scope.row)">停用</el-button><el-button v-else link type="primary" @click="activate(scope.row)">启用</el-button></template></el-table-column></el-table><el-empty v-if="!loading && !errorMessage && items.length === 0" description="暂无数据"/><el-pagination v-if="total > 0" class="data-pagination" background layout="total, sizes, prev, pager, next" :current-page="page + 1" :page-size="size" :page-sizes="[10, 20, 50]" :total="total" @current-change="changePage" @size-change="changePageSize"/></section>
  <el-dialog v-model="dialogOpen" :title="`${editingId ? '编辑' : '新增'}${resourceLabel}`" width="440px"><el-form label-width="82px"><el-form-item label="编码"><el-input v-model="form.code" maxlength="64" /></el-form-item><el-form-item label="名称"><el-input v-model="form.name" maxlength="128" /></el-form-item><el-form-item v-if="activeResource === 'student-groups'" label="人数"><el-input-number v-model="form.studentCount" :min="0" /></el-form-item><el-form-item v-if="activeResource === 'rooms'" label="容量"><el-input-number v-model="form.capacity" :min="1" /></el-form-item><el-form-item v-if="activeResource === 'rooms'" label="类型"><el-input v-model="form.roomType" /></el-form-item></el-form><template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
</template>
