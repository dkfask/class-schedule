<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http, jsonRequest } from '../api/http'

type Resource = 'teachers' | 'student-groups' | 'subjects' | 'rooms'
interface Item { id: number; code: string; name: string; active: boolean; attributes: Record<string, unknown> }
interface RoomOption { code: string; name: string; capacity: number; roomType?: string }
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
const roomOptions = ref<RoomOption[]>([])
const form = ref({ code: '', name: '', capacity: 50, studentCount: 0, roomType: '普通教室', homeRoomCode: '' })
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

async function loadRoomOptions() {
  try {
    const data = await http<{ items?: Item[] }>('/api/master-data/rooms?active=true&page=0&size=100')
    roomOptions.value = (data.items ?? []).map(item => ({
      code: item.code,
      name: item.name,
      capacity: Number(item.attributes.capacity ?? 0),
      roomType: String(item.attributes.roomType ?? ''),
    }))
  } catch {
    roomOptions.value = []
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

function openCreate() { editingId.value = null; form.value = { code: '', name: '', capacity: 50, studentCount: 0, roomType: '普通教室', homeRoomCode: '' }; dialogOpen.value = true }
function openEdit(item: Item) { editingId.value = item.id; form.value = { code: item.code, name: item.name, capacity: Number(item.attributes.capacity ?? 50), studentCount: Number(item.attributes.studentCount ?? 0), roomType: String(item.attributes.roomType ?? '普通教室'), homeRoomCode: String(item.attributes.homeRoomCode ?? '') }; dialogOpen.value = true }
async function save() {
  const method = editingId.value ? 'PATCH' : 'POST'
  const url = `/api/master-data/${activeResource.value}${editingId.value ? `/${editingId.value}` : ''}`
  try {
    await http(url, jsonRequest(method, form.value))
    dialogOpen.value = false
    ElMessage.success('已保存')
    await loadItems()
    await loadRoomOptions()
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
onMounted(async () => { await loadRoomOptions(); await loadItems() })
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">MASTER DATA / ADMIN</p>
      <h1>基础数据管理</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● 数据已同步</span>
      <el-button plain @click="router.push('/import')">按模板导入</el-button>
      <el-button type="primary" @click="openCreate">+ 新增{{ resourceLabel }}</el-button>
      <div class="avatar">教</div>
    </div>
  </header>

  <section class="data-page panel canvas-card">
    <div class="data-tabs">
      <button
        v-for="resource in resources"
        :key="resource.key"
        :class="{ selected: activeResource === resource.key }"
        @click="selectResource(resource.key)"
      >
        {{ resource.label }}
      </button>
    </div>

    <div class="data-toolbar">
      <div>
        <span class="eyebrow">{{ activeResource.toUpperCase() }}</span>
        <h2>{{ resourceLabel }}列表</h2>
      </div>
      <el-button plain size="small" @click="loadItems">刷新</el-button>
    </div>

    <div v-if="errorMessage" class="inline-message error-message">{{ errorMessage }}</div>

    <el-table
      v-loading="loading"
      :data="items"
      stripe
      class="styled-table"
      header-row-class-name="styled-table-header"
    >
      <el-table-column prop="code" label="编码" width="180">
        <template #default="scope">
          <span class="code-badge">{{ scope.row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称">
        <template #default="scope">
          <strong>{{ scope.row.name }}</strong>
        </template>
      </el-table-column>
      <el-table-column v-if="activeResource === 'student-groups'" label="人数" width="130">
        <template #default="scope">{{ scope.row.attributes.studentCount ?? 0 }} 人</template>
      </el-table-column>
      <el-table-column v-if="activeResource === 'student-groups'" label="绑定教室" width="150">
        <template #default="scope">{{ scope.row.attributes.homeRoomCode || '未绑定' }}</template>
      </el-table-column>
      <el-table-column v-if="activeResource === 'rooms'" label="容量" width="130">
        <template #default="scope">{{ scope.row.attributes.capacity ?? 0 }} 座</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="scope">
          <el-tag :type="scope.row.active ? 'success' : 'info'" effect="plain" round size="small">
            {{ scope.row.active ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="right">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="openEdit(scope.row)">编辑</el-button>
          <el-button
            v-if="scope.row.active"
            link
            type="danger"
            size="small"
            @click="deactivate(scope.row)"
          >
            停用
          </el-button>
          <el-button
            v-else
            link
            type="primary"
            size="small"
            @click="activate(scope.row)"
          >
            启用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !errorMessage && items.length === 0" description="暂无数据" />

    <el-pagination
      v-if="total > 0"
      class="data-pagination"
      background
      layout="total, sizes, prev, pager, next"
      :current-page="page + 1"
      :page-size="size"
      :page-sizes="[10, 20, 50]"
      :total="total"
      @current-change="changePage"
      @size-change="changePageSize"
    />
  </section>

  <el-dialog
    v-model="dialogOpen"
    :title="`${editingId ? '编辑' : '新增'}${resourceLabel}`"
    width="440px"
    custom-class="styled-dialog"
  >
    <el-form label-width="82px" label-position="left">
      <el-form-item label="编码" required>
        <el-input v-model="form.code" maxlength="64" placeholder="例如：T001, G7-1" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="128" placeholder="请输入名称" />
      </el-form-item>
      <el-form-item v-if="activeResource === 'student-groups'" label="人数">
        <el-input-number v-model="form.studentCount" :min="0" class="full-width" />
      </el-form-item>
      <el-form-item v-if="activeResource === 'student-groups'" label="绑定教室">
        <el-select v-model="form.homeRoomCode" clearable placeholder="行政班默认教室" class="full-width">
          <el-option v-for="item in roomOptions" :key="item.code" :label="`${item.name} · ${item.code}`" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="activeResource === 'rooms'" label="容量">
        <el-input-number v-model="form.capacity" :min="1" class="full-width" />
      </el-form-item>
      <el-form-item v-if="activeResource === 'rooms'" label="类型">
        <el-input v-model="form.roomType" placeholder="普通教室 / 实验室 / 机房" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogOpen = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.canvas-card {
  background: #ffffff;
  border: 1px solid rgba(23, 59, 54, 0.1);
  border-radius: 12px;
  box-shadow: 0 4px 16px -2px rgba(23, 59, 54, 0.03);
}
.code-badge {
  font-family: monospace;
  font-size: 12px;
  color: #B85C45;
  background: #f0f7f3;
  padding: 2px 6px;
  border-radius: 4px;
}
.styled-table {
  margin: 0;
  width: 100%;
}
.full-width {
  width: 100%;
}
</style>
