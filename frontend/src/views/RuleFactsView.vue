<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

type AvailabilityItem = { resourceType: string; resourceCode: string; periodCode: string; available: boolean }
type FeatureItem = { code: string; name: string; active: boolean }
type RoomFeatureItem = { roomCode: string; featureCode: string; featureName: string }
type RequirementFeatureItem = { requirementCode: string; featureCode: string }
type ActivityGroupItem = { code: string; name: string; activityType: string; requirementCodes: string[] }
type RuleCatalogItem = { ruleCode: string; label: string; valueType: 'INTEGER' | 'TEXT'; scopes: string[] }
type RuleItem = { id: number; rule_code: string; scope_type: string; scope_code?: string; int_value?: number; text_value?: string; severity: string; weight: number }
type RuleExplanation = { summary: string; impact: string; scope: string }

const RULE_EXPLANATIONS: Record<string, RuleExplanation> = {
  TEACHER_DAILY_MAX: { summary: '限制同一教师每天可承担的最大课时数。', impact: '超过阈值会挤压教师备课与休息时间；设置为 HARD 时会阻止发布。', scope: '学期或指定教师' },
  STUDENT_GROUP_DAILY_MAX: { summary: '限制同一班级每天可安排的最大课时数。', impact: '用于避免学生单日课程过密；设置为 HARD 时会阻止发布。', scope: '学期或指定班级' },
  SUBJECT_DAILY_MAX: { summary: '控制同一课程在一天内最多出现的次数。', impact: '用于分散课程安排，减少同一学科连续堆叠。', scope: '学期或指定课程' },
  SUBJECT_MIN_SPREAD_DAYS: { summary: '尽量把同一课程分散到指定数量的工作日。', impact: '有助于形成均衡教学节奏，通常作为优化目标参与评分。', scope: '学期或指定课程' },
  TEACHER_GAP_POLICY: { summary: '控制教师课表中的空档策略。', impact: '可减少无意义的等待时段，提升教师日程连续性。', scope: '学期或指定教师' },
  TEACHER_PREFERRED_PERIOD: { summary: '记录教师偏好的节次，供求解器进行软约束优化。', impact: '偏好未满足不会阻止发布，但会影响方案质量评分。', scope: '指定教师' },
}

const term = useTermStore()
const resourceType = ref('TEACHER')
const resourceCode = ref('T001')
const termCode = computed(() => term.selectedTermCode.value)
const periodCode = ref('MON-1')
const available = ref(false)
const roomCode = ref('A101')
const featureCode = ref('')
const featureName = ref('')
const requirementCode = ref('REQ-1')
const activityCode = ref('')
const activityName = ref('')
const activityType = ref('JOINED')
const requirementCodes = ref('')
const saving = ref(false)
const loadingList = ref(false)
const message = ref('')
const error = ref('')
const availability = ref<AvailabilityItem[]>([])
const features = ref<FeatureItem[]>([])
const roomFeatures = ref<RoomFeatureItem[]>([])
const requirementFeatures = ref<RequirementFeatureItem[]>([])
const activityGroups = ref<ActivityGroupItem[]>([])
const ruleCatalog = ref<RuleCatalogItem[]>([])
const rules = ref<RuleItem[]>([])
const ruleCode = ref('TEACHER_DAILY_MAX')
const ruleScopeType = ref('TERM')
const ruleScopeCode = ref('')
const ruleIntValue = ref(4)
const ruleTextValue = ref('')
const ruleSeverity = ref('HARD')
const ruleWeight = ref(1)
const selectedRule = computed(() => ruleCatalog.value.find(item => item.ruleCode === ruleCode.value))
const ruleScopes = computed(() => selectedRule.value?.scopes ?? [])
const ruleUsesText = computed(() => selectedRule.value?.valueType === 'TEXT')
const selectedRuleExplanation = computed(() => RULE_EXPLANATIONS[ruleCode.value] ?? { summary: '该规则由当前后端规则目录提供。', impact: '请结合规则级别和权重确认它对发布的影响。', scope: '由规则目录决定' })
let loadSequence = 0

