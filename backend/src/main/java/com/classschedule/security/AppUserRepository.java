package com.classschedule.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password_hash, display_name, enabled FROM app_user WHERE username = ?",
                username);
        if (rows.isEmpty()) throw new UsernameNotFoundException("用户名或密码错误");
        Map<String, Object> row = rows.get(0);
        List<GrantedAuthority> authorities = jdbc.query(
                "SELECT r.code FROM app_role r JOIN app_user_role ur ON ur.role_id = r.id WHERE ur.user_id = ? AND r.active = TRUE",
                (rs, rowNum) -> new SimpleGrantedAuthority("ROLE_" + rs.getString("code")),
                row.get("id"));
        return User.withUsername((String) row.get("username"))
                .password((String) row.get("password_hash"))
                .disabled(!Boolean.TRUE.equals(row.get("enabled")))
                .authorities(authorities)
                .build();
    }

    public UserProfile profile(String username) {
        try {
            return jdbc.queryForObject(
                    "SELECT u.id, u.username, u.display_name, u.enabled, COALESCE(array_agg(DISTINCT r.code) FILTER (WHERE r.code IS NOT NULL), ARRAY[]::varchar[]) AS roles FROM app_user u LEFT JOIN app_user_role ur ON ur.user_id = u.id LEFT JOIN app_role r ON r.id = ur.role_id WHERE u.username = ? GROUP BY u.id, u.username, u.display_name, u.enabled",
                    (rs, rowNum) -> new UserProfile(
                            rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                            rs.getBoolean("enabled"), Set.of((String[]) rs.getArray("roles").getArray())), username);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new UsernameNotFoundException("用户不存在: " + username, exception);
        }
    }

    public record UserProfile(long id, String username, String displayName, boolean enabled, Set<String> roles) {}
}
