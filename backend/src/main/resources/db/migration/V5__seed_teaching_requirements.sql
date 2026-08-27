INSERT INTO teaching_requirement (term_id, student_group_id, subject_id, teacher_id, weekly_periods, duration_periods)
SELECT t.id, g.id, s.id, te.id, v.weekly_periods, 1
FROM (VALUES
    ('G7-1', 'MATH', 'T001', 1),
    ('G7-1', 'CHN', 'T002', 1),
    ('G7-2', 'ENG', 'T003', 1)
) AS v(group_code, subject_code, teacher_code, weekly_periods)
JOIN academic_term t ON t.code = '2026-FALL'
JOIN student_group g ON g.code = v.group_code
JOIN subject s ON s.code = v.subject_code
JOIN teacher te ON te.code = v.teacher_code
WHERE NOT EXISTS (
    SELECT 1 FROM teaching_requirement r
    WHERE r.term_id = t.id AND r.student_group_id = g.id AND r.subject_id = s.id AND r.teacher_id = te.id
);
