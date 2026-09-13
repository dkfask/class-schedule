package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.Solver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Reproduces the real Furong-campus instance offline from
 * {@code datasets/real-school-furong/reference-solution.json} and probes how solver quality
 * responds to instance tightness. This is a diagnostic for the end-to-end solve-job
 * measurement, not a production test.
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "run.furong.probe", matches = "true")
class FurongInstanceProbeTest {
    private static final Path DATASET =
            Path.of("..", "datasets", "real-school-furong", "reference-solution.json");
    private static final Map<String, Integer> GRADE_STUDENTS =
            Map.of("一", 42, "二", 43, "三", 45, "四", 46, "五", 47, "六", 48);

    @Test
    void probeInstanceTightness() throws Exception {
        JsonNode lessons = new ObjectMapper().readTree(Files.readString(DATASET));
        System.out.println("scenario,rooms,classCap,seed,terminationMs,elapsedMs,score,hardViolations");

        run("full-real", 24, Integer.MAX_VALUE, null, 30_000, lessons);
        run("more-rooms", 60, Integer.MAX_VALUE, null, 30_000, lessons);
        run("relaxed-classes", 24, 26, null, 30_000, lessons);
        for (long seed : new long[] {0L, 1L, 2L, 7L}) {
            run("seed-" + seed, 24, Integer.MAX_VALUE, seed, 30_000, lessons);
        }
    }

