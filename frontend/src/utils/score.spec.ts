import { describe, expect, it } from 'vitest'
import { describeScore, parseScore, resolveScore, versionStatusLabel } from './score'

describe('score utilities', () => {
  it.each([
    ['0hard/0soft', { hard: 0, medium: 0, soft: 0, valid: true }],
    ['-2hard/3medium/-4soft', { hard: -2, medium: 3, soft: -4, valid: true }],
    [' 0HARD / 0MEDIUM / 0SOFT ', { hard: 0, medium: 0, soft: 0, valid: true }],
  ])('parses %s', (raw, expected) => expect(parseScore(raw)).toEqual(expected))

  it.each(['', '  ', '等待结果', '0hard', '0medium/0soft', '0hard/0soft/1hard', '0hard/0soft/0unknown', '0hard/0hard/0soft', '0hard/0medium/0soft/0x'])('rejects invalid score %s', raw => {
    expect(parseScore(raw)).toEqual({ hard: null, medium: null, soft: null, valid: false })
  })

  it('prefers provided components while retaining zero values', () => {
    expect(resolveScore('0hard/0soft', { hard: 0, medium: null, soft: 0 })).toEqual({ hard: 0, medium: 0, soft: 0, valid: true })
  })

  it('describes scores in school language', () => {
    expect(describeScore({ hard: 0, medium: 0, soft: 0, valid: true })).toBe('没有硬冲突 · 偏好基本满足')
    expect(describeScore({ hard: -1, medium: -2, soft: -3, valid: true })).toBe('有 1 处硬冲突 · 还有 2 节未排 · 部分偏好未满足')
    expect(versionStatusLabel('CANDIDATE')).toBe('候选版本')
  })
})
