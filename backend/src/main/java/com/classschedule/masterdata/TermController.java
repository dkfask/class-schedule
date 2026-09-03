package com.classschedule.masterdata;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/terms")
public class TermController {
    private final JdbcTemplate jdbc;

    public TermController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT code, name, status FROM academic_term WHERE status <> 'ARCHIVED' ORDER BY id");
    }
}