    private void run(
            String name, int roomCount, int classCap, Long seed, long terminationMs, JsonNode lessons) {
        List<Timeslot> timeslots = timeslots();
        List<Room> rooms = rooms(roomCount);
        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        Set<String> breakAfter =
                new LinkedHashSet<>(
                        timeslots.stream()
                                .filter(Timeslot::isBreakAfter)
                                .map(Timeslot::getId)
                                .toList());

        Map<String, Integer> perClassCount = new HashMap<>();
        List<LessonOccurrence> occurrences = new ArrayList<>();
        long id = 1;
        for (JsonNode lesson : lessons) {
            String studentGroup = lesson.get("class").asText();
            int count = perClassCount.merge(studentGroup, 1, Integer::sum);
            if (count > classCap) continue;
            LessonOccurrence occurrence =
                    new LessonOccurrence(
                            id,
                            lesson.get("subject").asText(),
                            lesson.get("subject").asText(),
                            lesson.get("teacher").asText(),
                            lesson.get("teacher").asText(),
                            studentGroup,
                            studentGroup);
            occurrence.setOccurrenceKey("probe-" + id);
            occurrence.setTeachingRequirementId(id);
            occurrence.setRequirementCode("probe-" + id);
            occurrence.setDuration(1);
            occurrence.setStudentCount(GRADE_STUDENTS.getOrDefault(studentGroup.substring(0, 1), 45));
            occurrence.setAvailablePeriodCodes(
                    new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
            occurrence.setNextPeriodCodes(nextPeriodCodes);
            occurrence.setBreakAfterPeriodCodes(breakAfter);
            applyRoomPolicy(occurrence);
            occurrences.add(occurrence);
            id++;
        }

        Timetable input = new Timetable(timeslots, rooms, occurrences, List.of(), List.of());
        input.initializeGreedyAssignments();
        Solver<Timetable> solver =
                new SolverConfiguration(Duration.ofMillis(terminationMs), "NONE").solverFactory().buildSolver();
        if (seed != null) {
            var config = new SolverConfiguration(Duration.ofMillis(terminationMs), "NONE").solverConfig();
            config.setRandomSeed(seed);
            solver =
                    ai.timefold.solver.core.api.solver.SolverFactory.<Timetable>create(config)
                            .buildSolver();
        }
        long started = System.nanoTime();
        Timetable solved = solver.solve(input);
        long elapsed = (System.nanoTime() - started) / 1_000_000;

        Violations violations = countViolations(solved);
        System.out.printf(
                "%s,%d,%d,%s,%d,%d,%s,%d%n",
                name,
                roomCount,
                classCap,
                seed == null ? "-" : seed,
                terminationMs,
                elapsed,
                solved.getScore(),
                violations.total());
        assertThat(solved.getScore()).isNotNull();
    }

    /**
     * Rebuilds the instance exactly as {@code PlanningProblemRepository} does: one teaching
     * requirement per (class, subject, teacher) expanded into {@code weeklyPeriods} occurrences
     * with {@code id = requirementId * 100 + index}. If this reproduces the pipeline's -8 hard
     * score while the lesson-ordered reconstruction reaches 0, the gap is occurrence ordering,
     * not solver capability.
     */
    @Test
    void probePipelineOrdering() throws Exception {
        Path requirements = Path.of(System.getProperty("furong.requirements.csv", "/tmp/furong-requirements.csv"));
        List<Timeslot> timeslots = timeslots();
        List<Room> rooms = rooms(24);
        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        Set<String> breakAfter =
                new LinkedHashSet<>(
                        timeslots.stream().filter(Timeslot::isBreakAfter).map(Timeslot::getId).toList());

        List<LessonOccurrence> occurrences = new ArrayList<>();
        for (String line : Files.readAllLines(requirements)) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",", -1);
            long requirementId = Long.parseLong(cells[0]);
            String studentGroup = cells[2];
            int weeklyPeriods = Integer.parseInt(cells[5]);
            int studentCount = Integer.parseInt(cells[7]);
            for (int index = 0; index < weeklyPeriods; index++) {
                LessonOccurrence occurrence =
                        new LessonOccurrence(
                                requirementId * 100 + index,
                                cells[3],
                                cells[3],
                                cells[4],
                                cells[4],
                                studentGroup,
                                studentGroup);
                occurrence.setTeachingRequirementId(requirementId);
                occurrence.setRequirementCode(cells[1]);
                occurrence.setOccurrenceKey(requirementId + "-" + index);
                occurrence.setActivityIndex(index);
                occurrence.setDuration(1);
                occurrence.setStudentCount(studentCount);
                occurrence.setAvailablePeriodCodes(
                        new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
                occurrence.setNextPeriodCodes(nextPeriodCodes);
                occurrence.setBreakAfterPeriodCodes(breakAfter);
                applyRoomPolicy(occurrence);
                occurrences.add(occurrence);
            }
        }

        Timetable input = new Timetable(timeslots, rooms, occurrences, List.of(), List.of());
        input.initializeGreedyAssignments();
        System.out.println(
                "greedy-init-violations=" + countViolations(input).total() + " occurrences=" + occurrences.size());

        for (Long seed : new Long[] {0L, null}) {
            Timetable probe = new Timetable(timeslots, rooms, occurrences, List.of(), List.of());
            boolean greedy = Boolean.parseBoolean(System.getProperty("furong.greedy", "true"));
            if (greedy) probe.initializeGreedyAssignments();
            long budgetMs = Long.getLong("furong.budget.ms", 30_000);
            var config = new SolverConfiguration(Duration.ofMillis(budgetMs), "NONE").solverConfig();
            applyAcceptorExperiment(config);
            if (seed != null) config.setRandomSeed(seed);
            Solver<Timetable> solver =
                    ai.timefold.solver.core.api.solver.SolverFactory.<Timetable>create(config).buildSolver();
            long started = System.nanoTime();
            Timetable solved = solver.solve(probe);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var repairer = new TimetableConflictRepairer(
                    new SolverConfiguration(Duration.ofSeconds(1), "NONE").solverFactory());
            var solverScore = solved.getScore();
            long repairStarted = System.nanoTime();
            Timetable repaired = repairer.repair(solved, 500, 120_000);
            long repairMs = (System.nanoTime() - repairStarted) / 1_000_000;
            if (repaired.getScore().hardScore() < 0 || solved.getScore().hardScore() < 0) {
                System.out.printf(
                        "repair-detail,seed=%s,before=%s,after=%s%n",
                        seed == null ? "-" : seed, solved.getScore(), repaired.getScore());
            }
            System.out.printf(
                    "pipeline-ordering,greedy=%s,seed=%s,elapsedMs=%d,solverScore=%s,repairMs=%d,repairedScore=%s,repairedViolations=%d%n",
                    greedy,
                    seed == null ? "-" : seed,
                    elapsed,
                    solverScore,
                    repairMs,
                    repaired.getScore(),
                    countViolations(repaired).total());
        }
    }

