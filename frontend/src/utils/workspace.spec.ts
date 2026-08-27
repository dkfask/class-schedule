import { describe, expect, it } from 'vitest'
import {
  canCancelSolve,
  countAssignedOccurrences,
  getPeriods,
  getQualityPercent,
  getResourceOptions,
  getSlotItems,
  getStatusLabel,
  getWeekdays,
  resolveBoardOccurrences,
  type WorkspaceOccurrence,
  type WorkspaceOptions,
} from './workspace'

const occurrences: WorkspaceOccurrence[] = [
  {
    occurrenceId: 1,
    subjectCode: 'MATH',
    subjectName: '数学',
    teacherCode: 'T001',
    teacherName: '张老师',
    studentGroupCode: 'G7-1',
    studentGroupName: '七年级1班',
    timeslotCode: 'MON-1',
    weekday: 1,
    period: 1,
    roomCode: 'A101',
    roomName: '教学楼 A101',
    locked: false,
    duration: 1,
  },
  {
    occurrenceId: 2,
    subjectCode: 'CHN',
    subjectName: '语文',
    teacherCode: 'T002',
    teacherName: '李老师',
    studentGroupCode: 'G7-1',
    studentGroupName: '七年级1班',
    timeslotCode: 'TUE-2',
    weekday: 2,
    period: 2,
    roomCode: 'A102',
    roomName: '教学楼 A102',
    locked: true,
    duration: 2,
  },
  {
    occurrenceId: 3,
    subjectCode: 'ENG',
    subjectName: '英语',
    teacherCode: 'T003',
    teacherName: '王老师',
    studentGroupCode: 'G7-2',
    studentGroupName: '七年级2班',
  },
]

const options: WorkspaceOptions = {
  timeslots: [
    { code: 'TUE-2', label: '周二 第2节', weekday: 2, period: 2 },
    { code: 'MON-2', label: '周一 第2节', weekday: 1, period: 2 },
    { code: 'MON-1', label: '周一 第1节', weekday: 1, period: 1 },
    { code: 'MON-1-copy', label: '重复位置', weekday: 1, period: 1 },
  ],
  rooms: [
    { code: 'A101', name: '教学楼 A101', capacity: 50, roomType: '普通教室' },
    { code: 'A102', name: '教学楼 A102', capacity: 50, roomType: '普通教室' },
  ],
  studentGroups: [{ code: 'G7-1', name: '七年级1班' }],
  teachers: [{ code: 'T001', name: '张老师' }],
}

describe('workspace display logic', () => {
  it('counts only assignments with both timeslot and room', () => {
    expect(countAssignedOccurrences(occurrences)).toBe(2)
    expect(getQualityPercent(occurrences)).toBe(67)
    expect(getQualityPercent([])).toBe(0)
  })

  it('maps resources by the selected stable-code view', () => {
    expect(getResourceOptions('CLASS', options)).toEqual([{ code: 'G7-1', name: '七年级1班' }])
    expect(getResourceOptions('TEACHER', options)).toEqual([{ code: 'T001', name: '张老师' }])
    expect(getResourceOptions('ROOM', options)).toEqual([
      { code: 'A101', name: '教学楼 A101' },
      { code: 'A102', name: '教学楼 A102' },
    ])
  })

  it('deduplicates and sorts weekdays and periods', () => {
    expect(getWeekdays(options.timeslots)).toEqual([{ number: 1, label: '周一' }, { number: 2, label: '周二' }])
    expect(getPeriods(options.timeslots)).toEqual([1, 2])
  })

  it('filters a grid slot and keeps empty server filters empty', () => {
    expect(getSlotItems(occurrences, 1, 1).map(item => item.occurrenceId)).toEqual([1])
    expect(resolveBoardOccurrences([], 42, occurrences)).toEqual([])
    expect(resolveBoardOccurrences([], null, occurrences)).toEqual(occurrences)
  })

  it('handles status labels and cancellation gates', () => {
    expect(getStatusLabel('RUNNING', 'SOLVING')).toBe('求解中')
    expect(getStatusLabel('WAITING', 'DRAFT')).toBe('草稿可编辑')
    expect(canCancelSolve(10, 'QUEUED', false)).toBe(true)
    expect(canCancelSolve(10, 'COMPLETED', false)).toBe(false)
    expect(canCancelSolve(10, 'RUNNING', true)).toBe(false)
    expect(canCancelSolve(null, 'RUNNING', false)).toBe(false)
  })
})
