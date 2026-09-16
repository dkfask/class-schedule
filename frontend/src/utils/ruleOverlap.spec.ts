import { describe, expect, it } from 'vitest'
import { detectRuleOverlaps, scopeLabel } from './ruleOverlap'

describe('detectRuleOverlaps', () => {
  it('flags a teacher rule that repeats the term-wide setting', () => {
    const hints = detectRuleOverlaps([
      { id: 1, rule_code: 'TEACHER_DAILY_MAX', scope_type: 'TERM', int_value: 4, severity: 'HARD', weight: 1 },
      { id: 2, rule_code: 'TEACHER_DAILY_MAX', scope_type: 'TEACHER', scope_code: 'T001', int_value: 4, severity: 'HARD', weight: 1 },
    ], { TEACHER_DAILY_MAX: '教师每日课时上限' })
    expect(hints).toHaveLength(1)
    expect(hints[0].kind).toBe('override')
    expect(hints[0].title).toContain('个别规则与全校规则重复')
    expect(hints[0].nextStep).toContain('删除个别规则')
    expect(hints[0].actionPath).toBe('#quality-rules')
  })

  it('warns when a looser teacher exception cannot override a hard term cap', () => {
    const hints = detectRuleOverlaps([
      { id: 1, rule_code: 'TEACHER_DAILY_MAX', scope_type: 'TERM', int_value: 4, severity: 'HARD', weight: 1 },
      { id: 2, rule_code: 'TEACHER_DAILY_MAX', scope_type: 'TEACHER', scope_code: 'T001', int_value: 6, severity: 'SOFT', weight: 1 },
    ], { TEACHER_DAILY_MAX: '教师每日课时上限' })
    expect(hints[0].kind).toBe('conflict')
    expect(hints[0].title).toContain('挡不住全校硬上限')
    expect(hints[0].detail).toContain('指定教师 T001')
    expect(hints[0].nextStep).toContain('放宽或取消全校硬上限')
  })

  it('explains stacked tighter resource rules without treating them as a replacement', () => {
    const hints = detectRuleOverlaps([
      { id: 1, rule_code: 'STUDENT_GROUP_DAILY_MAX', scope_type: 'TERM', int_value: 8, severity: 'SOFT', weight: 1 },
      { id: 2, rule_code: 'STUDENT_GROUP_DAILY_MAX', scope_type: 'STUDENT_GROUP', scope_code: 'G7-1', int_value: 6, severity: 'HARD', weight: 1 },
    ], { STUDENT_GROUP_DAILY_MAX: '班级每日课时上限' })
    expect(hints[0].kind).toBe('override')
    expect(hints[0].title).toContain('同时有全校规则和个别规则')
    expect(hints[0].detail).toContain('不是后者覆盖前者')
  })

  it('does not warn when only one rule exists', () => {
    expect(detectRuleOverlaps([
      { id: 1, rule_code: 'PREFER_ORIGINAL_SLOT', scope_type: 'TERM', int_value: 2, severity: 'SOFT', weight: 1 },
    ])).toEqual([])
  })

  it('labels term and teacher scopes in school language', () => {
    expect(scopeLabel('TERM')).toBe('全校')
    expect(scopeLabel('TEACHER', 'T001')).toBe('指定教师 T001')
  })
})
