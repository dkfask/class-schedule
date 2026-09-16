export type FirstRunStepState = 'done' | 'active' | 'pending'

export interface FirstRunStep {
  number: string
  title: string
  detail: string
  to: string
  state: FirstRunStepState
}

export function issueDestination(code?: string) {
  switch (code) {
    case 'TERM_REQUIRED':
    case 'TERM_NOT_FOUND':
    case 'TERM_ARCHIVED':
    case 'NO_TIMESLOTS':
    case 'NO_ACTIVE_ROOMS':
      return '/master-data'
    case 'NO_ACTIVE_REQUIREMENTS':
    case 'HOME_ROOM_NOT_CONFIGURED':
      return '/teaching-plan'
    default:
      return '/rule-facts'
  }
}

export function isFirstRunTerm(input: {
  isPlanner: boolean
  hasPublished: boolean
  hasCoreData: boolean
  requirementCount: number
  ready: boolean
}) {
  return input.isPlanner && !input.hasPublished && (!input.hasCoreData || input.requirementCount === 0 || !input.ready)
}

export function buildFirstRunSteps(input: {
  hasCoreData: boolean
  requirementCount: number
  ready: boolean
  blockingMessage?: string
  blockingCode?: string
}): FirstRunStep[] {
  const dataReady = input.hasCoreData
  const planReady = input.requirementCount > 0
  const checksReady = input.ready
  return [
    {
      number: '01',
      title: '导入学期数据',
      detail: '先准备教师、班级、课程、教室和节次。',
      to: '/import',
      state: dataReady ? 'done' : 'active',
    },
    {
      number: '02',
      title: '核对教学计划',
      detail: '确认每个班级的课程、教师和周课时。',
      to: '/teaching-plan',
      state: !dataReady ? 'pending' : planReady ? 'done' : 'active',
    },
    {
      number: '03',
      title: '处理阻塞项',
      detail: input.blockingMessage ?? '完成排课前置检查后再开始自动排课。',
      to: issueDestination(input.blockingCode),
      state: !planReady ? 'pending' : checksReady ? 'done' : 'active',
    },
    {
      number: '04',
      title: '生成候选课表',
      detail: '提交自动排课后，再进入检查与发布。',
      to: '/workspace',
      state: checksReady ? 'active' : 'pending',
    },
  ]
}

export function firstRunPrimaryStep(steps: FirstRunStep[]): FirstRunStep {
  return steps.find(step => step.state === 'active') ?? steps[steps.length - 1]
}
