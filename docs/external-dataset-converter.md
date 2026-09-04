# External Timetable Converter

`scripts/convert-external-timetable.sh` converts the public UniTime and ITC2007 benchmark formats into the project's `MASTER_DATA v1` Excel workbook.

## Usage

```bash
bash scripts/convert-external-timetable.sh \
  --source unitime \
  --input /path/to/pu-fal07-llr.zip \
  --output /tmp/unitime-llr.xlsx \
  --term-code 2026-FALL \
  --term-name '2026 秋季学期'

bash scripts/convert-external-timetable.sh \
  --source itc2007 \
  --input /path/to/comp01.ctt \
  --output /tmp/itc-comp01.xlsx \
  --term-code 2026-FALL \
  --term-name '2026 秋季学期'
```

The converter only reads the source and writes the requested workbook. It does not connect to PostgreSQL or import data.

## Mapping

- UniTime rooms become rooms, classes become synthetic student groups and teaching requirements, offerings become subjects, and instructor IDs become teachers.
- If a UniTime class cannot fit in any single candidate room, the converter adds a clearly named, class-specific logical-capacity room. This keeps the current one-room-per-occurrence model's capacity hard constraint explicit; the logical room is not presented as an original physical room.
- UniTime classes with `nrRooms="0"` are retained as zero-enrollment time-allocation placeholders because the current template has no separate "room not required" column. Their generated schedule must not be interpreted as a physical-room assignment.
- ITC2007 courses become subjects and teaching requirements, rooms become rooms, and curricula become student groups.
- `--term-code` defaults to `2026-FALL`; the target term must already exist when the workbook is imported.
- The converter emits all 11 template sheets in the same order as `MasterDataSchemaRegistry`.

## Deliberate limitations

- UniTime time and room alternatives are not converted into fixed period or room values. The source contains candidate placements, not a single assignment in the input package.
- UniTime student enrollment conflicts and synchronized/multi-section relationships are not represented by the current master-data template.
- ITC2007 allows one course to belong to multiple curricula. Because the current template permits one student group per teaching requirement, the converter keeps the first curriculum and reports the dropped memberships.
- ITC2007 course unavailability constraints are reported but not imported because the current template models resource availability, not per-course forbidden periods.
- These workbooks are demand fixtures for the current importer and solver. They are not claims that the converted output preserves every original benchmark constraint.
- The default `2026-FALL` term supplies a five-day, six-period teaching week so a real-sized converted fixture is not accidentally evaluated against the four-period demo seed.
