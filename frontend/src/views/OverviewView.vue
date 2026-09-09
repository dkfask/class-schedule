<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { http } from '../api/http'
import { useTermStore } from '../stores/term'

interface OverviewResource {
  code: string
  name: string
}

interface OverviewData {
  periods?: Array<{ code: string; weekday: number; period: number; label: string }>
  teachers?: OverviewResource[]
  studentGroups?: OverviewResource[]
  subjects?: OverviewResource[]
  rooms?: Array<OverviewResource & { capacity?: number; roomType?: string }>
}

interface ReadinessIssue {
  code: string
  message: string
}

interface SolveReadiness {
  ready: boolean
  timeslotCount: number
  roomCount: number
  requirementCount: number
  issues: ReadinessIssue[]
}

interface VersionSummary {
  id: number
  status: string
  score?: string | null
  publishable?: boolean
  createdAt?: string
  updatedAt?: string
  revision?: number
}

interface VersionPage {
  items?: VersionSummary[]
}

type StageStatus = 'done' | 'active' | 'blocked' | 'pending'

interface StageItem {
  key: string
  number: string
  label: string
  detail: string
  status: StageStatus
  statusLabel: string
  to: string
  action: string
}

const term = useTermStore()
const overview = ref<OverviewData | null>(null)
const readiness = ref<SolveReadiness | null>(null)
const versions = ref<VersionSummary[]>([])
const loading = ref(false)
const errorMessage = ref('')
const lastLoadedAt = ref('')
let loadSequence = 0

const selectedTerm = computed(() => term.terms.value.find(item => item.code === term.selectedTermCode.value))
const termLabel = computed(() => selectedTerm.value ? `${selectedTerm.value.name} · ${selectedTerm.value.code}` : term.selectedTermCode.value || '尚未选择学期')
const resourceCounts = computed(() => ({
  teachers: overview.value?.teachers?.length ?? 0,
  studentGroups: overview.value?.studentGroups?.length ?? 0,
  subjects: overview.value?.subjects?.length ?? 0,
  rooms: overview.value?.rooms?.length ?? 0,
  periods: overview.value?.periods?.length ?? readiness.value?.timeslotCount ?? 0,
}))
const requirementCount = computed(() => readiness.value?.requirementCount ?? 0)
const publishedVersion = computed(() => versions.value.find(version => version.status === 'PUBLISHED'))
const latestVersion = computed(() => versions.value[0])
const workingVersion = computed(() => versions.value.find(version => ['DRAFT', 'CANDIDATE', 'SOLVING'].includes(version.status)))
const hasCoreData = computed(() => resourceCounts.value.teachers > 0
  && resourceCounts.value.studentGroups > 0
  && resourceCounts.value.subjects > 0
  && resourceCounts.value.rooms > 0
  && (readiness.value
    ? readiness.value.roomCount > 0 && readiness.value.timeslotCount > 0
    : resourceCounts.value.periods > 0))
const readinessIssues = computed(() => readiness.value?.issues ?? [])

const metrics = computed(() => [
  { label: '教师', value: resourceCounts.value.teachers, unit: '位', to: '/master-data' },
  { label: '行政班', value: resourceCounts.value.studentGroups, unit: '个', to: '/master-data' },
  { label: '课程', value: resourceCounts.value.subjects, unit: '门', to: '/master-data' },
  { label: '教室', value: resourceCounts.value.rooms, unit: '间', to: '/master-data' },
  { label: '教学需求', value: requirementCount.value, unit: '项', to: '/teaching-plan' },
  { label: '可排节次', value: resourceCounts.value.periods, unit: '节', to: '/rule-facts' },
])

const readinessState = computed(() => {
  if (loading.value) return { label: '正在同步', tone: 'loading' }
  if (!term.hasValidTerm.value) return { label: '等待选择学期', tone: 'muted' }
  if (readiness.value?.ready) return { label: '可以开始排课', tone: 'good' }
  if (readinessIssues.value.length) return { label: `${readinessIssues.value.length} 项待处理`, tone: 'warning' }
  return { label: '数据尚未就绪', tone: 'warning' }
})