function normalizeRuleScope() {
  if (!ruleScopes.value.includes(ruleScopeType.value)) ruleScopeType.value = ruleScopes.value[0] ?? 'TERM'
  if (ruleScopeType.value === 'TERM') ruleScopeCode.value = ''
}

function requestFailure(label: string, reason: unknown) {
  const detail = reason instanceof Error ? reason.message : '请求失败'
  return `${label}（${detail}）`
}

function ruleLabel(code: string) {
  return ruleCatalog.value.find(item => item.ruleCode === code)?.label ?? code
}

function severityLabel(severity: string) {
  return ({ HARD: '硬约束', MEDIUM: '中度优化', SOFT: '软约束' }[severity] ?? severity)
}

async function loadRules() {
  if (!term.hasValidTerm.value) {
    rules.value = []
    return []
  }
  const failures: string[] = []
  const [catalogResult, rulesResult] = await Promise.allSettled([
    http<RuleCatalogItem[]>('/api/schedule-rules/catalog'),
    http<RuleItem[]>(`/api/schedule-rules?termCode=${encodeURIComponent(termCode.value)}`),
  ])
  if (catalogResult.status === 'fulfilled' && Array.isArray(catalogResult.value)) ruleCatalog.value = catalogResult.value
  else if (catalogResult.status === 'rejected') failures.push(requestFailure('质量规则目录', catalogResult.reason))
  if (rulesResult.status === 'fulfilled' && Array.isArray(rulesResult.value)) rules.value = rulesResult.value
  else if (rulesResult.status === 'rejected') failures.push(requestFailure('已配置质量规则', rulesResult.reason))
  normalizeRuleScope()
  return failures
}

async function saveRule() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    error.value = term.error.value || '暂无可用学期'
    return
  }
  await submit('/api/schedule-rules', {
    termCode: termCode.value, ruleCode: ruleCode.value, scopeType: ruleScopeType.value,
    scopeCode: ruleScopeCode.value || null, intValue: ruleUsesText.value ? null : ruleIntValue.value,
    textValue: ruleUsesText.value ? ruleTextValue.value : null, severity: ruleSeverity.value, weight: ruleWeight.value,
  })
  await loadRules()
}

async function deleteRule(id: number) {
  await remove(`/api/schedule-rules/${id}`)
  await loadRules()
}

async function loadAll() {
  const sequence = ++loadSequence
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    availability.value = []
    activityGroups.value = []
    rules.value = []
    error.value = term.error.value || '暂无可用学期'
    return
  }
  loadingList.value = true
  error.value = ''
  try {
    const failures: string[] = []
    const results = await Promise.allSettled([
      http<AvailabilityItem[]>(`/api/rule-facts/availability?termCode=${encodeURIComponent(termCode.value)}`),
      http<FeatureItem[]>('/api/rule-facts/features'),
      http<RoomFeatureItem[]>('/api/rule-facts/room-features'),
      http<RequirementFeatureItem[]>('/api/rule-facts/requirement-features'),
      http<ActivityGroupItem[]>(`/api/rule-facts/activity-groups?termCode=${encodeURIComponent(termCode.value)}`),
    ])
    const [availabilityResult, featureResult, roomFeatureResult, requirementFeatureResult, activityResult] = results
    if (sequence !== loadSequence) return
    const sections: Array<[string, PromiseSettledResult<unknown>, (value: any) => void]> = [
      ['资源可用性', availabilityResult, value => { if (Array.isArray(value)) availability.value = value }],
      ['特征目录', featureResult, value => { if (Array.isArray(value)) features.value = value }],
      ['教室特征绑定', roomFeatureResult, value => { if (Array.isArray(value)) roomFeatures.value = value }],
      ['需求特征绑定', requirementFeatureResult, value => { if (Array.isArray(value)) requirementFeatures.value = value }],
      ['活动组', activityResult, value => { if (Array.isArray(value)) activityGroups.value = value.map(item => ({ ...item, requirementCodes: Array.isArray(item.requirementCodes) ? item.requirementCodes : [] })) }],
    ]
    for (const [label, result, apply] of sections) {
      if (result.status === 'fulfilled') apply(result.value)
      else failures.push(requestFailure(label, result.reason))
    }
    failures.push(...await loadRules())
    error.value = failures.length ? `部分规则事实无法加载：${failures.join('、')}` : ''
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '规则事实加载失败'
  } finally {
    loadingList.value = false
  }
}

