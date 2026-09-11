-- Creates an isolated term for the real Furong-campus dataset.
-- Copies the 30 period templates (5 teaching days x 6 periods) from 2026-FALL so the
-- dataset can be imported and solved without touching existing demo data.
--
-- Run: docker compose exec -T postgres psql -U class_schedule -d class_schedule -f - < datasets/real-school-furong/create-term.sql

INSERT INTO academic_term(code, name, status)
VALUES ('2026-FALL-FR', '2026-2027学年上期 芙蓉校区', 'DRAFT')
ON CONFLICT (code) DO NOTHING;

INSERT INTO period_template(term_id, code, weekday, period_no, label, start_time, end_time, continuity_group, break_after)
SELECT t.id, p.code, p.weekday, p.period_no, p.label, p.start_time, p.end_time, p.continuity_group, p.break_after
FROM academic_term t
JOIN academic_term s ON s.code = '2026-FALL'
JOIN period_template p ON p.term_id = s.id
WHERE t.code = '2026-FALL-FR'
ON CONFLICT DO NOTHING;

SELECT
    (SELECT count(*) FROM period_template WHERE term_id = (SELECT id FROM academic_term WHERE code = '2026-FALL-FR')) AS periods,
    (SELECT count(*) FROM teaching_requirement WHERE term_id = (SELECT id FROM academic_term WHERE code = '2026-FALL-FR')) AS requirements;