const nextAction = computed(() => {
  if (!term.hasValidTerm.value) {
    return { title: '先选择一个可用学期', description: '选择学期后才能查看准备度和排课进度。', label: '查看基础数据', to: '/master-data' }
  }
  if (loading.value && !overview.value) {
    return { title: '正在读取当前学期', description: '总览正在同步基础数据、排课准备度和版本状态。', label: '刷新总览', to: '/workspace' }
  }
  if (!overview.value || !hasCoreData.value) {
    return { title: '补齐基础数据', description: '先准备教师、班级、课程、教室和节次，才能进行可靠的预检。', label: '配置基础数据', to: '/master-data' }
  }
  if (requirementCount.value === 0) {
    return { title: '编制教学计划', description: '当前学期还没有可用于求解的教学需求。', label: '进入教学计划', to: '/teaching-plan' }
  }
  if (!readiness.value?.ready) {
    return { title: '处理排课前置问题', description: readinessIssues.value[0]?.message ?? '请完成当前学期的排课前置检查。', label: '查看规则与检查', to: '/rule-facts' }
  }
  if (workingVersion.value && ['DRAFT', 'CANDIDATE'].includes(workingVersion.value.status)) {
    return { title: '检查候选版本', description: '当前已有候选版本，可以进入版本页面完成检查和发布。', label: '检查并发布版本', to: '/versions' }
  }
  if (publishedVersion.value) {
    return { title: '查看已发布课表', description: `当前学期已有正式版本 v${publishedVersion.value.id}，可以直接查询或导出。`, label: '打开已发布课表', to: '/published' }
  }
  return { title: '开始自动排课', description: '排课前置检查已通过，可以提交新的求解任务。', label: '进入排课工作台', to: '/workspace' }
})

const stageItems = computed<StageItem[]>(() => {
  const solveStatus: StageStatus = latestVersion.value?.status === 'SOLVING'
    ? 'active'
    : latestVersion.value || readiness.value?.ready ? 'done' : 'pending'
  const publishStatus: StageStatus = publishedVersion.value ? 'done' : workingVersion.value ? 'active' : 'pending'
  return [
    {
      key: 'data', number: '01', label: '基础数据',
      detail: hasCoreData.value ? `${resourceCounts.value.teachers} 位教师 · ${resourceCounts.value.rooms} 间教室` : '教师、班级、课程、教室和节次',
      status: overview.value && hasCoreData.value ? 'done' : overview.value ? 'blocked' : 'pending',
      statusLabel: overview.value && hasCoreData.value ? '已准备' : overview.value ? '待补齐' : '待开始',
      to: '/master-data', action: '管理数据',
    },
    {
      key: 'plan', number: '02', label: '教学计划',
      detail: requirementCount.value ? `${requirementCount.value} 项教学需求` : '还没有可排课的教学需求',
      status: requirementCount.value ? 'done' : 'blocked',
      statusLabel: requirementCount.value ? '已建立' : '待建立',
      to: '/teaching-plan', action: '查看计划',
    },
    {
      key: 'rules', number: '03', label: '规则检查',
      detail: readiness.value?.ready ? '排课前置检查已通过' : readinessIssues.value[0]?.message ?? '等待数据完成后检查',
      status: readiness.value?.ready ? 'done' : readiness.value ? 'blocked' : 'pending',
      statusLabel: readiness.value?.ready ? '已通过' : readiness.value ? '有阻塞' : '待检查',
      to: '/rule-facts', action: '查看检查',
    },
    {
      key: 'solve', number: '04', label: '自动排课',
      detail: latestVersion.value ? `${versionStatusLabel(latestVersion.value.status)} · v${latestVersion.value.id}` : '还没有候选排课版本',
      status: solveStatus,
      statusLabel: latestVersion.value ? versionStatusLabel(latestVersion.value.status) : '待开始',
      to: '/workspace', action: '进入工作台',
    },
    {
      key: 'publish', number: '05', label: '发布交付',
      detail: publishedVersion.value ? `正式版本 v${publishedVersion.value.id} 已发布` : '完成检查后发布给教师和管理人员',
      status: publishStatus,
      statusLabel: publishedVersion.value ? '已发布' : workingVersion.value ? '待检查' : '待发布',
      to: publishedVersion.value ? '/published' : '/versions', action: publishedVersion.value ? '查看课表' : '进入发布',
    },
  ]
})

