export type ReleaseCheckLevel = 'block' | 'warn' | 'pass'

export interface ReleaseCheckItem {
  id: string
  title: string
  detail: string
  level: ReleaseCheckLevel
  actionLabel?: string
  actionPath?: string
}

export interface ReleaseChecklist {
  blocking: ReleaseCheckItem[]
  warnings: ReleaseCheckItem[]
  passed: ReleaseCheckItem[]
  statusLabel: string
  ready: boolean
  why: string
}

export type ReleaseReviewRole = 'PLANNER' | 'REVIEWER' | 'BUSINESS_OWNER'

export interface ReleaseChecklistInput {
  status?: string | null
  publishable?: boolean | null
  hard?: number | null
  medium?: number | null
  soft?: number | null
  scoreValid?: boolean
  releaseNote?: string
  releaseConfirmed?: boolean
  reviewOnly?: boolean
  reviewerRole?: ReleaseReviewRole
  approvalStatus?: string | null
  approvalRequired?: boolean
  approvalComment?: string | null
}

function resolveRole(input: ReleaseChecklistInput): ReleaseReviewRole {
  if (input.reviewerRole) return input.reviewerRole
  if (input.reviewOnly) return 'REVIEWER'
  return 'PLANNER'
}

function plannerAction(reviewOnly: boolean, actionLabel: string, actionPath: string): Pick<ReleaseCheckItem, 'actionLabel' | 'actionPath'> {
  return reviewOnly ? {} : { actionLabel, actionPath }
}

function group(items: ReleaseCheckItem[], role: ReleaseReviewRole, approvalStatus: string, approvalRequired: boolean): ReleaseChecklist {
  const blocking = items.filter(item => item.level === 'block')
  const warnings = items.filter(item => item.level === 'warn')
  const passed = items.filter(item => item.level === 'pass')
  const ready = blocking.length === 0 && items.some(item => item.id === 'completeness' && item.level === 'pass')
  const published = items.some(item => item.id === 'version-status' && item.title === '已发布课表')
  let statusLabel = '待处理'
  if (published) statusLabel = role === 'BUSINESS_OWNER' ? '已面向全校' : '已发布'
  else if (blocking.length) {
    const approvalOnly = blocking.length === 1 && blocking[0].id === 'owner-approval'
    statusLabel = approvalOnly
      ? (approvalStatus === 'REJECTED' ? '已退回' : role === 'BUSINESS_OWNER' ? '待您批准' : '待业务负责人批准')
      : role === 'BUSINESS_OWNER' ? '还不适合面向全校' : role === 'REVIEWER' ? '还不适合发布' : '暂不能发布'
  } else if (ready) {
    statusLabel = role === 'BUSINESS_OWNER'
      ? approvalStatus === 'APPROVED'
        ? '已批准，待排课员发布'
        : approvalStatus === 'REJECTED'
          ? '已退回'
          : '待您批准'
      : role === 'REVIEWER'
        ? '检查已通过'
        : approvalRequired && approvalStatus !== 'APPROVED'
          ? approvalStatus === 'REJECTED' ? '已退回' : '待业务负责人批准'
          : '可以发布'
  }
  const why = blocking.length
    ? role === 'PLANNER'
      ? `还有 ${blocking.length} 项会阻止发布，处理后才能面向全校生效。`
      : `还有 ${blocking.length} 项需要排课员处理，完成后再回来确认是否面向全校。`
    : ready
      ? role === 'BUSINESS_OWNER'
        ? approvalStatus === 'APPROVED'
          ? '您已批准。排课员填写版本说明后即可面向全校，您不能代替排课员发布。'
          : approvalStatus === 'REJECTED'
            ? '您已退回该候选课表。排课员处理后会重新进入待审批。'
            : '课次已排齐、没有硬冲突。请批准或退回；批准后仍由排课员发布。'
        : role === 'REVIEWER'
          ? '检查已通过。发布仍需排课员填写版本说明并确认。'
          : approvalRequired && approvalStatus !== 'APPROVED'
            ? approvalStatus === 'REJECTED'
              ? '业务负责人已退回，请处理后重新提交审阅。'
              : '课次已排齐，但仍需业务负责人批准后才能面向全校。'
            : '当前版本可以发布：教学任务已排齐，没有硬冲突。'
      : role === 'PLANNER'
        ? '请先完成发布前检查，再确认是否面向全校生效。'
        : '请先完成审阅检查，再把结论告知排课员。'
  return { blocking, warnings, passed, statusLabel, ready, why }
}

