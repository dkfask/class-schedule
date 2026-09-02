package com.classschedule.masterdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AcademicTermResolver {
    private final JdbcTemplate jdbc;

    public AcademicTermResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String resolve(String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) return requestedCode.trim();
        return jdbc.query(
                        "SELECT code FROM academic_term WHERE status <> 'ARCHIVED' ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END, id LIMIT 1",
                        (rs, rowNum) -> rs.getString("code"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有可用学期"));
    }
}
