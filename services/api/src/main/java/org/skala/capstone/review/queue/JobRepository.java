package org.skala.capstone.review.queue;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String reviewId, int version, String request) {
        jdbc.update("INSERT INTO app.review_jobs(review_id,version_no,request) VALUES (CAST(? AS uuid),?,CAST(? AS jsonb))",
            reviewId, version, request);
    }

    public List<Map<String, Object>> ready() {
        return jdbc.queryForList("SELECT review_id::text,version_no,kind FROM app.review_jobs "
            + "WHERE status IN ('pending','retry') AND kind='run' AND next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 20");
    }

    public void published(String id, int version, String kind) {
        jdbc.update("UPDATE app.review_jobs SET status='published',updated_at=now() "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status IN ('pending','retry') AND kind=?", id, version, kind);
    }

    public List<Map<String, Object>> claim(String id, int version, String kind) {
        return jdbc.queryForList("UPDATE app.review_jobs SET status='running',attempts=attempts+1,updated_at=now() "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status IN ('pending','published','retry') "
            + "AND next_attempt_at<=now() AND kind=? RETURNING request::text,attempts", id, version, kind);
    }

    public void retry(String id, int version, String kind, int delay, String error, String trace) {
        jdbc.update("UPDATE app.review_jobs SET status='retry',updated_at=now(), "
            + "next_attempt_at=now()+(? * interval '1 second'),last_error=?,last_trace_id=? "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?", delay, error, trace, id, version, kind);
    }

    public void failed(String id, int version, String kind, String error, String trace) {
        jdbc.update("UPDATE app.review_jobs SET status='failed',request='{}'::jsonb,updated_at=now(),last_error=?,last_trace_id=? "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?", error, trace, id, version, kind);
    }

    public void done(String id, int version, String kind) {
        jdbc.update("UPDATE app.review_jobs SET status='done',request='{}'::jsonb,updated_at=now(),last_error=NULL,last_trace_id=NULL "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?", id, version, kind);
    }

    public List<Map<String, Object>> stale() {
        return jdbc.queryForList("SELECT review_id::text,version_no,kind,attempts,request->>'tenantId' AS tenant "
            + "FROM app.review_jobs WHERE status='running' AND kind='run' AND updated_at<now()-interval '7 minutes' LIMIT 20");
    }

    public void recover(String id, int version, String kind, String status) {
        jdbc.update("UPDATE app.review_jobs SET status=?,request=CASE WHEN ?='retry' THEN request ELSE '{}'::jsonb END, "
            + "next_attempt_at=now(),updated_at=now() "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=? AND status='running' AND updated_at<now()-interval '7 minutes'",
            status, status, id, version, kind);
    }
}
