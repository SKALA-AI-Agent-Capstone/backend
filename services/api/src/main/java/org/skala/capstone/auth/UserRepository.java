package org.skala.capstone.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean emailExists(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM app.users WHERE email=?", Integer.class, email) > 0;
    }

    public List<Map<String, Object>> findCredentials(String email) {
        return jdbc.queryForList("SELECT id,password_hash FROM app.users WHERE email=?", email);
    }

    public Map<String, Object> identity(String id) {
        return jdbc.queryForMap("SELECT u.*,t.name AS tenant_name FROM app.users u "
            + "JOIN app.tenants t ON t.id=u.tenant_id WHERE u.id=CAST(? AS uuid)", id);
    }

    public Map<String, Object> tokenClaims(String id) {
        return jdbc.queryForMap("SELECT tenant_id,role FROM app.users WHERE id=CAST(? AS uuid)", id);
    }

    public void createTenant(UUID id, String name) {
        jdbc.update("INSERT INTO app.tenants VALUES (?,?)", id, name);
    }

    public void createUser(UUID id, String tenant, String email, String name, String passwordHash, String role) {
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role) "
            + "VALUES (?,CAST(? AS uuid),?,?,?,?)", id, tenant, email, name, passwordHash, role);
    }
    public String name(String userId) {
        return jdbc.queryForObject("SELECT name FROM app.users WHERE id=CAST(? AS uuid)",String.class,userId);
    }
}
