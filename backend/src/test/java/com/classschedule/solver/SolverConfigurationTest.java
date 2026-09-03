package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SolverConfigurationTest {
    @Test
    void terminationSpentLimitIsProvidedByConfiguration() {
        SolverConfiguration configuration = new SolverConfiguration(Duration.ofMillis(750));

        assertThat(configuration.terminationSpent()).isEqualTo(Duration.ofMillis(750));
        assertThat(configuration.solverConfig().getTerminationConfig().getSpentLimit())
                .isEqualTo(Duration.ofMillis(750));
    }
}