export function buildReleaseChecklist(input: ReleaseChecklistInput): ReleaseChecklist {
  const status = input.status ?? ''
  const isCandidate = status === 'CANDIDATE'
  const isPublished = status === 'PUBLISHED'
  const reviewOnly = Boolean(input.reviewOnly)
  const role = resolveRole(input)
  const approvalStatus = input.approvalStatus ?? 'NONE'
  const approvalRequired = Boolean(input.approvalRequired)
  const approvalComment = input.approvalComment?.trim()
  const items: ReleaseCheckItem[] = []

  if (isPublished) {
    items.push({
      id: 'version-status',
      title: '已发布课表',
      detail: role === 'BUSINESS_OWNER'
        ? '该版本已经面向全校生效。如需修正，请让排课员基于此版本创建修订稿，而不是覆盖已发布结果。'
        : '该版本已经面向全校生效。如需修正，请基于此版本创建修订稿，而不是覆盖已发布结果。',
      level: 'pass',
      actionLabel: '查看已发布课表',
      actionPath: '/published',
    })
  } else if (isCandidate) {
    items.push({
      id: 'version-status',
      title: '候选课表',
      detail: role === 'BUSINESS_OWNER'
        ? '当前是待面向全校的候选课表。请核对课次是否排齐、是否还有冲突，以及学校是否接受未满足的偏好。'
        : '当前是待发布的候选版本，可以继续检查课次完整性和冲突。',
      level: 'pass',
    })
  } else {
    items.push({
      id: 'version-status',
      title: role === 'PLANNER' ? '还不能发布' : '还不能面向全校',
      detail: status === 'DRAFT'
        ? role === 'PLANNER'
          ? '当前仍是草稿。请先完成自动排课或调整，形成候选课表后再发布。'
          : '当前仍是草稿。请等待排课员完成自动排课，形成候选课表后再审阅。'
        : role === 'PLANNER'
          ? '只有候选课表可以发布。请先回到自动排课生成或确认候选结果。'
          : '当前还没有可供审阅的候选课表。请等待排课员完成排课后再查看。',
      level: 'block',
      ...plannerAction(reviewOnly, '去自动排课', '/workspace'),
    })
  }

  if (input.publishable === true) {
    items.push({
      id: 'completeness',
      title: '课次已排齐',
      detail: '教学任务都已安排节次和教室，没有硬冲突，课表校验也已通过。',
      level: 'pass',
    })
  } else if (input.hard != null && input.hard !== 0) {
    items.push({
      id: 'completeness',
      title: '仍有硬冲突',
      detail: role === 'PLANNER'
        ? '当前还有教师、班级或教室冲突，发布会被拒绝。请先回到自动排课查看冲突课次。'
        : '当前还有教师、班级或教室冲突，还不适合面向全校。请等待排课员处理后再审阅。',
      level: 'block',
      ...plannerAction(reviewOnly, '查看冲突课次', '/workspace'),
    })
  } else if (input.medium != null && input.medium !== 0) {
    items.push({
      id: 'completeness',
      title: '仍有未排课次',
      detail: role === 'PLANNER'
        ? '还有教学任务没有安排好。请先处理待排任务，再回来发布。'
        : '还有教学任务没有安排好，还不适合面向全校。请等待排课员补齐后再审阅。',
      level: 'block',
      ...plannerAction(reviewOnly, '处理待排任务', '/workspace'),
    })
  } else {
    items.push({
      id: 'completeness',
      title: role === 'PLANNER' ? '发布检查未通过' : '审阅检查未通过',
      detail: role === 'PLANNER'
        ? '还有未排课次，或课表校验尚未通过。请先回到自动排课处理后再发布。'
        : '还有未排课次，或课表校验尚未通过。请等待排课员处理后再审阅。',
      level: 'block',
      ...plannerAction(reviewOnly, '去自动排课', '/workspace'),
    })
  }

  if (input.soft != null && input.soft !== 0) {
    items.push({
      id: 'preferences',
      title: '仍有偏好未完全满足',
      detail: role === 'BUSINESS_OWNER'
        ? '部分偏好还没完全满足，不会单独挡住面向全校。请确认学校是否接受这些取舍。'
        : '部分偏好还没完全满足，不会阻止发布。请在版本说明中注明学校已接受的取舍。',
      level: 'warn',
    })
  } else if (input.scoreValid || input.publishable === true) {
    items.push({
      id: 'preferences',
      title: '没有未满足的偏好提醒',
      detail: '当前没有需要在发布说明中特别交代的软约束提醒。',
      level: 'pass',
    })
  }

  if (isCandidate && approvalRequired) {
    if (approvalStatus === 'APPROVED') {
      items.push({
        id: 'owner-approval',
        title: role === 'BUSINESS_OWNER' ? '您已批准面向全校' : '业务负责人已批准',
        detail: role === 'BUSINESS_OWNER'
          ? '审批结论已记录。排课员填写版本说明后即可面向全校，您不能代替排课员发布。'
          : '业务负责人已批准当前候选课表。请填写版本说明并发布，发布后本版本只读。',
        level: 'pass',
      })
    } else if (approvalStatus === 'REJECTED') {
      items.push({
        id: 'owner-approval',
        title: role === 'BUSINESS_OWNER' ? '您已退回该候选课表' : '业务负责人已退回',
        detail: approvalComment
          ? `退回原因：${approvalComment}`
          : role === 'PLANNER'
            ? '业务负责人已退回，请处理后重新提交审阅。退回不会绕过硬冲突和未排课次检查。'
            : '已退回给排课员。处理完成后会重新进入待审批。',
        level: 'block',
        ...plannerAction(reviewOnly, '去处理退回意见', '/workspace'),
      })
    } else {
      items.push({
        id: 'owner-approval',
        title: role === 'BUSINESS_OWNER' ? '待您批准或退回' : '待业务负责人批准',
        detail: role === 'BUSINESS_OWNER'
          ? '课次检查通过后，请记录批准或退回。批准不会绕过硬冲突，也不能代替排课员发布。'
          : '学校已配置业务负责人。课次排齐且没有硬冲突后，需由其批准才能面向全校。',
        level: role === 'BUSINESS_OWNER' ? 'warn' : 'block',
      })
    }
  }

  if (isCandidate && !reviewOnly) {
    items.push(input.releaseNote?.trim()
      ? {
          id: 'release-note',
          title: '已填写版本说明',
          detail: '发布记录将保留本次说明，便于以后核对适用范围和特殊安排。',
          level: 'pass',
        }
      : {
          id: 'release-note',
          title: '尚未填写版本说明',
          detail: '发布前请写明适用学期、特殊安排或业务确认结论。填写后即可继续，不会单独阻止检查。',
          level: 'warn',
        })
    items.push(input.releaseConfirmed
      ? {
          id: 'confirmation',
          title: '已确认发布结果',
          detail: '已确认当前版本的适用学期、课次数量和发布结果。',
          level: 'pass',
        }
      : {
          id: 'confirmation',
          title: '尚未勾选发布确认',
          detail: '发布前请确认适用学期、课次数量和发布结果。确认后按钮才会可用。',
          level: 'warn',
        })
  } else if (isCandidate && reviewOnly && role !== 'BUSINESS_OWNER') {
    items.push({
      id: 'confirmation',
      title: '待排课员确认后发布',
      detail: '审核员可以核对审阅清单，但不能发布。发布需由排课员填写版本说明并确认。',
      level: 'warn',
    })
  }

  items.push({
    id: 'history-protection',
    title: '发布后保持只读',
    detail: role === 'PLANNER'
      ? '发布成功后本版本不能再改。如需修订，请创建新版本，已发布课表仍可查询和打印。'
      : '面向全校后本版本不能再改。如需修订，由排课员创建新版本，已发布课表仍可查询和打印。',
    level: 'pass',
  })

  return group(items, role, approvalStatus, approvalRequired)
}
