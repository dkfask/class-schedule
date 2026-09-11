package com.classschedule.masterdata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MasterDataRepository {
    private final JdbcTemplate jdbc;

    public MasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MasterDataList list(
            MasterDataResource resource, String query, boolean active, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = safePage * safeSize;
        String where = active ? "active = TRUE" : "TRUE";
        String q = query == null ? "" : query.trim();
        String filter = q.isBlank() ? "" : " AND (code ILIKE ? OR name ILIKE ?)";
        String table = resource.table();
        long total =
                q.isBlank()
                        ? jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class)
                        : jdbc.queryForObject(
                                "SELECT COUNT(*) FROM " + table + " WHERE " + where + filter,
                                Long.class,
                                "%" + q + "%",
                                "%" + q + "%");
        List<MasterDataItem> items =
                q.isBlank()
                        ? jdbc.query(
                                "SELECT id, code, name, active, "
                                        + selectColumns(resource)
                                        + " FROM "
                                        + table
                                        + " WHERE "
                                        + where
                                        + " ORDER BY code LIMIT ? OFFSET ?",
                                (rs, row) -> item(rs, resource),
                                safeSize,
                                offset)
                        : jdbc.query(
                                "SELECT id, code, name, active, "
                                        + selectColumns(resource)
                                        + " FROM "
                                        + table
                                        + " WHERE "
                                        + where
                                        + filter
                                        + " ORDER BY code LIMIT ? OFFSET ?",
                                (rs, row) -> item(rs, resource),
                                "%" + q + "%",
                                "%" + q + "%",
                                safeSize,
                                offset);
        return new MasterDataList(items, safePage, safeSize, total);
    }

    public MasterDataItem get(MasterDataResource resource, long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, code, name, active, "
                            + selectColumns(resource)
                            + " FROM "
                            + resource.table()
                            + " WHERE id = ?",
                    (rs, row) -> item(rs, resource),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException(resource.label() + "不存在: " + id);
        }
    }

    public MasterDataItem save(MasterDataResource resource, MasterDataRequest request) {
        String table = resource.table();
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE code = ?",
                        Integer.class,
                        request.code())
                > 0) throw new IllegalArgumentException("编码已存在: " + request.code());
        if (resource == MasterDataResource.STUDENT_GROUPS) validateHomeRoom(request.homeRoomCode());
        Long id;
        if (resource == MasterDataResource.ROOMS) {
            id =
                    jdbc.queryForObject(
                            "INSERT INTO room (code,name,capacity,room_type) VALUES (?,?,?,?) RETURNING id",
                            Long.class,
                            request.code(),
                            request.name(),
                            request.capacity(),
                            request.roomType());
        } else if (resource == MasterDataResource.STUDENT_GROUPS) {
            id =
                    jdbc.queryForObject(
                            "INSERT INTO student_group (code,name,student_count,home_room_id) VALUES (?,?,?,(SELECT id FROM room WHERE code=? AND active=TRUE)) RETURNING id",
                            Long.class,
                            request.code(),
                            request.name(),
                            request.studentCount(),
                            nullable(request.homeRoomCode()));
        } else {
            id =
                    jdbc.queryForObject(
                            "INSERT INTO "
                                    + table
                                    + " (code,name) VALUES (?,?) RETURNING id",
                            Long.class,
                            request.code(),
                            request.name());
        }
        jdbc.update(
                "INSERT INTO audit_event (action, aggregate_type, aggregate_id, detail) VALUES ('CREATE', ?, ?, jsonb_build_object('code', ?))",
                resource.name(),
                String.valueOf(id),
                request.code());
        return get(resource, id);
    }

    public MasterDataItem update(MasterDataResource resource, long id, MasterDataRequest request) {
        get(resource, id);
        Integer duplicate =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + resource.table() + " WHERE code = ? AND id <> ?",
                        Integer.class,
                        request.code(),
                        id);
        if (duplicate != null && duplicate > 0)
            throw new IllegalArgumentException("编码已存在: " + request.code());
        if (resource == MasterDataResource.ROOMS)
            jdbc.update(
                    "UPDATE room SET code=?, name=?, capacity=?, room_type=? WHERE id=?",
                    request.code(),
                    request.name(),
                    request.capacity(),
                    request.roomType(),
                    id);
        else if (resource == MasterDataResource.STUDENT_GROUPS) {
            validateHomeRoom(request.homeRoomCode());
            jdbc.update(
                    "UPDATE student_group SET code=?, name=?, student_count=?, home_room_id=(SELECT id FROM room WHERE code=? AND active=TRUE) WHERE id=?",
                    request.code(),
                    request.name(),
                    request.studentCount(),
                    nullable(request.homeRoomCode()),
                    id);
        }
        else
            jdbc.update(
                    "UPDATE " + resource.table() + " SET code=?, name=? WHERE id=?",
                    request.code(),
                    request.name(),
                    id);
        jdbc.update(
                "INSERT INTO audit_event (action, aggregate_type, aggregate_id, detail) VALUES ('UPDATE', ?, ?, jsonb_build_object('code', ?))",
                resource.name(),
                String.valueOf(id),
                request.code());
        return get(resource, id);
    }

    public void deactivate(MasterDataResource resource, long id) {
        get(resource, id);
        jdbc.update("UPDATE " + resource.table() + " SET active = FALSE WHERE id = ?", id);
        jdbc.update(
                "INSERT INTO audit_event (action, aggregate_type, aggregate_id) VALUES ('DEACTIVATE', ?, ?)",
                resource.name(),
                String.valueOf(id));
    }

    public void activate(MasterDataResource resource, long id) {
        get(resource, id);
        jdbc.update("UPDATE " + resource.table() + " SET active = TRUE WHERE id = ?", id);
        jdbc.update(
                "INSERT INTO audit_event (action, aggregate_type, aggregate_id) VALUES ('ACTIVATE', ?, ?)",
                resource.name(),
                String.valueOf(id));
    }

    private MasterDataItem item(java.sql.ResultSet rs, MasterDataResource resource)
            throws java.sql.SQLException {
        Map<String, Object> attributes = new HashMap<>();
        if (resource == MasterDataResource.ROOMS) {
            attributes.put("capacity", rs.getInt("capacity"));
            attributes.put("roomType", rs.getString("room_type"));
        } else if (resource == MasterDataResource.STUDENT_GROUPS) {
            attributes.put("studentCount", rs.getInt("student_count"));
            attributes.put("homeRoomCode", rs.getString("home_room_code"));
        }
        return new MasterDataItem(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("active"),
                attributes);
    }

    private String selectColumns(MasterDataResource resource) {
        if (resource == MasterDataResource.ROOMS)
            return "capacity, room_type, NULL::integer AS student_count, NULL::varchar AS home_room_code";
        if (resource == MasterDataResource.STUDENT_GROUPS)
            return "NULL::integer AS capacity, NULL::varchar AS room_type, student_count, (SELECT code FROM room WHERE id=student_group.home_room_id) AS home_room_code";
        return "NULL::integer AS capacity, NULL::varchar AS room_type, NULL::integer AS student_count, NULL::varchar AS home_room_code";
    }

    private void validateHomeRoom(String homeRoomCode) {
        if (homeRoomCode == null || homeRoomCode.isBlank()) return;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM room WHERE code=? AND active=TRUE",
                        Integer.class,
                        homeRoomCode.trim());
        if (count == null || count == 0)
            throw new IllegalArgumentException("绑定教室不存在或已停用: " + homeRoomCode.trim());
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
