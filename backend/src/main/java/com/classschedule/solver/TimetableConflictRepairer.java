package com.classschedule.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic post-solve repair for hard-conflict leftovers.
 *
 * Local search samples the move universe uniformly; on tight instances (95%+ class
 * utilization) improving moves are so rare that the solver plateaus with a handful of
 * class/teacher/room double bookings it never samples a fix for. This pass restricts
 * the neighborhood to the conflicting occurrences and exhaustively evaluates every
 * (timeslot, room) reassignment, keeping only moves that strictly improve the hard
 * score. Full rescoring per candidate keeps this on public APIs and stays fast enough
 * because the conflicting set is tiny.
 */
@Component
public class TimetableConflictRepairer {
    private static final Logger log = LoggerFactory.getLogger(TimetableConflictRepairer.class);

    private final SolverFactory<Timetable> solverFactory;
    private final ScoreManager<Timetable, HardMediumSoftScore> scoreManager;

    public TimetableConflictRepairer(SolverFactory<Timetable> solverFactory) {
        this.solverFactory = solverFactory;
        this.scoreManager = ScoreManager.create(solverFactory);
    }

    public Timetable repair(Timetable solution, int maxPasses, long budgetMs) {
        // Fast path: the solver reports its own score; only pay the score-director warmup
        // when there is something structurally conflicted to repair.
        boolean fast = solution.getScore() != null
                && solution.getScore().hardScore() >= 0
                && conflictedOccurrenceIds(solution.getOccurrences()).isEmpty();
        if (fast) {
            log.info("conflict repair skipped, no conflicts, score {}", solution.getScore());
            return solution;
        }
        long deadline = System.nanoTime() + budgetMs * 1_000_000;
        int stalledPasses = 0;
        int lastConflicted = conflictedOccurrenceIds(solution.getOccurrences()).size();
        for (int pass = 0; pass < maxPasses; pass++) {
            if (System.nanoTime() > deadline) {
                solution.setScore(scoreManager.updateScore(solution));
                log.warn(
                        "conflict repair hit the time budget ({}ms) after {} pass(es), score {}",
                        budgetMs,
                        pass,
                        solution.getScore());
                return solution;
            }
            RepairMove move = findBestImprovingMove(solution);
            if (move == null) {
                solution.setScore(scoreManager.updateScore(solution));
                log.info("conflict repair converged after {} pass(es), score {}", pass, solution.getScore());
                return solution;
            }
            move.occurrence().setTimeslot(move.timeslot());
            move.occurrence().setRoom(move.room());
            if (move.swapWith() != null) {
                move.swapWith().setTimeslot(move.previousTimeslot());
                move.swapWith().setRoom(move.previousRoom());
            }
            // Score can improve while the conflict count stays flat (pure shuffling on
            // structurally infeasible instances) — bail out after two such passes.
            int nowConflicted = conflictedOccurrenceIds(solution.getOccurrences()).size();
            if (nowConflicted >= lastConflicted) {
                stalledPasses++;
                if (stalledPasses >= 2) {
                    solution.setScore(scoreManager.updateScore(solution));
                    log.info(
                            "conflict repair stalled at {} conflicted occurrence(s), score {}",
                            nowConflicted,
                            solution.getScore());
                    return solution;
                }
            } else {
                stalledPasses = 0;
            }
            lastConflicted = nowConflicted;
        }
        solution.setScore(scoreManager.updateScore(solution));
        log.warn("conflict repair hit the pass limit ({}) with score {}", maxPasses, solution.getScore());
        return solution;
    }

