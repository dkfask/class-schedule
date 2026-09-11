<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http } from '../api/http'
import { resolveScore } from '../utils/score'
import { useTermStore } from '../stores/term'
import { getPeriods, getResourceOptions, getSlotItems, getWeekdays, type WorkspaceOccurrence, type WorkspaceOptions, type WorkspaceViewType } from '../utils/workspace'

interface Version { id: number; status: string; revision?: number; score?: string; hardScore?: number | null; mediumScore?: number | null; softScore?: number | null; createdAt?: string }
interface PublishedOverview extends WorkspaceOptions { periods?: WorkspaceOptions['timeslots'] }
const term = useTermStore()
const versions = ref<Version[]>([])
const selected = ref<number | null>(null)
const loading = ref(false)
const message = ref('')
const scheduleLoading = ref(false)
const scheduleError = ref('')
const viewType = ref<WorkspaceViewType>('CLASS')
const resourceCode = ref('')
const searchQuery = ref('')
const options = ref<WorkspaceOptions>({ timeslots: [], rooms: [], studentGroups: [], teachers: [] })
const assignments = ref<WorkspaceOccurrence[]>([])
const activeViewLabel = computed(() => ({ CLASS: '班级', TEACHER: '教师', ROOM: '教室' })[viewType.value])
const resourceOptions = computed(() => getResourceOptions(viewType.value, options.value))
const visibleResourceOptions = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return resourceOptions.value
  return resourceOptions.value.filter(item => `${item.name} ${item.code}`.toLowerCase().includes(query))
})
const weekdays = computed(() => getWeekdays(options.value.timeslots))
const periods = computed(() => getPeriods(options.value.timeslots))
const gridStyle = computed(() => ({ gridTemplateColumns: `58px repeat(${Math.max(weekdays.value.length, 1)}, minmax(112px, 1fr))` }))
const selectedVersion = computed(() => versions.value.find(version => version.id === selected.value))
const visibleAssignments = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return assignments.value
  return assignments.value.filter(item => [item.subjectCode, item.subjectName, item.teacherCode, item.teacherName, item.studentGroupCode, item.studentGroupName, item.roomCode, item.roomName, item.timeslotCode, item.timeslotLabel].some(value => value?.toLowerCase().includes(query)))
})

async function load() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    versions.value = []
    selected.value = null
    assignments.value = []
    options.value = { timeslots: [], rooms: [], studentGroups: [], teachers: [] }
    return
  }
  loading.value = true
  message.value = ''
  try {
    const data = await http<{ items?: Version[] }>(`/api/schedule-versions?termCode=${encodeURIComponent(term.selectedTermCode.value)}&status=PUBLISHED&page=0&size=50`)
    versions.value = data.items ?? []
    selected.value = versions.value[0]?.id ?? null
    await loadPublishedSchedule()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '已发布课表加载失败'
  } finally { loading.value = false }
}

async function loadPublishedSchedule() {
  if (!selected.value || !term.hasValidTerm.value) {
    assignments.value = []
    return
  }
  scheduleLoading.value = true
  scheduleError.value = ''
  try {
    const overview = await http<PublishedOverview>(`/api/master-data/overview?termCode=${encodeURIComponent(term.selectedTermCode.value)}`)
    options.value = {
      timeslots: overview.periods ?? overview.timeslots ?? [],
      rooms: overview.rooms ?? [],
      studentGroups: overview.studentGroups ?? [],
      teachers: overview.teachers ?? [],
    }
    if (!resourceOptions.value.some(item => item.code === resourceCode.value)) {
      resourceCode.value = resourceOptions.value[0]?.code ?? ''
    }
    const query = resourceCode.value ? `&resourceCode=${encodeURIComponent(resourceCode.value)}` : ''
    const result = await http<{ assignments?: WorkspaceOccurrence[] }>(`/api/schedule-versions/${selected.value}/filtered?view=${viewType.value}${query}`)
    assignments.value = result.assignments ?? []
  } catch (error) {
    assignments.value = []
    scheduleError.value = error instanceof Error ? error.message : '已发布课表查询失败'
  } finally {
    scheduleLoading.value = false
  }
}

async function selectVersion(id: number) {
  selected.value = id
  await loadPublishedSchedule()
}

async function selectView(type: WorkspaceViewType) {
  viewType.value = type
  resourceCode.value = ''
  await loadPublishedSchedule()
}