async function submit(url: string, body: unknown) {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    await http(url, jsonRequest('POST', body))
    message.value = url === '/api/schedule-rules' ? '规则已保存' : '规则事实已保存'
    await loadAll()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '规则事实保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(url: string, init?: RequestInit) {
  error.value = ''
  try {
    await http<void>(url, init ?? { method: 'DELETE' })
    message.value = '规则事实已删除'
    await loadAll()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '规则事实删除失败'
  }
}

async function saveAvailability() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    error.value = term.error.value || '暂无可用学期'
    return
  }
  return submit(`/api/rule-facts/availability/${resourceType.value}`, { resourceCode: resourceCode.value, termCode: termCode.value, periodCode: periodCode.value, available: available.value })
}
function saveRoomFeature() { return submit('/api/rule-facts/room-features', { roomCode: roomCode.value, featureCode: featureCode.value, featureName: featureName.value }) }
function saveRequirementFeature() { return submit('/api/rule-facts/requirement-features', { requirementCode: requirementCode.value, featureCode: featureCode.value }) }
async function saveActivity() {
  await term.loadTerms()
  if (!term.hasValidTerm.value) {
    error.value = term.error.value || '暂无可用学期'
    return
  }
  return submit('/api/rule-facts/activity-groups', { code: activityCode.value, name: activityName.value, activityType: activityType.value, requirementCodes: requirementCodes.value.split(',').map(value => value.trim()).filter(Boolean), termCode: termCode.value })
}
function deleteAvailability(item: AvailabilityItem) { return remove(`/api/rule-facts/availability/${item.resourceType}`, jsonRequest('DELETE', { resourceCode: item.resourceCode, termCode: termCode.value, periodCode: item.periodCode, available: item.available })) }
function deleteRoomFeature(item: RoomFeatureItem) { return remove(`/api/rule-facts/room-features?roomCode=${encodeURIComponent(item.roomCode)}&featureCode=${encodeURIComponent(item.featureCode)}`) }
function deleteRequirementFeature(item: RequirementFeatureItem) { return remove(`/api/rule-facts/requirement-features?requirementCode=${encodeURIComponent(item.requirementCode)}&featureCode=${encodeURIComponent(item.featureCode)}`) }
function deleteActivityGroup(item: ActivityGroupItem) { return remove(`/api/rule-facts/activity-groups/${encodeURIComponent(item.code)}`) }

