package org.skala.capstone.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActive(String id) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM app.sessions WHERE id=CAST(? AS uuid) AND expires_at>now()", Integer.class, id);
        return count != null && count == 1;
    }

    public void deleteExpired() {
        jdbc.update("DELETE FROM app.sessions WHERE expires_at<=now()");
    }

    public void create(UUID id, String userId, Instant expiresAt) {
        jdbc.update("INSERT INTO app.sessions (id,user_id,expires_at) VALUES (?,CAST(? AS uuid),?)",
            id, userId, Timestamp.from(expiresAt));
    }

    public List<Map<String, Object>> lockRefresh(String hash) {
        return jdbc.queryForList("""
            SELECT r.session_id, r.expires_at, r.used_at, s.user_id FROM app.refresh_tokens r
            JOIN app.sessions s ON s.id=r.session_id WHERE r.token_hash=? FOR UPDATE OF r, s""", hash);
    }

    public void markRefreshUsed(String hash) {
        jdbc.update("UPDATE app.refresh_tokens SET used_at=now() WHERE token_hash=?", hash);
    }

    public void extend(UUID id, Instant expiresAt) {
        jdbc.update("UPDATE app.sessions SET expires_at=? WHERE id=?", Timestamp.from(expiresAt), id);
    }

    public void createRefresh(String hash, UUID session, Instant expiresAt) {
        jdbc.update("INSERT INTO app.refresh_tokens (token_hash,session_id,expires_at) VALUES (?,?,?)",
            hash, session, Timestamp.from(expiresAt));
    }

    public void revokeByRefresh(String hash) {
        jdbc.update("DELETE FROM app.sessions WHERE id=(SELECT session_id FROM app.refresh_tokens WHERE token_hash=?)", hash);
    }

    public void revoke(String id) {
        jdbc.update("DELETE FROM app.sessions WHERE id=CAST(? AS uuid)", id);
    }

    public void revoke(UUID id) {
        jdbc.update("DELETE FROM app.sessions WHERE id=?", id);
    }
}
