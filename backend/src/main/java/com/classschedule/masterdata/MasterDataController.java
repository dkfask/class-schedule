package com.classschedule.masterdata;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/master-data")
public class MasterDataController {
    private final JdbcTemplate jdbc;

    public MasterDataController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "terms", rows("SELECT code, name, status FROM academic_term ORDER BY id"),
                "periods",
                        rows(
                                "SELECT code, weekday, period_no AS period, label FROM period_template ORDER BY weekday, period_no"),
                "teachers",
                        rows("SELECT code, name FROM teacher WHERE active = TRUE ORDER BY code"),
                "studentGroups",
                        rows(
                                "SELECT code, name, group_type AS \"groupType\" FROM student_group WHERE active = TRUE ORDER BY code"),
                "subjects",
                        rows("SELECT code, name FROM subject WHERE active = TRUE ORDER BY code"),
                "rooms",
                        rows(
                                "SELECT code, name, capacity, room_type AS \"roomType\" FROM room WHERE active = TRUE ORDER BY code"));
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.queryForList(sql);
    }
}
