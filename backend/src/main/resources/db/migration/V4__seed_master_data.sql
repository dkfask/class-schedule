INSERT INTO teacher (code, name)
VALUES ('T001', '张老师'), ('T002', '李老师'), ('T003', '王老师')
ON CONFLICT (code) DO NOTHING;

INSERT INTO student_group (code, name)
VALUES ('G7-1', '七年级1班'), ('G7-2', '七年级2班')
ON CONFLICT (code) DO NOTHING;

INSERT INTO subject (code, name)
VALUES ('MATH', '数学'), ('CHN', '语文'), ('ENG', '英语')
ON CONFLICT (code) DO NOTHING;

INSERT INTO room (code, name, capacity, room_type)
VALUES ('A101', '教学楼 A101', 50, '普通教室'), ('A102', '教学楼 A102', 50, '普通教室')
ON CONFLICT (code) DO NOTHING;
