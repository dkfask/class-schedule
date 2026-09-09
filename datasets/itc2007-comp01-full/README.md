# ITC2007 comp01 full fixture

This fixture uses the complete ITC2007 `comp01.ctt` instance from:

- Repository: https://github.com/tomas-muller/cpsolver-itc2007
- Source path: `data/ctt/comp01.ctt`
- Retrieved revision: `a253a5a` (`v1.1-build27`)

The source contains 30 courses, 6 rooms, 5 days x 6 periods, 14 curricula, and 53 course unavailability constraints. The converted workbook is `MASTER_DATA-v1.xlsx` and targets the existing `2026-FALL` term.

## Conversion result

The project converter completed successfully:

- 24 teachers
- 14 student groups
- 30 subjects
- 6 rooms
- 30 teaching requirements
- 11 `MASTER_DATA v1` sheets
- 160 weekly occurrences represented by the source course lecture counts

Command:

```bash
bash scripts/convert-external-timetable.sh \
  --source itc2007 \
  --input datasets/itc2007-comp01-full/comp01.ctt \
  --output datasets/itc2007-comp01-full/MASTER_DATA-v1.xlsx \
  --term-code 2026-FALL \
  --term-name '2026 秋季学期'
```

## Mapping limits

The current template supports one student group per teaching requirement. Twelve additional curriculum memberships were therefore reported and dropped, keeping the first curriculum for each course. All 53 course-specific unavailability constraints were also reported but not imported because the current template only models teacher, room, and student-group availability. The workbook is a demand fixture for importer and solver testing, not a lossless ITC2007 benchmark reproduction.

The workbook was checked with `unzip -t`; all 11 sheets are present and the generated archive is valid. It is isolated under this directory and does not modify production data.
