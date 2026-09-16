import { describe, expect, it } from 'vitest'
import { explainConflict, explainPendingReason } from './scheduleExplain'

describe('scheduleExplain', () => {
  it('explains missing timeslot and room with a next step', () => {
    expect(explainPendingReason({})).toMatchObject({
      title: '还没有安排时间和教室',
      nextStep: '点开后选择空闲节次和教室，或与另一节课对调。',
    })
    expect(explainPendingReason({ roomCode: 'A101' }).title).toBe('还没有安排时间')
    expect(explainPendingReason({ timeslotCode: 'MON-1' }).title).toBe('还没有安排教室')
  })

  it('turns engine conflict codes into school language', () => {
    const teacher = explainConflict('TEACHER_CONFLICT', '教师在目标节次已有课程', 'T001')
    expect(teacher.title).toBe('教师在这个节次已有课')
    expect(teacher.detail).toContain('T001')
    expect(teacher.nextStep).toContain('对调')

    const capacity = explainConflict('ROOM_CAPACITY', '教室容量不足', 'A101')
    expect(capacity.actionPath).toBe('/master-data')
    expect(capacity.actionLabel).toBe('核对教室')
  })
})