const recentSignals = computed(() => {
  const signals: Array<{ label: string; detail: string; time: string }> = []
  if (latestVersion.value) {
    signals.push({
      label: '最近版本',
      detail: `v${latestVersion.value.id} · ${versionStatusLabel(latestVersion.value.status)}${latestVersion.value.publishable ? ' · 可发布' : ''}`,
      time: formatDate(latestVersion.value.updatedAt ?? latestVersion.value.createdAt),
    })
  }
  signals.push({
    label: '数据预检',
    detail: readiness.value?.ready ? '当前学期已满足求解前置条件' : readinessIssues.value[0]?.message ?? '等待检查结果',
    time: lastLoadedAt.value ? `同步于 ${lastLoadedAt.value}` : '尚未同步',
  })
  signals.push({
    label: '当前学期',
    detail: `${resourceCounts.value.teachers} 位教师 · ${resourceCounts.value.studentGroups} 个班级 · ${requirementCount.value} 项需求`,
    time: termLabel.value,
  })
  return signals
})

function versionStatusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    SOLVING: '求解中',
    CANDIDATE: '候选版本',
    PUBLISHED: '已发布',
    ARCHIVED: '已归档',
    CANCELLED: '已取消',
  }
  return labels[status] ?? status
}

function formatDate(value?: string) {
  if (!value) return '尚未记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

function setEmptyState() {
  overview.value = null
  readiness.value = null
  versions.value = []
  lastLoadedAt.value = ''
}

async function loadOverview(termCode = term.selectedTermCode.value) {
  const sequence = ++loadSequence
  if (!termCode) {
    setEmptyState()
    errorMessage.value = ''
    loading.value = false
    return
  }
  loading.value = true
  errorMessage.value = ''
  const encodedTerm = encodeURIComponent(termCode)
  const [overviewResult, readinessResult, versionsResult] = await Promise.allSettled([
    http<OverviewData>(`/api/master-data/overview?termCode=${encodedTerm}`),
    http<SolveReadiness>(`/api/solve-readiness?termCode=${encodedTerm}`),
    http<VersionPage>(`/api/schedule-versions?termCode=${encodedTerm}&page=0&size=20`),
  ])
  if (sequence !== loadSequence || termCode !== term.selectedTermCode.value) return

  const errors: string[] = []
  if (overviewResult.status === 'fulfilled') overview.value = overviewResult.value
  else errors.push(overviewResult.reason instanceof Error ? overviewResult.reason.message : '基础数据读取失败')
  if (readinessResult.status === 'fulfilled') readiness.value = readinessResult.value
  else errors.push(readinessResult.reason instanceof Error ? readinessResult.reason.message : '排课前置检查失败')
  if (versionsResult.status === 'fulfilled') versions.value = versionsResult.value.items ?? []
  else errors.push(versionsResult.reason instanceof Error ? versionsResult.reason.message : '版本列表读取失败')
  errorMessage.value = errors.length ? errors.join('；') : ''
  lastLoadedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())
  loading.value = false
}

async function refresh() {
  await loadOverview()
}

watch(() => term.selectedTermCode.value, (code) => { void loadOverview(code) })

onMounted(async () => {
  await term.loadTerms()
  await loadOverview()
})
</script>

