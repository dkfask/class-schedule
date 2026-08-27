package com.classschedule.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.config.solver.SolverConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SolverConfiguration {
    @Bean
    public SolverFactory<Timetable> solverFactory() {
        return SolverFactory.create(new SolverConfig()
            .withSolutionClass(Timetable.class)
            .withEntityClasses(LessonOccurrence.class)
            .withConstraintProviderClass(TimetableConstraintProvider.class)
            .withTerminationSpentLimit(java.time.Duration.ofSeconds(3)));
    }

    @Bean
    public SolverManager<Timetable, Long> solverManager(SolverFactory<Timetable> solverFactory) {
        return SolverManager.create(solverFactory);
    }
}
