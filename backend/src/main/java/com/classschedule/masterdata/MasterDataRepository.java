package com.classschedule.masterdata;

import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MasterDataRepository {
    private final JdbcTemplate jdbc;

    public MasterDataRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MasterDataList list(MasterDataResource resource, String query, boolean active, int page, int size) {
        int safePage = Math.max(0, page); int safeSize = Math.min(100, Math.max(1, size)); int offset = safePage * safeSize;
        String where = active ? "active = TRUE" : "TRUE";
        String q = query == null ? "" : query.trim();
        String filter = q.isBlank() ? "" : " AND (code ILIKE ? OR name ILIKE ?)";
        String table = resource.table();
        long total = q.isBlank() ? jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class) : jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where + filter, Long.class, "%" + q + "%", "%" + q + "%");
        List<MasterDataItem> items = q.isBlank()
                ? jdbc.query("SELECT id, code, name, active, " + (resource == MasterDataResource.ROOMS ? "capacity, room_type, NULL::integer AS student_count" : resource == MasterDataResource.STUDENT_GROUPS ? "NULL::integer AS capacity, NULL::varchar AS room_type, student_count" : "NULL::integer AS capacity, NULL::varchar AS room_type, NULL::integer AS student_count") + " FROM " + table + " WHERE " + where + " ORDER BY code LIMIT ? OFFSET ?", (rs, row) -> item(rs, resource), safeSize, offset)
                : jdbc.query("SELECT id, code, name, active, " + (resource == MasterDataResource.ROOMS ? "capacity, room_type, NULL::integer AS student_count" : resource == MasterDataResource.STUDENT_GROUPS ? "NULL::integer AS capacity, NULL::varchar AS room_type, student_count" : "NULL::integer AS capacity, NULL::varchar AS room_type, NULL::integer AS student_count") + " FROM " + table + " WHERE " + where + filter + " ORDER BY code LIMIT ? OFFSET ?", (rs, row) -> item(rs, resource), "%" + q + "%", "%" + q + "%", safeSize, offset);
        return new MasterDataList(items, safePage, safeSize, total);
    }

    public MasterDataItem get(MasterDataResource resource, long id) {
        try { return jdbc.queryForObject("SELECT id, code, name, active, " + (resource == MasterDataResource.ROOMS ? "capacity, room_type, NULL::integer AS student_count" : resource == MasterDataResource.STUDENT_GROUPS ? "NULL::integer AS capacity, NULL::varchar AS room_type, student_count" : "NULL::integer AS capacity, NULL::varchar AS room_type, NULL::integer AS student_count") + " FROM " + resource.table() + " WHERE id = ?", (rs, row) -> item(rs, resource), id); }
        catch (EmptyResultDataAccessException e) { throw new IllegalArgumentException(resource.label() + "不存在: " + id); }
    }

    public MasterDataItem save(MasterDataResource resource, MasterDataRequest request) {
        String table = resource.table();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE code = ?", Integer.class, request.code()) > 0) throw new IllegalArgumentException("编码已存在: " + request.code());
        Long id = resource == MasterDataResource.ROOMS
                ? jdbc.queryForObject("INSERT INTO room (code,name,capacity,room_type) VALUES (?,?,?,?) RETURNING id", Long.class, request.code(), request.name(), request.capacity(), request.roomType())
                : resource == MasterDataResource.STUDENT_GROUPS
                ? jdbc.queryForObject("INSERT INTO student_group (code,name,student_count) VALUES (?,?,?) RETURNING id", Long.class, request.code(), request.name(), request.studentCount())
                : jdbc.queryForObject("INSERT INTO " + table + " (code,name) VALUES (?,?) RETURNING id", Long.class, request.code(), request.name());
        jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id, detail) VALUES ('CREATE', ?, ?, jsonb_build_object('code', ?))", resource.name(), String.valueOf(id), request.code());
        return get(resource, id);
    }

    public MasterDataItem update(MasterDataResource resource, long id, MasterDataRequest request) {
        get(resource, id);
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM " + resource.table() + " WHERE code = ? AND id <> ?", Integer.class, request.code(), id);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("编码已存在: " + request.code());
        if (resource == MasterDataResource.ROOMS) jdbc.update("UPDATE room SET code=?, name=?, capacity=?, room_type=? WHERE id=?", request.code(), request.name(), request.capacity(), request.roomType(), id);
        else if (resource == MasterDataResource.STUDENT_GROUPS) jdbc.update("UPDATE student_group SET code=?, name=?, student_count=? WHERE id=?", request.code(), request.name(), request.studentCount(), id);
        else jdbc.update("UPDATE " + resource.table() + " SET code=?, name=? WHERE id=?", request.code(), request.name(), id);
        jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id, detail) VALUES ('UPDATE', ?, ?, jsonb_build_object('code', ?))", resource.name(), String.valueOf(id), request.code());
        return get(resource, id);
    }

    public void deactivate(MasterDataResource resource, long id) {
        get(resource, id);
        jdbc.update("UPDATE " + resource.table() + " SET active = FALSE WHERE id = ?", id);
        jdbc.update("INSERT INTO audit_event (action, aggregate_type, aggregate_id) VALUES ('DEACTIVATE', ?, ?)", resource.name(), String.valueOf(id));
    }

    private MasterDataItem item(java.sql.ResultSet rs, MasterDataResource resource) throws java.sql.SQLException {
        Map<String,Object> attributes = resource == MasterDataResource.ROOMS ? Map.of("capacity", rs.getInt("capacity"), "roomType", rs.getString("room_type")) : resource == MasterDataResource.STUDENT_GROUPS ? Map.of("studentCount", rs.getInt("student_count")) : Map.of();
        return new MasterDataItem(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("active"), attributes);
    }
}