    /**
     * Offline experiment hook: -Dfurong.acceptor=tabu switches the local search acceptor
     * to entity tabu (with the given -Dfurong.tabu.size, default 50) so worsening moves
     * can escape the full-grid class-swap plateau that late annealing cannot cross.
     */
    static void applyAcceptorExperiment(ai.timefold.solver.core.config.solver.SolverConfig config) {
        String acceptor = System.getProperty("furong.acceptor", "");
        if (!"tabu".equals(acceptor)) return;
        int tabuSize = Integer.getInteger("furong.tabu.size", 50);
        ai.timefold.solver.core.config.phase.PhaseConfig ch =
                new ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig();
        ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig ls =
                new ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig();
        ls.setAcceptorConfig(
                new ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig()
                        .withAcceptorTypeList(java.util.List.of(
                                ai.timefold.solver.core.config.localsearch.decider.acceptor.AcceptorType.ENTITY_TABU))
                        .withEntityTabuSize(tabuSize));
        ls.setTerminationConfig(
                new ai.timefold.solver.core.config.solver.termination.TerminationConfig()
                        .withSpentLimit(config.getTerminationConfig().getSpentLimit()));
        config.setPhaseConfigList(java.util.List.of(ch, ls));
    }

    /** Prints the best score over time to show whether local search moves at all. */
    @Test
    void probeConvergence() throws Exception {
        Path requirements = Path.of(System.getProperty("furong.requirements.csv", "/tmp/furong-requirements.csv"));
        List<Timeslot> timeslots = timeslots();
        List<Room> rooms = rooms(24);
        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        Set<String> breakAfter =
                new LinkedHashSet<>(
                        timeslots.stream().filter(Timeslot::isBreakAfter).map(Timeslot::getId).toList());

        List<LessonOccurrence> occurrences = new ArrayList<>();
        for (String line : Files.readAllLines(requirements)) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",", -1);
            long requirementId = Long.parseLong(cells[0]);
            int weeklyPeriods = Integer.parseInt(cells[5]);
            for (int index = 0; index < weeklyPeriods; index++) {
                LessonOccurrence occurrence =
                        new LessonOccurrence(
                                requirementId * 100 + index,
                                cells[3],
                                cells[3],
                                cells[4],
                                cells[4],
                                cells[2],
                                cells[2]);
                occurrence.setTeachingRequirementId(requirementId);
                occurrence.setRequirementCode(cells[1]);
                occurrence.setOccurrenceKey(requirementId + "-" + index);
                occurrence.setActivityIndex(index);
                occurrence.setDuration(1);
                occurrence.setStudentCount(Integer.parseInt(cells[7]));
                occurrence.setAvailablePeriodCodes(
                        new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
                occurrence.setNextPeriodCodes(nextPeriodCodes);
                occurrence.setBreakAfterPeriodCodes(breakAfter);
                applyRoomPolicy(occurrence);
                occurrences.add(occurrence);
            }
        }

        Timetable input = new Timetable(timeslots, rooms, occurrences, List.of(), List.of());
        input.initializeGreedyAssignments();
        System.out.println("greedy-init score=" + scoreOf(input) + " violations=" + countViolations(input).total());

