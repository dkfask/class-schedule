import type { WorkspaceOccurrence } from './workspace'

export type PendingReasonKind = 'timeslot-and-room' | 'timeslot' | 'room' | 'review'

export interface PendingReason {
  kind: PendingReasonKind
  title: string
  detail: string
  nextStep: string
}

export interface ConflictExplanation {
  title: string
  detail: string
  nextStep: string
  actionPath?: string
  actionLabel?: string
}

const CONFLICTS: Record<string, ConflictExplanation> = {
  TEACHER_CONFLICT: {
    title: '教师在这个节次已有课',
    detail: '同一位教师不能在同一节次上两门课。',
    nextStep: '换一个空闲节次，或与另一节课对调。',
  },
  STUDENT_GROUP_CONFLICT: {
    title: '班级在这个节次已有课',
    detail: '同一个班不能在同一节次上两门课。',
    nextStep: '换一个该班空闲的节次，或与另一节课对调。',
  },
  ROOM_CONFLICT: {
    title: '教室在这个节次已被占用',
    detail: '同一间教室不能在同一节次安排两门课。',
    nextStep: '换一间空闲教室，或改到其他节次。',
  },
  LOCKED_CONFLICT: {
    title: '目标位置有已锁定课程',
    detail: '已锁定的课次不能被这次调整挤占。',
    nextStep: '先解锁相关课次，或改选其他节次和教室。',
  },
  LOCKED_ASSIGNMENT: {
    title: '这节课已锁定',
    detail: '锁定课次不能直接改时间和教室。',
    nextStep: '先解锁，再调整；或保持原安排。',
  },
  ROOM_CAPACITY: {
    title: '教室容量不够',
    detail: '目标教室容纳不了当前班级人数。',
    nextStep: '换一间更大的教室，或回到基础数据核对容量。',
    actionPath: '/master-data',
    actionLabel: '核对教室',
  },
  ROOM_FEATURE: {
    title: '教室类型不匹配',
    detail: '这门课需要实验室或专用教室，目标教室不具备所需条件。',
    nextStep: '换一间符合要求的教室，或回到规则检查核对场地要求。',
    actionPath: '/rule-facts',
    actionLabel: '查看场地要求',
  },
  ROOM_NOT_FOUND: {
    title: '目标教室不可用',
    detail: '所选教室不存在或已停用。',
    nextStep: '换一间启用中的教室，或回到基础数据核对教室。',
    actionPath: '/master-data',
    actionLabel: '核对教室',
  },
  TEACHER_UNAVAILABLE: {
    title: '教师这个节次不可排课',
    detail: '该教师在这个节次已被锁定为不可用。',
    nextStep: '换一个可用节次，或回到规则检查调整教师锁定时段。',
    actionPath: '/rule-facts',
    actionLabel: '查看锁定时段',
  },
  STUDENT_GROUP_UNAVAILABLE: {
    title: '班级这个节次不可排课',
    detail: '该班级在这个节次已被锁定为不可用。',
    nextStep: '换一个可用节次，或回到规则检查调整班级锁定时段。',
    actionPath: '/rule-facts',
    actionLabel: '查看锁定时段',
  },
  ROOM_UNAVAILABLE: {
    title: '教室这个节次不可排课',
    detail: '该教室在这个节次已被锁定为不可用。',
    nextStep: '换一间可用教室，或回到规则检查调整教室锁定时段。',
    actionPath: '/rule-facts',
    actionLabel: '查看锁定时段',
  },
  TEACHER_DAILY_MAX: {
    title: '教师当天课时已满',
    detail: '这会超过该教师每天最多可上的节数。',
    nextStep: '改到其他工作日，或回到规则检查调整每日上限。',
    actionPath: '/rule-facts',
    actionLabel: '查看课时上限',
  },
  STUDENT_GROUP_DAILY_MAX: {
    title: '班级当天课时已满',
    detail: '这会超过该班级每天最多可上的节数。',
    nextStep: '改到其他工作日，或回到规则检查调整每日上限。',
    actionPath: '/rule-facts',
    actionLabel: '查看课时上限',
  },
}

export function explainPendingReason(item: Pick<WorkspaceOccurrence, 'timeslotCode' | 'roomCode'>): PendingReason {
  if (!item.timeslotCode && !item.roomCode) {
    return {
      kind: 'timeslot-and-room',
      title: '还没有安排时间和教室',
      detail: '这节课还没有节次和教室。',
      nextStep: '点开后选择空闲节次和教室，或与另一节课对调。',
    }
  }
  if (!item.timeslotCode) {
    return {
      kind: 'timeslot',
      title: '还没有安排时间',
      detail: '这节课已有教室，但还没有节次。',
      nextStep: '点开后选择一个班级和教师都空闲的节次。',
    }
  }
  if (!item.roomCode) {
    return {
      kind: 'room',
      title: '还没有安排教室',
      detail: '这节课已有节次，但还没有教室。',
      nextStep: '点开后选择一间容量足够、当时空闲的教室。',
    }
  }
  return {
    kind: 'review',
    title: '需要复核',
    detail: '这节课已有安排，但仍需人工确认。',
    nextStep: '点开后核对节次、教室和受影响班级。',
  }
}

export function explainConflict(code?: string, message?: string, resourceCode?: string): ConflictExplanation {
  const known = code ? CONFLICTS[code] : undefined
  if (known) {
    const resource = resourceCode ? `（${resourceCode}）` : ''
    return {
      ...known,
      detail: `${known.detail}${resource}`,
    }
  }
  return {
    title: '这个位置放不下',
    detail: message || '目标节次或教室与现有课表冲突。',
    nextStep: '换一个空闲节次或教室后再预览。',
  }
}
