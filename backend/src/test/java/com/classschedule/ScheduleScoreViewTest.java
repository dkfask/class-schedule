package com.classschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.classschedule.schedule.ScheduleScoreView;
import org.junit.jupiter.api.Test;

class ScheduleScoreViewTest {
    @Test
    void parsesLegacyTwoComponentScoreWithZeroMedium() {
        ScheduleScoreView score = ScheduleScoreView.parse("0hard/0soft");

        assertThat(score.valid()).isTrue();
        assertThat(score.hardScore()).isZero();
        assertThat(score.mediumScore()).isZero();
        assertThat(score.softScore()).isZero();
        assertThat(score.hardFeasible()).isTrue();
    }

    @Test
    void parsesThreeComponentScoreAndNegativeValues() {
        ScheduleScoreView score = ScheduleScoreView.parse("-2hard/-5medium/-9soft");

        assertThat(score.valid()).isTrue();
        assertThat(score.hardScore()).isEqualTo(-2);
        assertThat(score.mediumScore()).isEqualTo(-5);
        assertThat(score.softScore()).isEqualTo(-9);
        assertThat(score.hardFeasible()).isFalse();
    }

    @Test
    void invalidAndEmptyScoresRemainExplicitlyUnscored() {
        assertThat(ScheduleScoreView.parse(null).valid()).isFalse();
        assertThat(ScheduleScoreView.parse("等待结果").valid()).isFalse();
        assertThat(ScheduleScoreView.parse("0hard").valid()).isFalse();
        assertThat(ScheduleScoreView.parse("0hard/0medium").valid()).isFalse();
        assertThat(ScheduleScoreView.parse("not-a-score").valid()).isFalse();
    }

    @Test
    void serializesTimefoldScoreWithoutLosingComponents() {
        ScheduleScoreView score =
                ScheduleScoreView.from(
                        ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore
                                .of(-1, -3, -7));

        assertThat(score.score()).isEqualTo("-1hard/-3medium/-7soft");
        assertThat(score.hardScore()).isEqualTo(-1);
        assertThat(score.mediumScore()).isEqualTo(-3);
        assertThat(score.softScore()).isEqualTo(-7);
    }
}
