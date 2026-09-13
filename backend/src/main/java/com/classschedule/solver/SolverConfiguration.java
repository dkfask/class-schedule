package com.classschedule.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.SolverConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SolverConfiguration {
    private final java.time.Duration terminationSpent;
    private final String moveThreadCount;

    public SolverConfiguration(
            @Value("${app.solver.termination-spent:3s}") java.time.Duration terminationSpent,
            @Value("${app.solver.move-thread-count:NONE}") String moveThreadCount) {
        this.terminationSpent = terminationSpent;
        this.moveThreadCount = moveThreadCount;
    }

    @Bean
    public SolverFactory<Timetable> solverFactory() {
        return SolverFactory.create(solverConfig());
    }

    SolverConfig solverConfig() {
        return new SolverConfig()
                .withSolutionClass(Timetable.class)
                .withEntityClasses(LessonOccurrence.class)
                .withConstraintProviderClass(TimetableConstraintProvider.class)
                .withTerminationSpentLimit(terminationSpent)
                .withMoveThreadCount(moveThreadCount);
    }

    @Bean
    public SolverManager<Timetable, Long> solverManager(SolverFactory<Timetable> solverFactory) {
        return SolverManager.create(solverFactory);
    }

    java.time.Duration terminationSpent() {
        return terminationSpent;
    }
}
