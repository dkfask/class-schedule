-- The original demo seed had only four periods, which cannot host a real-sized
-- imported timetable. Keep those stable codes and add a five-day, six-period
-- teaching week for the default term.
INSERT INTO period_template (term_id, code, weekday, period_no, label, continuity_group, break_after)
SELECT
    t.id,
    'DAY-' || day_no || '-' || period_no,
    day_no,
    period_no,
    '第' || day_no || '教学日 第' || period_no || '节',
    'DAY-' || day_no,
    period_no = 3
FROM academic_term t
CROSS JOIN generate_series(1, 5) AS days(day_no)
CROSS JOIN generate_series(1, 6) AS periods(period_no)
WHERE t.code = '2026-FALL'
  AND NOT (day_no IN (1, 2) AND period_no IN (1, 2))
ON CONFLICT DO NOTHING;
