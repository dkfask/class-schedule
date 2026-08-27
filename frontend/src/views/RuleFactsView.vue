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

function normalizeRuleScope() {
  if (!ruleScopes.value.includes(ruleScopeType.value)) ruleScopeType.value = ruleScopes.value[0] ?? 'TERM'
  if (ruleScopeType.value === 'TERM') ruleScopeCode.value = ''
}

function requestFailure(label: string, reason: unknown) {
  const detail = reason instanceof Error ? reason.message : '请求失败'
  return `${label}（${detail}）`
}

async function loadRules() {
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
  loadingList.value = true
  error.value = ''
  try {
    const failures: string[] = []
    const results = await Promise.allSettled([
      http<AvailabilityItem[]>(`/api/rule-facts/availability?termCode=${termCode.value}`),
      http<FeatureItem[]>('/api/rule-facts/features'),
      http<RoomFeatureItem[]>('/api/rule-facts/room-features'),
      http<RequirementFeatureItem[]>('/api/rule-facts/requirement-features'),
      http<ActivityGroupItem[]>(`/api/rule-facts/activity-groups?termCode=${encodeURIComponent(termCode.value)}`),
    ])
    const [availabilityResult, featureResult, roomFeatureResult, requirementFeatureResult, activityResult] = results
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

function saveAvailability() { return submit(`/api/rule-facts/availability/${resourceType.value}`, { resourceCode: resourceCode.value, termCode: termCode.value, periodCode: periodCode.value, available: available.value }) }
function saveRoomFeature() { return submit('/api/rule-facts/room-features', { roomCode: roomCode.value, featureCode: featureCode.value, featureName: featureName.value }) }
function saveRequirementFeature() { return submit('/api/rule-facts/requirement-features', { requirementCode: requirementCode.value, featureCode: featureCode.value }) }
function saveActivity() { return submit('/api/rule-facts/activity-groups', { code: activityCode.value, name: activityName.value, activityType: activityType.value, requirementCodes: requirementCodes.value.split(',').map(value => value.trim()).filter(Boolean), termCode: termCode.value }) }
function deleteAvailability(item: AvailabilityItem) { return remove(`/api/rule-facts/availability/${item.resourceType}`, jsonRequest('DELETE', { resourceCode: item.resourceCode, termCode: termCode.value, periodCode: item.periodCode, available: item.available })) }
function deleteRoomFeature(item: RoomFeatureItem) { return remove(`/api/rule-facts/room-features?roomCode=${encodeURIComponent(item.roomCode)}&featureCode=${encodeURIComponent(item.featureCode)}`) }
function deleteRequirementFeature(item: RequirementFeatureItem) { return remove(`/api/rule-facts/requirement-features?requirementCode=${encodeURIComponent(item.requirementCode)}&featureCode=${encodeURIComponent(item.featureCode)}`) }
function deleteActivityGroup(item: ActivityGroupItem) { return remove(`/api/rule-facts/activity-groups/${encodeURIComponent(item.code)}`) }

watch(() => term.selectedTermCode.value, () => void loadAll())
onMounted(() => void loadAll())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">RULE FACTS / PLAN</p><h1>规则事实</h1></div><div class="top-actions"><span class="sync-state">● 可写配置</span><div class="avatar">{{ activityGroups.length }}</div></div></header>
  <div v-if="message" class="inline-message">{{ message }}</div><div v-if="error" class="inline-message error-message">{{ error }}</div>
  <section class="rules-page">
    <article class="panel rule-card"><div class="panel-heading"><div><span class="eyebrow">AVAILABILITY</span><h2>资源可用性</h2></div></div><div class="rule-form"><label>资源类型<select v-model="resourceType"><option value="TEACHER">教师</option><option value="ROOM">教室</option><option value="STUDENT_GROUP">班级</option></select></label><label>资源编码<input v-model="resourceCode" /></label><label>当前学期<input :value="termCode" disabled /></label><label>节次编码<input v-model="periodCode" /></label><label class="check-line"><input v-model="available" type="checkbox" /> 可用</label><button :disabled="saving" @click="saveAvailability">保存可用性</button></div></article>
    <article class="panel rule-card"><div class="panel-heading"><div><span class="eyebrow">ROOM FEATURE</span><h2>教室特征绑定</h2></div></div><div class="rule-form"><label>教室编码<input v-model="roomCode" /></label><label>特征编码<input v-model="featureCode" placeholder="如 LAB" /></label><label>特征名称<input v-model="featureName" placeholder="如 实验室" /></label><button :disabled="saving || !featureCode" @click="saveRoomFeature">绑定教室特征</button></div></article>
    <article class="panel rule-card"><div class="panel-heading"><div><span class="eyebrow">REQUIREMENT FEATURE</span><h2>教学需求特征</h2></div></div><div class="rule-form"><label>教学需求编码<input v-model="requirementCode" /></label><label>特征编码<input v-model="featureCode" /></label><button :disabled="saving || !requirementCode || !featureCode" @click="saveRequirementFeature">绑定需求特征</button></div></article>
    <article class="panel rule-card"><div class="panel-heading"><div><span class="eyebrow">ACTIVITY GROUP</span><h2>合班与同步活动</h2></div></div><div class="rule-form"><label>活动编码<input v-model="activityCode" /></label><label>活动名称<input v-model="activityName" /></label><label>活动类型<select v-model="activityType"><option value="JOINED">合班</option><option value="SYNCHRONIZED">同步</option><option value="CONSECUTIVE">连堂</option></select></label><label>教学需求编码<input v-model="requirementCodes" placeholder="多个编码用逗号分隔" /></label><button :disabled="saving || !activityCode || !requirementCodes" @click="saveActivity">保存活动组</button></div></article>
    <article class="panel rule-card"><div class="panel-heading"><div><span class="eyebrow">TYPED RULES</span><h2>质量规则</h2></div></div><div class="rule-form"><label>规则编码<select v-model="ruleCode" @change="normalizeRuleScope"><option v-for="item in ruleCatalog" :key="item.ruleCode" :value="item.ruleCode">{{ item.label }}</option></select></label><label>作用域<select v-model="ruleScopeType" @change="normalizeRuleScope"><option v-for="scope in ruleScopes" :key="scope" :value="scope">{{ scope }}</option></select></label><label v-if="ruleScopeType !== 'TERM'">资源编码<input v-model="ruleScopeCode" /></label><label v-if="!ruleUsesText">整数参数<input v-model.number="ruleIntValue" type="number" min="1" /></label><label v-else>文本参数<input v-model="ruleTextValue" placeholder="如 NO_SINGLE_GAP 或 MON-1,TUE-2" /></label><label>级别<select v-model="ruleSeverity"><option value="HARD">HARD · 阻止发布</option><option value="MEDIUM">MEDIUM · 报告</option><option value="SOFT">SOFT · 报告</option></select></label><label>权重<input v-model.number="ruleWeight" type="number" min="1" /></label><button :disabled="saving || !ruleCode" @click="saveRule">保存质量规则</button></div></article>
  </section>
  <section class="panel rule-list-panel"><div class="panel-heading"><div><span class="eyebrow">CONFIGURED FACTS</span><h2>已配置规则</h2></div><el-button plain size="small" :loading="loadingList" @click="loadAll">刷新</el-button></div><div class="facts-grid">
    <div><h3>可用性 · {{ availability.length }}</h3><div v-for="(item, index) in availability" :key="`a-${index}`" class="rule-list-row"><span>{{ item.resourceType }} · {{ item.resourceCode }}</span><small>{{ item.periodCode }} · {{ item.available ? '可用' : '不可用' }} <button @click="deleteAvailability(item)">删除</button></small></div></div>
    <div><h3>特征目录 · {{ features.length }}</h3><div v-for="item in features" :key="item.code" class="rule-list-row"><span>{{ item.code }}</span><small>{{ item.name }}</small></div><div v-for="item in roomFeatures" :key="`${item.roomCode}-${item.featureCode}`" class="rule-list-row"><span>{{ item.roomCode }} · {{ item.featureCode }}</span><small>{{ item.featureName }} <button @click="deleteRoomFeature(item)">解绑</button></small></div></div>
    <div><h3>需求特征 · {{ requirementFeatures.length }}</h3><div v-for="item in requirementFeatures" :key="`${item.requirementCode}-${item.featureCode}`" class="rule-list-row"><span>{{ item.requirementCode }}</span><small>{{ item.featureCode }} <button @click="deleteRequirementFeature(item)">删除</button></small></div></div>
    <div><h3>活动组 · {{ activityGroups.length }}</h3><div v-for="item in activityGroups" :key="item.code" class="rule-list-row"><span>{{ item.code }} · {{ item.activityType }}</span><small>{{ item.requirementCodes.join(', ') }} <button @click="deleteActivityGroup(item)">删除</button></small></div></div>
    <div><h3>质量规则 · {{ rules.length }}</h3><div v-for="item in rules" :key="item.id" class="rule-list-row"><span>{{ item.rule_code }} · {{ item.scope_type }}{{ item.scope_code ? `:${item.scope_code}` : '' }}</span><small>{{ item.severity }} · {{ item.int_value ?? item.text_value }} · w{{ item.weight }} <button @click="deleteRule(item.id)">删除</button></small></div></div>
  </div></section>
</template>