    /** One exhaustive scan for the best hard-improving reassignment of a conflicting occurrence. */
    private RepairMove findBestImprovingMove(Timetable solution) {
        List<LessonOccurrence> occurrences = solution.getOccurrences();
        Set<Long> conflicted = conflictedOccurrenceIds(occurrences);
        if (conflicted.isEmpty()) return null;
        HardMediumSoftScore baseline = scoreManager.updateScore(solution);
        boolean allowBroadSwaps = Boolean.parseBoolean(System.getProperty("repair.broadSwaps", "false"));
        RepairMove best = null;
        for (LessonOccurrence occurrence : occurrences) {
            if (!conflicted.contains(occurrence.getId())) continue;
            best = betterOf(best, reassignmentMoves(solution, baseline, occurrence, occurrences));
            if (best != null && best.score().hardScore() >= 0) return best;
            best = betterOf(best, sameClassSwapMoves(solution, baseline, occurrence, occurrences));
            if (best != null && best.score().hardScore() >= 0) return best;
        }
        if (best == null && allowBroadSwaps) {
            // Last resort on plateaus no single-entity or same-class move fixes: exchange the
            // full (timeslot, room) assignment between a conflicting occurrence and any other.
            for (LessonOccurrence occurrence : occurrences) {
                if (!conflicted.contains(occurrence.getId())) continue;
                best = betterOf(best, broadSwapMoves(solution, baseline, occurrence, occurrences));
                if (best != null && best.score().hardScore() >= 0) return best;
            }
        }
        return best;
    }

    private List<RepairMove> reassignmentMoves(
            Timetable solution, HardMediumSoftScore baseline, LessonOccurrence occurrence, List<LessonOccurrence> all) {
        List<RepairMove> moves = new ArrayList<>();
        Timeslot currentTimeslot = occurrence.getTimeslot();
        Room currentRoom = occurrence.getRoom();
        for (Timeslot timeslot : candidateTimeslots(occurrence, solution.getTimeslots())) {
            for (Room room : candidateRooms(occurrence, solution.getRooms())) {
                if (sameAssignment(timeslot, room, currentTimeslot, currentRoom)) continue;
                addIfImproves(moves, solution, baseline, occurrence, timeslot, room, null);
            }
        }
        return moves;
    }

    /** Swapping two occurrences of the same class keeps every class slot unique by construction. */
    private List<RepairMove> sameClassSwapMoves(
            Timetable solution, HardMediumSoftScore baseline, LessonOccurrence occurrence, List<LessonOccurrence> all) {
        List<RepairMove> moves = new ArrayList<>();
        for (LessonOccurrence other : all) {
            if (other.getId() == occurrence.getId()) continue;
            if (!other.getStudentGroupCode().equals(occurrence.getStudentGroupCode())) continue;
            if (other.getTimeslot() == null) continue;
            addIfImproves(moves, solution, baseline, occurrence, other.getTimeslot(), other.getRoom(), other);
        }
        return moves;
    }

    private List<RepairMove> broadSwapMoves(
            Timetable solution, HardMediumSoftScore baseline, LessonOccurrence occurrence, List<LessonOccurrence> all) {
        List<RepairMove> moves = new ArrayList<>();
        for (LessonOccurrence other : all) {
            if (other.getId() == occurrence.getId()) continue;
            if (other.getTimeslot() == null) continue;
            addIfImproves(moves, solution, baseline, occurrence, other.getTimeslot(), other.getRoom(), other);
        }
        return moves;
    }

    /**
     * Scores candidate "occurrence goes to (timeslot, room)" — when {@code swapWith} is
     * non-null the swap partner simultaneously takes occurrence's current assignment, so
     * class slot uniqueness inside the swapped pair is preserved.
     */
    private void addIfImproves(
            List<RepairMove> moves,
            Timetable solution,
            HardMediumSoftScore baseline,
            LessonOccurrence occurrence,
            Timeslot timeslot,
            Room room,
            LessonOccurrence swapWith) {
        Timetable candidate = copyWith(solution, occurrence.getId(), timeslot, room);
        if (swapWith != null) {
            LessonOccurrence swapCopy = null;
            for (LessonOccurrence item : candidate.getOccurrences()) {
                if (item.getId() == swapWith.getId()) swapCopy = item;
            }
            swapCopy.setTimeslot(occurrence.getTimeslot());
            swapCopy.setRoom(occurrence.getRoom());
        }
        HardMediumSoftScore score = scoreManager.updateScore(candidate);
        if (score.hardScore() > baseline.hardScore()) {
            moves.add(new RepairMove(occurrence, timeslot, room, score, swapWith));
        }
    }

