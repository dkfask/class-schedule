INSERT INTO academic_term (code, name, status)
VALUES ('2026-FALL', '2026 秋季学期', 'DRAFT')
ON CONFLICT (code) DO NOTHING;

INSERT INTO period_template (term_id, code, weekday, period_no, label)
SELECT id, 'MON-1', 1, 1, '周一 第1节' FROM academic_term WHERE code = '2026-FALL'
ON CONFLICT DO NOTHING;
INSERT INTO period_template (term_id, code, weekday, period_no, label)
SELECT id, 'MON-2', 1, 2, '周一 第2节' FROM academic_term WHERE code = '2026-FALL'
ON CONFLICT DO NOTHING;
INSERT INTO period_template (term_id, code, weekday, period_no, label)
SELECT id, 'TUE-1', 2, 1, '周二 第1节' FROM academic_term WHERE code = '2026-FALL'
ON CONFLICT DO NOTHING;
INSERT INTO period_template (term_id, code, weekday, period_no, label)
SELECT id, 'TUE-2', 2, 2, '周二 第2节' FROM academic_term WHERE code = '2026-FALL'
ON CONFLICT DO NOTHING;
