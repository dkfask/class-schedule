# ITC2007 comp01 medium fixture

Input is an ITC2007 Curriculum-Based Course Timetabling instance in `comp01.ctt`; the converted submission workbook is `MASTER_DATA-v1.xlsx`. The fixture is isolated under `datasets/itc2007-comp01-medium/` and does not modify production data.

## Import preflight

The converter completed successfully and emitted all 11 MASTER_DATA v1 sheets. Counts: 6 teachers, 2 student groups, 6 subjects, 3 rooms, and 6 teaching requirements. The workbook is suitable for importer submission for term `2026-FALL` (the term must already exist). The converter reported the known ITC2007 limitation that course-to-multiple-curricula relationships are reduced to the template's single-group mapping; original course unavailability and other competition constraints are not imported.

## Solver baseline

Command: `mvn -q -Drun.solver.benchmark=true -Dsolver.benchmark.termination-ms=3000 -Dtest=SolverBenchmarkTest test`

Representative benchmark scale: 240 occurrences, 480 planning variables. Elapsed time: 3009 ms (3 s termination). Score: `-3hard/0medium/-134soft`.

## Provenance

The public ITC2007 source endpoint was not reachable from this environment (TLS connection failure). `comp01.ctt` is a small, format-faithful, reproducible fixture based on the published ITC2007 schema; replace it with the obtained public comp01 instance when network access is available and rerun the same converter command.