    private RepairMove betterOf(RepairMove current, List<RepairMove> candidates) {
        RepairMove best = current;
        for (RepairMove candidate : candidates) {
            if (best == null || candidate.score().hardScore() > best.score().hardScore()) {
                best = candidate;
            }
        }
        return best;
    }

    private Timetable copyWith(Timetable source, long occurrenceId, Timeslot timeslot, Room room) {
        List<LessonOccurrence> copies = new ArrayList<>();
        for (LessonOccurrence occurrence : source.getOccurrences()) {
            LessonOccurrence copy = occurrence.copy();
            if (occurrence.getId() == occurrenceId) {
                copy.setTimeslot(timeslot);
                copy.setRoom(room);
            }
            copies.add(copy);
        }
        return new Timetable(source.getTimeslots(), source.getRooms(), copies, List.of(), List.of());
    }

    private List<Timeslot> candidateTimeslots(LessonOccurrence occurrence, List<Timeslot> timeslots) {
        Set<String> available = occurrence.getAvailablePeriodCodes();
        if (available == null || available.isEmpty()) return timeslots;
        List<Timeslot> filtered = new ArrayList<>();
        for (Timeslot timeslot : timeslots) {
            if (available.contains(timeslot.getId())) filtered.add(timeslot);
        }
        return filtered;
    }

    private List<Room> candidateRooms(LessonOccurrence occurrence, List<Room> rooms) {
        if (!"HOME".equals(occurrence.getRoomAssignmentMode())
                || occurrence.getHomeRoomCode() == null) {
            return rooms;
        }
        // A HOME occurrence only scores without the binding penalty in its bound room;
        // trying other rooms never wins, so keep the neighborhood tight.
        List<Room> home = new ArrayList<>();
        for (Room room : rooms) {
            if (room.getId().equals(occurrence.getHomeRoomCode())) home.add(room);
        }
        return home;
    }

    private boolean sameAssignment(Timeslot timeslot, Room room, Timeslot currentTimeslot, Room currentRoom) {
        return (currentTimeslot == null ? timeslot == null : timeslot.equals(currentTimeslot))
                && (currentRoom == null ? room == null : room.equals(currentRoom));
    }

    private Set<Long> conflictedOccurrenceIds(List<LessonOccurrence> occurrences) {
        Set<Long> conflicted = new HashSet<>();
        Map<String, List<LessonOccurrence>> buckets = new HashMap<>();
        for (LessonOccurrence occurrence : occurrences) {
            if (occurrence.getTimeslot() == null) {
                conflicted.add(occurrence.getId());
                continue;
            }
            String slot = occurrence.getTimeslot().getId();
            if (occurrence.getTeacherCode() != null) {
                buckets.computeIfAbsent("T:" + occurrence.getTeacherCode() + "@" + slot, k -> new ArrayList<>())
                        .add(occurrence);
            }
            buckets.computeIfAbsent("C:" + occurrence.getStudentGroupCode() + "@" + slot, k -> new ArrayList<>())
                    .add(occurrence);
            if (occurrence.getRoom() != null) {
                buckets.computeIfAbsent("R:" + occurrence.getRoom().getId() + "@" + slot, k -> new ArrayList<>())
                        .add(occurrence);
            }
        }
        for (List<LessonOccurrence> bucket : buckets.values()) {
            if (bucket.size() > 1) {
                for (LessonOccurrence occurrence : bucket) conflicted.add(occurrence.getId());
            }
        }
        return conflicted;
    }

    private record RepairMove(
            LessonOccurrence occurrence,
            Timeslot timeslot,
            Room room,
            HardMediumSoftScore score,
            LessonOccurrence swapWith) {

        Timeslot previousTimeslot() {
            return swapWith.getTimeslot();
        }

        Room previousRoom() {
            return swapWith.getRoom();
        }
    }
}