<template>
  <div class="overview-page">
    <header class="topbar">
      <div>
        <p class="eyebrow">SEMESTER OVERVIEW / CONTROL CENTER</p>
        <h1>学期总览</h1>
      </div>
      <div class="top-actions">
        <span class="sync-state" :class="{ 'sync-loading': loading }">● {{ loading ? '正在同步' : '状态已同步' }}</span>
        <button class="icon-button" type="button" aria-label="刷新总览" title="刷新总览" :disabled="loading" @click="refresh">↻</button>
        <div class="avatar">教</div>
      </div>
    </header>

    <div v-if="errorMessage" class="overview-alert" role="alert">
      <span class="alert-icon">!</span>
      <div>
        <strong>部分状态暂时无法读取</strong>
        <p>{{ errorMessage }}</p>
      </div>
      <button type="button" class="text-button" :disabled="loading" @click="refresh">重新加载</button>
    </div>

    <section class="overview-hero" aria-labelledby="overview-title">
      <div class="overview-hero-main">
        <p class="eyebrow">CURRENT TERM</p>
        <h2 id="overview-title">{{ termLabel }}</h2>
        <p class="overview-description">从数据准备到正式发布，集中查看当前学期的排课进度和需要处理的事项。</p>
        <div class="overview-hero-actions">
          <RouterLink class="primary-action" :to="nextAction.to">{{ nextAction.label }} <span aria-hidden="true">→</span></RouterLink>
          <RouterLink class="secondary-action" to="/workspace">打开排课工作台</RouterLink>
        </div>
      </div>
      <div class="readiness-card" :class="`readiness-${readinessState.tone}`">
        <span class="readiness-label">当前准备度</span>
        <strong>{{ readinessState.label }}</strong>
        <p>{{ nextAction.title }}</p>
        <div class="readiness-line"><i :style="{ width: `${readiness?.ready ? 100 : Math.min(90, Math.max(12, stageItems.filter(item => item.status === 'done').length * 20))}%` }"></i></div>
        <small>{{ nextAction.description }}</small>
      </div>
    </section>

    <section class="overview-metrics" aria-label="当前学期数据概览">
      <RouterLink v-for="metric in metrics" :key="metric.label" class="overview-metric" :to="metric.to">
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
        <small>{{ metric.unit }}</small>
      </RouterLink>
    </section>

    <section class="overview-content-grid">
      <div class="overview-panel pipeline-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">WORKFLOW</span>
            <h2>排课流程</h2>
          </div>
          <span class="panel-caption">{{ stageItems.filter(item => item.status === 'done').length }}/5 已完成</span>
        </div>
        <ol class="stage-list">
          <li v-for="stage in stageItems" :key="stage.key" class="stage-row" :class="`stage-${stage.status}`">
            <span class="stage-number">{{ stage.number }}</span>
            <span class="stage-state" aria-hidden="true">{{ stage.status === 'done' ? '✓' : stage.status === 'blocked' ? '!' : stage.status === 'active' ? '·' : '—' }}</span>
            <div class="stage-content">
              <div class="stage-title-line"><strong>{{ stage.label }}</strong><span>{{ stage.statusLabel }}</span></div>
              <small>{{ stage.detail }}</small>
            </div>
            <RouterLink class="stage-action" :to="stage.to">{{ stage.action }} <span aria-hidden="true">→</span></RouterLink>
          </li>
        </ol>
      </div>

      <div class="overview-panel blocker-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">ATTENTION</span>
            <h2>当前需要处理</h2>
          </div>
          <span class="panel-caption warning-caption">{{ readinessIssues.length }} 项</span>
        </div>
        <div v-if="readinessIssues.length" class="blocker-list">
          <RouterLink v-for="issue in readinessIssues" :key="issue.code" class="blocker-row" to="/rule-facts">
            <span class="blocker-mark">!</span>
            <span><strong>{{ issue.message }}</strong><small>{{ issue.code }} · 查看处理入口</small></span>
            <span aria-hidden="true">→</span>
          </RouterLink>
        </div>
        <div v-else-if="readiness?.ready" class="clear-state">
          <span class="clear-mark">✓</span>
          <strong>暂时没有阻塞项</strong>
          <small>当前学期已通过排课前置检查，可以开始求解或继续处理候选版本。</small>
          <RouterLink to="/workspace" class="text-button">进入排课工作台 →</RouterLink>
        </div>
        <div v-else class="empty-state overview-empty">
          <span class="empty-icon">—</span>
          <strong>等待检查结果</strong>
          <small>完成基础数据和教学计划后，系统会显示具体的处理事项。</small>
        </div>
      </div>
    </section>

    <section class="overview-content-grid lower-grid">
      <div class="overview-panel signal-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">RECENT SIGNALS</span>
            <h2>近期状态</h2>
          </div>
          <span class="panel-caption">{{ termLabel }}</span>
        </div>
        <div class="signal-list">
          <div v-for="signal in recentSignals" :key="signal.label" class="signal-row">
            <span class="signal-dot"></span>
            <div><strong>{{ signal.label }}</strong><small>{{ signal.detail }}</small></div>
            <time>{{ signal.time }}</time>
          </div>
        </div>
      </div>

      <div class="overview-panel version-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">VERSION CONTROL</span>
            <h2>版本动态</h2>
          </div>
          <RouterLink class="panel-link" to="/versions">查看全部 →</RouterLink>
        </div>
        <div v-if="versions.length" class="version-list">
          <RouterLink v-for="version in versions.slice(0, 3)" :key="version.id" class="version-row" to="/versions">
            <span class="version-id">v{{ version.id }}</span>
            <span class="version-info"><strong>{{ versionStatusLabel(version.status) }}</strong><small>{{ version.publishable ? '满足发布门禁' : `revision ${version.revision ?? 0}` }}</small></span>
            <span class="version-arrow" aria-hidden="true">→</span>
          </RouterLink>
        </div>
        <div v-else class="empty-state overview-empty">
          <span class="empty-icon">◷</span>
          <strong>还没有排课版本</strong>
          <small>完成前置检查后，可以从排课工作台提交首次求解。</small>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.overview-page { display: grid; gap: 20px; }