        var config = new SolverConfiguration(Duration.ofSeconds(20), "NONE").solverConfig();
        config.setRandomSeed(0L);
        Solver<Timetable> solver =
                ai.timefold.solver.core.api.solver.SolverFactory.<Timetable>create(config).buildSolver();
        long started = System.nanoTime();
        solver.addEventListener(
                event -> {
                    long seconds = (System.nanoTime() - started) / 1_000_000_000;
                    System.out.printf(
                            "  best t+%ds score=%s violations=%d%n",
                            seconds,
                            event.getNewBestScore(),
                            countViolations((Timetable) event.getNewBestSolution()).total());
                });
        Timetable solved = solver.solve(input);
        System.out.println("convergence final score=" + solved.getScore() + " violations=" + countViolations(solved).total());
    }

    private String scoreOf(Timetable timetable) {
        var scoreManager =
                ai.timefold.solver.core.api.score.ScoreManager.<Timetable, ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore>create(
                        new SolverConfiguration(Duration.ofSeconds(1), "NONE").solverFactory());
        return String.valueOf(scoreManager.updateScore(timetable));
    }

    /**
     * Brute-force single-variable moves from the stuck solution. If a move improves the hard
     * score but the solver never takes it, the plateau is a move-selector problem rather than a
     * genuine local optimum.
     */
    @Test
    void probeLocalMoves() throws Exception {
        Path requirements = Path.of(System.getProperty("furong.requirements.csv", "/tmp/furong-requirements.csv"));
        List<Timeslot> timeslots = timeslots();
        List<Room> rooms = rooms(24);
        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        Set<String> breakAfter =
                new LinkedHashSet<>(
                        timeslots.stream().filter(Timeslot::isBreakAfter).map(Timeslot::getId).toList());

        List<LessonOccurrence> occurrences = new ArrayList<>();
        for (String line : Files.readAllLines(requirements)) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",", -1);
            long requirementId = Long.parseLong(cells[0]);
            int weeklyPeriods = Integer.parseInt(cells[5]);
            for (int index = 0; index < weeklyPeriods; index++) {
                LessonOccurrence occurrence =
                        new LessonOccurrence(
                                requirementId * 100 + index,
                                cells[3],
                                cells[3],
                                cells[4],
                                cells[4],
                                cells[2],
                                cells[2]);
                occurrence.setTeachingRequirementId(requirementId);
                occurrence.setRequirementCode(cells[1]);
                occurrence.setOccurrenceKey(requirementId + "-" + index);
                occurrence.setActivityIndex(index);
                occurrence.setDuration(1);
                occurrence.setStudentCount(Integer.parseInt(cells[7]));
                occurrence.setAvailablePeriodCodes(
                        new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
                occurrence.setNextPeriodCodes(nextPeriodCodes);
                occurrence.setBreakAfterPeriodCodes(breakAfter);
                applyRoomPolicy(occurrence);
                occurrences.add(occurrence);
            }
        }

        var solverFactory = new SolverConfiguration(Duration.ofSeconds(30), "NONE").solverFactory();
        var scoreManager =
                ai.timefold.solver.core.api.score.ScoreManager
                        .<Timetable, ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore>create(
                                solverFactory);
        var config = new SolverConfiguration(Duration.ofSeconds(30), "NONE").solverConfig();
        config.setRandomSeed(0L);
        Solver<Timetable> solver =
                ai.timefold.solver.core.api.solver.SolverFactory.<Timetable>create(config).buildSolver();

        Timetable input = new Timetable(timeslots, rooms, occurrences, List.of(), List.of());
        input.initializeGreedyAssignments();
        Timetable solved = solver.solve(input);
        var baseScore = scoreManager.updateScore(solved);
        System.out.println("stuck score=" + baseScore);

        Set<Long> violating = new LinkedHashSet<>();
        Map<String, List<LessonOccurrence>> buckets = new LinkedHashMap<>();
        for (LessonOccurrence occurrence : solved.getOccurrences()) {
            String slot = occurrence.getTimeslot().getId();
            buckets.computeIfAbsent(occurrence.getTeacherCode() + "@" + slot, k -> new ArrayList<>()).add(occurrence);
            buckets.computeIfAbsent(occurrence.getStudentGroupCode() + "@" + slot, k -> new ArrayList<>()).add(occurrence);
            buckets.computeIfAbsent(occurrence.getRoom().getId() + "@" + slot, k -> new ArrayList<>()).add(occurrence);
        }
        buckets.values().stream()
                .filter(list -> list.size() > 1)
                .forEach(list -> list.forEach(item -> violating.add(item.getId())));
        System.out.println("occurrences in conflict=" + violating.size());

        int improving = 0;
        String bestMove = null;
        var bestScore = baseScore;
        for (LessonOccurrence target : solved.getOccurrences()) {
            if (!violating.contains(target.getId())) continue;
            for (Timeslot timeslot : timeslots) {
                if (timeslot.getId().equals(target.getTimeslot().getId())) continue;
                Timetable candidate = copyWith(solved, target.getId(), timeslot, null);
                var score = scoreManager.updateScore(candidate);
                if (score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    bestMove = "timeslot " + target.getId() + " " + target.getSubjectCode() + "/"
                            + target.getTeacherCode() + " " + target.getStudentGroupCode() + " -> "
                            + timeslot.getId();
                    improving++;
                }
            }
            for (Room room : rooms) {
                if (room.getId().equals(target.getRoom().getId())) continue;
                Timetable candidate = copyWith(solved, target.getId(), null, room);
                var score = scoreManager.updateScore(candidate);
                if (score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    bestMove = "room " + target.getId() + " " + target.getSubjectCode() + "/"
                            + target.getTeacherCode() + " " + target.getStudentGroupCode() + " -> "
                            + room.getId();
                    improving++;
                }
            }
        }
        System.out.println("improving single moves found=" + improving + " best=" + bestMove + " bestScore=" + bestScore);
    }

    private Timetable copyWith(Timetable source, long occurrenceId, Timeslot timeslot, Room room) {
        List<LessonOccurrence> copies = new ArrayList<>();
        for (LessonOccurrence occurrence : source.getOccurrences()) {
            LessonOccurrence copy = occurrence.copy();
            if (occurrence.getId() == occurrenceId) {
                if (timeslot != null) copy.setTimeslot(timeslot);
                if (room != null) copy.setRoom(room);
            }
            copies.add(copy);
        }
        return new Timetable(source.getTimeslots(), source.getRooms(), copies, List.of(), List.of());
    }

    /** Compares the pipeline path (greedy init, construction heuristic skipped) against letting
     * Timefold's own construction heuristic initialise the same instance. */
    @Test
    void probeInitialisationStrategies() throws Exception {
        Path requirements = Path.of(System.getProperty("furong.requirements.csv", "/tmp/furong-requirements.csv"));
        List<Timeslot> timeslots = timeslots();
        List<Room> rooms = rooms(24);
        Map<String, String> nextPeriodCodes = PeriodContinuity.nextCodesFromTimeslots(timeslots);
        Set<String> breakAfter =
                new LinkedHashSet<>(
                        timeslots.stream().filter(Timeslot::isBreakAfter).map(Timeslot::getId).toList());

        List<LessonOccurrence> occurrences = new ArrayList<>();
        for (String line : Files.readAllLines(requirements)) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",", -1);
            long requirementId = Long.parseLong(cells[0]);
            int weeklyPeriods = Integer.parseInt(cells[5]);
            for (int index = 0; index < weeklyPeriods; index++) {
                LessonOccurrence occurrence =
                        new LessonOccurrence(
                                requirementId * 100 + index,
                                cells[3],
                                cells[3],
                                cells[4],
                                cells[4],
                                cells[2],
                                cells[2]);
                occurrence.setTeachingRequirementId(requirementId);
                occurrence.setRequirementCode(cells[1]);
                occurrence.setOccurrenceKey(requirementId + "-" + index);
                occurrence.setActivityIndex(index);
                occurrence.setDuration(1);
                occurrence.setStudentCount(Integer.parseInt(cells[7]));
                occurrence.setAvailablePeriodCodes(
                        new LinkedHashSet<>(timeslots.stream().map(Timeslot::getId).toList()));
                occurrence.setNextPeriodCodes(nextPeriodCodes);
                occurrence.setBreakAfterPeriodCodes(breakAfter);
                applyRoomPolicy(occurrence);
                occurrences.add(occurrence);
            }
        }

        long terminationMs = Long.getLong("furong.termination-ms", 30_000L);
        // Multi-threaded solving is Enterprise-only, so the Community edition is single-threaded.
        for (String mode : new String[] {"greedy-init", "timefold-ch"}) {
            List<LessonOccurrence> entities = new ArrayList<>();
            for (LessonOccurrence occurrence : occurrences) entities.add(occurrence.copy());
            Timetable input = new Timetable(timeslots, rooms, entities, List.of(), List.of());
            if ("greedy-init".equals(mode)) input.initializeGreedyAssignments();
            var config = new SolverConfiguration(Duration.ofMillis(terminationMs), "NONE").solverConfig();
            config.setRandomSeed(0L);
            Solver<Timetable> solver =
                    ai.timefold.solver.core.api.solver.SolverFactory.<Timetable>create(config).buildSolver();
            long started = System.nanoTime();
            Timetable solved = solver.solve(input);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            System.out.printf(
                    "%s,terminationMs=%d,elapsedMs=%d,score=%s,violations=%d%n",
                    mode, terminationMs, elapsed, solved.getScore(), countViolations(solved).total());
        }
    }

    private record Violations(int teacher, int studentGroup, int room, int unassigned) {
        int total() {
            return teacher + studentGroup + room + unassigned;
        }
    }

    private void applyRoomPolicy(LessonOccurrence occurrence) {
        if (Set.of("艺术-美术", "艺术-音乐", "科学")
                .contains(occurrence.getSubjectCode())) {
            occurrence.setRoomAssignmentMode("FLEXIBLE");
            return;
        }
        String group = occurrence.getStudentGroupCode();
        if (group.startsWith("FR-G")) {
            String[] parts = group.substring(4).split("-");
            if (parts.length == 2) {
                occurrence.setRoomAssignmentMode("HOME");
                occurrence.setHomeRoomCode(
                        String.format("FR-R%s%02d", parts[0], Integer.parseInt(parts[1])));
                return;
            }
        }
        if (group.length() > 3 && "一二三四五六".indexOf(group.charAt(0)) >= 0) {
            int separator = group.indexOf(' ');
            if (separator > 0) {
                int grade = "一二三四五六".indexOf(group.charAt(0)) + 1;
                int number = Integer.parseInt(group.substring(separator + 1).trim());
                occurrence.setRoomAssignmentMode("HOME");
                occurrence.setHomeRoomCode(String.format("FR-R%d%02d", grade, number));
                return;
            }
        }
        occurrence.setRoomAssignmentMode("FLEXIBLE");
    }

    private Violations countViolations(Timetable solved) {
        Map<String, Integer> teacherSlots = new LinkedHashMap<>();
        Map<String, Integer> groupSlots = new LinkedHashMap<>();
        Map<String, Integer> roomSlots = new LinkedHashMap<>();
        int unassigned = 0;
        for (LessonOccurrence occurrence : solved.getOccurrences()) {
            if (occurrence.getTimeslot() == null || occurrence.getRoom() == null) {
                unassigned++;
                continue;
            }
            String slot = occurrence.getTimeslot().getId();
            teacherSlots.merge(occurrence.getTeacherCode() + "@" + slot, 1, Integer::sum);
            groupSlots.merge(occurrence.getStudentGroupCode() + "@" + slot, 1, Integer::sum);
            roomSlots.merge(occurrence.getRoom().getId() + "@" + slot, 1, Integer::sum);
        }
        return new Violations(
                conflicts(teacherSlots), conflicts(groupSlots), conflicts(roomSlots), unassigned);
    }

    private int conflicts(Map<String, Integer> slots) {
        return slots.values().stream().mapToInt(count -> count > 1 ? count - 1 : 0).sum();
    }

    private List<Timeslot> timeslots() {
        List<Timeslot> timeslots = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            for (int period = 1; period <= 6; period++) {
                String code;
                if (weekday <= 2 && period <= 2) {
                    code = (weekday == 1 ? "MON-" : "TUE-") + period;
                } else {
                    code = "DAY-" + weekday + "-" + period;
                }
                Timeslot slot = new Timeslot(code, weekday, period, "第" + weekday + "教学日 第" + period + "节");
                slot.setContinuityGroup("DAY-" + weekday);
                slot.setBreakAfter(period == 3);
                timeslots.add(slot);
            }
        }
        return timeslots;
    }

    private List<Room> rooms(int count) {
        List<Room> rooms = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int grade = 1; grade <= 6; grade++) {
            int classes = grade == 6 ? 2 : 3;
            for (int number = 1; number <= classes; number++) {
                codes.add(String.format("FR-R%d%02d", grade, number));
            }
        }
        codes.add("FR-R901");
        codes.add("FR-R902");
        codes.add("FR-R903");
        codes.add("A101");
        codes.add("A102");
        codes.add("RAPI");
        codes.add("RHTTP");
        for (int index = 0; index < count; index++) {
            String code = index < codes.size() ? codes.get(index) : "EXTRA-R" + index;
            rooms.add(new Room(code, code, 50));
        }
        return rooms;
    }
}
