package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PeriodContinuityTest {
    @Test
    void usesTheNextLegalPeriodAfterTheWholeDuration() {
        Map<String, String> next =
                PeriodContinuity.nextCodes(
                        List.of(
                                new PeriodContinuity.Segment("MON-1", 1, 1, "REGULAR", false),
                                new PeriodContinuity.Segment("MON-2", 1, 2, "REGULAR", false),
                                new PeriodContinuity.Segment("MON-3", 1, 3, "REGULAR", true),
                                new PeriodContinuity.Segment("MON-4", 1, 4, "REGULAR", false)));

        assertThat(PeriodContinuity.codeAfter(next, "MON-1", 1)).isEqualTo("MON-2");
        assertThat(PeriodContinuity.isConsecutive(next, "MON-1", "MON-2", 1)).isTrue();
        assertThat(PeriodContinuity.isConsecutive(next, "MON-1", "MON-3", 2)).isTrue();
        assertThat(PeriodContinuity.isConsecutive(next, "MON-2", "MON-4", 2)).isFalse();
    }

    @Test
    void doesNotJoinDifferentContinuityGroupsOrWeekdays() {
        Map<String, String> next =
                PeriodContinuity.nextCodes(
                        List.of(
                                new PeriodContinuity.Segment("MON-1", 1, 1, "REGULAR", false),
                                new PeriodContinuity.Segment("MON-2", 1, 2, "BREAKOUT", false),
                                new PeriodContinuity.Segment("TUE-1", 2, 1, "REGULAR", false),
                                new PeriodContinuity.Segment("TUE-2", 2, 2, "REGULAR", false)));

        assertThat(next).containsEntry("TUE-1", "TUE-2").doesNotContainKey("MON-1");
        assertThat(PeriodContinuity.isConsecutive(next, "MON-1", "MON-2", 1)).isFalse();
        assertThat(PeriodContinuity.isConsecutive(next, "MON-1", "TUE-1", 1)).isFalse();
    }
}