.top-actions { align-items: center; }
.icon-button { width: 32px; height: 32px; border: 1px solid #d9e2dc; background: #fff; color: #4c7762; cursor: pointer; font-size: 18px; line-height: 1; }
.icon-button:hover:not(:disabled) { border-color: #75ad8b; color: #23654a; }
.icon-button:disabled { opacity: .5; cursor: wait; }
.sync-loading { color: #9b6c2f; }
.overview-alert { display: flex; align-items: center; gap: 12px; border: 1px solid #efd6a9; background: #fff8ec; color: #7a5728; padding: 13px 16px; }
.alert-icon, .blocker-mark { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 auto; border-radius: 50%; background: #e2a34c; color: #fff; font-weight: 700; font-size: 12px; }
.overview-alert strong, .overview-alert p { display: block; }
.overview-alert strong { font-size: 12px; }
.overview-alert p { margin: 3px 0 0; font-size: 11px; line-height: 1.5; }
.overview-alert .text-button { margin-left: auto; }
.text-button { border: 0; background: transparent; color: #317957; cursor: pointer; font-size: 11px; padding: 4px 0; text-decoration: none; }
.text-button:hover { color: #1b5a45; text-decoration: underline; }
.text-button:disabled { opacity: .5; cursor: wait; }
.overview-hero { display: grid; grid-template-columns: minmax(0, 1fr) minmax(270px, 330px); gap: 26px; background: #fff; border: 1px solid #d9e2dc; padding: 28px 30px; }
.overview-hero-main { min-width: 0; }
.overview-hero h2 { margin: 0; color: #213b32; font-size: 25px; font-weight: 650; }
.overview-description { max-width: 620px; margin: 12px 0 21px; color: #6d8579; font-size: 13px; line-height: 1.7; }
.overview-hero-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.primary-action, .secondary-action { display: inline-flex; align-items: center; gap: 12px; min-height: 38px; padding: 0 16px; text-decoration: none; font-size: 12px; }
.primary-action { background: #2e8a65; color: #fff; }
.primary-action:hover { background: #236f50; }
.secondary-action { border-bottom: 1px solid #c5d9cc; color: #387957; }
.secondary-action:hover { color: #1b5a45; border-color: #71a98a; }
.readiness-card { display: flex; flex-direction: column; justify-content: center; min-width: 0; border-left: 3px solid #78b394; padding: 8px 0 8px 22px; }
.readiness-card.readiness-warning { border-color: #d89b45; }
.readiness-card.readiness-muted { border-color: #a9bbb2; }
.readiness-card.readiness-loading { border-color: #80a794; }
.readiness-label { color: #7d958a; font-size: 11px; }
.readiness-card strong { margin-top: 6px; color: #27734e; font-size: 20px; }
.readiness-warning strong { color: #946128; }
.readiness-card p { margin: 9px 0 0; color: #4e6e5e; font-size: 12px; font-weight: 650; }
.readiness-card small { margin-top: 9px; color: #80968b; font-size: 10px; line-height: 1.55; }
.readiness-line { height: 5px; margin-top: 16px; background: #e8efeb; }
.readiness-line i { display: block; height: 100%; background: #62ad83; transition: width .2s ease; }
.readiness-warning .readiness-line i { background: #d6a05a; }
.overview-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1px; border: 1px solid #d9e2dc; background: #d9e2dc; }
.overview-metric { min-width: 0; background: #fff; padding: 16px 17px; color: #7b9188; text-decoration: none; }
.overview-metric:hover { background: #f6fbf8; }
.overview-metric span, .overview-metric small { display: block; font-size: 10px; }
.overview-metric strong { display: inline-block; margin-top: 8px; color: #244a3b; font-size: 23px; line-height: 1; }
.overview-metric small { display: inline-block; margin-left: 4px; color: #8ba197; }
.overview-content-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(320px, .75fr); gap: 20px; }
.overview-panel { min-width: 0; background: #fff; border: 1px solid #d9e2dc; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 18px 19px 15px; border-bottom: 1px solid #e5ece8; }
.panel-heading h2 { margin: 1px 0 0; color: #294d3f; font-size: 16px; font-weight: 650; }
.panel-caption { flex: 0 0 auto; color: #80968b; font-size: 10px; }
.warning-caption { color: #af7632; }
.panel-link { color: #3b8061; font-size: 10px; text-decoration: none; }
.panel-link:hover { text-decoration: underline; }
.stage-list, .signal-list, .version-list { margin: 0; padding: 0; list-style: none; }
.stage-row { display: grid; grid-template-columns: 30px 22px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-height: 64px; padding: 9px 19px; border-bottom: 1px solid #edf2ef; }
.stage-row:last-child, .signal-row:last-child, .version-row:last-child { border-bottom: 0; }
.stage-row:hover { background: #fbfdfb; }
.stage-number { color: #a6b7ae; font-size: 10px; }
.stage-state { display: grid; place-items: center; width: 21px; height: 21px; border: 1px solid #c5d5cd; border-radius: 50%; color: #7f978a; font-size: 11px; }
.stage-done .stage-state { border-color: #71b38c; background: #e7f5ed; color: #2d875a; }
.stage-active .stage-state { border-color: #78a991; background: #eef7f1; color: #3c815f; }
.stage-blocked .stage-state { border-color: #d9a15a; background: #fff5e4; color: #a16b2b; }
.stage-content { min-width: 0; }
.stage-title-line { display: flex; align-items: center; gap: 9px; }
.stage-title-line strong { color: #365b4b; font-size: 12px; }
.stage-title-line span { color: #779184; font-size: 10px; }
.stage-blocked .stage-title-line span { color: #ad7333; }
.stage-content small { display: block; overflow: hidden; margin-top: 4px; color: #83978d; font-size: 10px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.stage-action { color: #478466; font-size: 10px; text-decoration: none; white-space: nowrap; }
.stage-action:hover { color: #1b5a45; }
.blocker-list { padding: 3px 0; }
.blocker-row { display: grid; grid-template-columns: 22px minmax(0, 1fr) 16px; align-items: start; gap: 11px; padding: 15px 19px; color: #76582f; text-decoration: none; border-bottom: 1px solid #f0eadf; }
.blocker-row:hover { background: #fffaf1; }
.blocker-row:last-child { border-bottom: 0; }
.blocker-mark { width: 20px; height: 20px; background: #dfa65e; font-size: 11px; }
.blocker-row strong, .blocker-row small { display: block; }
.blocker-row strong { color: #79582f; font-size: 11px; line-height: 1.45; }
.blocker-row small { margin-top: 5px; color: #ad8960; font-size: 9px; }
.clear-state { display: flex; flex-direction: column; align-items: flex-start; padding: 38px 24px; }
.clear-mark { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; background: #e3f4e9; color: #2b8a5b; font-size: 15px; }
.clear-state strong { margin-top: 13px; color: #35634e; font-size: 13px; }
.clear-state small { max-width: 270px; margin-top: 7px; color: #82978d; font-size: 10px; line-height: 1.6; }
.clear-state .text-button { margin-top: 14px; }
.overview-empty { padding: 35px 24px; }
.overview-empty strong { margin-top: 10px; color: #557365; font-size: 12px; }
.overview-empty small { max-width: 260px; }
.lower-grid { align-items: start; }
.signal-row, .version-row { display: grid; align-items: center; gap: 11px; min-height: 67px; padding: 10px 19px; border-bottom: 1px solid #edf2ef; }
.signal-row { grid-template-columns: 8px minmax(0, 1fr) auto; }
.signal-dot { width: 7px; height: 7px; border-radius: 50%; background: #65ad82; }
.signal-row strong, .signal-row small { display: block; }
.signal-row strong { color: #416551; font-size: 11px; }
.signal-row small { overflow: hidden; margin-top: 4px; color: #83978d; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.signal-row time { color: #9aaca3; font-size: 9px; white-space: nowrap; }
.version-row { grid-template-columns: 45px minmax(0, 1fr) 15px; color: #365b4b; text-decoration: none; }
.version-row:hover { background: #fbfdfb; }
.version-id { color: #3c805f; font-size: 12px; font-weight: 700; }
.version-info strong, .version-info small { display: block; }
.version-info strong { font-size: 11px; }
.version-info small { margin-top: 4px; color: #84988e; font-size: 10px; }
.version-arrow { color: #85a294; font-size: 12px; }
.overview-page a:focus-visible, .overview-page button:focus-visible { outline: 2px solid #2e8a65; outline-offset: 2px; }
@media (max-width: 1120px) {
  .overview-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 900px) {
  .overview-hero, .overview-content-grid { grid-template-columns: 1fr; }
  .readiness-card { border-top: 1px solid #e5ece8; border-left: 0; padding: 18px 0 0; }
  .readiness-card.readiness-warning { border-color: #e5ece8; }
}
@media (max-width: 640px) {
  .overview-page { gap: 14px; }
  .overview-hero { padding: 21px 18px; }
  .overview-hero h2 { font-size: 21px; }
  .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .stage-row { grid-template-columns: 25px 20px minmax(0, 1fr); padding: 9px 13px; }
  .stage-action { grid-column: 3; }
  .panel-heading { padding-left: 14px; padding-right: 14px; }
  .overview-alert { align-items: flex-start; flex-wrap: wrap; padding: 12px 13px; }
  .overview-alert .text-button { margin-left: 34px; }
  .signal-row, .version-row { padding-left: 14px; padding-right: 14px; }
  .signal-row { grid-template-columns: 8px minmax(0, 1fr); }
  .signal-row time { grid-column: 2; }
}
</style>
