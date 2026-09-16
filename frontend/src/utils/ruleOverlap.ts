export type RuleOverlapKind = 'duplicate' | 'override' | 'conflict'

export interface ConfiguredRule {
  id: number
  rule_code: string
  scope_type: string
  scope_code?: string | null
  int_value?: number | null
  text_value?: string | null
  severity: string
  weight: number
}

export interface RuleOverlapHint {
  id: string
  kind: RuleOverlapKind
  title: string
  detail: string
  nextStep: string
  actionLabel: string
  actionPath: string
  ruleIds: number[]
  ruleCode: string
}

const MAX_RULES = new Set(['TEACHER_DAILY_MAX', 'STUDENT_GROUP_DAILY_MAX', 'SUBJECT_DAILY_MAX'])
const MIN_RULES = new Set(['SUBJECT_MIN_SPREAD_DAYS'])

const SCOPE_LABELS: Record<string, string> = {
  TERM: '全校',
  TEACHER: '指定教师',
  STUDENT_GROUP: '指定班级',
  SUBJECT: '指定课程',
  TEACHING_REQUIREMENT: '指定教学需求',
}

export function scopeLabel(scopeType?: string | null, scopeCode?: string | null) {
  const type = SCOPE_LABELS[scopeType ?? ''] ?? scopeType ?? '未知范围'
  return scopeType === 'TERM' || !scopeCode ? type : `${type} ${scopeCode}`
}

function severityRank(severity?: string | null) {
  if (severity === 'HARD') return 3
  if (severity === 'MEDIUM') return 2
  if (severity === 'SOFT') return 1
  return 0
}

function sameValue(left: ConfiguredRule, right: ConfiguredRule) {
  return (left.int_value ?? null) === (right.int_value ?? null)
    && (left.text_value ?? null) === (right.text_value ?? null)
    && left.severity === right.severity
    && left.weight === right.weight
}

function resourceWeakerThanTerm(ruleCode: string, termRule: ConfiguredRule, resourceRule: ConfiguredRule) {
  const looserSeverity = severityRank(resourceRule.severity) < severityRank(termRule.severity)
  if (MAX_RULES.has(ruleCode) && termRule.int_value != null && resourceRule.int_value != null) {
    return resourceRule.int_value > termRule.int_value || (resourceRule.int_value === termRule.int_value && looserSeverity)
  }
  if (MIN_RULES.has(ruleCode) && termRule.int_value != null && resourceRule.int_value != null) {
    return resourceRule.int_value < termRule.int_value || (resourceRule.int_value === termRule.int_value && looserSeverity)
  }
  return looserSeverity
}

function hintId(kind: RuleOverlapKind, ruleCode: string, left: ConfiguredRule, right: ConfiguredRule) {
  return `${kind}:${ruleCode}:${[left.id, right.id].sort((a, b) => a - b).join('-')}`
}

export function detectRuleOverlaps(
  rules: ConfiguredRule[],
  labels: Record<string, string> = {},
): RuleOverlapHint[] {
  const hints: RuleOverlapHint[] = []
  const grouped = new Map<string, ConfiguredRule[]>()
  for (const rule of rules) {
    const key = rule.rule_code
    const bucket = grouped.get(key) ?? []
    bucket.push(rule)
    grouped.set(key, bucket)
  }

  for (const [ruleCode, items] of grouped) {
    const label = labels[ruleCode] ?? ruleCode
    const byScope = new Map<string, ConfiguredRule[]>()
    for (const item of items) {
      const key = `${item.scope_type}:${item.scope_code ?? ''}`
      const bucket = byScope.get(key) ?? []
      bucket.push(item)
      byScope.set(key, bucket)
    }
    for (const scoped of byScope.values()) {
      if (scoped.length < 2) continue
      const [left, right] = scoped
      hints.push({
        id: hintId('duplicate', ruleCode, left, right),
        kind: 'duplicate',
        title: `${label}重复配置了两次`,
        detail: `${scopeLabel(left.scope_type, left.scope_code)}已经有同一条规则。重复保存不会叠加成两条更严的限制，只会造成核对困难。`,
        nextStep: '请删除多余的一条，只保留学校真正要用的那条。系统不会自动停用任何规则。',
        actionLabel: '查看已生效规则',
        actionPath: '#quality-rules',
        ruleIds: scoped.map(item => item.id),
        ruleCode,
      })
    }

    const termRules = items.filter(item => item.scope_type === 'TERM')
    const resourceRules = items.filter(item => item.scope_type !== 'TERM')
    for (const termRule of termRules) {
      for (const resourceRule of resourceRules) {
        const weaker = resourceWeakerThanTerm(ruleCode, termRule, resourceRule)
        if (weaker && termRule.severity === 'HARD') {
          hints.push({
            id: hintId('conflict', ruleCode, termRule, resourceRule),
            kind: 'conflict',
            title: `${label}的个别规则更松，挡不住全校硬上限`,
            detail: `全校规则是硬约束（${termRule.int_value ?? termRule.text_value ?? termRule.severity}），${scopeLabel(resourceRule.scope_type, resourceRule.scope_code)}的规则更松。排课时仍按全校硬上限执行，个别规则不会形成例外。`,
            nextStep: '若学校确实要给这位教师/班级开口子，需要先放宽或取消全校硬上限；否则删掉这条更松的个别规则，避免误以为已经特批。',
            actionLabel: '核对这两条规则',
            actionPath: '#quality-rules',
            ruleIds: [termRule.id, resourceRule.id],
            ruleCode,
          })
          continue
        }
        if (sameValue(termRule, resourceRule)) {
          hints.push({
            id: hintId('override', ruleCode, termRule, resourceRule),
            kind: 'override',
            title: `${label}的个别规则与全校规则重复`,
            detail: `${scopeLabel(resourceRule.scope_type, resourceRule.scope_code)}上的设置和全校规则相同。它不会额外收紧课表，只是重复写了一遍。`,
            nextStep: '如果没有单独例外，删除个别规则即可；全校规则会继续对所有人生效。',
            actionLabel: '查看已生效规则',
            actionPath: '#quality-rules',
            ruleIds: [termRule.id, resourceRule.id],
            ruleCode,
          })
          continue
        }
        hints.push({
          id: hintId('override', ruleCode, termRule, resourceRule),
          kind: 'override',
          title: `${label}同时有全校规则和个别规则`,
          detail: `全校规则会对所有人生效；${scopeLabel(resourceRule.scope_type, resourceRule.scope_code)}还会再套一条。两条都会参与排课，不是后者覆盖前者。`,
          nextStep: '请确认个别规则是学校要单独收紧的例外。如果只是误填，删除其中一条后再自动排课。',
          actionLabel: '核对这两条规则',
          actionPath: '#quality-rules',
          ruleIds: [termRule.id, resourceRule.id],
          ruleCode,
        })
      }
    }
  }

  const rank = { conflict: 0, duplicate: 1, override: 2 }
  return hints.sort((left, right) => rank[left.kind] - rank[right.kind] || left.ruleCode.localeCompare(right.ruleCode))
}
