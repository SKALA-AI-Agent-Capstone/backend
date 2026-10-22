package org.skala.capstone.review;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewFailureRepository {
    public record Failure(String tenant, String reviewId, int version, String error) {}

    private final JdbcTemplate jdbc;

    public ReviewFailureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(Failure failure) {
        jdbc.update("""
            INSERT INTO app.review_failure_requests(review_id,version_no,tenant_id,error_code)
            SELECT id,?,tenant_id,? FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)
            ON CONFLICT (review_id,version_no) DO NOTHING""",
            failure.version(), failure.error(), failure.reviewId(), failure.tenant());
    }

    public List<Failure> pending() {
        return jdbc.query("""
            SELECT tenant_id::text,review_id::text,version_no,error_code
            FROM app.review_failure_requests ORDER BY created_at LIMIT 20""",
            (rs, n) -> new Failure(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
    }

    public void remove(Failure failure) {
        jdbc.update("DELETE FROM app.review_failure_requests WHERE review_id=CAST(? AS uuid) AND version_no=?",
            failure.reviewId(), failure.version());
    }

    /** 큐 작업이 없는 실행의 프로세스 종료와 DB 장애를 저장된 예약에서 복구한다. */
    public List<Failure> abandonedLocalRuns() {
        return jdbc.query("""
            SELECT r.tenant_id::text,r.id::text,c.version_no
            FROM app.review_credit_charges c JOIN app.reviews r ON r.id=c.review_id AND r.tenant_id=c.tenant_id
            CROSS JOIN LATERAL jsonb_array_elements(r.document->'versions') v
            WHERE c.status='reserved' AND (v->>'no')::integer=c.version_no
              AND NOT EXISTS (SELECT 1 FROM app.review_jobs j WHERE j.review_id=r.id AND j.version_no=c.version_no)
              AND NOT EXISTS (SELECT 1 FROM app.review_failure_requests f WHERE f.review_id=r.id AND f.version_no=c.version_no)
              AND (v->>'status'='failed'
                OR (v->>'status'='analyzing' AND r.updated_at<now()-interval '7 minutes')
                OR (v->>'status'='queued' AND r.updated_at<now()-interval '30 minutes'))
            ORDER BY r.updated_at LIMIT 20""",
            (rs, n) -> new Failure(rs.getString(1), rs.getString(2), rs.getInt(3), "ANALYSIS_FAILED_REUPLOAD_REQUIRED"));
    }
}
