<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { http } from '../api/http'
import { useTermStore } from '../stores/term'
import { buildFirstRunSteps, firstRunPrimaryStep, isFirstRunTerm } from '../utils/firstRun'

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
}

interface VersionPage {
  items?: VersionSummary[]
}

const term = useTermStore()
const overview = ref<OverviewData | null>(null)
const readiness = ref<SolveReadiness | null>(null)
const versions = ref<VersionSummary[]>([])
const loading = ref(false)
const errorMessage = ref('')
let loadSequence = 0

const resourceCounts = computed(() => ({
  teachers: overview.value?.teachers?.length ?? 0,
  studentGroups: overview.value?.studentGroups?.length ?? 0,
  subjects: overview.value?.subjects?.length ?? 0,
  rooms: overview.value?.rooms?.length ?? 0,
  periods: overview.value?.periods?.length ?? readiness.value?.timeslotCount ?? 0,
}))
const requirementCount = computed(() => readiness.value?.requirementCount ?? 0)
const hasCoreData = computed(() => resourceCounts.value.teachers > 0
  && resourceCounts.value.studentGroups > 0
  && resourceCounts.value.subjects > 0
  && resourceCounts.value.rooms > 0
  && (readiness.value
    ? readiness.value.roomCount > 0 && readiness.value.timeslotCount > 0
    : resourceCounts.value.periods > 0))
const publishedVersion = computed(() => versions.value.find(version => version.status === 'PUBLISHED'))
const blockingIssue = computed(() => readiness.value?.issues[0])
const firstRun = computed(() => isFirstRunTerm({
  isPlanner: true,
  hasPublished: Boolean(publishedVersion.value),
  hasCoreData: hasCoreData.value,
  requirementCount: requirementCount.value,
  ready: Boolean(readiness.value?.ready),
}))
const steps = computed(() => buildFirstRunSteps({
  hasCoreData: hasCoreData.value,
  requirementCount: requirementCount.value,
  ready: Boolean(readiness.value?.ready),
  blockingMessage: blockingIssue.value?.message,
  blockingCode: blockingIssue.value?.code,
}))
const currentStep = computed(() => firstRunPrimaryStep(steps.value))
const completedCount = computed(() => steps.value.filter(step => step.state === 'done').length)

async function loadSetup(termCode = term.selectedTermCode.value) {
  const sequence = ++loadSequence
  if (!termCode) {
    overview.value = null
    readiness.value = null
    versions.value = []
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
  loading.value = false
}

watch(() => term.selectedTermCode.value, (code) => { void loadSetup(code) })

onMounted(async () => {
  await term.loadTerms()
  await loadSetup()
})
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">学期准备</p>
      <h1>首次排课</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● {{ firstRun ? `当前进行到第 ${currentStep.number} 步` : '本学期已具备排课条件' }}</span>
      <div class="avatar">教</div>
    </div>
  </header>

  <section class="setup-page" data-testid="first-run-wizard">
    <div v-if="errorMessage" class="setup-alert" role="alert">{{ errorMessage }}</div>

    <div class="setup-hero panel canvas-card">
      <div>
        <span class="eyebrow">{{ firstRun ? '按顺序完成' : '准备已完成' }}</span>
        <h2>{{ firstRun ? '按这四步完成本学期课表' : '本学期已经可以继续排课' }}</h2>
        <p>{{ firstRun ? currentStep.detail : '基础数据、教学计划和前置检查都已就绪。如需再核对一遍，仍可从下面进入各步骤。' }}</p>
      </div>
      <div class="setup-hero-actions">
        <RouterLink class="primary-action" :to="firstRun ? currentStep.to : '/workspace'">
          {{ firstRun ? `现在处理：${currentStep.title}` : '进入自动排课' }}
          <span aria-hidden="true">→</span>
        </RouterLink>
        <RouterLink class="setup-secondary" to="/overview">返回学期总览</RouterLink>
      </div>
    </div>

    <ol class="setup-steps" :aria-busy="loading">
      <li v-for="step in steps" :key="step.number" :class="`setup-${step.state}`" :data-testid="`first-run-step-${step.number}`">
        <span>{{ step.number }}</span>
        <div>
          <strong>{{ step.title }}</strong>
          <small>{{ step.detail }}</small>
        </div>
        <RouterLink :to="step.to">{{ step.state === 'done' ? '已完成' : step.state === 'active' ? '现在处理' : '稍后' }}</RouterLink>
      </li>
    </ol>

    <p class="setup-progress">已完成 {{ completedCount }}/{{ steps.length }} 步。完成前请不要跳过当前步骤。</p>
  </section>
</template>

<style scoped>
.setup-page { display: grid; gap: 20px; }
.setup-alert { border: 1px solid #EED8AF; background: #fff8ec; color: #7a5728; padding: 13px 16px; font-size: 12px; }
.setup-hero { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; padding: 22px 24px; }
.setup-hero h2 { margin: 6px 0 8px; color: #2d5544; font-size: 22px; }
.setup-hero p { margin: 0; max-width: 36rem; color: #6d8075; font-size: 13px; line-height: 1.55; }
.setup-hero-actions { display: grid; gap: 10px; justify-items: end; }
.setup-secondary { color: #2d6a56; font-size: 12px; font-weight: 650; text-decoration: none; }
.setup-steps { display: grid; gap: 1px; margin: 0; padding: 0; list-style: none; background: #eef3f0; border: 1px solid #d7e4dd; }
.setup-steps li { display: grid; grid-template-columns: 48px minmax(0, 1fr) auto; gap: 16px; align-items: start; padding: 20px 22px; background: #fff; }
.setup-steps span { color: #8aa197; font-size: 13px; font-weight: 700; }
.setup-steps strong, .setup-steps small { display: block; }
.setup-steps strong { color: #2d5544; font-size: 15px; }
.setup-steps small { margin-top: 6px; color: #6d8075; font-size: 12px; line-height: 1.5; }
.setup-steps a { color: #2d6a56; font-size: 12px; font-weight: 650; text-decoration: none; white-space: nowrap; }
.setup-active { background: #f7fbf8; }
.setup-done span { color: #2a8658; }
.setup-progress { margin: 0; color: #6d8075; font-size: 12px; }
</style>
