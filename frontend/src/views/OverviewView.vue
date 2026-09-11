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
const dataReadinessPercent = computed(() => {
  if (!overview.value) return '—'
  const checks = [
    resourceCounts.value.teachers > 0,
    resourceCounts.value.studentGroups > 0,
    resourceCounts.value.subjects > 0,
    resourceCounts.value.rooms > 0,
    resourceCounts.value.periods > 0,
  ]
  return `${Math.round((checks.filter(Boolean).length / checks.length) * 100)}%`
})
const dashboardMetrics = computed(() => [
  {
    label: '核心数据就绪度',
    value: dataReadinessPercent.value,
    detail: overview.value ? `${[resourceCounts.value.teachers, resourceCounts.value.studentGroups, resourceCounts.value.subjects, resourceCounts.value.rooms, resourceCounts.value.periods].filter(Boolean).length} / 5 类数据已载入` : '等待同步',
    to: '/master-data',
    tone: hasCoreData.value ? 'success' : 'info',
    icon: '◇',
  },
  {
    label: '待处理问题',
    value: String(readinessIssues.value.length),
    detail: readinessIssues.value.length ? '需要处理的前置检查项' : '当前没有阻塞项',
    to: '/rule-facts',
    tone: readinessIssues.value.length ? 'warning' : 'success',
    icon: '!',
  },
  {
    label: '当前工作版本',
    value: latestVersion.value ? `v${latestVersion.value.id}` : '—',
    detail: latestVersion.value ? versionStatusLabel(latestVersion.value.status) : '尚未提交求解',
    to: '/versions',
    tone: 'info',
    icon: '□',
  },
  {
    label: '正式发布版本',
    value: publishedVersion.value ? `v${publishedVersion.value.id}` : '—',
    detail: publishedVersion.value ? '当前学期已发布' : '尚未发布正式版本',
    to: '/published',
    tone: publishedVersion.value ? 'success' : 'muted',
    icon: '✓',
  },
])
const readinessWidth = computed(() => {
  if (readiness.value?.ready) return 100
  if (!overview.value) return 8
  return Math.max(12, Math.round((stageItems.value.filter(item => item.status === 'done').length / stageItems.value.length) * 100))
})

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
      <div class="overview-heading">
        <p class="overview-breadcrumb">智程排课系统 <span>/</span> 学期总览 <span>/</span> <strong>{{ termLabel }}</strong></p>
        <h1>学期总览</h1>
        <p class="topbar-subtitle">排课准备度与发布状态一览</p>
      </div>
      <div class="top-actions">
        <span class="sync-state" :class="{ 'sync-loading': loading }"><i></i>{{ loading ? '正在同步' : `最后同步 ${lastLoadedAt || '尚未同步'}` }}</span>
        <button class="icon-button" type="button" aria-label="刷新总览" title="刷新总览" :disabled="loading" @click="refresh">↻</button>
        <button class="icon-button" type="button" aria-label="通知" title="通知">!</button>
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

    <section class="overview-metrics dashboard-metrics" aria-label="当前学期状态概览">
      <RouterLink v-for="metric in dashboardMetrics" :key="metric.label" class="overview-metric" :class="`metric-${metric.tone}`" :to="metric.to">
        <div class="metric-topline"><span>{{ metric.label }}</span><b class="metric-icon" aria-hidden="true">{{ metric.icon }}</b></div>
        <div class="metric-value-line">
          <strong>{{ metric.value }}</strong>
          <small>{{ metric.detail }}</small>
        </div>
      </RouterLink>
    </section>

    <section class="overview-content-grid overview-primary-grid">
      <div class="overview-panel pipeline-panel preparation-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">PREPARATION / WORKFLOW</span>
            <h2>排课准备度与流水线推进</h2>
          </div>
          <span class="panel-caption" :class="`caption-${readinessState.tone}`">{{ readinessState.label }}</span>
        </div>
        <div class="preparation-summary">
          <div><strong>{{ dataReadinessPercent }}</strong><span>核心数据就绪度</span></div>
          <div class="preparation-track"><i :style="{ width: `${readinessWidth}%` }"></i></div>
          <small>{{ nextAction.title }} · {{ nextAction.description }}</small>
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
        <div class="panel-action-row">
          <span>{{ stageItems.filter(item => item.status === 'done').length }}/{{ stageItems.length }} 个阶段已完成</span>
          <RouterLink class="primary-action" :to="nextAction.to">{{ nextAction.label }} <span aria-hidden="true">→</span></RouterLink>
        </div>
      </div>

      <div class="overview-panel health-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">SYSTEM HEALTH</span>
            <h2>约束与算法健康度</h2>
          </div>
          <span class="panel-caption">{{ termLabel }}</span>
        </div>
        <div class="health-list">
          <div class="health-row health-success">
            <span class="health-mark">✓</span>
            <div><strong>排课前置检查</strong><small>{{ readiness?.ready ? '当前学期已满足求解条件' : readiness ? '存在需要处理的前置问题' : '等待接口返回检查结果' }}</small></div>
            <b>{{ readiness?.ready ? '已通过' : readiness ? `${readinessIssues.length} 项` : '待检查' }}</b>
          </div>
          <div class="health-row health-info">
            <span class="health-mark">□</span>
            <div><strong>可排节次</strong><small>当前学期可用的时间片数量</small></div>
            <b>{{ resourceCounts.periods || '—' }}</b>
          </div>
          <div class="health-row health-warning">
            <span class="health-mark">!</span>
            <div><strong>教学需求</strong><small>已进入教学计划的排课需求</small></div>
            <b>{{ requirementCount || '—' }}</b>
          </div>
        </div>
        <div class="health-footer">
          <span>规则状态：{{ readinessIssues.length ? '需要关注' : readiness?.ready ? '正常' : '等待同步' }}</span>
          <RouterLink to="/rule-facts" class="panel-link">查看规则中心 →</RouterLink>
        </div>
      </div>
    </section>

    <section class="overview-content-grid lower-grid">
      <div class="overview-panel version-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">VERSION LIFECYCLE</span>
            <h2>排课版本生命周期</h2>
          </div>
          <RouterLink class="panel-link" to="/versions">版本对比 →</RouterLink>
        </div>
        <div v-if="versions.length" class="version-list">
          <RouterLink v-for="version in versions.slice(0, 3)" :key="version.id" class="version-row" to="/versions">
            <span class="version-id">v{{ version.id }}</span>
            <span class="version-info"><strong>{{ versionStatusLabel(version.status) }}</strong><small>{{ version.publishable ? '满足发布门禁' : `revision ${version.revision ?? 0}` }} · {{ formatDate(version.updatedAt ?? version.createdAt) }}</small></span>
            <span class="version-arrow" aria-hidden="true">→</span>
          </RouterLink>
        </div>
        <div v-else class="empty-state overview-empty">
          <span class="empty-icon">□</span>
          <strong>还没有排课版本</strong>
          <small>完成前置检查后，可以从排课工作台提交首次求解。</small>
        </div>
      </div>

      <div class="overview-panel blocker-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">ATTENTION QUEUE</span>
            <h2>待办与冲突阻断清单</h2>
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

    <section class="overview-panel signal-panel activity-panel">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">RECENT SIGNALS / AUDIT</span>
          <h2>最近状态与同步记录</h2>
        </div>
        <span class="panel-caption">{{ lastLoadedAt ? `同步于 ${lastLoadedAt}` : '尚未同步' }}</span>
      </div>
      <div class="signal-list">
        <div v-for="signal in recentSignals" :key="signal.label" class="signal-row">
          <span class="signal-dot"></span>
          <div><strong>{{ signal.label }}</strong><small>{{ signal.detail }}</small></div>
          <time>{{ signal.time }}</time>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.overview-page { display: grid; gap: 20px; }
