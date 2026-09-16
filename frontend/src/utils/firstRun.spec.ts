import { describe, expect, it } from 'vitest'
import { buildFirstRunSteps, firstRunPrimaryStep, isFirstRunTerm, issueDestination } from './firstRun'

describe('first-run wizard helpers', () => {
  it('only starts the wizard for planners on an unpublished incomplete term', () => {
    expect(isFirstRunTerm({ isPlanner: true, hasPublished: false, hasCoreData: false, requirementCount: 0, ready: false })).toBe(true)
    expect(isFirstRunTerm({ isPlanner: false, hasPublished: false, hasCoreData: false, requirementCount: 0, ready: false })).toBe(false)
    expect(isFirstRunTerm({ isPlanner: true, hasPublished: true, hasCoreData: false, requirementCount: 0, ready: false })).toBe(false)
    expect(isFirstRunTerm({ isPlanner: true, hasPublished: false, hasCoreData: true, requirementCount: 3, ready: true })).toBe(false)
  })

  it('marks import as the active first step when core data is missing', () => {
    const steps = buildFirstRunSteps({ hasCoreData: false, requirementCount: 0, ready: false })
    expect(steps.map(step => step.state)).toEqual(['active', 'pending', 'pending', 'pending'])
    expect(firstRunPrimaryStep(steps)).toMatchObject({ title: '导入学期数据', to: '/import' })
  })

  it('sends a home-room blocker to the teaching plan after data and plan exist', () => {
    const steps = buildFirstRunSteps({
      hasCoreData: true,
      requirementCount: 3,
      ready: false,
      blockingMessage: '有 2 条行政班教学需求缺少有效的绑定教室',
      blockingCode: 'HOME_ROOM_NOT_CONFIGURED',
    })
    expect(issueDestination('HOME_ROOM_NOT_CONFIGURED')).toBe('/teaching-plan')
    expect(firstRunPrimaryStep(steps)).toMatchObject({ title: '处理阻塞项', to: '/teaching-plan' })
    expect(steps[3].state).toBe('pending')
  })
})