function download(format: 'xlsx' | 'pdf') {
  if (!selected.value) return
  const resource = resourceCode.value ? `&resourceCode=${encodeURIComponent(resourceCode.value)}` : ''
  window.open(`/api/schedule-versions/${selected.value}/exports/${format}?view=${viewType.value}${resource}`, '_blank')
}

function downloadValidation(format: 'xlsx' | 'pdf') {
  if (!selected.value) return
  window.open(`/api/schedule-versions/${selected.value}/validation/export.${format}`, '_blank')
}

function print() {
  if (!selected.value) return
  const resource = resourceCode.value ? `&resourceCode=${encodeURIComponent(resourceCode.value)}` : ''
  window.open(`/api/schedule-versions/${selected.value}/print?view=${viewType.value}${resource}`, '_blank')
}

function scoreParts(version: Version) {
  return resolveScore(version.score, { hard: version.hardScore, medium: version.mediumScore, soft: version.softScore })
}

watch(() => term.selectedTermCode.value, () => void load())
onMounted(() => void load())
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">PUBLISHED / READ ONLY</p>
      <h1>全校已发布课表</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● 正式只读查看</span>
      <div class="avatar">教</div>
    </div>
  </header>

  <section class="data-page panel published-page canvas-card">
    <div class="data-toolbar">
      <div>
        <span class="eyebrow">PUBLISHED SCHEDULES</span>
        <h2>官方已发布版本</h2>
      </div>
      <div class="published-actions">
        <el-button plain :disabled="!selected" @click="download('xlsx')">下载 Excel</el-button>
        <el-button plain :disabled="!selected" @click="download('pdf')">下载 PDF</el-button>
        <el-button plain :disabled="!selected" data-testid="validation-export-xlsx" @click="downloadValidation('xlsx')">校验 Excel</el-button>
        <el-button plain :disabled="!selected" data-testid="validation-export-pdf" @click="downloadValidation('pdf')">校验 PDF</el-button>
        <el-button plain :disabled="!selected" @click="print">打印课表</el-button>
        <el-button plain :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-if="message" class="inline-message error-message">{{ message }}</div>

    <div v-if="versions.length" class="published-list">
      <button
        v-for="version in versions"
        :key="version.id"
        class="version-row"
        :class="{ selected: selected === version.id }"
        @click="selectVersion(version.id)"
      >
        <div class="flex justify-between items-center">
          <strong>版本 v{{ version.id }} · revision {{ version.revision ?? 0 }}</strong>
          <span class="published-badge">正式发布</span>
        </div>
        <span>
          {{ version.status }} · {{ version.score ?? '未评分' }} · H{{ scoreParts(version).hard ?? '—' }} / M{{ scoreParts(version).medium ?? '—' }} / S{{ scoreParts(version).soft ?? '—' }}
        </span>
        <small>{{ version.createdAt ?? '' }}</small>
      </button>
    </div>

    <el-empty v-else-if="!loading" description="暂无已发布课表" />

    <div v-if="versions.length" class="published-workspace" data-testid="published-schedule-view">
      <div class="published-filter-bar">
        <div>
          <span class="eyebrow">READ ONLY QUERY</span>
          <h2>{{ selectedVersion ? `版本 v${selectedVersion.id} · ${activeViewLabel}课表` : '只读课表查询' }}</h2>
        </div>
        <label class="published-search"><span>搜索当前课表</span><input v-model="searchQuery" placeholder="课程、教师、班级或教室" /></label>
        <div class="view-tabs" role="tablist" aria-label="课表视图">
          <button v-for="view in [{ type: 'CLASS', label: '班级课表' }, { type: 'TEACHER', label: '教师课表' }, { type: 'ROOM', label: '教室课表' }]" :key="view.type" :class="{ selected: viewType === view.type }" :aria-pressed="viewType === view.type" :data-testid="`published-view-${view.type.toLowerCase()}`" @click="selectView(view.type as WorkspaceViewType)">{{ view.label }}</button>
        </div>
        <label class="resource-picker">
          <span>选择{{ activeViewLabel }}</span>
          <select v-model="resourceCode" :disabled="!resourceOptions.length" @change="loadPublishedSchedule">
            <option v-for="item in visibleResourceOptions" :key="item.code" :value="item.code">{{ item.name }} · {{ item.code }}</option>
          </select>
        </label>
      </div>

      <div v-if="scheduleError" class="inline-message error-message">{{ scheduleError }}</div>
      <div v-else-if="scheduleLoading" class="published-empty">正在加载课表…</div>
      <div v-else-if="!weekdays.length" class="published-empty">当前学期还没有配置节次</div>
      <div v-else-if="!visibleAssignments.length" class="published-empty">{{ searchQuery ? '没有匹配当前搜索条件的课程' : resourceCode ? `当前${activeViewLabel}暂无已发布课程` : '当前版本暂无已发布课程' }}</div>
      <div v-else class="published-board" :style="gridStyle">
        <div class="grid-corner">节次</div>
        <div v-for="day in weekdays" :key="day.number" class="day-head">{{ day.label }}</div>
        <template v-for="period in periods" :key="period">
          <div class="period-label">第{{ period }}节</div>
          <div v-for="day in weekdays" :key="`${period}-${day.number}`" class="published-slot">
            <div v-for="item in getSlotItems(visibleAssignments, day.number, period)" :key="item.occurrenceId" class="published-lesson" :data-testid="`published-assignment-${item.occurrenceId}`">
              <strong>{{ item.subjectName }}</strong>
              <small>{{ viewType === 'CLASS' ? item.teacherName : viewType === 'TEACHER' ? item.studentGroupName : item.subjectName }}</small>
              <em>{{ item.roomName ?? '待分配教室' }}</em>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.canvas-card {
  background: #ffffff;
  border: 1px solid rgba(23, 59, 54, 0.1);
  border-radius: 12px;
  box-shadow: 0 4px 16px -2px rgba(23, 59, 54, 0.03);
  overflow: hidden;
}
.published-badge {
  font-size: 10.5px;
  font-weight: 700;
  color: #15803d;
  background: #dcfce7;
  padding: 1px 8px;
  border-radius: 9999px;
}
.published-workspace {
  margin-top: 22px;
  border-top: 1px solid #EEF1F3;
  padding-top: 22px;
}
.published-filter-bar {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(180px, 240px) auto minmax(180px, 240px);
  align-items: end;
  gap: 16px;
  margin-bottom: 16px;
}
.published-filter-bar h2 {
  margin: 5px 0 0;
  font-size: 18px;
}
.view-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.view-tabs button {
  border: 1px solid #D9DEE3;
  background: #fff;
  color: #566474;
  border-radius: 6px;
  padding: 8px 11px;
  font-size: 12px;
  cursor: pointer;
}
.view-tabs button.selected {
  border-color: #202A35;
  background: #202A35;
  color: #fff;
}
.resource-picker {
  display: grid;
  gap: 5px;
  color: #566474;
  font-size: 11px;
}
.resource-picker select {
  min-width: 0;
  border: 1px solid #D9DEE3;
  border-radius: 6px;
  background: #F9FAFB;
  color: #191c1d;
  padding: 8px 10px;
  font-size: 12px;
}
.published-search { display: grid; gap: 5px; color: #566474; font-size: 11px; }
.published-search input { min-width: 0; border: 1px solid #D9DEE3; border-radius: 6px; background: #F9FAFB; color: #191c1d; padding: 8px 10px; font-size: 12px; }
.published-search input:focus { outline: 2px solid rgba(77, 138, 120, .2); border-color: #4d8a78; }
.published-empty {
  border: 1px dashed #cddbd5;
  border-radius: 8px;
  color: #566474;
  padding: 30px 16px;
  text-align: center;
  font-size: 12px;
}
.published-board {
  display: grid;
  gap: 1px;
  overflow-x: auto;
  background: #D9DEE3;
  border: 1px solid #D9DEE3;
  border-radius: 8px;
  min-width: 620px;
}
.published-board > * {
  min-width: 0;
  background: #fff;
}
.grid-corner, .day-head, .period-label {
  background: #f6faf8 !important;
  color: #566474;
  font-size: 11px;
  font-weight: 700;
  padding: 10px 8px;
  text-align: center;
}
.published-slot {
  min-height: 84px;
  padding: 5px;
}
.published-lesson {
  display: grid;
  gap: 3px;
  padding: 8px;
  border-left: 3px solid #4d8a78;
  border-radius: 5px;
  background: #edf7f2;
  color: #202A35;
  font-size: 11px;
}
.published-lesson strong, .published-lesson small, .published-lesson em {
  overflow-wrap: anywhere;
}
.published-lesson small, .published-lesson em {
  color: #587069;
  font-style: normal;
}
@media (max-width: 860px) {
  .published-filter-bar { grid-template-columns: 1fr; align-items: stretch; }
  .published-filter-bar .view-tabs { order: 3; }
}
</style>
