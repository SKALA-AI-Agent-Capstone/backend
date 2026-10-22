package org.skala.capstone.violation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ViolationRepository {
    public record Progress(String reviewId, int total, int undecided) {}
    public record Rewrite(String violationId, int attempt, String text, boolean human, String note, OffsetDateTime createdAt) {}
    public record Violation(String id, String excerpt, String severity, String suggestion, int attempts, String resolution,
            String finalText, boolean validated, String decidedBy, String decidedByName, OffsetDateTime decidedAt) {}

    private final JdbcTemplate jdbc;

    public ViolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteForReview(String reviewId) {
        jdbc.update("DELETE FROM app.violations WHERE review_id=CAST(? AS uuid)", reviewId);
    }

    public void create(String id, String reviewId, String excerpt, String severity, String suggestion, int attempts) {
        jdbc.update("INSERT INTO app.violations(id,review_id,text_excerpt,severity,rewrite_suggestion,rewrite_attempt_count) "
            + "VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,?,?)", id, reviewId, excerpt, severity, suggestion, attempts);
    }

    public void createAutomaticRewrite(String violationId, int attempt, String text) {
        jdbc.update("INSERT INTO app.violation_rewrites(id,violation_id,attempt_no,text,requested_by_human) "
            + "VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,false)", UUID.randomUUID().toString(), violationId, attempt, text);
    }

    public List<Progress> progress(String tenant) {
        return jdbc.query("""
            SELECT r.id::text,
              (SELECT count(*) FROM app.violations v WHERE v.review_id=r.id),
              (SELECT count(*) FROM app.violations v WHERE v.review_id=r.id AND v.final_resolution IS NULL)
            FROM app.reviews r WHERE r.tenant_id=CAST(? AS uuid)""",
            (rs, n) -> new Progress(rs.getString(1), rs.getInt(2), rs.getInt(3)), tenant);
    }

    public List<Rewrite> rewrites(String reviewId) {
        return jdbc.query("""
            SELECT w.violation_id::text,w.attempt_no,w.text,w.requested_by_human,w.retry_note,w.created_at
            FROM app.violation_rewrites w JOIN app.violations v ON v.id=w.violation_id
            WHERE v.review_id=CAST(? AS uuid) ORDER BY w.attempt_no""",
            (rs, n) -> new Rewrite(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class)), reviewId);
    }

    public List<Violation> violations(String reviewId) {
        return jdbc.query("""
            SELECT v.id::text,v.text_excerpt,v.severity,v.rewrite_suggestion,v.rewrite_attempt_count,v.final_resolution,
              v.final_text,v.final_text_validated,v.decided_by::text,u.name,v.decided_at,v.created_at
            FROM app.violations v LEFT JOIN app.users u ON u.id=v.decided_by
            WHERE v.review_id=CAST(? AS uuid) ORDER BY v.created_at,v.id""",
            (rs, n) -> new Violation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getString(10),
                rs.getObject(11, OffsetDateTime.class)), reviewId);
    }

    public List<Map<String, Object>> lock(String reviewId, String violationId) {
        return jdbc.queryForList("SELECT text_excerpt,rewrite_suggestion FROM app.violations "
            + "WHERE id=CAST(? AS uuid) AND review_id=CAST(? AS uuid) FOR UPDATE", violationId, reviewId);
    }

    public void decide(String violationId, String resolution, String text, String userId) {
        jdbc.update("""
            UPDATE app.violations SET final_resolution=?,final_text=?,final_text_validated=true,
              decided_by=CAST(? AS uuid),decided_at=now() WHERE id=CAST(? AS uuid)""", resolution, text, userId, violationId);
    }

    public List<String> rewriteAttempt(String violationId, int attempt) {
        return jdbc.queryForList("SELECT text FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) AND attempt_no=?",
            String.class, violationId, attempt);
    }

    public List<String> previousRewrites(String violationId) {
        return jdbc.queryForList("SELECT text FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) ORDER BY attempt_no",
            String.class, violationId);
    }

    public int nextAttempt(String violationId) {
        return jdbc.queryForObject("SELECT coalesce(max(attempt_no),0)+1 FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid)",
            Integer.class, violationId);
    }

    public void createHumanRewrite(String violationId, int attempt, String text, String note) {
        jdbc.update("INSERT INTO app.violation_rewrites(id,violation_id,attempt_no,text,requested_by_human,retry_note) "
            + "VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,true,?)", UUID.randomUUID().toString(), violationId, attempt, text, note);
        jdbc.update("UPDATE app.violations SET rewrite_suggestion=?,rewrite_attempt_count=? WHERE id=CAST(? AS uuid)", text, attempt, violationId);
    }

    public int humanRetries(String violationId) {
        return jdbc.queryForObject("SELECT count(*) FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) AND requested_by_human",
            Integer.class, violationId);
    }
}
