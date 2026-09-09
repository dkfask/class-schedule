package com.classschedule.api;

import com.classschedule.solver.SolveReadiness;
import com.classschedule.solver.SolveReadinessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleTemplateService {
    private final JdbcTemplate jdbc;
    private final SolveReadinessService readiness;

    public RuleTemplateService(JdbcTemplate jdbc, SolveReadinessService readiness) {
        this.jdbc = jdbc;
        this.readiness = readiness;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT id,code,version,name,description,maintained_by,change_note,created_at,updated_at FROM rule_template WHERE active=TRUE ORDER BY code,version DESC");
    }

    @Transactional
    public Map<String, Object> create(RuleTemplateRequest request, String actor) {
        String source = required(request.sourceTermCode(), "来源学期不能为空");
        String code = required(request.code(), "模板编码不能为空");
        String name = required(request.name(), "模板名称不能为空");
        requireTerm(source);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM rule_template WHERE code=?",
                Integer.class,
                code);
        Long templateId = jdbc.queryForObject(
                "INSERT INTO rule_template(code,version,name,description,maintained_by,change_note) VALUES(?,?,?,?,?,?) RETURNING id",
                Long.class,
                code,
                version,
                name,
                blankToNull(request.description()),
                blankToNull(request.maintainedBy()),
                blankToNull(request.changeNote()));
        int itemCount = jdbc.update(
                "INSERT INTO rule_template_item(template_id,rule_code,scope_type,scope_code,int_value,text_value,severity,weight) SELECT ?,i.rule_code,i.scope_type,i.scope_code,i.int_value,i.text_value,i.severity,i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND p.active=TRUE AND i.active=TRUE",
                templateId,
                source);
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('RULE_TEMPLATE_CREATED','RULE_TEMPLATE',?,?,(SELECT id FROM app_user WHERE username=?),'USER',jsonb_build_object('code',?::text,'version',?::int,'sourceTermCode',?::text))",
                String.valueOf(templateId),
                actor,
                actor,
                code,
                version,
                source);
        return Map.of("id", templateId, "code", code, "version", version, "itemCount", itemCount);
    }

    public Map<String, Object> preview(long templateId, String termCode) {
        String target = required(termCode, "目标学期不能为空");
        requireTerm(target);
        List<Map<String, Object>> templateItems = items(templateId);
        Map<String, Map<String, Object>> current = currentItems(target);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> modified = new ArrayList<>();
        List<Map<String, Object>> retired = new ArrayList<>();
        List<String> unmatchedScopes = new ArrayList<>();
        for (Map<String, Object> item : templateItems) {
            String key = key(item);
            validateScope(target, item, unmatchedScopes);
            Map<String, Object> existing = current.remove(key);
            if (existing == null) added.add(item);
            else if (!sameValues(item, existing)) modified.add(item);
        }
        retired.addAll(current.values());
        SolveReadiness checked = readiness.check(target);
        return Map.of(
                "templateId", templateId,
                "termCode", target,
                "added", added,
                "modified", modified,
                "retired", retired,
                "unmatchedScopes", unmatchedScopes,
                "readiness", checked,
                "canApply", unmatchedScopes.isEmpty() && checked.ready());
    }

    @Transactional
    public Map<String, Object> apply(long templateId, String termCode, String actor) {
        Map<String, Object> plan = preview(templateId, termCode);
        if (!Boolean.TRUE.equals(plan.get("canApply")))
            throw new IllegalArgumentException("模板不能应用：请先处理未匹配资源或完成数据准备");
        Long profile = jdbc.queryForObject(
                "INSERT INTO schedule_rule_profile(term_id,code,name) VALUES((SELECT id FROM academic_term WHERE code=?),'DEFAULT','规则模板应用') ON CONFLICT(term_id,code) DO UPDATE SET active=TRUE RETURNING id",
                Long.class,
                termCode);
        List<Map<String, Object>> templateItems = items(templateId);
        Map<String, Map<String, Object>> current = currentItems(termCode);
        int retired = 0;
        for (Map<String, Object> row : current.values()) {
            if (templateItems.stream().noneMatch(item -> key(item).equals(key(row)))) {
                retired += jdbc.update("UPDATE schedule_rule_instance SET active=FALSE WHERE id=?", row.get("id"));
            }
        }
        for (Map<String, Object> item : templateItems) {
            jdbc.update(
                    "INSERT INTO schedule_rule_instance(profile_id,rule_code,scope_type,scope_code,int_value,text_value,severity,weight) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(profile_id,rule_code,scope_type,scope_code) DO UPDATE SET int_value=EXCLUDED.int_value,text_value=EXCLUDED.text_value,severity=EXCLUDED.severity,weight=EXCLUDED.weight,active=TRUE",
                    profile,
                    item.get("rule_code"),
                    item.get("scope_type"),
                    item.get("scope_code"),
                    item.get("int_value"),
                    item.get("text_value"),
                    item.get("severity"),
                    item.get("weight"));
        }
        jdbc.update(
                "INSERT INTO audit_event(action,aggregate_type,aggregate_id,actor,actor_user_id,actor_kind,detail) VALUES('RULE_TEMPLATE_APPLIED','RULE_TEMPLATE',?,?,(SELECT id FROM app_user WHERE username=?),'USER',jsonb_build_object('termCode',?::text,'itemCount',?::int,'retired',?::int))",
                String.valueOf(templateId),
                actor,
                actor,
                termCode,
                templateItems.size(),
                retired);
        return Map.of("status", "APPLIED", "templateId", templateId, "termCode", termCode, "itemCount", templateItems.size(), "retired", retired);
    }

    private List<Map<String, Object>> items(long templateId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM rule_template WHERE id=? AND active=TRUE", Integer.class, templateId) == 0)
            throw new IllegalArgumentException("规则模板不存在: " + templateId);
        return jdbc.queryForList(
                "SELECT i.id,i.rule_code,i.scope_type,i.scope_code,i.int_value,i.text_value,i.severity,i.weight FROM rule_template_item i WHERE i.template_id=? ORDER BY i.rule_code,i.scope_type,i.scope_code",
                templateId);
    }

    private Map<String, Map<String, Object>> currentItems(String termCode) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT i.id,i.rule_code,i.scope_type,i.scope_code,i.int_value,i.text_value,i.severity,i.weight FROM schedule_rule_instance i JOIN schedule_rule_profile p ON p.id=i.profile_id JOIN academic_term t ON t.id=p.term_id WHERE t.code=? AND p.active=TRUE AND i.active=TRUE",
                termCode)) result.put(key(row), row);
        return result;
    }

    private void validateScope(String termCode, Map<String, Object> item, List<String> missing) {
        String type = String.valueOf(item.get("scope_type"));
        String code = String.valueOf(item.get("scope_code"));
        if ("TERM".equals(type)) return;
        String table = switch (type) {
            case "TEACHER" -> "teacher";
            case "STUDENT_GROUP" -> "student_group";
            case "SUBJECT" -> "subject";
            case "TEACHING_REQUIREMENT" -> "teaching_requirement";
            default -> null;
        };
        if (table == null) {
            missing.add(type + ": " + code);
            return;
        }
        String query = "TEACHING_REQUIREMENT".equals(type)
                ? "SELECT COUNT(*) FROM teaching_requirement r JOIN academic_term t ON t.id=r.term_id WHERE r.code=? AND t.code=? AND r.active=TRUE"
                : "SELECT COUNT(*) FROM " + table + " WHERE code=? AND active=TRUE";
        Integer count = "TEACHING_REQUIREMENT".equals(type)
                ? jdbc.queryForObject(query, Integer.class, code, termCode)
                : jdbc.queryForObject(query, Integer.class, code);
        if (count == null || count == 0) missing.add(type + ": " + code);
    }

    private boolean sameValues(Map<String, Object> left, Map<String, Object> right) {
        return java.util.Objects.equals(left.get("int_value"), right.get("int_value"))
                && java.util.Objects.equals(left.get("text_value"), right.get("text_value"))
                && java.util.Objects.equals(left.get("severity"), right.get("severity"))
                && java.util.Objects.equals(left.get("weight"), right.get("weight"));
    }

    private String key(Map<String, Object> row) {
        return row.get("rule_code") + "|" + row.get("scope_type") + "|" + row.get("scope_code");
    }

    private void requireTerm(String code) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM academic_term WHERE code=? AND status <> 'ARCHIVED'", Integer.class, code) == 0)
            throw new IllegalArgumentException("学期不存在或已归档: " + code);
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