watch(() => term.selectedTermCode.value, () => void loadAll())
onMounted(() => void loadAll())
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">RULE FACTS / PLAN</p>
      <h1>规则中心与质量约束</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● 可写配置</span>
      <div class="avatar">{{ activityGroups.length }}</div>
    </div>
  </header>

  <div v-if="message" class="inline-message success-message">{{ message }}</div>
  <div v-if="error" class="inline-message error-message">{{ error }}</div>

  <section class="rules-page">
    <!-- 资源可用性 -->
    <article class="panel rule-card canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">AVAILABILITY</span>
          <h2>资源可用性 (教师/教室时间锁定)</h2>
        </div>
      </div>
      <div class="rule-form">
        <label>
          <span>资源类型</span>
          <select v-model="resourceType" class="styled-select">
            <option value="TEACHER">教师</option>
            <option value="ROOM">教室</option>
            <option value="STUDENT_GROUP">班级</option>
          </select>
        </label>
        <label>
          <span>资源编码</span>
          <input v-model="resourceCode" class="styled-input" placeholder="如 T001, A101" />
        </label>
        <label>
          <span>当前学期</span>
          <input :value="termCode" class="styled-input" disabled />
        </label>
        <label>
          <span>节次编码</span>
          <input v-model="periodCode" class="styled-input" placeholder="如 MON-1, TUE-2" />
        </label>
        <label class="check-line">
          <input v-model="available" type="checkbox" />
          <span>时段允许排课 (勾选为可用，取消为锁定不可排)</span>
        </label>
        <button class="rule-btn" :disabled="saving" @click="saveAvailability">保存可用性配置</button>
      </div>
    </article>

    <!-- 教室特征绑定 -->
    <article class="panel rule-card canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">ROOM FEATURE</span>
          <h2>教室特征绑定 (专业教室)</h2>
        </div>
      </div>
      <div class="rule-form">
        <label>
          <span>教室编码</span>
          <input v-model="roomCode" class="styled-input" placeholder="如 A101" />
        </label>
        <label>
          <span>特征编码</span>
          <input v-model="featureCode" class="styled-input" placeholder="如 LAB, MULTIMEDIA" />
        </label>
        <label>
          <span>特征名称</span>
          <input v-model="featureName" class="styled-input" placeholder="如 物理实验室, 多媒体教室" />
        </label>
        <button class="rule-btn" :disabled="saving || !featureCode" @click="saveRoomFeature">绑定教室特征</button>
      </div>
    </article>

    <!-- 教学需求特征 -->
    <article class="panel rule-card canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">REQUIREMENT FEATURE</span>
          <h2>教学需求场地特征要求</h2>
        </div>
      </div>
      <div class="rule-form">
        <label>
          <span>教学需求编码</span>
          <input v-model="requirementCode" class="styled-input" placeholder="如 REQ-1" />
        </label>
        <label>
          <span>指定特征编码</span>
          <input v-model="featureCode" class="styled-input" placeholder="如 LAB" />
        </label>
        <button class="rule-btn" :disabled="saving || !requirementCode || !featureCode" @click="saveRequirementFeature">绑定需求特征</button>
      </div>
    </article>

    <!-- 合班与同步活动 -->
    <article class="panel rule-card canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">ACTIVITY GROUP</span>
          <h2>合班上课与同步活动</h2>
        </div>
      </div>
      <div class="rule-form">
        <label>
          <span>活动编码</span>
          <input v-model="activityCode" class="styled-input" placeholder="如 G7-JOINED-PHYS" />
        </label>
        <label>
          <span>活动名称</span>
          <input v-model="activityName" class="styled-input" placeholder="如 七年级大合班" />
        </label>
        <label>
          <span>活动类型</span>
          <select v-model="activityType" class="styled-select">
            <option value="JOINED">合班 (同一教师同一教室)</option>
            <option value="SYNCHRONIZED">同步 (同时间不同班级)</option>
            <option value="CONSECUTIVE">连堂</option>
          </select>
        </label>
        <label>
          <span>教学需求编码</span>
          <input v-model="requirementCodes" class="styled-input" placeholder="多个需求用逗号分隔，如 REQ-1, REQ-2" />
        </label>
        <button class="rule-btn" :disabled="saving || !activityCode || !requirementCodes" @click="saveActivity">保存活动组规则</button>
      </div>
    </article>

    <!-- 质量规则 -->
    <article class="panel rule-card canvas-card">
      <div class="panel-heading">
        <div>
          <span class="eyebrow">TYPED RULES</span>
          <h2>求解器软/硬质量规则</h2>
        </div>
      </div>
      <div class="rule-form">
        <label>
          <span>规则类型</span>
          <select v-model="ruleCode" class="styled-select" @change="normalizeRuleScope">
            <option v-for="item in ruleCatalog" :key="item.ruleCode" :value="item.ruleCode">{{ item.label }}</option>
          </select>
        </label>
        <label>
          <span>生效作用域</span>
          <select v-model="ruleScopeType" class="styled-select" @change="normalizeRuleScope">
            <option v-for="scope in ruleScopes" :key="scope" :value="scope">{{ scope }}</option>
          </select>
        </label>
        <label v-if="ruleScopeType !== 'TERM'">
          <span>指定资源编码</span>
          <input v-model="ruleScopeCode" class="styled-input" placeholder="输入教师/班级/课程编码" />
        </label>
        <label v-if="!ruleUsesText">
          <span>整数参数 (阈值)</span>
          <input v-model.number="ruleIntValue" class="styled-input" type="number" min="1" />
        </label>
        <label v-else>
          <span>文本参数</span>
          <input v-model="ruleTextValue" class="styled-input" placeholder="如 NO_SINGLE_GAP 或 MON-1,TUE-2" />
        </label>
        <label>
          <span>约束级别 (Severity)</span>
          <select v-model="ruleSeverity" class="styled-select">
            <option value="HARD">HARD · 绝对硬约束 (违背则不可发布)</option>
            <option value="MEDIUM">MEDIUM · 中度优化 (计入惩罚分)</option>
            <option value="SOFT">SOFT · 偏好软约束 (尽量满足)</option>
          </select>
        </label>
        <label>
          <span>规则权重</span>
          <input v-model.number="ruleWeight" class="styled-input" type="number" min="1" />
        </label>
        <div v-if="selectedRule" class="rule-explanation" data-testid="rule-explanation">
          <div class="explanation-heading">
            <strong>{{ selectedRule.label }}</strong>
            <span class="severity-pill" :class="ruleSeverity.toLowerCase()">{{ severityLabel(ruleSeverity) }}</span>
          </div>
          <p>{{ selectedRuleExplanation.summary }}</p>
          <small>{{ selectedRuleExplanation.impact }}</small>
          <div class="explanation-meta"><span>作用域：{{ selectedRuleExplanation.scope }}</span><span>参数类型：{{ selectedRule.valueType === 'INTEGER' ? '整数阈值' : '文本策略' }}</span></div>
        </div>
        <button class="rule-btn" :disabled="saving || !ruleCode" @click="saveRule">保存质量规则</button>
      </div>
    </article>
  </section>

  <!-- 已配置规则列表 -->
  <section class="panel rule-list-panel canvas-card">
    <div class="panel-heading">
      <div>
        <span class="eyebrow">CONFIGURED FACTS</span>
        <h2>当前学期已生效的规则事实</h2>
      </div>
      <el-button plain size="small" :loading="loadingList" @click="loadAll">刷新规则库</el-button>
    </div>
    <div class="facts-grid">
      <div class="fact-column">
        <h3>可用性限制 · {{ availability.length }}</h3>
        <div v-for="(item, index) in availability" :key="`a-${index}`" class="rule-list-row">
          <span>{{ item.resourceType }} · {{ item.resourceCode }}</span>
          <small>{{ item.periodCode }} · {{ item.available ? '可用' : '锁定不可用' }} <button class="del-btn" @click="deleteAvailability(item)">删除</button></small>
        </div>
      </div>
      <div class="fact-column">
        <h3>特征目录与场地 · {{ features.length }}</h3>
        <div v-for="item in features" :key="item.code" class="rule-list-row">
          <span>{{ item.code }}</span>
          <small>{{ item.name }}</small>
        </div>
        <div v-for="item in roomFeatures" :key="`${item.roomCode}-${item.featureCode}`" class="rule-list-row">
          <span>{{ item.roomCode }} · {{ item.featureCode }}</span>
          <small>{{ item.featureName }} <button class="del-btn" @click="deleteRoomFeature(item)">解绑</button></small>
        </div>
      </div>
      <div class="fact-column">
        <h3>教学需求场地绑定 · {{ requirementFeatures.length }}</h3>
        <div v-for="item in requirementFeatures" :key="`${item.requirementCode}-${item.featureCode}`" class="rule-list-row">
          <span>{{ item.requirementCode }}</span>
          <small>{{ item.featureCode }} <button class="del-btn" @click="deleteRequirementFeature(item)">删除</button></small>
        </div>
      </div>
      <div class="fact-column">
        <h3>活动组与合班 · {{ activityGroups.length }}</h3>
        <div v-for="item in activityGroups" :key="item.code" class="rule-list-row">
          <span>{{ item.code }} · {{ item.activityType }}</span>
          <small>{{ item.requirementCodes.join(', ') }} <button class="del-btn" @click="deleteActivityGroup(item)">删除</button></small>
        </div>
      </div>
      <div class="fact-column">
        <h3>高级质量规则 · {{ rules.length }}</h3>
        <div v-for="item in rules" :key="item.id" class="rule-list-row">
          <span>{{ ruleLabel(item.rule_code) }} · {{ item.scope_type }}{{ item.scope_code ? `:${item.scope_code}` : '' }}</span>
          <small><span class="severity-pill" :class="item.severity.toLowerCase()">{{ severityLabel(item.severity) }}</span> · {{ item.int_value ?? item.text_value }} · w{{ item.weight }} · 当前生效 · 规则中心 <button class="del-btn" @click="deleteRule(item.id)">删除</button></small>
        </div>
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
.styled-input, .styled-select {
  border: 1px solid #dce4e0;
  padding: 8px 10px;
  background: #fbfdfc;
  color: #191c1d;
  border-radius: 6px;
  font-size: 12.5px;
  outline: none;
  transition: all 0.2s;
}
.styled-input:focus, .styled-select:focus {
  background: #ffffff;
  border-color: #173b36;
  box-shadow: 0 0 0 2px rgba(23, 59, 54, 0.1);
}
.rule-btn {
  border: 0;
  background: #173b36;
  color: #ffffff;
  padding: 9px 14px;
  border-radius: 6px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.rule-btn:hover:not(:disabled) {
  background: #12302c;
  box-shadow: 0 4px 12px rgba(23, 59, 54, 0.2);
}
.rule-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.del-btn {
  border: 0;
  background: transparent;
  color: #dc2626;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  margin-left: 6px;
}
.del-btn:hover {
  text-decoration: underline;
}
.fact-column {
  background: #fafcfb;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #edf2ef;
}
.rule-explanation {
  grid-column: 1 / -1;
  padding: 11px 13px;
  border: 1px solid #d9e8e0;
  border-left: 3px solid #4d8a78;
  border-radius: 7px;
  background: #f4faf7;
}
.explanation-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.rule-explanation p {
  margin: 6px 0 3px;
  color: #173b36;
  font-size: 12px;
  font-weight: 600;
}
.rule-explanation small {
  display: block;
  color: #587069;
  font-size: 11px;
  line-height: 1.5;
}
.explanation-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 8px;
  color: #50605c;
  font-size: 11px;
}
.severity-pill {
  display: inline-flex;
  align-items: center;
  border-radius: 9999px;
  padding: 2px 7px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
}
.severity-pill.hard { background: #fee2e2; color: #b91c1c; }
.severity-pill.medium { background: #fef3c7; color: #92400e; }
.severity-pill.soft { background: #dbeafe; color: #1d4ed8; }
.inline-message {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 12px;
}
.success-message {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}
.error-message {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
}
@media (max-width: 720px) {
  .explanation-heading { align-items: flex-start; flex-direction: column; }
}
</style>