.top-actions { align-items: center; }
.icon-button { display: inline-grid; place-items: center; flex: 0 0 32px; width: 32px; height: 32px; padding: 0; border: 1px solid #D9DEE3; background: #fff; color: #4c7762; cursor: pointer; font-size: 18px; line-height: 1; }
.icon-button:hover:not(:disabled) { border-color: #3B6F8F; color: #23654a; }
.icon-button:disabled { opacity: .5; cursor: wait; }
.sync-loading { color: #9b6c2f; }
.overview-alert { display: flex; align-items: center; gap: 12px; border: 1px solid #EED8AF; background: #fff8ec; color: #7a5728; padding: 13px 16px; }
.alert-icon, .blocker-mark { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 auto; border-radius: 50%; background: #D19A3B; color: #fff; font-weight: 700; font-size: 12px; }
.overview-alert strong, .overview-alert p { display: block; }
.overview-alert strong { font-size: 12px; }
.overview-alert p { margin: 3px 0 0; font-size: 11px; line-height: 1.5; }
.overview-alert .text-button { margin-left: auto; }
.text-button { border: 0; background: transparent; color: #317957; cursor: pointer; font-size: 11px; padding: 4px 0; text-decoration: none; }
.text-button:hover { color: #984936; text-decoration: underline; }
.text-button:disabled { opacity: .5; cursor: wait; }
.overview-hero { display: grid; grid-template-columns: minmax(0, 1fr) minmax(270px, 330px); gap: 26px; background: #fff; border: 1px solid #D9DEE3; padding: 28px 30px; }
.overview-hero-main { min-width: 0; }
.overview-hero h2 { margin: 0; color: #213b32; font-size: 25px; font-weight: 650; }
.overview-description { max-width: 620px; margin: 12px 0 21px; color: #6d8579; font-size: 13px; line-height: 1.7; }
.overview-hero-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.primary-action, .secondary-action { display: inline-flex; align-items: center; gap: 12px; min-height: 38px; padding: 0 16px; text-decoration: none; font-size: 12px; }
.primary-action { background: #B85C45; color: #fff; }
.primary-action:hover { background: #236f50; }
.secondary-action { border-bottom: 1px solid #c5d9cc; color: #387957; }
.secondary-action:hover { color: #984936; border-color: #71a98a; }
.readiness-card { display: flex; flex-direction: column; justify-content: center; min-width: 0; border-left: 3px solid #3F806F; padding: 8px 0 8px 22px; }
.readiness-card.readiness-warning { border-color: #D19A3B; }
.readiness-card.readiness-muted { border-color: #8795A5; }
.readiness-card.readiness-loading { border-color: #3B6F8F; }
.readiness-label { color: #566474; font-size: 11px; }
.readiness-card strong { margin-top: 6px; color: #27734e; font-size: 20px; }
.readiness-warning strong { color: #946128; }
.readiness-card p { margin: 9px 0 0; color: #4e6e5e; font-size: 12px; font-weight: 650; }
.readiness-card small { margin-top: 9px; color: #566474; font-size: 10px; line-height: 1.55; }
.readiness-line { height: 5px; margin-top: 16px; background: #e8efeb; }
.readiness-line i { display: block; height: 100%; background: #3F806F; transition: width .2s ease; }
.readiness-warning .readiness-line i { background: #d6a05a; }
.overview-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1px; border: 1px solid #D9DEE3; background: #D9DEE3; }
.overview-metric { min-width: 0; background: #fff; padding: 16px 17px; color: #566474; text-decoration: none; }
.overview-metric:hover { background: #F9FAFB; }
.overview-metric span, .overview-metric small { display: block; font-size: 10px; }
.overview-metric strong { display: inline-block; margin-top: 8px; color: #244a3b; font-size: 23px; line-height: 1; }
.overview-metric small { display: inline-block; margin-left: 4px; color: #8795A5; }
.overview-content-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(320px, .75fr); gap: 20px; }
.overview-panel { min-width: 0; background: #fff; border: 1px solid #D9DEE3; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 18px 19px 15px; border-bottom: 1px solid #EEF1F3; }
.panel-heading h2 { margin: 1px 0 0; color: #294d3f; font-size: 16px; font-weight: 650; }
.panel-caption { flex: 0 0 auto; color: #566474; font-size: 10px; }
.warning-caption { color: #af7632; }
.panel-link { color: #3b8061; font-size: 10px; text-decoration: none; }
.panel-link:hover { text-decoration: underline; }
.stage-list, .signal-list, .version-list { margin: 0; padding: 0; list-style: none; }
.stage-row { display: grid; grid-template-columns: 30px 22px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-height: 64px; padding: 9px 19px; border-bottom: 1px solid #EEF1F3; }
.stage-row:last-child, .signal-row:last-child, .version-row:last-child { border-bottom: 0; }
.stage-row:hover { background: #F9FAFB; }
.stage-number { color: #a6b7ae; font-size: 10px; }
.stage-state { display: grid; place-items: center; width: 21px; height: 21px; border: 1px solid #c5d5cd; border-radius: 50%; color: #7f978a; font-size: 11px; }
.stage-done .stage-state { border-color: #3F806F; background: #EAF2EF; color: #2d875a; }
.stage-active .stage-state { border-color: #78a991; background: #eef7f1; color: #3c815f; }
.stage-blocked .stage-state { border-color: #d9a15a; background: #FBF4E7; color: #a16b2b; }
.stage-content { min-width: 0; }
.stage-title-line { display: flex; align-items: center; gap: 9px; }
.stage-title-line strong { color: #182029; font-size: 12px; }
.stage-title-line span { color: #779184; font-size: 10px; }
.stage-blocked .stage-title-line span { color: #ad7333; }
.stage-content small { display: block; overflow: hidden; margin-top: 4px; color: #8795A5; font-size: 10px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.stage-action { color: #478466; font-size: 10px; text-decoration: none; white-space: nowrap; }
.stage-action:hover { color: #984936; }
.blocker-list { padding: 3px 0; }
.blocker-row { display: grid; grid-template-columns: 22px minmax(0, 1fr) 16px; align-items: start; gap: 11px; padding: 15px 19px; color: #76582f; text-decoration: none; border-bottom: 1px solid #f0eadf; }
.blocker-row:hover { background: #FBF4E7; }
.blocker-row:last-child { border-bottom: 0; }
.blocker-mark { width: 20px; height: 20px; background: #dfa65e; font-size: 11px; }
.blocker-row strong, .blocker-row small { display: block; }
.blocker-row strong { color: #79582f; font-size: 11px; line-height: 1.45; }
.blocker-row small { margin-top: 5px; color: #ad8960; font-size: 9px; }
.clear-state { display: flex; flex-direction: column; align-items: flex-start; padding: 38px 24px; }
.clear-mark { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; background: #EAF2EF; color: #2b8a5b; font-size: 15px; }
.clear-state strong { margin-top: 13px; color: #35634e; font-size: 13px; }
.clear-state small { max-width: 270px; margin-top: 7px; color: #82978d; font-size: 10px; line-height: 1.6; }
.clear-state .text-button { margin-top: 14px; }
.overview-empty { padding: 35px 24px; }
.overview-empty strong { margin-top: 10px; color: #557365; font-size: 12px; }
.overview-empty small { max-width: 260px; }
.lower-grid { align-items: start; }
.signal-row, .version-row { display: grid; align-items: center; gap: 11px; min-height: 67px; padding: 10px 19px; border-bottom: 1px solid #EEF1F3; }
.signal-row { grid-template-columns: 8px minmax(0, 1fr) auto; }
.signal-dot { width: 7px; height: 7px; border-radius: 50%; background: #3F806F; }
.signal-row strong, .signal-row small { display: block; }
.signal-row strong { color: #416551; font-size: 11px; }
.signal-row small { overflow: hidden; margin-top: 4px; color: #8795A5; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.signal-row time { color: #8795A5; font-size: 9px; white-space: nowrap; }
.version-row { grid-template-columns: 45px minmax(0, 1fr) 15px; color: #182029; text-decoration: none; }
.version-row:hover { background: #F9FAFB; }
.version-id { color: #3c805f; font-size: 12px; font-weight: 700; }
.version-info strong, .version-info small { display: block; }
.version-info strong { font-size: 11px; }
.version-info small { margin-top: 4px; color: #84988e; font-size: 10px; }
.version-arrow { color: #8795A5; font-size: 12px; }
.overview-page a:focus-visible, .overview-page button:focus-visible { outline: 2px solid #B85C45; outline-offset: 2px; }
.overview-heading { min-width: 0; }
.overview-breadcrumb { margin: 0 0 5px; color: #8795A5; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 10px; letter-spacing: .04em; text-transform: uppercase; }
.overview-breadcrumb span { padding: 0 7px; color: #D9DEE3; }
.overview-breadcrumb strong { color: #566474; font-weight: 500; }
.topbar-subtitle { display: inline-block; margin: 4px 0 0 11px; color: #566474; font-size: 12px; vertical-align: middle; }
.dashboard-metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; border: 0; background: transparent; }
.dashboard-metrics .overview-metric { border: 1px solid #D9DEE3; border-radius: 6px; padding: 16px 17px; box-shadow: 0 1px 3px rgba(24, 32, 41, .05); }
.dashboard-metrics .overview-metric:hover { border-color: #B8C2CB; background: #fff; box-shadow: 0 3px 10px rgba(24, 32, 41, .07); }
.metric-topline { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #566474; }
.metric-topline > span { font-size: 10px; }
.metric-icon { display: grid; place-items: center; width: 25px; height: 25px; border: 1px solid #D9DEE3; border-radius: 4px; color: #3B6F8F; font-family: ui-monospace, monospace; font-size: 12px; font-weight: 600; }
.metric-success .metric-icon { border-color: #BBD6CC; background: #EAF2EF; color: #3F806F; }
.metric-warning .metric-icon { border-color: #EED8AF; background: #FBF4E7; color: #D19A3B; }
.metric-info .metric-icon { border-color: #C5D8E3; background: #EEF4F7; color: #3B6F8F; }
.metric-muted .metric-icon { border-color: #D9DEE3; background: #F5F6F8; color: #8795A5; }
.metric-value-line { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 8px; min-width: 0; margin-top: 16px; }
.dashboard-metrics .overview-metric strong { color: #182029; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 25px; font-variant-numeric: tabular-nums; line-height: 1; }
.dashboard-metrics .overview-metric small { margin: 0; color: #8795A5; font-size: 10px; line-height: 1.35; }
.overview-primary-grid { grid-template-columns: minmax(0, 1.18fr) minmax(310px, .82fr); gap: 16px; }
.overview-primary-grid .overview-panel, .activity-panel { border-radius: 6px; box-shadow: 0 1px 3px rgba(24, 32, 41, .04); }
.preparation-summary { padding: 17px 19px 16px; background: #F5F6F8; border-bottom: 1px solid #D9DEE3; }
.preparation-summary > div:first-child { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; }
.preparation-summary strong { color: #182029; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 25px; font-variant-numeric: tabular-nums; }
.preparation-summary small { color: #566474; font-size: 10px; }
.preparation-summary p { margin: 6px 0 0; color: #566474; font-size: 11px; line-height: 1.5; }
.preparation-track { height: 6px; margin-top: 14px; overflow: hidden; background: #D9DEE3; }
.preparation-track i { display: block; height: 100%; min-width: 3px; background: #B85C45; transition: width .2s ease; }
.panel-action-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 19px; border-top: 1px solid #EEF1F3; }
.panel-action-row small { color: #8795A5; font-size: 10px; }
.panel-action-row .primary-action { min-height: 32px; padding: 0 12px; border-radius: 4px; font-size: 11px; }
.health-list { padding: 5px 0; }
.health-row { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 11px; min-height: 67px; padding: 10px 19px; border-bottom: 1px solid #EEF1F3; }
.health-row:last-child { border-bottom: 0; }
.health-mark { display: grid; place-items: center; width: 26px; height: 26px; border: 1px solid #D9DEE3; border-radius: 4px; color: #8795A5; font-size: 12px; font-weight: 700; }
.health-success .health-mark { border-color: #BBD6CC; background: #EAF2EF; color: #3F806F; }
.health-info .health-mark { border-color: #C5D8E3; background: #EEF4F7; color: #3B6F8F; }
.health-warning .health-mark { border-color: #EED8AF; background: #FBF4E7; color: #D19A3B; }
.health-row strong, .health-row small { display: block; }
.health-row strong { color: #182029; font-size: 11px; }
.health-row small { margin-top: 4px; color: #8795A5; font-size: 10px; line-height: 1.4; }
.health-row b { color: #566474; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 10px; font-weight: 500; }
.health-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 19px; border-top: 1px solid #EEF1F3; color: #8795A5; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 9px; }
.health-footer .text-button { font-family: inherit; }
.activity-panel { grid-column: 1 / -1; }
.activity-panel .signal-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.activity-panel .signal-row { min-height: 70px; border-right: 1px solid #EEF1F3; }
.activity-panel .signal-row:nth-child(3n) { border-right: 0; }
@media (max-width: 1120px) {
  .overview-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .dashboard-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 900px) {
  .overview-hero, .overview-content-grid { grid-template-columns: 1fr; }
  .readiness-card { border-top: 1px solid #EEF1F3; border-left: 0; padding: 18px 0 0; }
  .readiness-card.readiness-warning { border-color: #EEF1F3; }
}
@media (max-width: 640px) {
  .overview-page { gap: 14px; }
  .overview-hero { padding: 21px 18px; }
  .overview-hero h2 { font-size: 21px; }
  .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .dashboard-metrics { grid-template-columns: 1fr; gap: 10px; }
  .activity-panel .signal-list { grid-template-columns: 1fr; }
  .activity-panel .signal-row { border-right: 0; }
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
