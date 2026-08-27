package com.classschedule.solver;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;

final class SolverConfigurationForTest {
    private SolverConfigurationForTest() {}

    static Solver<Timetable> createSolver() {
        SolverFactory<Timetable> factory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(Timetable.class)
                .withEntityClasses(LessonOccurrence.class)
                .withConstraintProviderClass(TimetableConstraintProvider.class)
                .withTerminationSpentLimit(java.time.Duration.ofSeconds(2)));
        return factory.buildSolver();
    }
}
