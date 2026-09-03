package com.classschedule.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SolveReadinessServiceTest {
    @Test
    void rejectsBlankTermWithoutDatabaseLookup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        SolveReadiness result = new SolveReadinessService(jdbc).check(" ");

        assertThat(result.ready()).isFalse();
        assertThat(result.issues()).extracting(SolveReadiness.Issue::code)
                .containsExactly("TERM_REQUIRED");
    }

    @Test
    void rejectsMissingTerm() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("2026-FALL")))
                .thenReturn(List.of());

        SolveReadiness result = new SolveReadinessService(jdbc).check("2026-FALL");

        assertThat(result.ready()).isFalse();
        assertThat(result.issues()).extracting(SolveReadiness.Issue::code)
                .containsExactly("TERM_NOT_FOUND");
    }

    @Test
    void rejectsArchivedTerm() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("2026-FALL")))
                .thenReturn(List.of("ARCHIVED"));

        SolveReadiness result = new SolveReadinessService(jdbc).check("2026-FALL");

        assertThat(result.ready()).isFalse();
        assertThat(result.issues()).extracting(SolveReadiness.Issue::code)
                .containsExactly("TERM_ARCHIVED");
    }

    @Test
    void reportsMissingResourcesAndRequirements() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("2026-FALL")))
                .thenReturn(List.of("ACTIVE"));
        when(jdbc.queryForObject(contains("SELECT id FROM academic_term"), eq(Long.class), eq("2026-FALL")))
                .thenReturn(1L);
        when(jdbc.queryForObject(contains("period_template"), eq(Integer.class), eq(1L)))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("room WHERE active"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("teaching_requirement"), eq(Integer.class), eq(1L)))
                .thenReturn(0);

        SolveReadiness result = new SolveReadinessService(jdbc).check("2026-FALL");

        assertThat(result.ready()).isFalse();
        assertThat(result.timeslotCount()).isZero();
        assertThat(result.roomCount()).isZero();
        assertThat(result.requirementCount()).isZero();
        assertThat(result.issues()).extracting(SolveReadiness.Issue::code)
                .containsExactly("NO_TIMESLOTS", "NO_ACTIVE_ROOMS", "NO_ACTIVE_REQUIREMENTS");
    }

    @Test
    void acceptsReadyTerm() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("2026-FALL")))
                .thenReturn(List.of("ACTIVE"));
        when(jdbc.queryForObject(contains("SELECT id FROM academic_term"), eq(Long.class), eq("2026-FALL")))
                .thenReturn(1L);
        when(jdbc.queryForObject(contains("period_template"), eq(Integer.class), eq(1L)))
                .thenReturn(4);
        when(jdbc.queryForObject(contains("room WHERE active"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbc.queryForObject(contains("teaching_requirement"), eq(Integer.class), eq(1L)))
                .thenReturn(3);

        SolveReadiness result = new SolveReadinessService(jdbc).check("2026-FALL");

        assertThat(result.ready()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.timeslotCount()).isEqualTo(4);
        assertThat(result.roomCount()).isEqualTo(2);
        assertThat(result.requirementCount()).isEqualTo(3);
    }
}
