export type WorkspaceViewType = 'CLASS' | 'TEACHER' | 'ROOM'

export interface WorkspaceOccurrence {
  occurrenceId: number
  subjectCode: string
  subjectName: string
  teacherCode: string
  teacherName: string
  studentGroupCode: string
  studentGroupName: string
  timeslotCode?: string
  timeslotLabel?: string
  weekday?: number
  period?: number
  roomCode?: string
  roomName?: string
  source?: string
  locked?: boolean
  duration?: number
}

export interface WorkspaceTimeslotOption {
  code: string
  label: string
  weekday: number
  period: number
}

export interface WorkspaceResourceOption {
  code: string
  name: string
}

export interface WorkspaceRoomOption extends WorkspaceResourceOption {
  capacity: number
  roomType?: string
}

export interface WorkspaceOptions {
  timeslots: WorkspaceTimeslotOption[]
  rooms: WorkspaceRoomOption[]
  studentGroups: WorkspaceResourceOption[]
  teachers: WorkspaceResourceOption[]
}

export interface WorkspaceDayOption {
  number: number
  label: string
}

export function countAssignedOccurrences(occurrences: WorkspaceOccurrence[]): number {
  return occurrences.filter(item => Boolean(item.timeslotCode && item.roomCode)).length
}

export function getQualityPercent(occurrences: WorkspaceOccurrence[]): number {
  if (!occurrences.length) return 0
  return Math.round(countAssignedOccurrences(occurrences) / occurrences.length * 100)
}

export function getResourceOptions(viewType: WorkspaceViewType, options: WorkspaceOptions): WorkspaceResourceOption[] {
  if (viewType === 'TEACHER') return options.teachers
  if (viewType === 'ROOM') return options.rooms.map(room => ({ code: room.code, name: room.name }))
  return options.studentGroups
}

export function getWeekdays(timeslots: WorkspaceTimeslotOption[]): WorkspaceDayOption[] {
  const labels = ['一', '二', '三', '四', '五', '六', '日']
  const numbers = [...new Set(timeslots.map(item => item.weekday))].sort((left, right) => left - right)
  return numbers.map(number => ({ number, label: `周${labels[number - 1] ?? number}` }))
}

export function getPeriods(timeslots: WorkspaceTimeslotOption[]): number[] {
  return [...new Set(timeslots.map(item => item.period))].sort((left, right) => left - right)
}

export function getSlotItems(occurrences: WorkspaceOccurrence[], weekday: number, period: number): WorkspaceOccurrence[] {
  return occurrences.filter(item => item.weekday === weekday && item.period === period)
}

export function resolveBoardOccurrences(
  filteredOccurrences: WorkspaceOccurrence[],
  versionId: number | null,
  allOccurrences: WorkspaceOccurrence[],
): WorkspaceOccurrence[] {
  return versionId === null ? allOccurrences : filteredOccurrences
}

export function getStatusLabel(jobStatus: string, versionStatus: string): string {
  const labels: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '求解中',
    COMPLETED: '候选结果',
    FAILED: '求解失败',
    CANCELLED: '已取消',
    CANDIDATE: '候选可编辑',
    DRAFT: '草稿可编辑',
    PUBLISHED: '已发布只读',
  }
  return labels[jobStatus] ?? labels[versionStatus] ?? jobStatus
}

export function canCancelSolve(jobId: number | null, jobStatus: string, cancelling: boolean): boolean {
  return Boolean(jobId && ['QUEUED', 'RUNNING'].includes(jobStatus) && !cancelling)
}
