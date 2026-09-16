import { describe, expect, it } from 'vitest'
import { buildReleaseChecklist } from './releaseChecklist'

describe('buildReleaseChecklist', () => {
  it('groups a publishable candidate into passed items and form warnings', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: true,
      hard: 0,
      medium: 0,
      soft: 0,
      scoreValid: true,
    })
    expect(checklist.ready).toBe(true)
    expect(checklist.statusLabel).toBe('可以发布')
    expect(checklist.why).toContain('可以发布')
    expect(checklist.blocking).toEqual([])
    expect(checklist.warnings.map(item => item.id)).toEqual(['release-note', 'confirmation'])
    expect(checklist.passed.map(item => item.id)).toEqual(['version-status', 'completeness', 'preferences', 'history-protection'])
  })

  it('treats hard conflicts as blocking with a path back to the schedule', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: false,
      hard: -2,
      medium: 0,
      soft: 0,
      scoreValid: true,
    })
    expect(checklist.ready).toBe(false)
    expect(checklist.statusLabel).toBe('暂不能发布')
    expect(checklist.blocking[0]).toMatchObject({
      id: 'completeness',
      title: '仍有硬冲突',
      actionPath: '/workspace',
      actionLabel: '查看冲突课次',
    })
  })

  it('keeps unmet preferences as warnings that do not block publish', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: true,
      hard: 0,
      medium: 0,
      soft: -8,
      scoreValid: true,
      releaseNote: '已确认早自习安排',
      releaseConfirmed: true,
    })
    expect(checklist.ready).toBe(true)
    expect(checklist.blocking).toEqual([])
    expect(checklist.warnings).toHaveLength(1)
    expect(checklist.warnings[0].title).toBe('仍有偏好未完全满足')
  })

  it('explains a draft cannot be published yet', () => {
    const checklist = buildReleaseChecklist({ status: 'DRAFT', publishable: false })
    expect(checklist.blocking.some(item => item.id === 'version-status')).toBe(true)
    expect(checklist.statusLabel).toBe('暂不能发布')
  })

  it('asks business owners to approve or reject a publishable candidate', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: true,
      hard: 0,
      medium: 0,
      soft: -4,
      scoreValid: true,
      reviewOnly: true,
      reviewerRole: 'BUSINESS_OWNER',
      approvalRequired: true,
      approvalStatus: 'PENDING',
    })
    expect(checklist.ready).toBe(true)
    expect(checklist.statusLabel).toBe('待您批准')
    expect(checklist.why).toContain('请批准或退回')
    expect(checklist.warnings.map(item => item.title)).toEqual(['仍有偏好未完全满足', '待您批准或退回'])
    expect(checklist.blocking).toEqual([])
    expect(checklist.passed.every(item => !item.actionPath || item.actionPath === '/published')).toBe(true)
  })

  it('blocks planner publish until the business owner has approved', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: true,
      hard: 0,
      medium: 0,
      soft: 0,
      scoreValid: true,
      approvalRequired: true,
      approvalStatus: 'PENDING',
    })
    expect(checklist.ready).toBe(false)
    expect(checklist.statusLabel).toBe('待业务负责人批准')
    expect(checklist.blocking[0]).toMatchObject({ id: 'owner-approval', title: '待业务负责人批准' })
  })

  it('records an approved candidate as waiting for the planner to publish', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: true,
      hard: 0,
      medium: 0,
      soft: 0,
      scoreValid: true,
      reviewOnly: true,
      reviewerRole: 'BUSINESS_OWNER',
      approvalRequired: true,
      approvalStatus: 'APPROVED',
    })
    expect(checklist.ready).toBe(true)
    expect(checklist.statusLabel).toBe('已批准，待排课员发布')
    expect(checklist.passed.some(item => item.id === 'owner-approval')).toBe(true)
  })

  it('hides workspace repair links from read-only reviewers', () => {
    const checklist = buildReleaseChecklist({
      status: 'CANDIDATE',
      publishable: false,
      hard: -2,
      reviewOnly: true,
      reviewerRole: 'BUSINESS_OWNER',
    })
    expect(checklist.statusLabel).toBe('还不适合面向全校')
    expect(checklist.blocking[0].actionPath).toBeUndefined()
    expect(checklist.why).toContain('需要排课员处理')
  })
})
